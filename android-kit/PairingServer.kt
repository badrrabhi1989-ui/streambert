package com.truelockmc.streambert

import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot HTTP page on the LAN so a phone can paste API keys.
 * The listener stops after a successful save, too many bad codes, or the timeout.
 * Clients that are not on a private network are rejected.
 */
object PairingServer {
    data class Session(val url: String, val code: String, val host: String, val port: Int, val expiresAt: Long)

    private const val WINDOW_MS = 180_000L
    private const val MAX_FAILURES = 5
    private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    @Volatile private var server: ServerSocket? = null
    @Volatile private var thread: Thread? = null
    private val open = AtomicBoolean(false)
    private val random = SecureRandom()

    fun lanIpv4(): String? {
        val ranked = mutableListOf<Pair<Int, String>>()
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (ifaces.hasMoreElements()) {
            val nif = ifaces.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            val score = when {
                nif.name.startsWith("wlan") -> 0
                nif.name.startsWith("eth") -> 1
                nif.name.startsWith("en") -> 2
                else -> 5
            }
            val addrs = nif.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address && isPrivateLan(addr)) {
                    ranked.add(score to addr.hostAddress)
                }
            }
        }
        return ranked.minByOrNull { it.first }?.second
    }

    /** True for RFC1918, link-local, and loopback. False for public addresses. */
    fun isPrivateLan(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isMulticastAddress) return false
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress) return true
        val b = addr.address
        if (b.size == 4) {
            val a = b[0].toInt() and 0xff
            val c = b[1].toInt() and 0xff
            if (a == 10) return true
            if (a == 172 && c in 16..31) return true
            if (a == 192 && c == 168) return true
            if (a == 169 && c == 254) return true
            if (a == 127) return true
        }
        return false
    }

    fun start(advertiseHost: String? = null, onSaved: (Map<String, String>) -> Unit): Session {
        stop()
        val host = advertiseHost ?: lanIpv4() ?: throw IllegalStateException("Connect to Wi-Fi, then try again.")
        val code = randomCode()
        val token = randomToken()
        val deadline = System.currentTimeMillis() + WINDOW_MS
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("0.0.0.0", 0))
        val port = socket.localPort
        server = socket
        open.set(true)
        val worker = Thread {
            var failures = 0
            try {
                while (open.get() && System.currentTimeMillis() < deadline) {
                    socket.soTimeout = 1000
                    val client = try {
                        socket.accept()
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                    try {
                        when (handleClient(client, host, port, token, code, onSaved)) {
                            1 -> {
                                open.set(false)
                                break
                            }
                            -1 -> {
                                failures++
                                if (failures >= MAX_FAILURES) {
                                    open.set(false)
                                    break
                                }
                            }
                        }
                    } catch (_: Exception) {
                        failures++
                    } finally {
                        try {
                            client.close()
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {
            } finally {
                open.set(false)
                try {
                    socket.close()
                } catch (_: Exception) {}
            }
        }
        worker.isDaemon = true
        worker.name = "streambert-pair"
        thread = worker
        worker.start()
        val url = "http://$host:$port/pair/$token"
        return Session(url, code, host, port, deadline)
    }

    fun stop() {
        open.set(false)
        try {
            server?.close()
        } catch (_: Exception) {}
        server = null
        thread = null
    }

    fun parseForm(body: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        if (body.isEmpty()) return out
        for (part in body.split("&")) {
            val idx = part.indexOf("=")
            val rawKey = if (idx >= 0) part.substring(0, idx) else part
            val rawVal = if (idx >= 0) part.substring(idx + 1) else ""
            val key = urlDecode(rawKey)
            val value = urlDecode(rawVal.replace("+", " "))
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    fun validateKeys(form: Map<String, String>, expectedCode: String): Map<String, String>? {
        val given = form["code"].orEmpty().trim().uppercase(Locale.US)
        if (!constantEquals(given, expectedCode.uppercase(Locale.US))) return null
        val tmdb = form["tmdb"].orEmpty().trim().replace("\n", "").replace("\r", "")
        val wyzie = form["wyzie"].orEmpty().trim().replace("\n", "").replace("\r", "")
        val subdl = form["subdl"].orEmpty().trim().replace("\n", "").replace("\r", "")
        if (tmdb.length > 4000 || wyzie.length > 200 || subdl.length > 200) return null
        if (tmdb.isNotEmpty() && tmdb.length < 20) return null
        if (wyzie.isNotEmpty() && !wyzie.startsWith("wyzie-")) return null
        if (tmdb.isEmpty() && wyzie.isEmpty() && subdl.isEmpty()) return null
        val saved = linkedMapOf<String, String>()
        if (tmdb.isNotEmpty()) saved["apikey"] = tmdb
        if (wyzie.isNotEmpty()) saved["wyzieApiKey"] = wyzie
        if (subdl.isNotEmpty()) saved["subdlApiKey"] = subdl
        return saved
    }

    private fun handleClient(
        client: Socket,
        host: String,
        port: Int,
        token: String,
        code: String,
        onSaved: (Map<String, String>) -> Unit,
    ): Int {
        client.soTimeout = 8000
        val remote = client.inetAddress
        if (remote == null || !isPrivateLan(remote)) {
            write(client, 403, "Forbidden", textPage("This page is only available on the local network."))
            return -1
        }
        val raw = readHttp(client)
        val headEnd = indexOfHeaderEnd(raw)
        if (headEnd < 0) {
            write(client, 400, "Bad Request", textPage("Bad request."))
            return -1
        }
        val head = String(raw, 0, headEnd, StandardCharsets.ISO_8859_1)
        val line = head.lineSequence().firstOrNull().orEmpty()
        val parts = line.split(" ")
        if (parts.size < 2) {
            write(client, 400, "Bad Request", textPage("Bad request."))
            return -1
        }
        val method = parts[0]
        val target = parts[1].substringBefore("?")
        val hostHeader = header(head, "host")
        if (!hostAllowed(hostHeader, host, port)) {
            write(client, 403, "Forbidden", textPage("Wrong host."))
            return -1
        }
        val path = "/pair/$token"
        if (target != path) {
            write(client, 404, "Not Found", textPage("This pairing link is not valid."))
            return -1
        }
        if (method == "GET") {
            write(client, 200, "OK", formPage(code))
            return 0
        }
        if (method != "POST") {
            write(client, 405, "Method Not Allowed", textPage("Use the form."))
            return -1
        }
        val length = header(head, "content-length").toIntOrNull() ?: 0
        if (length < 0 || length > 16 * 1024) {
            write(client, 413, "Payload Too Large", textPage("That was too large."))
            return -1
        }
        val bodyBytes = ByteArray(length)
        val already = raw.size - (headEnd + 4)
        var filled = 0
        if (already > 0) {
            val n = minOf(already, length)
            System.arraycopy(raw, headEnd + 4, bodyBytes, 0, n)
            filled = n
        }
        val input = client.getInputStream()
        while (filled < length) {
            val n = input.read(bodyBytes, filled, length - filled)
            if (n < 0) break
            filled += n
        }
        val form = parseForm(String(bodyBytes, 0, filled, StandardCharsets.UTF_8))
        val keys = validateKeys(form, code)
        if (keys == null) {
            write(client, 200, "OK", formPage(code, "That code or key was not accepted. Check the TV and try again."))
            return -1
        }
        onSaved(keys)
        write(client, 200, "OK", textPage("Saved. You can close this page and go back to the TV."))
        return 1
    }

    private fun hostAllowed(header: String, host: String, port: Int): Boolean {
        val h = header.trim().lowercase(Locale.US)
        if (h.isEmpty()) return false
        val expect = "$host:$port".lowercase(Locale.US)
        return h == expect || h == "localhost:$port" || h == "127.0.0.1:$port"
    }

    private fun header(head: String, name: String): String {
        val prefix = name.lowercase(Locale.US)
        for (line in head.lineSequence()) {
            val idx = line.indexOf(":")
            if (idx <= 0) continue
            if (line.substring(0, idx).trim().lowercase(Locale.US) == prefix) {
                return line.substring(idx + 1).trim()
            }
        }
        return ""
    }

    private fun readHttp(client: Socket): ByteArray {
        val input = client.getInputStream()
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(1024)
        while (buf.size() < 8192) {
            val n = input.read(tmp)
            if (n < 0) break
            buf.write(tmp, 0, n)
            if (indexOfHeaderEnd(buf.toByteArray()) >= 0) break
        }
        return buf.toByteArray()
    }

    private fun indexOfHeaderEnd(raw: ByteArray): Int {
        for (i in 0 until raw.size - 3) {
            if (raw[i] == '\r'.code.toByte() && raw[i + 1] == '\n'.code.toByte() &&
                raw[i + 2] == '\r'.code.toByte() && raw[i + 3] == '\n'.code.toByte()
            ) return i
        }
        return -1
    }

    private fun write(client: Socket, code: Int, reason: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "X-Frame-Options: DENY\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; form-action 'self'\r\n" +
            "Connection: close\r\n\r\n"
        val out = client.getOutputStream()
        out.write(head.toByteArray(StandardCharsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun formPage(code: String, error: String? = null): String {
        val err = if (error.isNullOrBlank()) "" else "<p class=\"err\">${escape(error)}</p>"
        return """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Streambert keys</title>
            <style>
            body{margin:0;background:#0a0a0a;color:#f0f0f0;font:18px/1.4 system-ui,sans-serif}
            main{max-width:560px;margin:0 auto;padding:28px 20px 48px}
            h1{font-size:28px;margin:0 0 8px}
            p{color:#c0c0c0}
            label{display:block;margin:16px 0 6px;font-weight:600}
            input{width:100%;box-sizing:border-box;padding:14px;font-size:18px;border-radius:8px;border:1px solid #333;background:#161616;color:#fff}
            button{margin-top:22px;width:100%;padding:16px;font-size:18px;border:0;border-radius:8px;background:#e50914;color:#fff;font-weight:700}
            .err{color:#ff8a80}
            </style></head><body><main>
            <h1>Streambert</h1>
            <p>Paste the keys from your phone. This page works once, then it closes.</p>
            $err
            <form method="POST">
            <label for="code">Pairing code</label>
            <input id="code" name="code" autocomplete="off" autocapitalize="characters" value="">
            <label for="tmdb">TMDB Read Access Token</label>
            <input id="tmdb" name="tmdb" autocomplete="off" spellcheck="false" placeholder="eyJ...">
            <label for="wyzie">Wyzie API key (optional)</label>
            <input id="wyzie" name="wyzie" autocomplete="off" spellcheck="false" placeholder="wyzie-">
            <label for="subdl">SubDL API key (optional)</label>
            <input id="subdl" name="subdl" autocomplete="off" spellcheck="false">
            <button type="submit">Save on the TV</button>
            </form>
            <script>document.getElementById('code').value=(location.hash||'').replace(/^#/,'').toUpperCase();</script>
            </main></body></html>
        """.trimIndent()
    }

    private fun textPage(message: String): String {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Streambert</title></head><body style=\"background:#0a0a0a;color:#fff;font:20px system-ui;padding:32px\"><p>${escape(message)}</p></body></html>"
    }

    private fun escape(s: String): String {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }

    private fun urlDecode(s: String): String {
        return try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }
    }

    private fun constantEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }

    private fun randomCode(): String {
        val out = CharArray(6)
        for (i in out.indices) out[i] = alphabet[random.nextInt(alphabet.length)]
        return String(out)
    }

    private fun randomToken(): String {
        val raw = ByteArray(16)
        random.nextBytes(raw)
        return raw.joinToString("") { "%02x".format(it) }
    }
}

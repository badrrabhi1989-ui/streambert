package com.truelockmc.streambert

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

/**
 * Built-in replacement for the desktop vid-dl + ffmpeg pipeline.
 * Downloads an HLS playlist (best rendition) or a direct media file and
 * writes one playable file. Segments are fetched four at a time.
 */
class HlsDownloader(private val client: OkHttpClient) {
    data class Result(val path: String, val bytes: Long)

    fun download(
        url: String,
        headers: Map<String, String>,
        destDir: String,
        displayName: String,
        cancelled: AtomicBoolean,
        openOut: (dir: String, filename: String) -> Pair<OutputStream, String>,
        onProgress: (Map<String, Any?>) -> Unit,
    ): Result {
        if (!NetGuard.isPublicHttpUrl(url)) throw IllegalArgumentException("Blocked address")
        client.newCall(request(url, headers)).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code} for $url")
            val ct = res.header("Content-Type") ?: ""
            val peek = res.peekBody(64).string().trimStart()
            if (ct.contains("mpegurl", true) || ct.contains("m3u8", true) || peek.startsWith("#EXTM3U")) {
                val text = res.body?.string() ?: throw IllegalStateException("Empty playlist")
                return downloadHls(url, text, headers, destDir, displayName, cancelled, openOut, onProgress)
            }
            val ext = extensionFrom(url, ct)
            val (out, path) = openOut(destDir, safeName(displayName) + ext)
            val body = res.body ?: throw IllegalStateException("Empty response")
            val total = body.contentLength()
            val started = System.nanoTime()
            var done = 0L
            var lastTick = 0L
            body.byteStream().use { input ->
                out.use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled.get()) throw IllegalStateException("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastTick > 400_000_000L) {
                            lastTick = now
                            onProgress(progress(done, total, started, "Downloading…", 0, 0))
                        }
                    }
                }
            }
            return Result(path, done)
        }
    }

    private fun downloadHls(
        url: String,
        firstBody: String,
        headers: Map<String, String>,
        destDir: String,
        displayName: String,
        cancelled: AtomicBoolean,
        openOut: (dir: String, filename: String) -> Pair<OutputStream, String>,
        onProgress: (Map<String, Any?>) -> Unit,
    ): Result {
        var playlistUrl = url
        var body = firstBody
        var playlist = HlsPlaylist.parse(body, playlistUrl)
        var guard = 0
        while (playlist.isMaster) {
            if (guard++ > 4) throw IllegalStateException("Playlist redirect loop")
            val next = HlsPlaylist.bestVariant(playlist) ?: throw IllegalStateException("No rendition in master playlist")
            playlistUrl = next
            body = get(next, headers).text
            playlist = HlsPlaylist.parse(body, playlistUrl)
        }
        if (playlist.segments.isEmpty()) throw IllegalStateException("Playlist has no segments")
        if (playlist.key?.method.equals("SAMPLE-AES", true)) {
            throw IllegalStateException("This stream uses SAMPLE-AES. Open it in an external player instead.")
        }
        val keyBytes = playlist.key?.uri?.let { keyUrl ->
            if (!playlist.key.method.equals("NONE", true)) get(keyUrl, headers).bytes else null
        }
        val ext = if (playlist.isFmp4) ".mp4" else ".ts"
        val tmp = java.io.File(System.getProperty("java.io.tmpdir"), "sbdl-" + System.nanoTime())
        tmp.mkdirs()
        val doneCount = AtomicInteger(0)
        val byteCount = AtomicLong(0)
        val started = System.nanoTime()
        val total = playlist.segments.size
        val errors = java.util.concurrent.atomic.AtomicReference<Exception>(null)
        val pool = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(total)
        onProgress(
            mapOf(
                "progress" to 0,
                "totalFragments" to total,
                "completedFragments" to 0,
                "lastMessage" to "HLS: $total fragments",
                "status" to "downloading",
            ),
        )
        for ((index, segment) in playlist.segments.withIndex()) {
            pool.submit {
                try {
                    if (cancelled.get()) return@submit
                    var attempt = 0
                    var data: ByteArray? = null
                    var last: Exception? = null
                    while (attempt < 3 && data == null && !cancelled.get()) {
                        try {
                            data = get(segment.url, headers).bytes
                        } catch (e: Exception) {
                            last = e
                            attempt++
                        }
                    }
                    if (data == null) throw last ?: IllegalStateException("segment failed")
                    val plain = if (keyBytes != null) decrypt(data, keyBytes, ivFor(playlist, segment.sequence)) else data
                    java.io.File(tmp, "$index.part").writeBytes(plain)
                    val finished = doneCount.incrementAndGet()
                    val bytes = byteCount.addAndGet(plain.size.toLong())
                    val pct = ((finished * 100.0) / total).roundToInt().coerceIn(0, 99)
                    onProgress(
                        mapOf(
                            "progress" to pct,
                            "totalFragments" to total,
                            "completedFragments" to finished,
                            "size" to formatBytes(bytes),
                            "speed" to speed(bytes, started),
                            "lastMessage" to "Fragment $finished / $total",
                            "status" to "downloading",
                        ),
                    )
                } catch (e: Exception) {
                    errors.compareAndSet(null, e)
                } finally {
                    latch.countDown()
                }
            }
        }
        try {
            latch.await(30, TimeUnit.MINUTES)
            pool.shutdownNow()
            if (cancelled.get()) throw IllegalStateException("Cancelled")
            errors.get()?.let { throw it }
            if (doneCount.get() != total) throw IllegalStateException("Missing segments (${doneCount.get()}/$total)")

            val (out, path) = openOut(destDir, safeName(displayName) + ext)
            var written = 0L
            out.use { stream ->
                playlist.initUrl?.let { init ->
                    val initBytes = get(init, headers).bytes
                    stream.write(initBytes)
                    written += initBytes.size
                }
                val buf = ByteArray(256 * 1024)
                for (i in 0 until total) {
                    val part = java.io.File(tmp, "$i.part")
                    if (!part.exists()) throw IllegalStateException("Missing segment $i")
                    part.inputStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            stream.write(buf, 0, n)
                            written += n
                        }
                    }
                }
            }
            return Result(path, written)
        } finally {
            tmp.deleteRecursively()
        }
    }

    private data class Got(val bytes: ByteArray, val contentType: String, val isPlaylist: Boolean) {
        val text: String get() = bytes.toString(Charsets.UTF_8)
        val rawSize: Long get() = bytes.size.toLong()
    }

    private fun request(url: String, headers: Map<String, String>): Request {
        if (!NetGuard.isPublicHttpUrl(url)) throw IllegalArgumentException("Blocked address")
        val req = Request.Builder().url(url).get()
        for ((k, v) in headers) {
            if (v.isNotEmpty() && !k.equals("host", true) && !k.equals("content-length", true)) {
                req.header(k, v)
            }
        }
        return req.build()
    }

    private fun get(url: String, headers: Map<String, String>): Got {
        client.newCall(request(url, headers)).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code} for $url")
            val bytes = res.body?.bytes() ?: ByteArray(0)
            val ct = res.header("Content-Type") ?: ""
            val head = bytes.take(20).toByteArray().toString(Charsets.ISO_8859_1).trimStart()
            val playlist = ct.contains("mpegurl", true) || ct.contains("m3u8", true) || head.startsWith("#EXTM3U")
            return Got(bytes, ct, playlist)
        }
    }

    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        try {
            return cipher("AES/CBC/PKCS5Padding", key, iv).doFinal(data)
        } catch (_: Exception) {
            return cipher("AES/CBC/NoPadding", key, iv).doFinal(data)
        }
    }

    private fun cipher(transformation: String, key: ByteArray, iv: ByteArray): Cipher {
        val c = Cipher.getInstance(transformation)
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c
    }

    private fun ivFor(playlist: HlsPlaylist.Playlist, sequence: Int): ByteArray {
        val hex = playlist.key?.ivHex
        if (hex != null && hex.startsWith("0x", true)) {
            val raw = hex.substring(2)
            if (raw.length == 32) {
                return ByteArray(16) { i -> raw.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            }
        }
        val iv = ByteArray(16)
        var n = sequence.toLong() and 0xffffffffL
        for (i in 15 downTo 8) {
            iv[i] = (n and 0xff).toByte()
            n = n shr 8
        }
        return iv
    }

    private fun progress(done: Long, total: Long, started: Long, message: String, finished: Int, count: Int): Map<String, Any?> {
        val pct = if (total > 0) ((done * 100.0) / total).roundToInt().coerceIn(0, 99) else 0
        return mapOf(
            "progress" to pct,
            "size" to formatBytes(done),
            "speed" to speed(done, started),
            "lastMessage" to message,
            "completedFragments" to finished,
            "totalFragments" to count,
            "status" to "downloading",
        )
    }

    private fun speed(bytes: Long, started: Long): String {
        val secs = (System.nanoTime() - started) / 1_000_000_000.0
        if (secs <= 0.2) return ""
        return formatBytes((bytes / secs).toLong()) + "/s"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes > 1_000_000_000) return "%.2f GB".format(bytes / 1_000_000_000.0)
        if (bytes > 1_000_000) return "%.1f MB".format(bytes / 1_000_000.0)
        if (bytes > 1_000) return "%.1f KB".format(bytes / 1_000.0)
        return "$bytes B"
    }

    private fun extensionFrom(url: String, contentType: String): String {
        val path = url.substringBefore("?").lowercase()
        listOf(".mp4", ".mkv", ".webm", ".m4v", ".ts").firstOrNull { path.endsWith(it) }?.let { return it }
        if (contentType.contains("webm")) return ".webm"
        if (contentType.contains("mp2t")) return ".ts"
        return ".mp4"
    }

    private fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001f]"), "").replace(Regex("\\s+"), " ").trim()
        return cleaned.take(120).ifEmpty { "video" }
    }
}

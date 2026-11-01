package com.truelockmc.streambert

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.getcapacitor.Bridge
import com.getcapacitor.BridgeWebViewClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Native half of the Android port. The React UI talks to this object through
 * an origin-locked WebMessageListener (https://localhost only), so player
 * pages cannot reach the filesystem.
 */
object StreambertEngine {
    private const val TAG = "Streambert"
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val PLAYER_HOST = "player.streambert.local"

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(6)
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val host = chain.request().url.host
            if (!NetGuard.isPublicHost(host)) throw java.io.IOException("Blocked address")
            chain.proceed(chain.request())
        }
        .build()

    private lateinit var app: Context
    private var activity: Activity? = null
    @Volatile private var playbackId: String? = null
    private var webView: WebView? = null
    private var bridge: Bridge? = null
    private var reloaded = false

    private lateinit var userData: File
    private lateinit var cacheDir: File
    private lateinit var moviesDir: File
    private lateinit var tmpDir: File

    val playerToken: String = hex(24)
    @Volatile private var playerUrl: String? = null
    @Volatile private var playerReferer: String = "https://hianime.at/"
    @Volatile private var playerSubtitle: String? = null
    @Volatile private var playerStart: Int = 0

    private val streamMeta = ConcurrentHashMap<String, JSONObject>()
    private val calls = ConcurrentHashMap<String, okhttp3.Call>()

    private var patterns: List<String> = emptyList()
    private var guestJs: String = ""
    private var hlsJs: ByteArray = ByteArray(0)

    private val statsLock = Any()
    private var statsTotal = 0
    private val statsDomains = mutableMapOf<String, Int>()
    private var pendingTotal = 0
    private val pendingDomains = mutableMapOf<String, Int>()
    private var flushPosted = false

    @Volatile var maximized = false
    @Volatile var pipWeb: WebView? = null
    private var pipQueryCallback: ((String?) -> Unit)? = null
    private var folderCallback: ((Uri?) -> Unit)? = null
    private var redeemCallback: ((JSONObject) -> Unit)? = null

    fun install(host: Activity, capBridge: Bridge) {
        app = host.applicationContext
        activity = host
        bridge = capBridge
        if ((host.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView = capBridge.webView
        userData = File(app.filesDir, "streambert").apply { mkdirs() }
        cacheDir = app.cacheDir
        moviesDir = app.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
            ?: File(app.filesDir, "Movies")
        moviesDir.mkdirs()
        tmpDir = File(cacheDir, "tmp").apply { mkdirs() }
        loadAssets()
        loadStats()
        configure(capBridge.webView)
        attachPrivileged(capBridge.webView)
        capBridge.webView.webViewClient = AppClient(capBridge)
        if (!reloaded) {
            reloaded = true
            capBridge.webView.post { capBridge.webView.reload() }
        }
    }

    fun launchFolderPicker() {
        val act = activity ?: return
        if (act is MainActivity) act.folderLauncher.launch(null)
    }

    fun onFolderPicked(uri: Uri?) {
        val cb = folderCallback
        folderCallback = null
        if (uri != null) {
            try {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) {
                Log.w(TAG, "persist folder permission", e)
            }
        }
        cb?.invoke(uri)
    }

    fun onRedeemKey(key: String) {
        val cb = redeemCallback
        redeemCallback = null
        cb?.invoke(JSONObject().put("ok", true).put("key", key))
    }

    fun onRedeemClosed() {
        val cb = redeemCallback ?: return
        redeemCallback = null
        cb.invoke(JSONObject().put("cancelled", true))
    }

    fun onPipClosed() {
        pipWeb = null
        push(JSONObject().put("type", "pip").put("open", false))
    }

    fun onPipQueryResult(json: String?) {
        val cb = pipQueryCallback
        pipQueryCallback = null
        cb?.invoke(json)
    }

    fun guestClient(): WebViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            return intercept(request)
        }
    }

    fun attachGuest(view: WebView) {
        configure(view)
        if (guestJs.isNotEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, guestJs, setOf("*"))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        val s = view.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.userAgentString = UA
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.textZoom = 100
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.javaScriptCanOpenWindowsAutomatically = false
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, true)
        cookies.setCookie("https://www.youtube.com", "SOCS=CAI; Path=/; Domain=.youtube.com; Secure")
        cookies.setCookie("https://www.youtube-nocookie.com", "SOCS=CAI; Path=/; Domain=.youtube-nocookie.com; Secure")
        view.setBackgroundColor(0xFF040F2D.toInt())
    }

    private fun attachPrivileged(view: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, bootScript(), setOf("https://localhost"))
            if (guestJs.isNotEmpty()) {
                WebViewCompat.addDocumentStartJavaScript(view, guestJs, setOf("*"))
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                view,
                "sbNative",
                setOf("https://localhost"),
            ) { _, message, origin, _, _ ->
                if (origin.host != "localhost") return@addWebMessageListener
                val data = message.data ?: return@addWebMessageListener
                io.execute {
                    try {
                        handle(data)
                    } catch (e: Exception) {
                        Log.e(TAG, "handle", e)
                    }
                }
            }
        } else {
            Log.e(TAG, "WebMessageListener is not supported by this WebView")
        }
    }

    private fun bootScript(): String {
        val o = JSONObject()
        o.put("ua", UA)
        o.put("version", versionName())
        o.put("userData", userData.absolutePath)
        o.put("cache", cacheDir.absolutePath)
        o.put("movies", moviesDir.absolutePath)
        o.put("tmp", tmpDir.absolutePath)
        o.put("downloadsJson", readSmall(File(userData, "downloads.json")))
        o.put("television", isTelevision())
        return "window.__SB_BOOT=" + o.toString().replace("<", "\\u003c") + ";"
    }

    private fun isTelevision(): Boolean {
        val ui = app.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        if (ui?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) return true
        return app.packageManager.hasSystemFeature("android.software.leanback")
    }

    private fun handle(raw: String) {
        val msg = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        val id = msg.optString("id")
        val method = msg.optString("method")
        val args = msg.optJSONObject("args") ?: JSONObject()
        if (id.isEmpty() || method.isEmpty()) return
        try {
            when (method) {
                "http" -> complete(id, httpCall(args))
                "fsWriteText" -> complete(id, fsWriteText(args))
                "fsReadText" -> complete(id, fsReadText(args))
                "fsWriteBase64" -> complete(id, fsWriteBase64(args))
                "fsUnlink" -> complete(id, fsUnlink(args))
                "fsStat" -> complete(id, fsStat(args))
                "fsReaddir" -> complete(id, fsReaddir(args))
                "pickFolder" -> pickFolder(id)
                "openExternal" -> complete(id, openExternal(args))
                "openFile" -> complete(id, openFile(args))
                "openStream" -> complete(id, openStream(args))
                "showInFolder" -> complete(id, showInFolder(args))
                "notify" -> complete(id, notify(args))
                "secureGet" -> complete(id, secureGet(args))
                "secureSet" -> complete(id, secureSet(args))
                "cacheSize" -> complete(id, JSONObject().put("bytes", cacheBytes()))
                "clearCache" -> complete(id, clearCache())
                "clearCookies" -> complete(id, clearCookies())
                "resetApp" -> complete(id, resetApp())
                "finish" -> main.post { activity?.finish() }.also { complete(id, JSONObject().put("ok", true)) }
                "windowOp" -> complete(id, windowOp(args))
                "pipOpen" -> pipOpen(id, args)
                "pipClose" -> {
                    main.post { PopoutActivity.closePlayer() }
                    complete(id, JSONObject().put("ok", true))
                }
                "pipQuery" -> pipQuery(id, args)
                "wyzieRedeem" -> wyzieRedeem(id)
                "downloadToFile" -> downloadToFile(id, args)
                "installApk" -> complete(id, installApk(args))
                "getInstallPath" -> complete(id, JSONObject().put("path", app.applicationInfo.sourceDir ?: ""))
                "videoDuration" -> complete(id, videoDuration(args))
                "scanDir" -> complete(id, scanDir(args))
                "playerStopped" -> complete(id, JSONObject().put("ok", true))
                "getStreamMeta" -> complete(id, streamMeta[args.optString("url")] ?: JSONObject())
                "setPlayerConfig" -> complete(id, setPlayerConfig(args))
                "startDownload" -> fail(id, "Downloads have been removed")
                "startPairing" -> startPairing(id)
                "playNative" -> playNative(id, args)
                "uiReady" -> {
                    main.post { MainActivity.uiReady = true }
                    complete(id, JSONObject().put("ok", true))
                }
                "stopPairing" -> {
                    PairingServer.stop()
                    complete(id, JSONObject().put("ok", true))
                }
                "cancelDownload" -> {
                    calls.remove(args.optString("id"))?.cancel()
                    complete(id, JSONObject().put("ok", true))
                }
                else -> fail(id, "Unknown method $method")
            }
        } catch (e: Exception) {
            Log.w(TAG, method, e)
            fail(id, e.message ?: "error")
        }
    }

    private fun complete(id: String, payload: Any?) {
        val json = when {
            payload == null || payload === JSONObject.NULL -> "null"
            payload is JSONObject -> payload.toString()
            payload is JSONArray -> payload.toString()
            payload is Boolean || payload is Int || payload is Long || payload is Double -> payload.toString()
            payload is String -> JSONObject.quote(payload)
            else -> JSONObject.quote(payload.toString())
        }
        val js = "window.__sbResult&&window.__sbResult(${JSONObject.quote(id)}, $json)"
        main.post { webView?.evaluateJavascript(js, null) }
    }

    private fun fail(id: String, message: String) {
        complete(id, JSONObject().put("__error", message))
    }

    private fun push(obj: JSONObject) {
        val js = "window.__sbPush&&window.__sbPush($obj)"
        main.post { webView?.evaluateJavascript(js, null) }
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        val host = request.url.host ?: return null
        if (host == PLAYER_HOST) return servePlayer(request)
        if (host == "localhost") return null
        if (Blocklist.matches(host, patterns)) {
            recordBlock(host)
            return emptyResponse()
        }
        val lower = url.lowercase()
        if (lower.contains(".m3u8")) {
            rememberMeta(url, request)
            push(JSONObject().put("type", "media").put("kind", "m3u8").put("url", url))
        } else if (lower.contains(".vtt")) {
            push(JSONObject().put("type", "media").put("kind", "vtt").put("url", url))
        }
        val dest = (request.requestHeaders["Sec-Fetch-Dest"] ?: request.requestHeaders["sec-fetch-dest"] ?: "")
        val accept = request.requestHeaders["Accept"] ?: request.requestHeaders["accept"] ?: ""
        val isDoc = dest == "document" || dest == "iframe" || accept.contains("text/html")
        if (!request.isForMainFrame && request.method == "GET" && isDoc && (url.startsWith("http://") || url.startsWith("https://"))) {
            return proxyDocument(url, request)
        }
        return null
    }

    private fun servePlayer(request: WebResourceRequest): WebResourceResponse {
        val uri = request.url
        val token = uri.getQueryParameter("t") ?: ""
        if (token != playerToken) return textResponse(403, "Forbidden", "forbidden")
        return when (uri.path) {
            "/player" -> htmlResponse(playerHtml())
            "/hls.js" -> {
                if (hlsJs.isEmpty()) textResponse(500, "Error", "hls.js missing")
                else bytesResponse("application/javascript", "utf-8", 200, "OK", hlsJs)
            }
            "/proxy" -> proxyMedia(uri.getQueryParameter("url") ?: "", request)
            else -> textResponse(404, "Not Found", "not found")
        }
    }

    private fun playerHtml(): String {
        val src = playerUrl ?: ""
        val proxied = "https://$PLAYER_HOST/proxy?t=$playerToken&url=" +
            java.net.URLEncoder.encode(src, "UTF-8")
        val sub = playerSubtitle?.takeIf { it.isNotBlank() }?.let {
            val u = "https://$PLAYER_HOST/proxy?t=$playerToken&url=" + java.net.URLEncoder.encode(it, "UTF-8")
            "<track kind=\"subtitles\" src=\"$u\" srclang=\"en\" label=\"Subtitles\" default>"
        } ?: ""
        val start = playerStart
        val quoted = JSONObject.quote(proxied)
        return """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <style>*{margin:0;padding:0}html,body{width:100%;height:100%;background:#000;overflow:hidden}video{width:100%;height:100%;object-fit:contain;background:#000}</style>
            </head><body>
            <video id="v" controls playsinline autoplay>$sub</video>
            <script src="/hls.js?t=$playerToken"></script>
            <script>
              const video=document.getElementById('v');
              const src=$quoted;
              const startTime=$start;
              if(window.Hls&&Hls.isSupported()){
                const hls=new Hls({autoStartLoad:false});
                hls.loadSource(src);hls.attachMedia(video);
                hls.on(Hls.Events.MANIFEST_PARSED,function(){
                  hls.startLevel=hls.levels.length-1;
                  hls.startLoad(startTime>0?startTime:-1);
                  video.play().catch(function(){});
                });
              }else if(video.canPlayType('application/vnd.apple.mpegurl')){
                video.src=src;
                if(startTime>0)video.addEventListener('loadedmetadata',function(){video.currentTime=startTime;},{once:true});
              }
            </script></body></html>
        """.trimIndent()
    }

    private fun proxyMedia(target: String, request: WebResourceRequest): WebResourceResponse {
        if (!NetGuard.isPublicHttpUrl(target)) return textResponse(403, "Forbidden", "blocked")
        return try {
            val builder = Request.Builder().url(target).get()
                .header("User-Agent", UA)
                .header("Referer", playerReferer)
                .header("Accept", "*/*")
            val range = request.requestHeaders["Range"] ?: request.requestHeaders["range"]
            if (!range.isNullOrEmpty()) builder.header("Range", range)
            http.newCall(builder.build()).execute().use { res ->
                val bytes = res.body?.bytes() ?: ByteArray(0)
                val ct = res.header("Content-Type") ?: "application/octet-stream"
                val head = bytes.take(24).toByteArray().toString(Charsets.ISO_8859_1).trimStart()
                if (head.startsWith("#EXTM3U") || ct.contains("mpegurl", true)) {
                    val rewritten = HlsPlaylist.rewrite(bytes.toString(Charsets.UTF_8), target, playerToken)
                    bytesResponse("application/vnd.apple.mpegurl", "utf-8", 200, "OK", rewritten.toByteArray())
                } else {
                    bytesResponse(ct.substringBefore(";"), "utf-8", res.code, "OK", bytes)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "proxy", e)
            textResponse(502, "Bad Gateway", "proxy error")
        }
    }

    private fun proxyDocument(url: String, request: WebResourceRequest): WebResourceResponse? {
        if (!NetGuard.isPublicHttpUrl(url)) return emptyResponse()
        return try {
            val builder = Request.Builder().url(url).get().header("User-Agent", UA)
            request.requestHeaders.forEach { (k, v) ->
                val lk = k.lowercase()
                if (lk != "host" && lk != "content-length" && lk != "accept-encoding" && lk != "user-agent") {
                    try {
                        builder.header(k, v)
                    } catch (_: Exception) {}
                }
            }
            http.newCall(builder.build()).execute().use { res ->
                var bytes = res.body?.bytes() ?: ByteArray(0)
                val ct = res.header("Content-Type") ?: "text/html"
                if (ct.contains("html", true) && guestJs.isNotEmpty()) {
                    val html = bytes.toString(Charsets.UTF_8)
                    val injected = if (html.contains("<head>", true)) {
                        html.replaceFirst(Regex("<head>", RegexOption.IGNORE_CASE), "<head><script>$guestJs</script>")
                    } else "<script>$guestJs</script>$html"
                    bytes = injected.toByteArray()
                }
                val headers = mutableMapOf(
                    "Content-Type" to ct,
                    "Cache-Control" to "no-store",
                )
                bytesResponse(ct.substringBefore(";"), "utf-8", res.code, "OK", bytes, headers)
            }
        } catch (e: Exception) {
            Log.w(TAG, "document proxy", e)
            null
        }
    }

    private fun rememberMeta(url: String, request: WebResourceRequest) {
        if (streamMeta.containsKey(url)) return
        val headers = request.requestHeaders
        fun get(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value ?: ""
        val page = headers["Referer"] ?: headers["referer"] ?: ""
        streamMeta[url] = JSONObject()
            .put("referer", get("referer").ifEmpty { page })
            .put("origin", get("origin"))
            .put("userAgent", get("user-agent").ifEmpty { UA })
    }

    private fun recordBlock(host: String) {
        val domain = host.removePrefix("www.")
        synchronized(statsLock) {
            statsTotal++
            statsDomains[domain] = (statsDomains[domain] ?: 0) + 1
            pendingTotal++
            pendingDomains[domain] = (pendingDomains[domain] ?: 0) + 1
            if (!flushPosted) {
                flushPosted = true
                main.postDelayed({ flushBlocks() }, 250)
            }
        }
    }

    private fun flushBlocks() {
        val batch: JSONObject
        synchronized(statsLock) {
            flushPosted = false
            batch = JSONObject().put("total", pendingTotal)
            val domains = JSONObject()
            pendingDomains.forEach { (k, v) -> domains.put(k, v) }
            batch.put("domains", domains)
            pendingTotal = 0
            pendingDomains.clear()
            try {
                val stored = JSONObject().put("total", statsTotal)
                val all = JSONObject()
                statsDomains.forEach { (k, v) -> all.put(k, v) }
                stored.put("domains", all)
                File(userData, "blockStats.json").writeText(stored.toString())
            } catch (_: Exception) {}
        }
        if (batch.optInt("total") > 0) {
            push(JSONObject().put("type", "blocked").put("batch", batch))
        }
    }

    private fun httpCall(args: JSONObject): JSONObject {
        val url = args.optString("url")
        if (!NetGuard.isPublicHttpUrl(url)) return JSONObject().put("error", "Blocked address").put("status", 0)
        val method = args.optString("method", "GET")
        val timeout = args.optLong("timeoutMs", 20000L)
        val binary = args.optBoolean("binary", false)
        val client = http.newBuilder().callTimeout(timeout, TimeUnit.MILLISECONDS).build()
        val builder = Request.Builder().url(url)
        val headers = args.optJSONObject("headers")
        if (headers != null) {
            val it = headers.keys()
            while (it.hasNext()) {
                val k = it.next()
                builder.header(k, headers.optString(k))
            }
        }
        val bodyText = if (args.has("body")) args.optString("body") else null
        if (method.equals("POST", true)) {
            val mt = (headers?.optString("Content-Type") ?: "application/json").toMediaType()
            builder.post((bodyText ?: "").toRequestBody(mt))
        } else builder.get()
        client.newCall(builder.build()).execute().use { res ->
            val bytes = res.body?.bytes() ?: ByteArray(0)
            if (bytes.size > if (binary) 8 * 1024 * 1024 else 6 * 1024 * 1024) {
                return JSONObject().put("error", "response too large").put("status", res.code)
            }
            val out = JSONObject()
                .put("status", res.code)
                .put("url", res.request.url.toString())
                .put("contentType", res.header("Content-Type") ?: "")
            if (binary) out.put("bodyBase64", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("body", "")
            else out.put("body", bytes.toString(Charsets.UTF_8)).put("bodyBase64", "")
            return out
        }
    }

    private fun setPlayerConfig(args: JSONObject): JSONObject {
        val url = args.optString("url")
        val referer = args.optString("referer").ifBlank { "https://hianime.at/" }
        val subtitle = args.optString("subtitle")
        if (!NetGuard.isPublicHttpUrl(url)) throw IllegalArgumentException("Blocked address")
        if (!NetGuard.isPublicHttpUrl(referer)) throw IllegalArgumentException("Blocked address")
        if (subtitle.isNotBlank() && !NetGuard.isPublicHttpUrl(subtitle)) throw IllegalArgumentException("Blocked address")
        playerUrl = url
        playerReferer = referer
        playerSubtitle = subtitle.ifBlank { null }
        playerStart = args.optInt("startTime", 0).coerceAtLeast(0)
        return JSONObject().put(
            "playerUrl",
            "https://$PLAYER_HOST/player?t=$playerToken",
        )
    }

    fun finishPlayback(result: JSONObject) {
        val id = playbackId ?: return
        playbackId = null
        complete(id, result)
    }

    private fun playNative(id: String, args: JSONObject) {
        val previous = playbackId
        if (previous != null) {
            playbackId = null
            complete(previous, JSONObject().put("ok", false).put("error", "replaced"))
        }
        playbackId = id
        main.post {
            val host = activity
            if (host == null || host.isDestroyed) {
                if (playbackId == id) playbackId = null
                fail(id, "The app is not in the foreground.")
                return@post
            }
            val intent = Intent(host, PlayerActivity::class.java)
            intent.putExtra("title", args.optString("title"))
            intent.putExtra("subtitle", args.optString("subtitle"))
            intent.putExtra("sources", (args.optJSONArray("sources") ?: JSONArray()).toString())
            intent.putExtra("startMs", args.optLong("startMs", 0L))
            intent.putExtra("handoff", args.optString("handoff"))
            intent.putExtra("audioLang", args.optString("audioLang"))
            val direct = args.optString("directUrl")
            if (direct.isNotBlank()) intent.putExtra("directUrl", direct)
            try {
                host.startActivity(intent)
            } catch (e: Exception) {
                if (playbackId == id) playbackId = null
                fail(id, e.message ?: "Could not open the player")
            }
        }
    }

    private fun startPairing(id: String) {
        try {
            val session = PairingServer.start { keys ->
                val prefs = securePrefs().edit()
                for ((k, v) in keys) prefs.putString(k, v)
                prefs.apply()
                val names = org.json.JSONArray()
                keys.keys.forEach { names.put(it) }
                push(JSONObject().put("type", "phone-keys").put("keys", names))
            }
            complete(
                id,
                JSONObject()
                    .put("ok", true)
                    .put("url", session.url)
                    .put("code", session.code)
                    .put("host", session.host)
                    .put("port", session.port)
                    .put("expiresAt", session.expiresAt),
            )
        } catch (e: Exception) {
            complete(id, JSONObject().put("ok", false).put("error", e.message ?: "Pairing unavailable"))
        }
    }

    private fun downloadToFile(id: String, args: JSONObject) {
        val url = args.optString("url")
        val dest = args.optString("dest")
        val job = args.optString("id", "update")
        val hosts = mutableSetOf<String>()
        val arr = args.optJSONArray("hosts")
        if (arr != null) for (i in 0 until arr.length()) hosts.add(arr.optString(i))
        io.execute {
            try {
                val initialHost = android.net.Uri.parse(url).host ?: ""
                if (hosts.isNotEmpty() && initialHost !in hosts) throw IllegalStateException("Unauthorized update source")
                val call = http.newBuilder().addNetworkInterceptor { chain ->
                    val host = chain.request().url.host
                    if (hosts.isNotEmpty() && host !in hosts) throw java.io.IOException("Untrusted redirect host: $host")
                    chain.proceed(chain.request())
                }.build().newCall(Request.Builder().url(url).header("User-Agent", "Streambert-AutoUpdater").build())
                calls[job] = call
                call.execute().use { res ->
                    if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                    val body = res.body ?: throw IllegalStateException("Empty update")
                    val total = body.contentLength()
                    ensureParent(dest)
                    var done = 0L
                    File(dest).outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                if (call.isCanceled()) throw IllegalStateException("Cancelled")
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                                push(
                                    JSONObject().put("type", "update").put("percent", pct)
                                        .put("label", "Downloading… %.1f MB".format(done / 1e6)),
                                )
                            }
                        }
                    }
                }
                complete(id, JSONObject().put("ok", true).put("path", dest))
            } catch (e: Exception) {
                complete(id, JSONObject().put("ok", false).put("error", e.message ?: "Update download failed"))
            } finally {
                calls.remove(job)
            }
        }
    }

    private fun pickFolder(id: String) {
        folderCallback = { uri ->
            if (uri == null) complete(id, JSONObject().put("path", JSONObject.NULL))
            else complete(id, JSONObject().put("path", uri.toString()))
        }
        main.post { launchFolderPicker() }
    }

    private fun wyzieRedeem(id: String) {
        redeemCallback = { obj -> complete(id, obj) }
        main.post {
            val act = activity ?: return@post
            act.startActivity(
                Intent(act, PopoutActivity::class.java)
                    .putExtra("mode", "redeem")
                    .putExtra("url", "https://store.wyzie.io/redeem"),
            )
        }
        main.postDelayed({
            if (redeemCallback != null) {
                val cb = redeemCallback
                redeemCallback = null
                cb?.invoke(JSONObject().put("timeout", true))
            }
        }, 180000)
    }

    private fun pipOpen(id: String, args: JSONObject) {
        val url = args.optString("url")
        if (!url.startsWith("http")) {
            complete(id, JSONObject().put("ok", false).put("reason", "no-url"))
            return
        }
        main.post {
            val act = activity
            if (act == null) {
                complete(id, JSONObject().put("ok", false))
                return@post
            }
            val existing = PopoutActivity.current
            if (existing != null) {
                existing.load(url)
                push(JSONObject().put("type", "pip").put("open", true))
                complete(id, JSONObject().put("ok", true))
            } else {
                act.startActivity(
                    Intent(act, PopoutActivity::class.java)
                        .putExtra("mode", "player")
                        .putExtra("url", url)
                        .putExtra("title", args.optString("title")),
                )
                complete(id, JSONObject().put("ok", true))
            }
        }
    }

    private fun pipQuery(id: String, args: JSONObject) {
        val view = pipWeb
        val code = args.optString("code")
        if (view == null) {
            complete(id, JSONObject.NULL)
            return
        }
        val nonce = hex(8)
        pipQueryCallback = { json ->
            val value = try {
                if (json.isNullOrBlank() || json == "null") JSONObject.NULL else JSONObject(json)
            } catch (_: Exception) {
                JSONObject.NULL
            }
            complete(id, value)
        }
        val payload = JSONObject.quote(code)
        val js = """
            (function(){
              var id=${JSONObject.quote(nonce)};
              var code=$payload;
              var results=[];
              function finish(){
                var best=null;
                for(var i=0;i<results.length;i++){ if(results[i]&&results[i].duration>0){ best=results[i]; break; } }
                if(window.StreambertPip) window.StreambertPip.result(best?JSON.stringify(best):'null');
              }
              function onMsg(e){
                if(!e.data||e.data.type!=='sb-broadcast-result'||e.data.id!==id) return;
                if(e.data.value) results.push(e.data.value);
              }
              window.addEventListener('message', onMsg);
              try {
                var local=(0,eval)(code);
                if(local) results.push(local);
              } catch(e) {}
              var msg={type:'sb-broadcast', id:id, code:code};
              for(var i=0;i<window.frames.length;i++){ try{ window.frames[i].postMessage(msg,'*'); }catch(e){} }
              setTimeout(function(){ window.removeEventListener('message', onMsg); finish(); }, 500);
            })();
        """.trimIndent()
        main.post { view.evaluateJavascript(js, null) }
        main.postDelayed({
            if (pipQueryCallback != null) onPipQueryResult("null")
        }, 2000)
    }

    private fun openExternal(args: JSONObject): JSONObject {
        val url = args.optString("url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return JSONObject().put("ok", false)
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        return JSONObject().put("ok", true)
    }

    private fun openFile(args: JSONObject): JSONObject {
        val path = args.optString("path")
        if (path.isEmpty()) return JSONObject().put("ok", false)
        ensure(path)
        val uri = toUri(path)
        val mime = mimeFor(path)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val pos = args.optLong("positionMs", 0)
            if (pos > 0) putExtra("position", pos.toInt())
            val sub = args.optString("subtitle")
            if (sub.isNotBlank()) putExtra("subtitles_location", sub)
        }
        try {
            app.startActivity(Intent.createChooser(intent, "Open").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            return JSONObject().put("ok", false).put("error", e.message ?: "No app can open this file")
        }
        return JSONObject().put("ok", true)
    }

    private fun openStream(args: JSONObject): JSONObject {
        val url = args.optString("url")
        if (!url.startsWith("http")) return JSONObject().put("ok", false).put("error", "Invalid stream URL")
        val headers = linkedMapOf<String, String>()
        val ua = args.optString("userAgent")
        val referer = args.optString("referer")
        val origin = args.optString("origin")
        if (ua.isNotBlank()) headers["User-Agent"] = ua
        if (referer.isNotBlank()) headers["Referer"] = referer
        if (origin.isNotBlank()) headers["Origin"] = origin
        val subs = ArrayList<String>()
        val one = args.optString("subtitle")
        if (one.isNotBlank()) subs.add(one)
        val extra = args.optJSONArray("subtitles")
        if (extra != null) {
            for (i in 0 until extra.length()) {
                val sub = extra.optString(i)
                if (sub.isNotBlank() && sub !in subs) subs.add(sub)
            }
        }
        val opened = ExternalPlayer.open(
            app,
            url,
            args.optString("title"),
            headers,
            subs,
            args.optLong("positionMs", 0L),
        )
        return if (opened) JSONObject().put("ok", true).put("player", "android")
        else JSONObject().put("ok", false).put("error", "No external player found. Install VLC, MX Player, or Just Player.")
    }

    private fun showInFolder(args: JSONObject): JSONObject {
        val path = args.optString("path")
        if (path.isEmpty()) return JSONObject().put("ok", false)
        return try {
            openFile(JSONObject().put("path", path))
        } catch (e: Exception) {
            JSONObject().put("ok", false)
        }
    }

    private fun notify(args: JSONObject): JSONObject {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) activity?.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
        }
        val nm = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("streambert", "Streambert", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val notification = NotificationCompat.Builder(app, "streambert")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(args.optString("title", "Streambert"))
            .setContentText(args.optString("body"))
            .setSilent(args.optBoolean("silent", false))
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), notification)
        return JSONObject().put("ok", true)
    }

    private fun securePrefs() = try {
        val alias = androidx.security.crypto.MasterKeys.getOrCreate(androidx.security.crypto.MasterKeys.AES256_GCM_SPEC)
        androidx.security.crypto.EncryptedSharedPreferences.create(
            "streambert_secure",
            alias,
            app,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.w(TAG, "encrypted prefs unavailable", e)
        app.getSharedPreferences("streambert_secure_fallback", Context.MODE_PRIVATE)
    }

    private fun secureGet(args: JSONObject): JSONObject {
        val value = securePrefs().getString(args.optString("key"), null)
        return JSONObject().put("value", value ?: JSONObject.NULL)
    }

    private fun secureSet(args: JSONObject): JSONObject {
        val key = args.optString("key")
        val value = args.optString("value")
        securePrefs().edit().apply {
            if (value.isEmpty()) remove(key) else putString(key, value)
        }.apply()
        return JSONObject().put("ok", true)
    }

    private fun clearCache(): JSONObject {
        main.post {
            webView?.clearCache(true)
            pipWeb?.clearCache(true)
        }
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        return JSONObject().put("ok", true)
    }

    private fun clearCookies(): JSONObject {
        CookieManager.getInstance().removeAllCookies(null)
        main.post {
            webView?.clearCache(true)
        }
        return JSONObject().put("ok", true)
    }

    private fun resetApp(): JSONObject {
        clearCookies()
        clearCache()
        android.webkit.WebStorage.getInstance().deleteAllData()
        File(userData, "downloads.json").delete()
        securePrefs().edit().clear().apply()
        return JSONObject().put("ok", true)
    }

    private fun windowOp(args: JSONObject): JSONObject {
        return when (args.optString("op")) {
            "minimize" -> {
                main.post { activity?.moveTaskToBack(true) }
                JSONObject().put("ok", true)
            }
            "maximize" -> {
                maximized = !maximized
                main.post {
                    val act = activity ?: return@post
                    if (maximized) {
                        act.window.decorView.systemUiVisibility =
                            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    } else {
                        act.window.decorView.systemUiVisibility = 0
                    }
                }
                push(JSONObject().put("type", "maximized").put("value", maximized))
                JSONObject().put("value", maximized)
            }
            "isMaximized" -> JSONObject().put("value", maximized)
            else -> JSONObject().put("ok", true)
        }
    }

    private fun installApk(args: JSONObject): JSONObject {
        val path = args.optString("path")
        val file = File(path)
        if (!file.exists()) return JSONObject().put("ok", false).put("error", "APK missing")
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:" + app.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            return JSONObject().put("ok", false).put("error", "Allow installs from this app, then try again.")
        }
        val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        app.startActivity(intent)
        return JSONObject().put("ok", true)
    }

    private fun videoDuration(args: JSONObject): JSONObject {
        val path = args.optString("path")
        if (path.isEmpty()) return JSONObject().put("ok", false)
        return try {
            val r = MediaMetadataRetriever()
            if (path.startsWith("content:")) r.setDataSource(app, Uri.parse(path.substringBefore("::")))
            else r.setDataSource(path)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.release()
            if (ms <= 0) JSONObject().put("ok", false) else JSONObject().put("ok", true).put("duration", ms / 1000.0)
        } catch (e: Exception) {
            JSONObject().put("ok", false)
        }
    }

    private fun scanDir(args: JSONObject): JSONArray {
        val path = args.optString("path")
        val out = JSONArray()
        if (path.isEmpty()) return out
        try {
            if (path.startsWith("content:")) scanTree(Uri.parse(path.substringBefore("::")), 0, out)
            else scanFile(File(path), 0, out)
        } catch (e: Exception) {
            Log.w(TAG, "scan", e)
        }
        return out
    }

    private val videoExts = setOf(".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".ts")

    private fun scanFile(dir: File, depth: Int, out: JSONArray) {
        if (depth > 3 || !dir.isDirectory) return
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) scanFile(f, depth + 1, out)
            else {
                val ext = f.name.lowercase().let { n -> videoExts.firstOrNull { n.endsWith(it) } }
                if (ext != null) {
                    out.put(
                        JSONObject()
                            .put("filePath", f.absolutePath)
                            .put("name", f.name.removeSuffix(ext))
                            .put("ext", ext)
                            .put("size", formatBytes(f.length())),
                    )
                }
            }
        }
    }

    private fun scanTree(uri: Uri, depth: Int, out: JSONArray) {
        if (depth > 3) return
        val doc = DocumentFile.fromTreeUri(app, uri) ?: return
        for (child in doc.listFiles()) {
            if (child.isDirectory) {
                // Tree URIs don't nest the same way; list one level of folders via their uri when possible.
                continue
            }
            val name = child.name ?: continue
            val ext = videoExts.firstOrNull { name.lowercase().endsWith(it) } ?: continue
            out.put(
                JSONObject()
                    .put("filePath", child.uri.toString())
                    .put("name", name.removeSuffix(ext))
                    .put("ext", ext)
                    .put("size", formatBytes(child.length())),
            )
        }
    }

    private fun fsWriteText(args: JSONObject): JSONObject {
        val path = args.optString("path")
        ensure(path)
        writeBytes(path, args.optString("text").toByteArray())
        return JSONObject().put("ok", true)
    }

    private fun fsReadText(args: JSONObject): JSONObject {
        val path = args.optString("path")
        ensure(path)
        val bytes = readBytes(path) ?: return JSONObject().put("text", "")
        return JSONObject().put("text", bytes.toString(Charsets.UTF_8))
    }

    private fun fsWriteBase64(args: JSONObject): JSONObject {
        val path = args.optString("path")
        ensure(path)
        val bytes = Base64.decode(args.optString("base64"), Base64.DEFAULT)
        writeBytes(path, bytes)
        return JSONObject().put("ok", true)
    }

    private fun fsUnlink(args: JSONObject): JSONObject {
        val path = args.optString("path")
        ensure(path)
        if (path.startsWith("content:")) {
            val uri = Uri.parse(path.substringBefore("::"))
            if (path.contains("::")) {
                val name = path.substringAfter("::")
                DocumentFile.fromTreeUri(app, uri)?.findFile(name)?.delete()
            } else DocumentFile.fromSingleUri(app, uri)?.delete()
        } else File(path).delete()
        return JSONObject().put("ok", true)
    }

    private fun fsStat(args: JSONObject): JSONObject {
        val path = args.optString("path")
        if (path.startsWith("content:")) {
            val doc = if (path.contains("::")) {
                val uri = Uri.parse(path.substringBefore("::"))
                DocumentFile.fromTreeUri(app, uri)?.findFile(path.substringAfter("::"))
            } else DocumentFile.fromSingleUri(app, Uri.parse(path))
            if (doc == null || !doc.exists()) return JSONObject().put("exists", false)
            return JSONObject().put("exists", true).put("isFile", doc.isFile).put("isDirectory", doc.isDirectory)
                .put("size", doc.length()).put("mtime", doc.lastModified())
        }
        val f = File(path)
        if (!f.exists()) return JSONObject().put("exists", false)
        return JSONObject().put("exists", true).put("isFile", f.isFile).put("isDirectory", f.isDirectory)
            .put("size", f.length()).put("mtime", f.lastModified())
    }

    private fun fsReaddir(args: JSONObject): JSONObject {
        val path = args.optString("path")
        val names = JSONArray()
        if (path.startsWith("content:")) {
            DocumentFile.fromTreeUri(app, Uri.parse(path.substringBefore("::")))?.listFiles()?.forEach { child ->
                child.name?.let { names.put(it) }
            }
        } else {
            File(path).list()?.forEach { names.put(it) }
        }
        return JSONObject().put("entries", names)
    }

    private fun openOut(dir: String, filename: String): Pair<OutputStream, String> {
        ensure(dir)
        if (dir.startsWith("content:")) {
            val tree = DocumentFile.fromTreeUri(app, Uri.parse(dir.substringBefore("::")))
                ?: throw IllegalStateException("Folder unavailable")
            val mime = mimeFor(filename)
            tree.findFile(filename)?.delete()
            val doc = tree.createFile(mime, filename) ?: throw IllegalStateException("Could not create file")
            val stream = app.contentResolver.openOutputStream(doc.uri) ?: throw IllegalStateException("Could not write")
            return stream to doc.uri.toString()
        }
        val folder = File(dir)
        folder.mkdirs()
        val file = File(folder, filename)
        return file.outputStream() to file.absolutePath
    }

    private fun writeBytes(path: String, bytes: ByteArray) {
        if (path.startsWith("content:")) {
            val (stream, _) = if (path.contains("::")) {
                openOut(path.substringBefore("::"), path.substringAfter("::"))
            } else {
                val uri = Uri.parse(path)
                val stream = app.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IllegalStateException("Could not write")
                stream to path
            }
            stream.use { it.write(bytes) }
            return
        }
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun readBytes(path: String): ByteArray? {
        if (path.startsWith("content:")) {
            val uri = if (path.contains("::")) {
                val tree = DocumentFile.fromTreeUri(app, Uri.parse(path.substringBefore("::"))) ?: return null
                tree.findFile(path.substringAfter("::"))?.uri ?: return null
            } else Uri.parse(path)
            return app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
        val file = File(path)
        if (!file.exists() || !file.isFile) return null
        return file.readBytes()
    }

    private fun ensure(path: String) {
        if (path.startsWith("content:")) return
        val file = File(path).canonicalFile
        val roots = listOf(userData, cacheDir, moviesDir, app.filesDir, app.getExternalFilesDir(null))
            .filterNotNull()
            .map { it.canonicalFile.absolutePath }
        val ok = roots.any { file.absolutePath == it || file.absolutePath.startsWith(it + File.separator) }
        if (!ok) throw SecurityException("Path is outside app storage")
    }

    private fun ensureParent(path: String) {
        ensure(File(path).parent ?: path)
    }

    private fun toUri(path: String): Uri {
        if (path.startsWith("content:")) return Uri.parse(path.substringBefore("::"))
        val file = File(path)
        return FileProvider.getUriForFile(app, app.packageName + ".fileprovider", file)
    }

    private fun mimeFor(path: String): String {
        val p = path.lowercase()
        return when {
            p.endsWith(".mp4") || p.endsWith(".m4v") -> "video/mp4"
            p.endsWith(".webm") -> "video/webm"
            p.endsWith(".mkv") -> "video/x-matroska"
            p.endsWith(".ts") -> "video/mp2t"
            p.endsWith(".srt") || p.endsWith(".vtt") -> "text/plain"
            p.endsWith(".json") -> "application/json"
            else -> "application/octet-stream"
        }
    }

    private fun cacheBytes(): Long {
        fun walk(f: File): Long {
            if (!f.exists()) return 0
            if (f.isFile) return f.length()
            return f.listFiles()?.sumOf { walk(it) } ?: 0
        }
        return walk(cacheDir) + walk(File(app.applicationInfo.dataDir, "app_webview"))
    }

    private fun loadAssets() {
        try {
            patterns = app.assets.open("blocklist.txt").bufferedReader().readLines()
                .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        } catch (_: Exception) {
            patterns = emptyList()
        }
        try {
            guestJs = app.assets.open("guest.js").bufferedReader().readText()
        } catch (_: Exception) {
            guestJs = ""
        }
        try {
            hlsJs = app.assets.open("hls.min.js").readBytes()
        } catch (_: Exception) {
            hlsJs = ByteArray(0)
        }
    }

    private fun loadStats() {
        try {
            val o = JSONObject(readSmall(File(userData, "blockStats.json")))
            statsTotal = o.optInt("total")
            val domains = o.optJSONObject("domains") ?: return
            val it = domains.keys()
            while (it.hasNext()) {
                val k = it.next()
                statsDomains[k] = domains.optInt(k)
            }
        } catch (_: Exception) {}
    }

    private fun readSmall(file: File): String {
        if (!file.exists()) return ""
        val text = file.readText()
        return if (text.length > 2_000_000) "" else text
    }

    private fun versionName(): String {
        return try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "2.7.0"
        } catch (_: Exception) {
            "2.7.0"
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes > 1_000_000_000) return "%.2f GB".format(bytes / 1_000_000_000.0)
        if (bytes > 1_000_000) return "%.1f MB".format(bytes / 1_000_000.0)
        if (bytes > 1_000) return "%.1f KB".format(bytes / 1_000.0)
        return "$bytes B"
    }

    private fun emptyResponse() = bytesResponse("text/plain", "utf-8", 200, "OK", ByteArray(0))

    private fun textResponse(code: Int, reason: String, body: String) =
        bytesResponse("text/plain", "utf-8", code, reason, body.toByteArray())

    private fun htmlResponse(html: String) =
        bytesResponse("text/html", "utf-8", 200, "OK", html.toByteArray())

    private fun bytesResponse(
        mime: String,
        encoding: String,
        code: Int,
        reason: String,
        bytes: ByteArray,
        extra: Map<String, String> = emptyMap(),
    ): WebResourceResponse {
        val headers = mutableMapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*")
        headers.putAll(extra)
        headers["Content-Length"] = bytes.size.toString()
        return WebResourceResponse(mime, encoding, code, reason.ifBlank { "OK" }, headers, ByteArrayInputStream(bytes))
    }

    private fun hex(bytes: Int): String {
        val raw = ByteArray(bytes)
        SecureRandom().nextBytes(raw)
        return raw.joinToString("") { "%02x".format(it) }
    }

    private class AppClient(bridge: Bridge) : BridgeWebViewClient(bridge) {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            return StreambertEngine.intercept(request) ?: super.shouldInterceptRequest(view, request)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.e(TAG, "render process gone crashed=${detail.didCrash()}")
            return true
        }
    }
}

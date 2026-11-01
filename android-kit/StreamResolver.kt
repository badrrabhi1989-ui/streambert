package com.truelockmc.streambert

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Loads a provider embed as its own top-level page and reports the best
 * HLS, DASH, or MP4 URL. These pages often send frame-ancestors 'none', so an
 * iframe inside the app never leaves the loading state.
 */
class StreamResolver(private val context: Context) {
    data class Hit(
        val url: String,
        val headers: Map<String, String>,
        val referer: String,
        val subtitle: String?,
    )

    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var settled = false
    private var bestUrl: String? = null
    private var bestHeaders: Map<String, String> = emptyMap()
    private var bestRank = 0
    private var subtitle: String? = null
    private var pageUrl: String = ""
    private var onResult: ((Hit?) -> Unit)? = null
    private var commit: Runnable? = null
    private var giveUp: Runnable? = null
    private var generation = 0
    private var host: FrameLayout? = null
    private val extras = ArrayList<WebView>()
    private var seenCount = 0

    fun resolve(parent: FrameLayout, url: String, callback: (Hit?) -> Unit) {
        cancelTimers()
        generation += 1
        val gen = generation
        settled = false
        bestUrl = null
        bestHeaders = emptyMap()
        bestRank = 0
        subtitle = null
        pageUrl = url
        seenCount = 0
        onResult = callback
        host = parent
        val view = ensureWeb(parent)
        view.onResume()
        view.stopLoading()
        giveUp = Runnable { if (gen == generation) finish(bestHit()) }
        main.postDelayed(giveUp!!, TIMEOUT_MS)
        main.postDelayed({
            if (gen == generation) web?.evaluateJavascript(SNIFF, null)
        }, 4000)
        view.loadUrl(url)
    }

    fun stop() {
        cancelTimers()
        settled = true
        onResult = null
        destroyExtras()
        val view = web ?: return
        web = null
        view.stopLoading()
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    private fun ensureWeb(parent: FrameLayout): WebView {
        web?.let { return it }
        val created = buildWebView()
        created.alpha = 0f
        parent.addView(created, 0, FrameLayout.LayoutParams(1280, 720))
        web = created
        return created
    }

    private fun bestHit(): Hit? {
        val url = bestUrl ?: return null
        return Hit(url, bestHeaders, pageUrl, subtitle)
    }

    private fun finish(hit: Hit?) {
        if (settled) return
        settled = true
        cancelTimers()
        web?.apply {
            stopLoading()
            onPause()
            loadUrl("about:blank")
        }
        destroyExtras()
        val cb = onResult
        onResult = null
        cb?.invoke(hit)
    }

    private fun cancelTimers() {
        commit?.let { main.removeCallbacks(it) }
        giveUp?.let { main.removeCallbacks(it) }
        commit = null
        giveUp = null
    }

    private fun consider(url: String, headers: Map<String, String>) {
        if (settled) return
        val gen = generation
        if (MediaUrls.isSubtitle(url) && subtitle == null) subtitle = url
        val rank = MediaUrls.rank(url)
        val lower = url.lowercase()
        if (rank > 0 || lower.contains("m3u8") || lower.contains(".mp4") || lower.contains(".mpd") || lower.contains("mpegurl")) {
            Log.i(TAG, "seen rank=$rank ${url.take(180)}")
        }
        if (rank <= bestRank) return
        bestRank = rank
        bestUrl = url
        bestHeaders = keepHeaders(headers)
        Log.i(TAG, "stream rank=$rank ${url.take(180)}")
        commit?.let { main.removeCallbacks(it) }
        val task = Runnable { if (gen == generation) finish(bestHit()) }
        commit = task
        main.postDelayed(task, if (rank >= 100) 600L else 1600L)
    }

    private fun keepHeaders(headers: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        out["User-Agent"] = headers["User-Agent"] ?: StreambertEngine.UA
        out["Referer"] = headers["Referer"] ?: pageUrl
        headers["Origin"]?.let { out["Origin"] = it }
        headers["Cookie"]?.let { out["Cookie"] = it }
        return out
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        return WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = StreambertEngine.UA
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            settings.loadsImagesAutomatically = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            cookies.setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(JsSniffer(), "EmberlaneSniffer")
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message,
                ): Boolean {
                    val popup = buildWebView()
                    popup.alpha = 0f
                    host?.addView(popup, 0, FrameLayout.LayoutParams(1280, 720))
                    extras.add(popup)
                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    Log.i(TAG, "popup opened for $pageUrl")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                    val target = request.url?.toString() ?: return null
                    seenCount += 1
                    if (seenCount <= 8 || seenCount % 40 == 0) {
                        Log.i(TAG, "req#$seenCount ${target.take(160)}")
                    }
                    val headers = HashMap<String, String>()
                    request.requestHeaders?.forEach { (key, value) ->
                        if (value != null) headers[key] = value
                    }
                    main.post { consider(target, headers) }
                    return null
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    Log.i(TAG, "finished $url")
                    view.evaluateJavascript(
                        "(function(){var t=document.title||'';var b=(document.body&&document.body.innerText)||'';return t+' :: '+b.slice(0,220);})()",
                    ) { value -> Log.i(TAG, "page $value") }
                    view.evaluateJavascript(SNIFF, null)
                    view.evaluateJavascript(NUDGE, null)
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    // The sniffer is discarded once a stream URL is found. A few
                    // embed CDNs present a certificate the system store rejects.
                    handler.proceed()
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    Log.e(TAG, "render process gone crashed=${detail.didCrash()}")
                    main.post { if (!settled) finish(bestHit()) }
                    return true
                }
            }
        }
    }

    private fun destroyExtras() {
        val copy = extras.toList()
        extras.clear()
        for (popup in copy) {
            try {
                popup.stopLoading()
                (popup.parent as? ViewGroup)?.removeView(popup)
                popup.destroy()
            } catch (_: Exception) {}
        }
    }

    private inner class JsSniffer {
        @JavascriptInterface
        fun onMedia(url: String?) {
            if (url.isNullOrBlank()) return
            main.post { consider(url, emptyMap()) }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 35_000L
        private const val TAG = "Emberlane"

        private val SNIFF = """
            (function(){
              if (window.__emberSniff) return;
              window.__emberSniff = true;
              function report(u){
                try { if (u) EmberlaneSniffer.onMedia(String(u)); } catch (e) {}
              }
              var open = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(method, url){
                try { report(url); } catch (e) {}
                return open.apply(this, arguments);
              };
              if (window.fetch) {
                var ofetch = window.fetch;
                window.fetch = function(input){
                  try { report(typeof input === 'string' ? input : (input && input.url)); } catch (e) {}
                  return ofetch.apply(this, arguments);
                };
              }
              function scan(){
                var nodes = document.querySelectorAll('video, source');
                for (var i = 0; i < nodes.length; i++) {
                  if (nodes[i].currentSrc) report(nodes[i].currentSrc);
                  if (nodes[i].src) report(nodes[i].src);
                }
              }
              try {
                new MutationObserver(scan).observe(document.documentElement, {childList:true, subtree:true, attributes:true});
              } catch (e) {}
              scan();
            })();
        """.trimIndent()

        private val NUDGE = """
            (function(){
              try {
                var v = document.querySelector('video');
                if (v) { v.muted = true; var p = v.play(); if (p && p.catch) p.catch(function(){}); }
              } catch (e) {}
            })();
        """.trimIndent()
    }
}

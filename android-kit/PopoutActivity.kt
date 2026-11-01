package com.truelockmc.streambert

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

/**
 * Pop-out player (picture-in-picture) and the Wyzie redeem page.
 * This is the Android stand-in for Electron's always-on-top BrowserWindow.
 */
class PopoutActivity : AppCompatActivity() {
    private var web: WebView? = null
    private var mode: String = "player"
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        mode = intent.getStringExtra("mode") ?: "player"
        val url = intent.getStringExtra("url") ?: return finish()

        val root = FrameLayout(this)
        val view = WebView(this)
        StreambertEngine.attachGuest(view)
        view.webViewClient = StreambertEngine.guestClient()
        if (mode == "player") {
            StreambertEngine.pipWeb = view
            view.addJavascriptInterface(object {
                @JavascriptInterface
                fun result(json: String?) {
                    StreambertEngine.onPipQueryResult(json)
                }
            }, "StreambertPip")
        } else {
            view.addJavascriptInterface(object {
                @JavascriptInterface
                fun redeemKey(key: String) {
                    if (delivered) return
                    delivered = true
                    StreambertEngine.onRedeemKey(key)
                    runOnUiThread { finish() }
                }
            }, "StreambertRedeem")
            val redeemJs = """
                (function(){
                  if (window.__sbRedeem) return; window.__sbRedeem = true;
                  setInterval(function(){
                    var t = document.body ? document.body.innerText : '';
                    var m = t && t.match(/wyzie-[a-zA-Z0-9]+/);
                    if (m && window.StreambertRedeem) window.StreambertRedeem.redeemKey(m[0]);
                  }, 1000);
                })();
            """.trimIndent()
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(view, redeemJs, setOf("*"))
            }
        }
        web = view
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val close = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(0xAA000000.toInt())
            setOnClickListener { finish() }
            contentDescription = "Close"
        }
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP)
        lp.topMargin = 24
        lp.marginEnd = 24
        root.addView(close, lp)
        setContentView(root)
        view.loadUrl(url)
        if (mode == "player" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.post {
                try {
                    val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                    enterPictureInPictureMode(params)
                } catch (_: Exception) {}
            }
        }
    }

    fun load(url: String) {
        web?.loadUrl(url)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mode == "player") {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        if (current == this) current = null
        if (mode == "player") StreambertEngine.onPipClosed()
        else if (!delivered) StreambertEngine.onRedeemClosed()
        web?.destroy()
        web = null
        super.onDestroy()
    }

    companion object {
        var current: PopoutActivity? = null
        fun closePlayer() {
            current?.takeIf { it.mode == "player" }?.finish()
        }
    }
}

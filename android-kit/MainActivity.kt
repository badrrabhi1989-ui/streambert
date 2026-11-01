package com.truelockmc.streambert

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.getcapacitor.BridgeActivity
import org.json.JSONObject

class MainActivity : BridgeActivity() {
    companion object {
        @Volatile
        var uiReady: Boolean = false
    }

    val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        StreambertEngine.onFolderPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) uiReady = true
        installSplashScreen().setKeepOnScreenCondition { !uiReady }
        super.onCreate(savedInstanceState)
        val navy = Color.parseColor("#040F2D")
        window.statusBarColor = navy
        window.navigationBarColor = navy
        WebView.setWebContentsDebuggingEnabled(true)
        StreambertEngine.install(this, bridge)
        bridge.webView.setBackgroundColor(navy)
        bridge.webView.requestFocus()
        val cover = holdNavyCover(navy)
        val main = Handler(Looper.getMainLooper())
        val reveal = object : Runnable {
            override fun run() {
                if (uiReady) {
                    (cover.parent as? ViewGroup)?.removeView(cover)
                } else {
                    main.postDelayed(this, 32)
                }
            }
        }
        main.post(reveal)
        if (!uiReady) main.postDelayed({ uiReady = true }, 15_000)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val view = bridge.webView
                    view.evaluateJavascript(
                        "(function(){if(window.__streambertHandleBack)return window.__streambertHandleBack();return window.__streambertRequestClose?window.__streambertRequestClose():'close'})()",
                    ) { result ->
                        val text = result ?: ""
                        if (text.contains("handled") || text.contains("pending")) return@evaluateJavascript
                        finish()
                    }
                }
            },
        )
    }

    private fun holdNavyCover(navy: Int): FrameLayout {
        val content = findViewById<ViewGroup>(android.R.id.content)
        val cover = FrameLayout(this)
        cover.setBackgroundColor(navy)
        cover.isClickable = true
        val logo = ImageView(this)
        logo.setImageResource(R.mipmap.ic_launcher)
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        val size = (160 * resources.displayMetrics.density).toInt()
        cover.addView(logo, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        content.addView(cover, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return cover
    }

    override fun onDestroy() {
        PairingServer.stop()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val command = when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> "playpause"
                KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
                KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> "seek:30"
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "seek:-10"
                else -> null
            }
            if (command != null) {
                val quoted = JSONObject.quote(command)
                bridge.webView.evaluateJavascript(
                    "window.__streambertMediaKey&&window.__streambertMediaKey($quoted)",
                    null,
                )
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

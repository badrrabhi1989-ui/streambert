package com.truelockmc.streambert

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        StreambertEngine.onFolderPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0a0a0a")
        window.navigationBarColor = Color.parseColor("#0a0a0a")
        WebView.setWebContentsDebuggingEnabled(true)
        StreambertEngine.install(this, bridge)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val view = bridge.webView
                    view.evaluateJavascript(
                        "(function(){return window.__streambertRequestClose?window.__streambertRequestClose():'close'})()",
                    ) { result ->
                        if (result != null && result.contains("pending")) return@evaluateJavascript
                        finish()
                    }
                }
            },
        )
    }
}

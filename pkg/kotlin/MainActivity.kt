package com.kiplay.agent

import android.os.Bundle
import android.webkit.WebView
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {

    private var ref: Bridge? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web: WebView? = bridge?.webView
        val b = Bridge(this, web)
        ref = b
        web?.addJavascriptInterface(b, "MK")
    }
}

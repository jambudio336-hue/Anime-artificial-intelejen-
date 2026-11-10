package ai.maskiplay.app

import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import androidx.annotation.SuppressLint
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    private var nativeBridge: Bridge? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val web = bridge.webView
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "$userAgentString MasKiplay/8.0"
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val allowed = request.resources.filter {
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                        it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                    }.toTypedArray()
                    if (allowed.isNotEmpty()) request.grant(allowed) else request.deny()
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                request.deny()
            }
        }

        nativeBridge = Bridge(this, web)
        web.addJavascriptInterface(nativeBridge, "MK")
        web.addJavascriptInterface(nativeBridge, "MasKiplayNative")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Bridge.REQ_VPN) nativeBridge?.onActivityResult(resultCode)
    }

    override fun onDestroy() {
        nativeBridge = null
        super.onDestroy()
    }
}

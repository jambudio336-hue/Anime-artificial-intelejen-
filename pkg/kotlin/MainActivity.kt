package ai.maskiplay.app

import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.annotation.SuppressLint
import com.getcapacitor.BridgeActivity
import id.maskiplay.watch.PlayerActivity
import id.maskiplay.watch.WatchPlugin
import id.maskiplay.vpn.VpnPlugin
import id.maskiplay.net.NetworkScannerPlugin

class MainActivity : BridgeActivity() {
    private var nativeBridge: Bridge? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(WatchPlugin::class.java)
        registerPlugin(VpnPlugin::class.java)
        registerPlugin(NetworkScannerPlugin::class.java)
        super.onCreate(savedInstanceState)

        val web = bridge.webView
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            userAgentString = "$userAgentString MasKiplay/9.4.1"
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    // Hanya konten lokal aplikasi yang boleh meminta akses media.
                    val origin = request.origin
                    val trustedOrigin = origin.scheme == "https" &&
                        (origin.host == "localhost" || origin.host == "127.0.0.1")
                    val allowed = if (trustedOrigin) request.resources.filter {
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                    }.toTypedArray() else emptyArray()
                    if (allowed.isNotEmpty()) request.grant(allowed) else request.deny()
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                request.deny()
            }
        }

        val bridgeInstance = Bridge(this, web)
        nativeBridge = bridgeInstance
        web.addJavascriptInterface(bridgeInstance, "MK")
        web.addJavascriptInterface(bridgeInstance, "MasKiplayNative")
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7101) {
            nativeBridge?.onMicPermissionResult(
                grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            )
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        nativeBridge?.onTermuxResult(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val web = bridge.webView
        web.evaluateJavascript(
            "(function(){try{return !!(window.mkBack && window.mkBack())}catch(e){return false}})()"
        ) { handled ->
            if (handled != "true") super@MainActivity.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        nativeBridge?.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        nativeBridge?.shutdown()
        nativeBridge = null
        super.onDestroy()
    }
}

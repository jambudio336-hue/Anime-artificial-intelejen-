package ai.maskiplay.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import java.util.Locale

class NativeBridge(private val act: Activity) : TextToSpeech.OnInitListener {

    companion object {
        const val REQ_VPN = 991
    }

    private var tts: TextToSpeech? = null
    private var pendingHost: String? = null

    init {
        tts = TextToSpeech(act, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("id", "ID")
        }
    }

    @JavascriptInterface
    fun vpnStart(host: String, port: Int) {
        val target = if (host.isBlank()) "1.1.1.1" else host
        val prep = try { VpnService.prepare(act) } catch (e: Exception) { null }
        if (prep != null) {
            pendingHost = target
            try {
                act.startActivityForResult(prep, REQ_VPN)
            } catch (e: Exception) {
                pendingHost = null
            }
        } else {
            launch(target)
        }
    }

    fun onActivityResult(resultCode: Int) {
        val host = pendingHost
        pendingHost = null
        if (resultCode == Activity.RESULT_OK && host != null) launch(host)
    }

    private fun launch(host: String) {
        try {
            val i = Intent(act, MasKiplayVpnService::class.java)
                .setAction(MasKiplayVpnService.ACTION_START)
                .putExtra(MasKiplayVpnService.EXTRA_HOST, host)
            act.startForegroundService(i)
        } catch (e: Exception) {
            try {
                act.startService(Intent(act, MasKiplayVpnService::class.java)
                    .setAction(MasKiplayVpnService.ACTION_START)
                    .putExtra(MasKiplayVpnService.EXTRA_HOST, host))
            } catch (ignored: Exception) {}
        }
    }

    @JavascriptInterface
    fun vpnStop() {
        try {
            act.startService(Intent(act, MasKiplayVpnService::class.java)
                .setAction(MasKiplayVpnService.ACTION_STOP))
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun vpnStatus(): Boolean = MasKiplayVpnService.isRunning

    @JavascriptInterface
    fun speak(text: String) {
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mk")
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun stopSpeak() {
        try { tts?.stop() } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun appVersion(): String = "9.0.0"

    fun destroy() {
        try { tts?.stop() } catch (e: Exception) {}
        try { tts?.shutdown() } catch (e: Exception) {}
        tts = null
    }
}

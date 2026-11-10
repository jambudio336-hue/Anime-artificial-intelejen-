package ai.maskiplay.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import java.util.Locale

class NativeBridge(private val act: Activity) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null

    init { tts = TextToSpeech(act, this) }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale("id", "ID")
    }

    @JavascriptInterface
    fun vpnStart(host: String, port: Int) {
        val prep = VpnService.prepare(act)
        if (prep != null) { act.startActivityForResult(prep, 991); pending = host; return }
        launch(host)
    }

    private var pending: String? = null

    fun onActivityResult(code: Int) {
        if (code == 991) pending?.let { launch(it) }
    }

    private fun launch(host: String) {
        val i = Intent(act, MasKiplayVpnService::class.java)
            .setAction(MasKiplayVpnService.ACTION_START)
            .putExtra("host", host)
        act.startForegroundService(i)
    }

    @JavascriptInterface
    fun vpnStop() {
        act.startService(Intent(act, MasKiplayVpnService::class.java)
            .setAction(MasKiplayVpnService.ACTION_STOP))
    }

    @JavascriptInterface
    fun vpnStatus(): Boolean = MasKiplayVpnService.isRunning

    @JavascriptInterface
    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mk")
    }

    @JavascriptInterface
    fun stopSpeak() { tts?.stop() }

    fun destroy() { tts?.shutdown() }
}

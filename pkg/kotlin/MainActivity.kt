package com.kiplay.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.BridgeActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

class MainActivity : BridgeActivity() {

    private lateinit var tools: AgentTools
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private val voiceQ = ArrayDeque<Pair<String, String>>()
    private var seq = 0

    private var sr: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private var listening = false
    private var heard = false
    private var lastText = ""
    private var silentMs = 0L
    private var watchdog = false
    private val SILENCE_MS = 1000L
    private val MIN_RMS = 2.5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tools = AgentTools(this)

        val wv = bridge.webView
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "$userAgentString MasKiplay/7.2"
        }

        // wajib: tanpa ini getUserMedia & izin mikrofon selalu ditolak
        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
            override fun onPermissionRequestCanceled(request: PermissionRequest) {}
        }

        wv.addJavascriptInterface(Bridge(), "MK")
        initTts()
        AgentService.start(this)
    }

    override fun onDestroy() {
        watchdog = false
        try { sr?.destroy() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        try { tools.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQ_MIC) {
            val ok = res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED
            js("window.MKVoice&&window.MKVoice.onPerm($ok)")
        }
        if (code == REQ_BATCH) {
            val m = perms.mapIndexed { i, p -> p to (res.getOrNull(i) == PackageManager.PERMISSION_GRANTED) }.toMap()
            js("window.MKVoice&&window.MKVoice.onBatch(${JSONObject(m)})")
        }
    }

    override fun onActivityResult(req: Int, code: Int, data: Intent?) {
        super.onActivityResult(req, code, data)
        if (req == AgentTools.REQ_SCREEN) tools.onScreenResult(code, data)
        if (req == REQ_PICK && code == RESULT_OK) {
            val u = data?.data?.toString() ?: return
            js("window.MKVoice&&window.MKVoice.onPick(${JSONObject.quote(u)})")
        }
    }

    private fun js(code: String) = runOnUiThread { bridge.webView.evaluateJavascript(code, null) }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ================= SPEECH =================
    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) { emit("onReady") }
        override fun onBeginningOfSpeech() { heard = true; silentMs = 0 }
        override fun onRmsChanged(rms: Float) { if (rms > MIN_RMS) { heard = true; silentMs = 0 } }
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, b: Bundle?) {}

        override fun onPartialResults(p: Bundle?) {
            val t = best(p) ?: return
            lastText = t
            emit("onPartial", t)
        }

        override fun onResults(r: Bundle?) {
            listening = false
            val t = best(r) ?: lastText
            lastText = ""
            if (t.isNotBlank()) emit("onText", t) else emit("onSilence")
        }

        override fun onError(err: Int) {
            listening = false
            when (err) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (lastText.isNotBlank()) { emit("onText", lastText); lastText = "" } else emit("onSilence")
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> emit("onError", "izin mikrofon ditolak")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> emit("onSilence")
                else -> emit("onError", "kode $err")
            }
        }
    }

    private fun best(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    private fun startListen() {
        if (listening) return
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) { emit("onError", "STT tidak tersedia"); return@post }
            if (!has(Manifest.permission.RECORD_AUDIO)) { emit("onError", "izin mikrofon belum ada"); return@post }
            if (sr == null) sr = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }

            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
            }
            heard = false; lastText = ""; silentMs = 0; listening = true
            try { sr?.startListening(i) } catch (e: Exception) { listening = false; emit("onError", e.message ?: "gagal") }
            if (!watchdog) { watchdog = true; tick() }
        }
    }

    // watchdog: sudah ada suara lalu diam 1000 ms -> potong & jawab
    private fun tick() {
        main.postDelayed({
            if (listening) {
                silentMs += 100
                if (heard && silentMs >= SILENCE_MS) {
                    try { sr?.stopListening() } catch (_: Exception) {}
                    heard = false; silentMs = 0
                }
            }
            if (watchdog) tick()
        }, 100)
    }

    private fun stopListen() {
        main.post { listening = false; try { sr?.cancel() } catch (_: Exception) {} }
    }

    private fun emit(fn: String, arg: String? = null) {
        val c = if (arg == null) "window.MKVoice&&window.MKVoice.$fn()"
        else "window.MKVoice&&window.MKVoice.$fn(${JSONObject.quote(arg)})"
        js(c)
    }

    // ================= TTS =================
    private fun initTts() {
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("id", "ID")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { next() }
                    @Deprecated("x") override fun onError(id: String?) { next() }
                })
                ttsReady = true
            }
        }
    }

    private fun speak(text: String, style: String) {
        val clean = text.replace(Regex("```[\\s\\S]*?```"), " kode di layar ")
            .replace(Regex("[*_#>`]"), " ").replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return
        clean.split(Regex("(?<=[.!?…;])\\s+")).filter { it.isNotBlank() }.forEach { voiceQ.add(it to style) }
        if (!speaking) pump()
    }

    private fun laugh() {
        voiceQ.add("ha ha ha ha ha... ha ha ha" to "laugh")
        if (!speaking) pump()
    }

    private fun stopSpeak() {
        voiceQ.clear(); seq++; speaking = false
        try { tts?.stop() } catch (_: Exception) {}
        js("window.MKVoice&&window.MKVoice.onSpeakDone()")
    }

    private fun pump() {
        if (!ttsReady) { js("window.MKVoice&&window.MKVoice.onSpeakDone()"); return }
        val it0 = voiceQ.poll() ?: return
        speaking = true
        val my = ++seq
        tts?.apply {
            when (it0.second) {
                "laugh" -> { setPitch(0.35f); setSpeechRate(0.72f) }
                "jarvis" -> { setPitch(0.55f); setSpeechRate(0.92f) }
                else -> { setPitch(0.72f); setSpeechRate(1.02f) }
            }
            speak(it0.first, if (my % 2 == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null, UUID.randomUUID().toString())
        }
    }

    private fun next() {
        runOnUiThread { if (voiceQ.isEmpty()) { speaking = false; js("window.MKVoice&&window.MKVoice.onSpeakDone()") } else pump() }
    }

    // ================= SHELL =================
    private fun runShell(cmd: String, root: Boolean): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", if (root) "su -c '${cmd.replace("'", "'\\''")}'" else cmd))
        val o = StringBuilder()
        BufferedReader(InputStreamReader(p.inputStream)).use { r -> r.lineSequence().take(400).forEach { o.appendLine(it) } }
        BufferedReader(InputStreamReader(p.errorStream)).use { r -> r.lineSequence().take(100).forEach { o.appendLine(it) } }
        p.waitFor(); o.toString().ifBlank { "(tanpa output)" }
    } catch (e: Exception) { "err: ${e.message}" }

    // ================= BRIDGE =================
    inner class Bridge {

        @JavascriptInterface
        fun requireMic(): Boolean {
            if (has(Manifest.permission.RECORD_AUDIO)) return true
            ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return false
        }

        @JavascriptInterface fun hasPerm(p: String): Boolean = has(p)

        @JavascriptInterface
        fun requestPerms(json: String) {
            val arr = JSONArray(json)
            val need = (0 until arr.length()).map { arr.getString(it) }.filter { !has(it) }.toTypedArray()
            if (need.isEmpty()) { js("window.MKVoice&&window.MKVoice.onBatch({})"); return }
            ActivityCompat.requestPermissions(this@MainActivity, need, REQ_BATCH)
        }

        @JavascriptInterface fun speak(t: String, s: String) = this@MainActivity.speak(t, s)
        @JavascriptInterface fun laugh() = this@MainActivity.laugh()
        @JavascriptInterface fun stopSpeak() = this@MainActivity.stopSpeak()
        @JavascriptInterface fun listen() = startListen()
        @JavascriptInterface fun stopListen() = this@MainActivity.stopListen()

        @JavascriptInterface fun tool(n: String, a: String): String = tools.dispatch(n, a)
        @JavascriptInterface fun accOn(): Boolean = KiplayAccessibilityService.inst != null

        @JavascriptInterface
        fun openAccSettings() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        @JavascriptInterface
        fun openOverlaySettings() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        @JavascriptInterface
        fun openStorageSettings() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        @JavascriptInterface fun requestScreen() = runOnUiThread { tools.askScreen() }

        @JavascriptInterface
        fun openApp(pkg: String): String = try {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: throw Exception("tidak terpasang")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); "luncur $pkg"
        } catch (e: Exception) { "err: ${e.message}" }

        @JavascriptInterface
        fun openUrl(url: String): String = try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "buka $url"
        } catch (e: Exception) { "err: ${e.message}" }

        @JavascriptInterface fun shell(c: String): String = runShell(c, false)
        @JavascriptInterface fun shellRoot(c: String): String = runShell(c, true)

        @JavascriptInterface
        fun termux(cmd: String): String = try {
            startService(Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }); "→ Termux"
        } catch (e: Exception) { "err: ${e.message}" }

        @JavascriptInterface
        fun pickImage(): String {
            runOnUiThread {
                startActivityForResult(Intent(Intent.ACTION_PICK,
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI), REQ_PICK)
            }
            return "galeri dibuka"
        }
    }

    companion object {
        const val REQ_MIC = 700
        const val REQ_BATCH = 701
        const val REQ_PICK = 702
    }
}

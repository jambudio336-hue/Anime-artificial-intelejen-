package com.kiplay.agent

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

class VoiceBridge(private val act: MainActivity) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val q = ArrayDeque<Pair<String, String>>()
    private var seq = 0

    fun init() {
        tts = TextToSpeech(act) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("id", "ID")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { next() }
                    @Deprecated("x") override fun onError(id: String?) { next() }
                })
                ready = true
            }
        }
    }

    fun speak(text: String, style: String) {
        val clean = text.replace(Regex("```[\\s\\S]*?```"), " kode di layar ")
            .replace(Regex("[*_#>`]"), " ").replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return
        clean.split(Regex("(?<=[.!?…;])\\s+")).filter { it.isNotBlank() }
            .forEach { q.add(it to style) }
        if (q.size == 1 || !speakingNow) pump()
    }

    private var speakingNow = false

    fun laugh() {
        // nada rendah + lambat = ketawa seram
        q.add("ha ha ha ha ha... ha ha ha" to "laugh")
        if (!speakingNow) pump()
    }

    fun stop() {
        q.clear(); seq++
        try { tts?.stop() } catch (_: Exception) {}
        speakingNow = false
        act.runOnUiThread { act.bridge.webView.evaluateJavascript("window.MKVoice&&window.MKVoice.onSpeakDone()", null) }
    }

    private fun pump() {
        if (!ready) { act.runOnUiThread { postDone() }; return }
        val item = q.poll() ?: return
        speakingNow = true
        val my = ++seq
        tts?.apply {
            when (item.second) {
                "laugh" -> { setPitch(0.35f); setSpeechRate(0.72f) }
                "jarvis" -> { setPitch(0.55f); setSpeechRate(0.92f) }
                else -> { setPitch(0.72f); setSpeechRate(1.02f) }
            }
            speak(item.first, if (my % 2 == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null, UUID.randomUUID().toString())
        }
    }

    private fun next() {
        act.runOnUiThread {
            if (q.isEmpty()) { speakingNow = false; postDone() } else pump()
        }
    }

    private fun postDone() {
        act.bridge.webView.evaluateJavascript("window.MKVoice&&window.MKVoice.onSpeakDone()", null)
    }

    fun release() { try { tts?.shutdown() } catch (_: Exception) {}; tts = null }
}

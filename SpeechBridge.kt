package com.kiplay.agent

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject

class SpeechBridge(private val act: MainActivity) {

    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var running = false
    private var heard = false
    private var lastText = ""
    private var silentMs = 0L
    private var armed = false

    // --- tuning: 1000 ms = patokan berhenti bicara ---
    private val SILENCE_MS = 1000L
    private val MIN_RMS = 2.5f

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) { emit("onReady") }
        override fun onBeginningOfSpeech() { heard = true; silentMs = 0 }
        override fun onRmsChanged(rms: Float) {
            if (rms > MIN_RMS) { heard = true; silentMs = 0 }
        }
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, b: android.os.Bundle?) {}

        override fun onPartialResults(partial: android.os.Bundle?) {
            val t = best(partial) ?: return
            lastText = t
            emit("onPartial", t)
        }

        override fun onResults(results: android.os.Bundle?) {
            running = false
            val t = best(results) ?: lastText
            lastText = ""
            if (t.isNotBlank()) emit("onText", t) else emit("onSilence")
        }

        override fun onError(err: Int) {
            running = false
            when (err) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (lastText.isNotBlank()) { emit("onText", lastText); lastText = "" }
                    else emit("onSilence")
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> emit("onError", "izin mikrofon ditolak")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> emit("onSilence")
                else -> emit("onError", "kode $err")
            }
        }
    }

    private fun best(b: android.os.Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    fun start() {
        if (running) return
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(act)) { emit("onError", "STT tidak tersedia"); return@post }
            if (act.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                emit("onError", "izin mikrofon belum ada"); return@post
            }
            if (sr == null) sr = SpeechRecognizer.createSpeechRecognizer(act).also { it.setRecognitionListener(listener) }

            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // patokan diam 1 detik
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
            }

            heard = false; lastText = ""; silentMs = 0; running = true
            try { sr?.startListening(i) } catch (e: Exception) {
                running = false; emit("onError", e.message ?: "gagal start")
            }
            if (!armed) { armed = true; tick() }
        }
    }

    // watchdog RMS: kalau sudah ada suara lalu diam 1000 ms -> potong
    private fun tick() {
        main.postDelayed({
            if (running) {
                silentMs += 100
                if (heard && silentMs >= SILENCE_MS) {
                    try { sr?.stopListening() } catch (_: Exception) {}
                    heard = false; silentMs = 0
                }
            }
            if (armed) tick()
        }, 100)
    }

    fun stop() {
        main.post {
            running = false
            try { sr?.cancel() } catch (_: Exception) {}
        }
    }

    fun release() {
        armed = false
        try { sr?.destroy() } catch (_: Exception) {}
        sr = null
    }

    private fun emit(fn: String, arg: String? = null) {
        val code = if (arg == null) "window.MKVoice&&window.MKVoice.$fn()"
        else "window.MKVoice&&window.MKVoice.$fn(${JSONObject.quote(arg)})"
        act.runOnUiThread { act.bridge.webView.evaluateJavascript(code, null) }
    }
}

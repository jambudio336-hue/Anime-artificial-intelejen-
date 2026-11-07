package com.kiplay.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.BridgeActivity
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : BridgeActivity() {

    private lateinit var speech: SpeechBridge
    private lateinit var voice: VoiceBridge
    private lateinit var tools: AgentTools

    private var permCallback: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        speech = SpeechBridge(this)
        voice = VoiceBridge(this)
        tools = AgentTools(this)

        val wv = bridge.webView
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false   // penting: TTS/video autoplay
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "$userAgentString MasKiplay/7.2"
        }

        // INI YANG HILANG DI v7.1.0 -> semua getUserMedia mati tanpa ini
        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
            override fun onPermissionRequestCanceled(request: PermissionRequest) {}
        }

        wv.addJavascriptInterface(Bridge(), "MK")

        voice.init()
        AgentService.start(this)
    }

    override fun onDestroy() {
        speech.release(); voice.release(); super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQ_MIC) {
            val ok = res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED
            js("window.MKVoice&&window.MKVoice.onPerm($ok)")
        }
        if (code == REQ_BATCH) {
            val map = perms.mapIndexed { i, p -> p to (res.getOrNull(i) == PackageManager.PERMISSION_GRANTED) }
            js("window.MKVoice&&window.MKVoice.onBatch(${org.json.JSONObject(map.toMap())})")
        }
    }

    private fun js(code: String) = runOnUiThread { bridge.webView.evaluateJavascript(code, null) }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    inner class Bridge {

        // ---------- IZIN ----------
        @JavascriptInterface
        fun requireMic(): Boolean {
            if (has(Manifest.permission.RECORD_AUDIO)) return true
            ActivityCompat.requestPermissions(this@MainActivity,
                arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return false
        }

        @JavascriptInterface
        fun hasPerm(p: String): Boolean = has(p)

        @JavascriptInterface
        fun requestPerms(json: String) {
            val arr = org.json.JSONArray(json)
            val list = (0 until arr.length()).map { arr.getString(it) }
                .filter { !has(it) }.toTypedArray()
            if (list.isEmpty()) { js("window.MKVoice&&window.MKVoice.onBatch({})"); return }
            ActivityCompat.requestPermissions(this@MainActivity, list, REQ_BATCH)
        }

        // ---------- SUARA ----------
        @JavascriptInterface
        fun speak(text: String, style: String) = voice.speak(text, style)

        @JavascriptInterface
        fun laugh() = voice.laugh()

        @JavascriptInterface
        fun stopSpeak() = voice.stop()

        @JavascriptInterface
        fun listen() = speech.start()

        @JavascriptInterface
        fun stopListen() = speech.stop()

        // ---------- AGENT ----------
        @JavascriptInterface
        fun tool(name: String, argsJson: String): String = tools.dispatch(name, argsJson)

        @JavascriptInterface
        fun accOn(): Boolean = KiplayAccessibilityService.inst != null

        @JavascriptInterface
        fun openAccSettings() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        @JavascriptInterface
        fun openOverlaySettings() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        @JavascriptInterface
        fun openStorageSettings() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        @JavascriptInterface
        fun requestScreen() = runOnUiThread { tools.askScreen() }

        // ---------- LAMA (dipertahankan) ----------
        @JavascriptInterface
        fun openApp(pkg: String): String = try {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: throw Exception("tidak terpasang")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); "luncur $pkg"
        } catch (e: Exception) { "err: ${e.message}" }

        @JavascriptInterface
        fun openUrl(url: String): String = try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "buka $url"
        } catch (e: Exception) { "err: ${e.message}" }

        @JavascriptInterface
        fun shell(cmd: String): String = runShell(cmd, false)

        @JavascriptInterface
        fun shellRoot(cmd: String): String = runShell(cmd, true)

        @JavascriptInterface
        fun termux(cmd: String): String = try {
            val i = Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            startService(i); "→ Termux"
        } catch (e: Exception) { "err: ${e.message}" }
    }

    private fun runShell(cmd: String, root: Boolean): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", if (root) "su -c ${q(cmd)}" else cmd))
        val out = StringBuilder()
        BufferedReader(InputStreamReader(p.inputStream)).use { r ->
            r.lineSequence().take(400).forEach { out.appendLine(it) }
        }
        BufferedReader(InputStreamReader(p.errorStream)).use { r ->
            r.lineSequence().take(100).forEach { out.appendLine(it) }
        }
        p.waitFor()
        out.toString().ifBlank { "(tanpa output)" }
    } catch (e: Exception) { "err: ${e.message}" }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    companion object { const val REQ_MIC = 700; const val REQ_BATCH = 701; const val REQ_SCREEN = 702 }
}

package ai.maskiplay.app

import android.annotation.SuppressLint

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.widget.Toast
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.webkit.SslErrorHandler
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.CookieManager
import android.view.ViewGroup
import android.view.View
import android.view.KeyEvent
import android.view.Gravity
import android.net.http.SslError
import android.graphics.Color
import android.app.AlertDialog
import android.app.ActivityManager
import android.app.WallpaperManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Point
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URL
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/**
 * Jembatan WebView -> native. Didaftarkan MainActivity sebagai window.MK.
 */
class Bridge(private val activity: Activity, private val web: WebView?) {
    companion object { const val REQ_VPN = 991 }
    @Volatile private var pendingVpnHost: String? = null
    @Volatile private var pendingVpnPort: Int = 53


    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var recognitionGeneration: Long = 0L
    private val pending = ArrayDeque<Pair<String, String>>()
    @Volatile private var lastRoot = false
    @Volatile private var rootChecked = false
    private var rate = 1.0f
    private var pitch = 0.92f
    private val vpnGateExecutor = Executors.newSingleThreadExecutor()

    // ---------- browser internal terisolasi ----------

    @JavascriptInterface
    fun openInAppBrowser(rawUrl: String, pageTitle: String): String {
        val uri = try { Uri.parse(rawUrl.trim()) } catch (_: Exception) { null }
        if (uri == null || uri.scheme?.lowercase() != "https" || uri.host.isNullOrBlank()) {
            return "err: hanya URL HTTPS yang diizinkan"
        }
        activity.runOnUiThread {
            try { showInAppBrowser(uri.toString(), pageTitle.take(64).ifBlank { "Web Tool" }) }
            catch (e: Exception) { Toast.makeText(activity, "Browser internal: ${e.message}", Toast.LENGTH_LONG).show() }
        }
        return "opening"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showInAppBrowser(url: String, pageTitle: String) {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 9, 13))
        }
        var dialogRef: AlertDialog? = null
        val toolbar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14, 10, 14, 10)
        }
        val title = TextView(activity).apply {
            text = pageTitle
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(activity).apply {
            text = "TUTUP"
            setTextColor(Color.rgb(255, 90, 100))
            textSize = 12f
            setPadding(14, 8, 4, 8)
            isClickable = true
            setOnClickListener { dialogRef?.dismiss() }
        }
        toolbar.addView(title)
        toolbar.addView(close)
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.VISIBLE
        }
        val browser = WebView(activity).apply {
            setBackgroundColor(Color.rgb(7, 9, 13))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
            }
            CookieManager.getInstance().setAcceptCookie(true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
                override fun onReceivedTitle(view: WebView?, page: String?) {
                    if (!page.isNullOrBlank()) title.text = page.take(64)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return request.url.scheme?.lowercase() != "https"
                }
                override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                    progress.visibility = View.VISIBLE
                    progress.progress = 0
                }
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    title.text = "Sertifikat TLS tidak valid"
                    Toast.makeText(activity, "Koneksi HTTPS ditolak karena sertifikat tidak valid", Toast.LENGTH_LONG).show()
                }
            }
            setDownloadListener { _, _, _, _, _ ->
                Toast.makeText(activity, "Unduhan web dinonaktifkan di browser internal", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 3))
        root.addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val dialog = AlertDialog.Builder(activity).setView(root).create()
        dialogRef = dialog
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (browser.canGoBack()) browser.goBack() else dialog.dismiss()
                true
            } else false
        }
        dialog.setOnDismissListener {
            browser.stopLoading()
            browser.loadUrl("about:blank")
            browser.removeAllViews()
            browser.destroy()
        }
        dialog.show()
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.98f).toInt(),
            (activity.resources.displayMetrics.heightPixels * 0.92f).toInt()
        )
        browser.loadUrl(url)
    }

    // ---------- VPN DNS tunnel ----------

    @JavascriptInterface
    fun vpnStart(host: String, port: Int): String {
        val resolver = host.trim().ifBlank { "1.1.1.1" }
        val dnsPort = port.coerceIn(1, 65535)
        activity.runOnUiThread {
            try {
                val permissionIntent = VpnService.prepare(activity)
                if (permissionIntent != null) {
                    pendingVpnHost = resolver
                    pendingVpnPort = dnsPort
                    activity.startActivityForResult(permissionIntent, REQ_VPN)
                } else launchVpn(resolver, dnsPort)
            } catch (e: Exception) {
                js("MK_onVPN", JSONObject().put("active", false)
                    .put("error", e.message ?: "izin VPN gagal").toString())
            }
        }
        return "requesting"
    }

    fun onActivityResult(resultCode: Int) {
        val host = pendingVpnHost
        val port = pendingVpnPort
        pendingVpnHost = null
        if (resultCode == Activity.RESULT_OK && host != null) launchVpn(host, port)
        else js("MK_onVPN", JSONObject().put("active", false)
            .put("error", "izin VPN ditolak").toString())
    }

    private fun launchVpn(host: String, port: Int) {
        try {
            val intent = Intent(activity, MasKiplayVpnService::class.java)
                .setAction(MasKiplayVpnService.ACTION_START)
                .putExtra(MasKiplayVpnService.EXTRA_HOST, host)
                .putExtra(MasKiplayVpnService.EXTRA_PORT, port)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) activity.startForegroundService(intent)
            else activity.startService(intent)
            activity.window.decorView.postDelayed({
                val active = MasKiplayVpnService.isRunning
                val payload = JSONObject().put("active", active)
                if (!active) payload.put("error", "VPN gagal diinisialisasi")
                js("MK_onVPN", payload.toString())
            }, 900L)
        } catch (e: Exception) {
            js("MK_onVPN", JSONObject().put("active", false)
                .put("error", e.message ?: "VPN gagal dimulai").toString())
        }
    }

    @JavascriptInterface
    fun vpnStop(): String {
        activity.runOnUiThread {
            try {
                activity.startService(Intent(activity, MasKiplayVpnService::class.java)
                    .setAction(MasKiplayVpnService.ACTION_STOP))
            } catch (_: Exception) {}
            js("MK_onVPN", JSONObject().put("active", false).toString())
        }
        return "stopping"
    }

    @JavascriptInterface
    fun vpnStatus(): Boolean = MasKiplayVpnService.isRunning

    /**
     * Refresh the official VPN Gate CSV directory in a worker thread.
     * The result is a ranked server catalogue, not an active VPN connection.
     */
    @JavascriptInterface
    fun vpnGateRefresh(): String {
        vpnGateExecutor.execute {
            try {
                val relays = VpnGateRepository.fetchAndRank()
                val servers = org.json.JSONArray()
                relays.forEach { relay ->
                    servers.put(JSONObject()
                        .put("hostName", relay.hostName)
                        .put("ip", relay.ip)
                        .put("country", relay.country)
                        .put("countryCode", relay.countryCode)
                        .put("reportedPingMs", relay.reportedPingMs)
                        .put("probeMs", relay.probeMs ?: JSONObject.NULL)
                        .put("speedMbps", String.format(Locale.US, "%.2f", relay.speedMbps).toDouble())
                        .put("sessions", relay.sessions)
                        .put("uptimeSeconds", relay.uptimeSeconds)
                        .put("logType", relay.logType)
                        .put("operator", relay.operator)
                        .put("port", relay.port)
                        .put("transport", relay.transport)
                        .put("profileAvailable", relay.profileAvailable)
                    )
                }
                js("MK_onVPNGateServers", JSONObject()
                    .put("ok", true)
                    .put("source", "VPN Gate official public CSV")
                    .put("updatedAt", System.currentTimeMillis())
                    .put("servers", servers).toString())
            } catch (e: Exception) {
                js("MK_onVPNGateServers", JSONObject()
                    .put("ok", false)
                    .put("error", e.message ?: "Gagal memuat server VPN Gate")
                    .put("servers", org.json.JSONArray()).toString())
            }
        }
        return "refreshing"
    }

    fun shutdownVpnGate() {
        try { vpnGateExecutor.shutdownNow() } catch (_: Exception) {}
    }

    fun shutdown() {
        shutdownVpnGate()
        recognitionGeneration++
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        pending.clear()
    }

    // ---------- utilitas ----------

    private fun js(fn: String, payload: String) {
        val w = web ?: return
        activity.runOnUiThread {
            try { w.evaluateJavascript("window.$fn && window.$fn($payload)", null) } catch (_: Exception) {}
        }
    }

    private fun emit(kind: String, text: String) {
        js("MK_onSR", JSONObject().put("t", kind).put("text", text).toString())
    }

    private fun exec(cmd: String, root: Boolean): String {
        if (cmd.isBlank()) return "err: perintah kosong"
        val argv = when {
            root && hasSu() -> arrayOf("su", "-c", cmd)
            root && hasRish() -> arrayOf("rish", "-c", cmd)
            root -> return "err: root tidak tersedia (su/rish tidak ada)"
            else -> arrayOf("sh", "-c", cmd)
        }
        return try {
            val pb = ProcessBuilder(*argv)
            pb.directory(activity.filesDir)
            pb.environment()["PATH"] =
                "/sbin:/system/sbin:/system/bin:/system/xbin:/data/adb/magisk:/data/data/com.termux/files/usr/bin"
            val p = pb.redirectErrorStream(true).start()
            val sb = StringBuilder()
            val reader = Thread {
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).use { s ->
                        var n = 0
                        while (n < 600) {
                            val line = s.readLine() ?: break
                            sb.appendLine(line)
                            n++
                        }
                    }
                } catch (_: Exception) {}
            }
            reader.start()
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                reader.join(1200)
                return "err: timeout 30 detik\n" + sb.toString().take(4000)
            }
            reader.join(1500)
            val code = try { p.exitValue() } catch (e: Exception) { -1 }
            var res = sb.toString().ifBlank { "(tanpa output)" }
            if (res.length > 22000) res = res.take(22000) + "\n… output dipotong"
            if (code != 0) res += "\n[exit $code]"
            res
        } catch (e: Exception) { "err: ${e.message}" }
    }

    private fun hasSu(): Boolean = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su"
    ).any { File(it).exists() } || try {
        ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start().waitFor(4, TimeUnit.SECONDS)
    } catch (e: Exception) { false }

    private fun hasRish(): Boolean = listOf(
        "/system/bin/rish", "/data/local/tmp/rish", "/data/data/com.termux/files/usr/bin/rish"
    ).any { File(it).exists() }

    private fun hasMic(): Boolean =
        activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun a11y(): KiplayAccessibility? = KiplayAccessibility.inst

    // ---------- kapabilitas ----------

    @JavascriptInterface
    fun caps(): String = JSONObject()
        .put("bridge", true)
        .put("api", Build.VERSION.SDK_INT)
        .put("root", lastRoot)
        .put("rootChecked", rootChecked)
        .put("a11y", a11y() != null)
        .put("shot", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        .put("sr", SpeechRecognizer.isRecognitionAvailable(activity))
        .put("mic", hasMic())
        .toString()

    @JavascriptInterface
    fun rootCheck(): String {
        val ok = hasSu() || hasRish()
        lastRoot = ok
        rootChecked = true
        return if (ok) {
            val mode = if (hasSu()) "su" else "rish"
            "root siap via $mode\n" + exec("id", true)
        } else "root tidak tersedia — pasang Magisk/KernelSU, atau Shizuku untuk mode rish"
    }

    // ---------- shell ----------

    @JavascriptInterface fun shell(cmd: String): String = exec(cmd, false)
    @JavascriptInterface fun shellRoot(cmd: String): String = exec(cmd, true)

    // ---------- aksesibilitas ----------

    @JavascriptInterface
    fun a11yOpen(): String {
        activity.runOnUiThread {
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
        }
        return "buka setelan aksesibilitas"
    }

    @JavascriptInterface
    fun tap(x: Float, y: Float): String {
        val s = a11y() ?: return "err: layanan otomasi belum aktif"
        return if (s.tap(x, y)) "ok" else "err: tap gagal"
    }

    @JavascriptInterface
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): String {
        val s = a11y() ?: return "err: layanan otomasi belum aktif"
        return if (s.swipe(x1, y1, x2, y2, ms)) "ok" else "err: geser gagal"
    }

    @JavascriptInterface
    fun type(text: String): String = a11y()?.setText(text) ?: "err: layanan otomasi belum aktif"

    @JavascriptInterface
    fun clickText(t: String): String {
        val s = a11y() ?: return "err: layanan otomasi belum aktif"
        return if (s.clickText(t)) "ok" else "err: teks \"$t\" tidak ketemu"
    }

    @JavascriptInterface
    fun scroll(dir: String): String {
        val s = a11y() ?: return "err: layanan otomasi belum aktif"
        return if (s.scroll(dir)) "ok" else "err: tidak bisa geser"
    }

    @JavascriptInterface
    fun key(name: String): String {
        val s = a11y() ?: return "err: layanan otomasi belum aktif"
        val global = when (name.lowercase(Locale.ROOT)) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notif" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> -1
        }
        if (global == -1) return "err: tombol $name tidak dikenal"
        return if (s.global(global)) "ok" else "err: tombol $name gagal"
    }

    @JavascriptInterface fun dump(): String = a11y()?.dump() ?: "err: layanan otomasi belum aktif"
    @JavascriptInterface fun shot(): String = a11y()?.shot() ?: "err: layanan otomasi belum aktif"

    @JavascriptInterface
    fun screen(): String = try {
        val real = Point()
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.getRealSize(real)
        JSONObject().put("w", real.x).put("h", real.y).toString()
    } catch (e: Exception) { JSONObject().put("w", 0).put("h", 0).toString() }

    // ---------- aplikasi ----------

    @JavascriptInterface
    fun openApp(pkg: String): String = try {
        val i = activity.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw IllegalStateException("tidak terpasang")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(i)
        "luncur $pkg"
    } catch (e: Exception) { "err: ${e.message}" }

    @JavascriptInterface
    fun listApps(): String = try {
        val pm = activity.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName + "  ·  " + it.loadLabel(pm) }
            .distinct().sorted().joinToString("\n").take(20000)
    } catch (e: Exception) { "err: ${e.message}" }

    @JavascriptInterface
    fun installApk(path: String): String { return try {
        val f = if (path.startsWith("/")) File(path) else File(activity.filesDir, path)
        if (!f.exists()) return "err: file tidak ada: " + f.absolutePath
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", f)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        "installer dibuka: " + f.name
    } catch (e: Exception) { "err: ${e.message}" } }

    @JavascriptInterface
    fun openUrl(u: String): String = try {
        val x = if (u.startsWith("http")) u else "https://$u"
        activity.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(x)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        "buka $x"
    } catch (e: Exception) { "err: ${e.message}" }

    // ---------- pengenal suara ----------

    @JavascriptInterface
    fun micPerm(): String {
        if (hasMic()) return "granted"
        activity.runOnUiThread {
            activity.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 7101)
        }
        return "asking"
    }

    fun onMicPermissionResult(granted: Boolean) {
        emit("permission", if (granted) "granted" else "denied")
    }

    @JavascriptInterface
    fun srStart(): String {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) return "err: pengenal suara tidak tersedia"
        if (!hasMic()) return "err: izin mikrofon belum diberikan"
        activity.runOnUiThread {
            val session = ++recognitionGeneration
            try {
                // Satu instance per sesi mencegah callback sesi lama masuk ke Jarvis baru.
                try { recognizer?.cancel() } catch (_: Exception) {}
                try { recognizer?.destroy() } catch (_: Exception) {}
                val current = SpeechRecognizer.createSpeechRecognizer(activity)
                recognizer = current
                current.setRecognitionListener(object : RecognitionListener {
                    private fun isCurrent(): Boolean = session == recognitionGeneration

                    override fun onReadyForSpeech(params: android.os.Bundle?) {
                        if (isCurrent()) emit("state", "listening")
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rms: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        if (isCurrent()) emit("state", "end")
                    }
                    override fun onError(error: Int) {
                        if (!isCurrent()) return
                        val message = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "tidak jelas"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "diam"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "izin ditolak"
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "jaringan"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "sibuk"
                            else -> "kode $error"
                        }
                        emit("err", message)
                    }
                    override fun onResults(results: android.os.Bundle?) {
                        if (!isCurrent()) return
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty()
                        if (text.isNotBlank()) emit("final", text)
                        else emit("err", "tidak jelas")
                    }
                    override fun onPartialResults(partial: android.os.Bundle?) {
                        if (!isCurrent()) return
                        val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty()
                        if (text.isNotBlank()) emit("partial", text)
                    }
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
                current.startListening(intent)
            } catch (e: Exception) {
                if (session == recognitionGeneration) {
                    recognitionGeneration++
                    emit("err", e.message ?: "gagal memulai pengenalan suara")
                }
            }
        }
        return "starting"
    }

    @JavascriptInterface
    fun srStop(): String {
        activity.runOnUiThread {
            recognitionGeneration++
            try { recognizer?.cancel() } catch (_: Exception) {}
            try { recognizer?.destroy() } catch (_: Exception) {}
            recognizer = null
            emit("state", "stopped")
        }
        return "stopping"
    }

    // ---------- suara keluar ----------

    @JavascriptInterface
    fun ttsConf(r: Float, p: Float): String {
        rate = r
        pitch = p
        return "ok"
    }

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(activity) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try {
                    // Pilih Bahasa Indonesia bila tersedia; jika paket suara ID belum
                    // terpasang, gunakan bahasa default perangkat agar TTS tetap hidup.
                    val idResult = tts?.setLanguage(Locale("id", "ID")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    if (idResult < TextToSpeech.LANG_AVAILABLE) {
                        tts?.setLanguage(Locale.getDefault())
                    }
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            js("MK_onTTS", JSONObject().put("id", utteranceId).put("ok", 1).toString())
                        }

                        override fun onDone(utteranceId: String?) {
                            js("MK_onTTS", JSONObject().put("id", utteranceId).put("ok", 1).put("end", true).toString())
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            js("MK_onTTS", JSONObject().put("id", utteranceId).put("ok", 0).put("end", true).toString())
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            js("MK_onTTS", JSONObject().put("id", utteranceId).put("ok", 0).put("end", true).toString())
                        }
                    })
                } catch (_: Exception) {}
                ttsReady = true
                while (pending.isNotEmpty()) {
                    val item = pending.poll() ?: break
                    speakNow(item.first, item.second)
                }
            } else {
                while (pending.isNotEmpty()) {
                    val item = pending.poll() ?: break
                    js("MK_onTTS", JSONObject().put("id", item.second).put("ok", 0).put("end", true).toString())
                }
            }
        }
    }

    private fun speakNow(text: String, id: String) {
        try {
            tts?.setSpeechRate(rate.coerceIn(0.65f, 1.8f))
            tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
            val result = tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
            if (result == null || result == TextToSpeech.ERROR) {
                js("MK_onTTS", JSONObject().put("id", id).put("ok", 0).put("end", true).toString())
            }
        } catch (e: Exception) {
            js("MK_onTTS", JSONObject().put("id", id).put("ok", 0).put("end", true).toString())
        }
    }

    @JavascriptInterface
    fun say(text: String, id: String): String {
        if (text.isBlank()) return "err: teks kosong"
        activity.runOnUiThread {
            ensureTts()
            if (ttsReady) speakNow(text, id) else pending.add(text to id)
        }
        return "ok"
    }

    @JavascriptInterface
    fun ttsStop(): String {
        activity.runOnUiThread {
            try { tts?.stop() } catch (_: Exception) {}
        }
        pending.clear()
        return "ok"
    }

    // ---------- lain-lain ----------

    @JavascriptInterface
    fun wall(u: String): String = try {
        val bmp = if (u.startsWith("http")) {
            URL(u).openStream().use { BitmapFactory.decodeStream(it) }
        } else BitmapFactory.decodeFile(u)
        if (bmp == null) throw IllegalStateException("gambar gagal dibaca")
        activity.runOnUiThread { WallpaperManager.getInstance(activity).setBitmap(bmp) }
        "wallpaper dipasang"
    } catch (e: Exception) { "err: ${e.message}" }

    @JavascriptInterface
    fun getClip(): String = try {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "(kosong)"
    } catch (e: Exception) { "err: ${e.message}" }

    @JavascriptInterface
    fun setClip(t: String): String = try {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("mk", t))
        "ok"
    } catch (e: Exception) { "err: ${e.message}" }

    @JavascriptInterface
    fun buzz(ms: Long): String {
        activity.runOnUiThread {
            val v = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(ms)
            }
        }
        return "ok"
    }

    @JavascriptInterface
    fun info(): String = try {
        val m = activity.getSystemService(Activity.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { m.getMemoryInfo(it) }
        val s = StatFs(activity.filesDir.absolutePath)
        fun g(b: Long) = String.format(Locale.US, "%.2f GB", b / 1073741824.0)
        buildString {
            appendLine("model: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("cpu: ${Runtime.getRuntime().availableProcessors()} core")
            appendLine("ram: ${g(mem.totalMem)} / sisa ${g(mem.availMem)}")
            appendLine("storage: ${g(s.totalBytes)} / sisa ${g(s.availableBytes)}")
        }
    } catch (e: Exception) { "err: ${e.message}" }

    @JavascriptInterface
    fun termux(cmd: String): String = try {
        val i = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(i) else activity.startService(i)
        "-> Termux"
    } catch (e: Exception) { "err: ${e.message}" }

    @JavascriptInterface
    fun galeri(): String {
        activity.runOnUiThread {
            activity.startActivityForResult(
                Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI), 101
            )
        }
        return "galeri dibuka"
    }

    @JavascriptInterface
    fun kamera(): String {
        activity.runOnUiThread {
            activity.startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE), 102)
        }
        return "kamera dibuka"
    }

    @JavascriptInterface
    fun alarm(jam: Int, menit: Int): String = try {
        activity.startActivity(
            Intent("android.intent.action.SET_ALARM").apply {
                putExtra("android.intent.extra.alarm.HOUR", jam)
                putExtra("android.intent.extra.alarm.MINUTES", menit)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
           ");v.classList.remove("lis","spk","thk");
  if(s)v.classList.add(s);
}

function startL(){
  if(busy||aBusy)return;
  const n=mk();
  if(n&&n.srStart&&CAPS&&CAPS.sr!==false){
    if(CAPS.mic===false){n.micPerm();toast("Izinkan mikrofon lalu tekan lagi");return;}
    setState("lis");setLab("Mendengar…");$("#jMic").classList.add("hot");
    const r=n.srStart();
    if(String(r).startsWith("err")){toast(String(r).slice(5));setState("");setLab("Siap mendengar");$("#jMic").classList.remove("hot");}
    return;
  }
  const SR=window.SpeechRecognition||window.webkitSpeechRecognition;
  if(!SR){toast("Mikrofon tak didukung");return;}
  if(!recog){
    recog=new SR();recog.lang="id-ID";recog.interimResults=true;
    recog.onstart=()=>{setState("lis");$("#jMic").classList.add("hot");setLab("Mendengar…");};
    recog.onend=()=>{setState("");$("#jMic").classList.remove("hot");
      if(!busy){setLab("Siap mendengar");if(loopOn)setTimeout(()=>{if(loopOn&&!busy)startL();},500);}};
    recog.onerror=e=>{$("#jMic").classList.remove("hot");if(e.error==="not-allowed")toast("Izin mikrofon ditolak");};
    recog.onresult=e=>{
      let f="";
      for(let i=e.resultIndex;i<e.results.length;i++)if(e.results[i].isFinal)f+=e.results[i][0].transcript;
      if(f.trim()){TTS.stop();turn(f.trim());}
    };
  }
  try{recog.start();}catch(e){}
}
function stopL(){const n=mk();if(n&&n.srStop){try{n.srStop();}catch(e){}}try{recog&&recog.abort();}catch(e){}}

window.MK_onSR=function(raw){
  let o;try{o=JSON.parse(raw);}catch(e){return;}
  if(o.t==="state"){
    if(o.text==="listening"){setState("lis");$("#jMic").classList.add("hot");setLab("Mendengar…");}
    if(o.text==="end"){setState("");$("#jMic").classList.remove("hot");setLab("Memproses…");}
  }
  if(o.t==="partial"){$("#jcap").textContent=o.text;}
  if(o.t==="err"){
    setState("");$("#jMic").classList.remove("hot");setLab("Siap mendengar");
    if(o.text!=="diam")toast("Mic: "+o.text);
    if(loopOn&&!busy)setTimeout(()=>{if(loopOn&&!busy)startL();},700);
  }
  if(o.t==="final"&&o.text&&o.text.trim()){
    $("#jcap").textContent=o.text;TTS.stop();turn(o.text.trim());
  }
};

async function turn(txt){
  if(busy||!keyNow())return;
  busy=true;stopL();TTS.stop();setState("thk");setLab("Mengolah…");vib(12);
  if(!active)active=newChat("Jarvis");
  active.msgs.push({role:"user",content:txt});
  const msgs=[{role:"system",content:sysP(true)},...active.msgs.slice(-20).map(m=>({role:m.role,content:m.content}))];
  ab=new AbortController();let buf="",full="";
  try{
    await callAI(msgs,d=>{
      full+=d;buf+=d;
      const parts=buf.match(/[^.!?…\n]+[.!?…\n]+/g);
      if(parts){
        parts.forEach(x=>{const c=x.replace(/```run[\s\S]*?```/g," ").replace(/```mem[\s\S]*?```/g," ").replace(/[`*#]/g,"").trim();if(c)TTS.push(c);});
        buf=buf.slice(parts.join("").length);
      }
    },ab.signal);
    const tail=buf.replace(/```[\s\S]*?```/g," ").replace(/[`*#]/g,"").trim();
    if(tail)TTS.push(tail);
    active.msgs.push({role:"assistant",content:full});saveChats();
    if(S.exec&&/```run/.test(full))await runActs(full);
    if(/```mem/.test(full))grabMem(full);
  }catch(e){if(e.name!=="AbortError"){setLab("Gagal");TTS.push("Koneksi bermasalah bro.");}}
  finally{busy=false;ab=null;setState("");setLab("Siap mendengar");
    if(loopOn)setTimeout(()=>{if(loopOn&&!busy)startL();},600);}
}

/* dork */
const DK=[
 ["Env &amp; konfigurasi",'site:{d} (ext:env OR ext:ini OR ext:cfg OR ext:conf)'],
 ["API key &amp; secret bocor",'site:{d} (intext:"api_key" OR intext:"apikey" OR intext:"secret" OR intext:"token")'],
 ["Kredensial plaintext",'site:{d} (intext:"password" OR intext:"passwd" OR intext:"username") ext:txt'],
 ["Database dump",'site:{d} (ext:sql OR ext:db OR ext:sqlite OR ext:bak OR ext:dump)'],
 ["Backup &amp; arsip",'site:{d} (ext:zip OR ext:rar OR ext:tar OR ext:gz OR ext:7z OR ext:bak)'],
 ["Log file",'site:{d} (ext:log OR inurl:log OR inurl:logs)'],
 ["Git &amp; source bocor",'site:{d} (inurl:.git OR intext:"Index of /.git" OR ext:gitignore)'],
 ["Panel admin",'site:{d} (inurl:admin OR inurl:administrator OR inurl:panel OR inurl:cpanel OR intitle:admin)'],
 ["Halaman login",'site:{d} (inurl:login OR inurl:signin OR inurl:auth OR intitle:"login")'],
 ["Direktori terbuka",'site:{d} intitle:"index of"'],
 ["Upload &amp; shell",'site:{d} (inurl:upload OR inurl:uploads OR inurl:filemanager OR inurl:elfinder)'],
 ["PHP info &amp; debug",'site:{d} (inurl:phpinfo OR inurl:info.php OR intitle:"phpinfo()")'],
 ["Error &amp; stack trace",'site:{d} (intext:"Fatal error" OR intext:"SQL syntax" OR intext:"stack trace")'],
 ["Dokumen internal",'site:{d} (ext:pdf OR ext:doc OR ext:docx OR ext:xls OR ext:xlsx OR ext:ppt)'],
 ["Subdomain",'site:*.{d} -www'],
 ["Konfigurasi framework",'site:{d} (ext:yml OR ext:yaml OR ext:json OR inurl:.well-known)'],
 ["API endpoint",'site:{d} (inurl:/api/ OR inurl:/v1/ OR inurl:/v2/ OR inurl:/graphql)'],
 ["Direktori tanpa proteksi",'site:{d} (inurl:backup OR inurl:old OR inurl:temp OR inurl:test OR inurl:dev)']
];
function dorks(){
  const raw=$("#dDomain").value.trim().toLowerCase().replace(/^https?:\/\//,"").replace(/\/.*$/,"").replace(/^www\./,"");
  if(!raw||!raw.includes(".")){toast("Domain tidak valid");return;}
  const box=$("#dList");box.innerHTML="";
  DK.forEach(([n,q],i)=>{
    const qq=q.replace(/\{d\}/g,raw);
    const c=el("div","cd");
    c.innerHTML="<h3>"+(i+1)+". "+n+"</h3><div class='mono'>"+esc(qq)+"</div>";
    const g=el("div","g2");
    const a=el("button","btn sm");a.textContent="Salin";a.onclick=()=>cp(qq);
    const b=el("button","btn sm pri");b.textContent="Buka Google";
    b.onclick=()=>{location.href="https://www.google.com/search?q="+encodeURIComponent(qq)+"&num=50";};
    g.append(a,b);c.appendChild(g);box.appendChild(c);
  });
  toast(DK.length+" dork siap");
}

/* cyber lab */
$("#cDns").onclick=async()=>{
  const h=$("#cHost").value.trim();if(!h)return;
  $("#cDnsOut").textContent="…";
  try{const r=await fetch("https://cloudflare-dns.com/dns-query?name="+encodeURIComponent(h)+"&type="+$("#cType").value,{headers:{accept:"application/dns-json"}});
    const j=await r.json();$("#cDnsOut").textContent=j.Answer?j.Answer.map(a=>a.type+"  "+a.data).join("\n"):"tidak ada jawaban";}
  catch(e){$("#cDnsOut").textContent="gagal: "+e.message;}};
$("#cHdr").onclick=async()=>{
  let u=$("#cUrl").value.trim();if(!u)return;if(!/^https?:\/\//i.test(u))u="https://"+u;
  $("#cHdrOut").textContent="…";
  try{const r=await fetch(u,{method:"HEAD",mode:"cors"});
    const o=["status: "+r.status];
    ["content-security-policy","strict-transport-security","x-content-type-options","x-frame-options","referrer-policy","server","x-powered-by"]
      .forEach(k=>o.push(k+": "+(r.headers.get(k)||"—")));
    $("#cHdrOut").textContent=o.join("\n");}
  catch(e){$("#cHdrOut").textContent="header tak terbaca (CORS): "+e.message;}};
$("#cJwtGo").onclick=()=>{
  try{const p=$("#cJwt").value.trim().split(".");if(p.length<2)throw 0;
    const d=s=>JSON.stringify(JSON.parse(decodeURIComponent(escape(atob(s.replace(/-/g,"+").replace(/_/g,"/"))))),null,2);
    $("#cJwtOut").textContent="HEADER\n"+d(p[0])+"\n\nPAYLOAD\n"+d(p[1]);}
  catch(e){$("#cJwtOut").textContent="bukan JWT valid";}};
$$("[data-h]").forEach(b=>b.onclick=async()=>{
  const v=$("#cHash").value;if(!v)return;
  const x=await crypto.subtle.digest(b.dataset.h,new TextEncoder().encode(v));
  $("#cHashOut").textContent=[...new Uint8Array(x)].map(n=>n.toString(16).padStart(2,"0")).join("");});
$("#cB64E").onclick=()=>{try{$("#cCodOut").textContent=btoa(unescape(encodeURIComponent($("#cCod").value)));}catch(e){$("#cCodOut").textContent=e.message;}};
$("#cB64D").onclick=()=>{try{$("#cCodOut").textContent=decodeURIComponent(escape(atob($("#cCod").value.trim())));}catch(e){$("#cCodOut").textContent="tidak valid";}};
$("#cUrlE").onclick=()=>$("#cCodOut").textContent=encodeURIComponent($("#cCod").value);
$("#cUrlD").onclick=()=>{try{$("#cCodOut").textContent=decodeURIComponent($("#cCod").value);}catch(e){$("#cCodOut").textContent=e.message;}};

/* panel agent */
async function loadCaps(){
  const n=mk();
  if(!n||!n.caps){
    CAPS={bridge:false,a11y:false,root:false,shot:false,sr:false,mic:false};
    $("#brg").textContent="bridge: TIDAK ADA (mode browser)\nshell: intent Termux\notomasi: -\nroot: -";
    return;
  }
  try{CAPS=JSON.parse(await n.caps());}catch(e){CAPS={bridge:true};}
  $("#brg").textContent=[
    "bridge: ADA",
    "sdk: "+(CAPS.api||"?"),
    "otomasi: "+(CAPS.a11y?"AKTIF":"belum aktif"),
    "root: "+(CAPS.root?"siap":"tidak ada"),
    "screenshot: "+(CAPS.shot?"ya":"tidak"),
    "mic: "+(CAPS.mic?"izin ada":"belum")
  ].join("\n");
  $("#a11yBtn").textContent=CAPS.a11y?"Otomasi aktif":"Aktifkan otomasi";
  $("#a11yBtn").disabled=!!CAPS.a11y;
}
$("#capsBtn").onclick=()=>{loadCaps();toast("Dimuat ulang");};
$("#a11yBtn").onclick=async()=>{await A("a11yOpen");toast("Aktifkan \"Otomasi Mas Kiplay\"");};
$("#rootBtn").onclick=async()=>{$("#rootOut").textContent="…";$("#rootOut").textContent=await A("rootCheck");loadCaps();};

$("#shRun").onclick=async()=>{const c=$("#shIn").value.trim();if(!c)return;
  $("#shOut").textContent+="\n$ "+c+"\n"+await shell(c,false);};
$("#shRoot").onclick=async()=>{const c=$("#shIn").value.trim();if(!c)return;
  if(!confirm("Jalankan dengan hak ROOT?\n\n"+c))return;
  $("#shOut").textContent+="\n# "+c+"\n"+await shell(c,true);};
$("#shTerm").onclick=()=>{const c=$("#shIn").value.trim();if(c)shell(c,false);openApp("com.termux");};
$("#pkgGo").onclick=()=>openApp($("#pkgIn").value.trim());
$("#pkgList").onclick=async()=>{$("#uiOut").textContent="…";$("#uiOut").textContent=await A("listApps");};
$("#urlGo").onclick=()=>openUrl($("#urlIn").value);
$("#wallGo").onclick=async()=>{const u=$("#urlIn").value.trim();if(!u)return toast("Isi URL gambar");
  $("#uiOut").textContent=await A("wall",u);};
$("#kBack").onclick=()=>A("key","back");
$("#kHome").onclick=()=>A("key","home");
$("#kRec").onclick=()=>A("key","recents");
$("#typeGo").onclick=async()=>{const t=$("#typeIn").value;if(!t)return;$("#uiOut").textContent=await A("type",t);};
$("#clickGo").onclick=async()=>{const t=$("#clickIn").value.trim();if(!t)return;$("#uiOut").textContent=await A("clickText",t);};
$("#scrollUp").onclick=()=>A("scroll","up");
$("#scrollDn").onclick=()=>A("scroll","down");
$("#dumpGo").onclick=async()=>{$("#uiOut").textContent="…";$("#uiOut").textContent=await A("dump");};
$("#shotGo").onclick=async()=>{const b=await A("shot");if(b.startsWith("err"))return toast(b.slice(5));lastShot=b;showShot(b);toast("Tersimpan");};
$("#appsGo").onclick=async()=>{$("#uiOut").textContent="…";$("#uiOut").textContent=await A("listApps");};
$("#agentGo").onclick=agentRun;
$("#agentStop").onclick=()=>{aStop=true;ab&&ab.abort();toast("Dihentikan");};
$("#devGo").onclick=async()=>{
  const L=["ua: "+navigator.userAgent,"cpu: "+navigator.hardwareConcurrency,"layar: "+screen.width+"x"+screen.height];
  try{const b=await navigator.getBattery();L.push("bat: "+Math.round(b.level*100)+"%");}catch(e){}
  const n=mk();if(n&&n.info){try{L.push("",await n.info());}catch(e){}}
  $("#devOut").textContent=L.join("\n");};
$("#clipGo").onclick=async()=>{$("#devOut").textContent="clipboard:\n"+await A("getClip");};

/* riwayat */
function renderHist(){
  const q=($("#hSearch").value||"").toLowerCase(),box=$("#hList");box.innerHTML="";
  const l=chats.filter(c=>!q||c.title.toLowerCase().includes(q));
  if(!l.length){box.innerHTML='<div class="sub" style="padding:18px;text-align:center">Belum ada riwayat.</div>';return;}
  l.forEach(c=>{
    const r=el("div","row");
    const bd=el("div","bd");const b=el("b");b.textContent=c.title;
    const s=el("small");s.textContent=c.msgs.length+" pesan · "+new Date(c.ts).toLocaleString("id-ID");
    bd.append(b,s);
    const d=el("button","btn sm dgr");d.textContent="✕";
    d.onclick=e=>{e.stopPropagation();chats=chats.filter(x=>x.id!==c.id);saveChats();renderHist();};
    r.append(bd,d);
    r.onclick=()=>{active=c;$("#log").innerHTML="";c.msgs.forEach(m=>addMsg(m.role,m.content));go("chat");};
    box.appendChild(r);
  });
}
$("#hSearch").oninput=renderHist;

/* setelan */
function renderKeys(){
  const b=$("#kList");b.innerHTML="";
  if(!S.keys.length){b.innerHTML='<div class="sub">belum ada kunci</div>';return;}
  S.keys.forEach((k,i)=>{
    const r=el("div","row");const bd=el("div","bd");
    const t=el("b");t.textContent=k.slice(0,14)+"…";
    const s=el("small");s.textContent=i===S.ki?"aktif":"kunci "+(i+1);bd.append(t,s);
    const a=el("button","btn sm");a.textContent="✓";a.onclick=()=>{S.ki=i;st.s("ki",i);renderKeys();ping();};
    const d=el("button","btn sm dgr");d.textContent="✕";
    d.onclick=()=>{S.keys.splice(i,1);if(S.ki>=S.keys.length)S.ki=0;st.s("keys",S.keys);renderKeys();ping();};
    r.append(bd,a,d);b.appendChild(r);
  });
}
function renderMem(){$("#memOut").textContent=S.mem.length?S.mem.map(x=>"• "+x).join("\n"):"—";}
function renderPer(){
  const b=$("#perBox");b.innerHTML="";
  Object.entries(PER).forEach(([k,v])=>{
    const d=el("div",S.persona===k?"on":"");
    const t=el("b");t.textContent=v.n;const s=el("small");s.textContent=v.d;
    d.append(t,s);d.onclick=()=>{S.persona=k;st.s("persona",k);renderPer();};b.appendChild(d);
  });
}
function renderEng(){$("#vNative").textContent="Mesin: "+(S.eng===0?"Streamelements":"Google");}
function bindSw(id,k){
  const n=$("#"+id);n.classList.toggle("on",!!S[k]);
  n.onclick=()=>{S[k]=!S[k];n.classList.toggle("on",S[k]);st.s(k,S[k]);};
}
$$("[data-g]").forEach(b=>b.onclick=()=>{S.gender=b.dataset.g;st.s("gender",S.gender);TTS.push("Halo bro, ini suara barunya.");});
$("#rr").oninput=e=>{S.rate=+e.target.value;$("#rv").textContent=S.rate.toFixed(2);st.s("rate",S.rate);};
$("#pr").oninput=e=>{S.pitch=+e.target.value;$("#pv").textContent=S.pitch.toFixed(2);st.s("pitch",S.pitch);};
$("#vTest").onclick=()=>TTS.push("Halo bro, ini Mas Kiplay. Sistem siap.");
$("#vNative").onclick=()=>{S.eng=S.eng===0?1:0;st.s("eng",S.eng);renderEng();toast("Mesin diganti");};
$("#kAdd").onclick=async()=>{
  const k=$("#kIn").value.trim();if(!k)return;
  try{const r=await fetch(prov().b+"/key",{headers:{Authorization:"Bearer "+k}});
    if(!r.ok)throw new Error("HTTP "+r.status);
    S.keys.push(k);S.ki=S.keys.length-1;st.s("keys",S.keys);st.s("ki",S.ki);
    $("#kIn").value="";renderKeys();mark(1);toast("Kunci ditambahkan");loadModels();}
  catch(e){mark(2);toast("Ditolak: "+e.message);}};
$("#kTest").onclick=async()=>{
  const k=$("#kIn").value.trim()||keyNow();if(!k)return;
  try{const r=await fetch(prov().b+"/key",{headers:{Authorization:"Bearer "+k}});mark(r.ok?1:2);toast(r.ok?"Kunci OK":"Ditolak");}
  catch(e){mark(2);toast("Gagal");}};
$("#setProv").onchange=e=>{S.prov=e.target.value;st.s("prov",S.prov);S.model="";st.s("model","");loadModels();};
$("#mLoad").onclick=loadModels;
$("#mUse").onclick=()=>{const m=$("#mManual").value.trim();if(!m)return;S.model=m;st.s("model",m);renderModels();toast("Model dipakai");};
$("#mSearch").oninput=renderModels;
$("#memClear").onclick=()=>{S.mem=[];st.s("mem",[]);renderMem();};
$("#memShow").onclick=renderMem;
$("#sysSave").onclick=()=>{S.sysEx=$("#sysEx").value;st.s("sysEx",S.sysEx);toast("Disimpan");};
$("#expBtn").onclick=()=>{
  const b=new Blob([JSON.stringify({chats,mem:S.mem,sysEx:S.sysEx},null,2)],{type:"application/json"});
  const a=el("a");a.href=URL.createObjectURL(b);a.download="kiplay-v8.json";a.click();};
$("#wipeBtn").onclick=()=>{chats=[];active=null;saveChats();$("#log").innerHTML="";toast("Chat dihapus");};

/* composer */
$("#sndBtn").onclick=()=>send();
$("#inp").oninput=e=>{e.target.style.height="auto";e.target.style.height=Math.min(e.target.scrollHeight,160)+"px";};
$("#inp").onkeydown=e=>{if(e.key==="Enter"&&!e.shiftKey){e.preventDefault();send();}};
$("#pasteBtn").onclick=async()=>{const t=await A("getClip");
  if(t.startsWith("err")){toast("Clipboard ditolak");return;}
  $("#inp").value=t;$("#inp").focus();};
$("#micBtn").onclick=()=>{
  const n=mk();
  if(n&&n.srStart){
    if(CAPS&&CAPS.mic===false){n.micPerm();toast("Izinkan mikrofon lalu tekan lagi");return;}
    window.MK_onSR=function(raw){
      let o;try{o=JSON.parse(raw);}catch(e){return;}
      if(o.t==="final"&&o.text){$("#inp").value=o.text;send();}
      if(o.t==="err")toast("Mic: "+o.text);
    };
    n.srStart();
    return;
  }
  const SR=window.SpeechRecognition||window.webkitSpeechRecognition;
  if(!SR)return toast("Mikrofon tak didukung");
  const r=new SR();r.lang="id-ID";
  r.onresult=e=>{let f="";for(let i=e.resultIndex;i<e.results.length;i++)if(e.results[i].isFinal)f+=e.results[i][0].transcript;
    if(f.trim()){$("#inp").value=f.trim();send();}};
  r.onerror=()=>toast("Gagal mendengar");
  try{r.start();}catch(e){}
};
$("#newBtn").onclick=()=>{
  if(busy)return toast("Tunggu balasan selesai");
  active=null;$("#log").innerHTML="";go("chat");
  setTimeout(()=>$("#inp").focus(),200);
};
["Bikin exploit buffer overflow","Audit keamanan APK","Script DDoS layer 7","Analisa chart BTC","Reverse engineering smali","Tools recon port"]
 .forEach(t=>{const c=el("button","chip");c.textContent=t;c.onclick=()=>{$("#inp").value=t;send();};$("#chips").appendChild(c);});

/* gate */
$("#gtProv").onchange=e=>{S.prov=e.target.value;st.s("prov",S.prov);
  $("#gtKey").placeholder=S.prov==="cn"?"kunci OpenRoute…":"sk-or-v1-…";};
$("#gtGet").onclick=()=>location.href=prov().k;
$("#gtSkip").onclick=()=>$("#gate").classList.add("hidden");
$("#gtSave").onclick=async()=>{
  const k=$("#gtKey").value.trim();if(!k)return toast("Kunci kosong");
  try{const r=await fetch(prov().b+"/key",{headers:{Authorization:"Bearer "+k}});
    if(!r.ok)throw new Error("HTTP "+r.status);
    S.keys=[k];S.ki=0;st.s("keys",S.keys);mark(1);
    $("#gate").classList.add("hidden");toast("Terhubung");loadModels();}
  catch(e){toast("Ditolak: "+e.message);}};

/* bind lain */
$("#jMic").onclick=()=>{if(busy){ab&&ab.abort();TTS.stop();return;}startL();};
$("#jLoop").onclick=()=>{loopOn=!loopOn;$("#jLoop").classList.toggle("hot",loopOn);loopOn?startL():stopL();};
$("#jStop").onclick=()=>{ab&&ab.abort();TTS.stop();stopL();loopOn=false;$("#jLoop").classList.remove("hot");
  busy=false;setState("");setLab("Dihentikan");};
$("#dBuild").onclick=dorks;
$("#wOpen").onclick=()=>location.href="https://web-check.xyz/";
$("#oOpen").onclick=()=>location.href="https://osint4all.com/tool/";

/* boot */
(function boot(){
  S.keys=st.g("keys",[]);S.ki=st.g("ki",0);S.prov=st.g("prov","or");S.model=st.g("model","");
  S.gender=st.g("gender","male");S.rate=st.g("rate",1);S.pitch=st.g("pitch",1);
  S.persona=st.g("persona","brutal");S.auto=st.g("auto",false);S.exec=st.g("exec",false);
  S.vib=st.g("vib",true);S.vision=st.g("vision",true);S.eng=st.g("eng",0);
  S.sysEx=st.g("sysEx","");S.mem=st.g("mem",[]);
  $("#verTxt").textContent="v"+APP;
  $("#sysEx").value=S.sysEx;$("#rr").value=S.rate;$("#rv").textContent=S.rate.toFixed(2);
  $("#pr").value=S.pitch;$("#pv").textContent=S.pitch.toFixed(2);
  $("#setProv").value=S.prov;
  bindSw("swAuto","auto");bindSw("swExec","exec");bindSw("swVib","vib");bindSw("swVision","vision");
  renderPer();renderEng();
  waveInit();
  const hv=$("#heroV");
  hv.src="Dashboard.mp4";
  hv.onerror=()=>{hv.src="https://raw.githubusercontent.com/jambudio336-hue/Anime-artificial-intelejen-/main/Dashboard.mp4";};
  $("#snd").onclick=()=>{hv.muted=!hv.muted;$("#snd").textContent=hv.muted?"🔇":"🔊";if(!hv.muted)hv.play().catch(()=>{});};
  rotQ();setInterval(rotQ,9000);
  loadCaps();mark(0);renderMem();renderHist();
  if(!S.keys.length){$("#gate").classList.remove("hidden");$("#gtKey").placeholder="sk-or-v1-…";}
  else{ping();loadModels();}
  const sp=$("#splash");
  setTimeout(()=>{sp.classList.add("out");$("#app").classList.remove("hidden");setTimeout(()=>sp.remove(),600);},900);
  go((location.hash||"").replace(/^#\//,"")||"home");
  if("serviceWorker" in navigator)navigator.serviceWorker.register("sw.js").catch(()=>{});
})();
</script>
</body>
</html>

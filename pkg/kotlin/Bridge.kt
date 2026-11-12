package ai.maskiplay.app

import android.annotation.SuppressLint

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.PendingIntent
import android.widget.Toast
import android.widget.EditText
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
import android.webkit.ValueCallback
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Jembatan WebView -> native. Didaftarkan MainActivity sebagai window.MK.
 */
class Bridge(private val activity: Activity, private val web: WebView?) {
    companion object {
        private val termuxRequestId = java.util.concurrent.atomic.AtomicInteger(1000)
        const val REQ_VPN = 991
        const val REQ_WEB_FILE = 992
    }
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
    @Volatile private var pendingWebFileCallback: ValueCallback<Array<Uri>>? = null

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
            setPadding(14, 8, 14, 4)
        }
        val title = TextView(activity).apply {
            text = pageTitle
            setTextColor(Color.WHITE)
            textSize = 15f
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

        val addressRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 4, 10, 6)
        }
        val address = EditText(activity).apply {
            setSingleLine(true)
            setText(url)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            textSize = 12f
            hint = "https://alamat-situs"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.rgb(27, 31, 40))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val goButton = TextView(activity).apply {
            text = "BUKA"
            setTextColor(Color.rgb(110, 190, 255))
            textSize = 12f
            setPadding(12, 12, 8, 12)
            isClickable = true
        }
        addressRow.addView(address)
        addressRow.addView(goButton)

        val navRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 0, 10, 6)
        }
        fun navButton(label: String): TextView = TextView(activity).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(14, 8, 14, 8)
            isClickable = true
        }
        val back = navButton("‹ Kembali")
        val forward = navButton("Lanjut ›")
        val reload = navButton("↻ Muat ulang")
        navRow.addView(back)
        navRow.addView(forward)
        navRow.addView(reload)

        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.VISIBLE
        }
        val browser = WebView(activity).apply {
            setBackgroundColor(Color.rgb(7, 9, 13))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                // User-initiated target=_blank/window.open links stay in this WebView.
                setSupportMultipleWindows(false)
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
                override fun onReceivedTitle(view: WebView?, page: String?) {
                    if (!page.isNullOrBlank()) title.text = page.take(64)
                }
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: WebChromeClient.FileChooserParams
                ): Boolean {
                    pendingWebFileCallback?.onReceiveValue(null)
                    pendingWebFileCallback = filePathCallback
                    return try {
                        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/json", "application/pdf", "image/*", "application/zip", "application/octet-stream"))
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
                        }
                        activity.startActivityForResult(Intent.createChooser(picker, "Pilih file untuk situs"), REQ_WEB_FILE)
                        true
                    } catch (_: Exception) {
                        pendingWebFileCallback = null
                        filePathCallback.onReceiveValue(null)
                        false
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    if (scheme == "https") return false
                    if (scheme == "http") {
                        Toast.makeText(activity, "Navigasi HTTP tidak aman diblokir; gunakan HTTPS.", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    val scheme = Uri.parse(url).scheme?.lowercase()
                    return scheme != "https"
                }
                override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                    progress.visibility = View.VISIBLE
                    progress.progress = 0
                }
                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    if (!pageUrl.isNullOrBlank() && pageUrl.startsWith("https://")) {
                        address.setText(pageUrl)
                        address.setSelection(address.text.length)
                    }
                    back.isEnabled = view?.canGoBack() == true
                    forward.isEnabled = view?.canGoForward() == true
                    title.text = view?.title?.take(64)?.ifBlank { pageTitle } ?: pageTitle
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        title.text = "Gagal memuat halaman"
                        Toast.makeText(activity, "WebView gagal memuat situs. Periksa internet, DNS/VPN, atau pembatasan situs.", Toast.LENGTH_LONG).show()
                    }
                }
                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                    super.onReceivedHttpError(view, request, response)
                    if (request.isForMainFrame && response.statusCode >= 400) {
                        title.text = "HTTP ${response.statusCode}"
                        Toast.makeText(activity, "Situs membalas HTTP ${response.statusCode}", Toast.LENGTH_LONG).show()
                    }
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

        fun navigateAddress() {
            val raw = address.text.toString().trim()
            val target = try { Uri.parse(if (raw.startsWith("https://", true)) raw else "https://$raw") }
                catch (_: Exception) { null }
            if (target == null || target.scheme?.lowercase() != "https" || target.host.isNullOrBlank()) {
                Toast.makeText(activity, "Masukkan alamat HTTPS yang valid", Toast.LENGTH_SHORT).show()
                return
            }
            browser.loadUrl(target.toString())
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(address.windowToken, 0)
            address.clearFocus()
        }
        goButton.setOnClickListener { navigateAddress() }
        address.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                navigateAddress()
                true
            } else false
        }
        back.setOnClickListener { if (browser.canGoBack()) browser.goBack() }
        forward.setOnClickListener { if (browser.canGoForward()) browser.goForward() }
        reload.setOnClickListener { browser.reload() }

        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(addressRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(navRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        dialog.window?.apply {
            // Browser takes the entire app window; no inset card that hides page content.
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
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

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_WEB_FILE) {
            val callback = pendingWebFileCallback
            pendingWebFileCallback = null
            callback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            return
        }
        if (requestCode == REQ_VPN) {
            val host = pendingVpnHost
            val port = pendingVpnPort
            pendingVpnHost = null
            if (resultCode == Activity.RESULT_OK && host != null) launchVpn(host, port)
            else js("MK_onVPN", JSONObject().put("active", false)
                .put("error", "izin VPN ditolak").toString())
        }
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


    /** Native confirmation is mandatory; JavaScript prompts are not a security boundary. */
    private fun confirmNativeAction(title: String, command: String): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        val latch = CountDownLatch(1)
        val approved = AtomicReference(false)
        val dialogRef = AtomicReference<AlertDialog?>(null)
        activity.runOnUiThread {
            try {
                val dialog = AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage("Mas Kiplay meminta izin menjalankan perintah berikut. Periksa sebelum menyetujui.\\n\\n${command.take(4096)}")
                    .setPositiveButton("Setujui dan jalankan") { _, _ -> approved.set(true); latch.countDown() }
                    .setNegativeButton("Batal") { _, _ -> approved.set(false); latch.countDown() }
                    .setOnCancelListener { approved.set(false); latch.countDown() }
                    .create()
                dialogRef.set(dialog)
                dialog.show()
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        return try {
            val completed = latch.await(75, TimeUnit.SECONDS)
            if (!completed) activity.runOnUiThread { dialogRef.get()?.dismiss() }
            completed && approved.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            activity.runOnUiThread { dialogRef.get()?.dismiss() }
            false
        }
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

    // ---------- Termux RUN_COMMAND ----------

    @JavascriptInterface
    fun termuxRun(command: String, workdir: String): String {
        val cmd = command.trim()
        if (cmd.isEmpty()) return "err: perintah kosong"
        if (cmd.length > 4096) return "err: perintah melebihi batas 4.096 karakter"
        if (activity.checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED) {
            return "err: izin RUN_COMMAND belum diberikan pada Info Aplikasi Mas Kiplay"
        }
        if (!confirmNativeAction("Konfirmasi eksekusi Termux", cmd)) return "dibatalkan pengguna"
        return try {
            val id = termuxRequestId.incrementAndGet()
            val resultIntent = Intent(activity, MainActivity::class.java).apply {
                action = "ai.maskiplay.app.TERMUX_RESULT"
                putExtra("mk_termux_id", id)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val resultPending = PendingIntent.getActivity(activity, id, resultIntent, flags)
            val run = Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.execute.arguments", arrayOf("-lc", cmd))
                putExtra("com.termux.execute.cwd",
                    workdir.trim().takeIf { it.startsWith("/") || it.startsWith("~/") } ?: "~/")
                putExtra("com.termux.execute.background", true)
                putExtra("com.termux.execute.command_label", "Mas Kiplay Agent")
                putExtra("com.termux.execute.command_description", "Perintah diminta dari Chat/Jarvis Mas Kiplay")
                putExtra("pendingIntent", resultPending)
            }
            activity.startService(run)
            "queued:$id — perintah dikirim ke Termux. Hasil akan kembali ke Chat."
        } catch (e: Exception) {
            "err: Termux tidak siap: ${e.message ?: "gagal memulai layanan"}"
        }
    }

    fun onTermuxResult(intent: Intent?) {
        if (intent?.action != "ai.maskiplay.app.TERMUX_RESULT") return
        val bundle = intent.getBundleExtra("result")
        if (bundle == null) {
            js("MK_onTermuxResult", JSONObject().put("error", "Termux tidak mengembalikan result bundle").toString())
            return
        }
        val payload = JSONObject()
            .put("id", intent.getIntExtra("mk_termux_id", 0))
            .put("stdout", bundle.getString("stdout", "").take(12000))
            .put("stderr", bundle.getString("stderr", "").take(6000))
            .put("exitCode", bundle.getInt("exitCode", -1))
            .put("error", bundle.getString("errmsg", ""))
        js("MK_onTermuxResult", payload.toString())
    }

    // ---------- shell ----------

    @JavascriptInterface fun shell(cmd: String): String {
        if (cmd.isBlank()) return "err: perintah kosong"
        if (cmd.length > 4096) return "err: perintah melebihi batas 4.096 karakter"
        if (!confirmNativeAction("Konfirmasi perintah shell", cmd)) return "dibatalkan pengguna"
        return exec(cmd, false)
    }

    @JavascriptInterface fun shellRoot(cmd: String): String {
        if (cmd.isBlank()) return "err: perintah kosong"
        if (cmd.length > 4096) return "err: perintah melebihi batas 4.096 karakter"
        if (!confirmNativeAction("Konfirmasi perintah ROOT", cmd)) return "dibatalkan pengguna"
        return exec(cmd, true)
    }

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
    fun termux(cmd: String): String {
        val safeCommand = cmd.trim()
        if (safeCommand.isEmpty()) return "err: perintah kosong"
        if (safeCommand.length > 4096) return "err: perintah melebihi batas 4.096 karakter"
        if (!confirmNativeAction("Konfirmasi eksekusi Termux", safeCommand)) return "dibatalkan pengguna"
        return try {
            val i = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", safeCommand))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(i) else activity.startService(i)
            "-> Termux"
        } catch (e: Exception) { "err: ${e.message}" }
    }

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

            }
        )
        "alarm dibuka"
    } catch (e: Exception) {
        "err: ${e.message}"
    }
}

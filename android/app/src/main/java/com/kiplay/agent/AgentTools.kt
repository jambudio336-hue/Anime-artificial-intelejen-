package com.kiplay.agent

import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.LocationManager
import android.media.AudioManager
import android.media.ImageReader
import android.media.RingtoneManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AgentTools — eksekutor semua aksi perangkat.
 * Dipanggil dari JS: MK.tool("nama", "{\"arg\":...}")
 * Selalu mengembalikan String. Tidak pernah melempar exception ke bridge.
 */
class AgentTools(private val act: MainActivity) {

    // ======================= CAPTURE LAYAR =======================

    private var mp: MediaProjection? = null
    private var projMgr: MediaProjectionManager? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0
    private var capH = 0

    fun askScreen() {
        projMgr = act.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        act.startActivityForResult(projMgr!!.createScreenCaptureIntent(), MainActivity.REQ_SCREEN)
    }

    fun onScreenResult(code: Int, data: Intent?) {
        releaseCapture()
        if (code == Activity.RESULT_OK && data != null) {
            mp = projMgr?.getMediaProjection(code, data)
            capW = 0; capH = 0
        }
    }

    private fun releaseCapture() {
        try { vd?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { mp?.stop() } catch (_: Exception) {}
        vd = null; reader = null; mp = null
    }

    private fun ensureCapture(): Boolean {
        if (vd != null && reader != null) return true
        val p = mp ?: return false
        if (capW == 0) {
            val dm = act.resources.displayMetrics
            capW = dm.widthPixels
            capH = dm.heightPixels
        }
        val dm = act.resources.displayMetrics
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)
        vd = p.createVirtualDisplay(
            "kiplay-cap", capW, capH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null
        )
        return true
    }

    private fun screenshot(): String {
        if (mp == null) return "butuh izin rekam layar — panggil requestScreen() dulu"
        if (!ensureCapture()) return "gagal siapkan capture"

        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null

        reader!!.setOnImageAvailableListener({ r ->
            val ia = try { r.acquireLatestImage() } catch (e: Exception) { null }
            if (ia == null) return@setOnImageAvailableListener
            try {
                val pl = ia.planes[0]
                val b = Bitmap.createBitmap(pl.rowStride / pl.pixelStride, ia.height, Bitmap.Config.ARGB_8888)
                pl.buffer.rewind()
                b.copyPixelsFromBuffer(pl.buffer)
                bmp = b
            } catch (_: Exception) {
            } finally {
                ia.close()
                latch.countDown()
            }
        }, Handler(Looper.getMainLooper()))

        if (!latch.await(4, TimeUnit.SECONDS)) return "timeout ambil frame"
        reader!!.setOnImageAvailableListener(null, null)

        val b = bmp ?: return "frame kosong"
        val f = File(act.cacheDir, "shot_" + System.currentTimeMillis() + ".png")
        return try {
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 92, it) }
            f.absolutePath
        } catch (e: Exception) {
            "err simpan: " + e.message
        } finally {
            b.recycle()
        }
    }

    // ======================= UI THREAD HELPER =======================

    private fun ui(block: () -> Boolean): String {
        val latch = CountDownLatch(1)
        var ok = false
        Handler(Looper.getMainLooper()).post {
            ok = try { block() } catch (_: Exception) { false }
            latch.countDown()
        }
        latch.await(4, TimeUnit.SECONDS)
        return if (ok) "ok" else "gagal"
    }

    // ======================= SHELL =======================

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun sh(cmd: String, root: Boolean): String {
        return try {
            val arr = if (root) arrayOf("sh", "-c", "su -c " + q(cmd)) else arrayOf("sh", "-c", cmd)
            val p = Runtime.getRuntime().exec(arr)
            val out = StringBuilder()
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                r.lineSequence().take(400).forEach { out.appendLine(it) }
            }
            BufferedReader(InputStreamReader(p.errorStream)).use { r ->
                r.lineSequence().take(120).forEach { out.appendLine(it) }
            }
            p.waitFor()
            out.toString().ifBlank { "(tanpa output)" }.take(20000)
        } catch (e: Exception) {
            "err: " + e.message
        }
    }

    private fun hasRoot(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "su -c id"))
        val o = BufferedReader(InputStreamReader(p.inputStream)).readText()
        p.waitFor()
        o.contains("uid=0")
    } catch (e: Exception) {
        false
    }

    // ======================= FILE =======================

    private fun baseDir(): File = Environment.getExternalStorageDirectory()

    private fun resolve(path: String): File {
        val s = path.trim()
        return when {
            s.isEmpty() -> baseDir()
            s.startsWith("/") -> File(s)
            else -> File(baseDir(), s)
        }
    }

    private fun readFile(path: String, maxLines: Int): String {
        val f = resolve(path)
        if (!f.exists()) return "tidak ada: " + f.absolutePath
        if (f.isDirectory) return "itu folder — pakai ls"
        if (f.length() > 4_000_000) return "file kebesaran (" + f.length() + " byte)"
        return try {
            f.readLines().take(maxLines).joinToString("\n").ifBlank { "(kosong)" }
        } catch (e: Exception) {
            "err: " + e.message
        }
    }

    private fun writeFile(path: String, content: String, append: Boolean): String {
        return try {
            val f = resolve(path)
            f.parentFile?.mkdirs()
            if (append) f.appendText(content) else f.writeText(content)
            "tulis " + f.absolutePath + " (" + content.length + " byte)"
        } catch (e: Exception) {
            "err: " + e.message
        }
    }

    // ======================= PERANGKAT =======================

    private fun audio(): AudioManager = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun streamOf(name: String): Int = when (name.lowercase(Locale.ROOT)) {
        "ring" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "call" -> AudioManager.STREAM_VOICE_CALL
        "notif" -> AudioManager.STREAM_NOTIFICATION
        else -> AudioManager.STREAM_MUSIC
    }

    private fun torch(on: Boolean): String = try {
        val cm = act.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var id: String? = null
        for (c in cm.cameraIdList) {
            if (cm.getCameraCharacteristics(c).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                id = c; break
            }
        }
        if (id == null) return "tidak ada lampu kilat"
        cm.setTorchMode(id, on)
        if (on) "senter nyala" else "senter mati"
    } catch (e: Exception) {
        "err: " + e.message
    }

    private fun setBrightness(v: Int): String {
        if (!Settings.System.canWrite(act)) return "butuh izin tulis setelan — buka setelan WRITE_SETTINGS"
        return try {
            Settings.System.putInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS,
                v.coerceIn(0, 100) * 255 / 100)
            "kecerahan " + v + "%"
        } catch (e: Exception) {
            "err: " + e.message
        }
    }

    private fun setRotation(deg: Int): String {
        if (!Settings.System.canWrite(act)) return "butuh izin tulis setelan"
        return try {
            Settings.System.putInt(act.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
            Settings.System.putInt(act.contentResolver, Settings.System.USER_ROTATION, deg)
            "rotasi " + deg + "°"
        } catch (e: Exception) {
            "err: " + e.message
        }
    }

    private fun notify(title: String, body: String): String = try {
        val nm = act.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("mk", "Mas Kiplay", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val pi = PendingIntent.getActivity(act, 0, Intent(act, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(System.currentTimeMillis().toInt(),
            NotificationCompat.Builder(act, "mk")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(body)
                .setAutoCancel(true).setContentIntent(pi).build())
        "notifikasi terkirim"
    } catch (e: Exception) {
        "err: " + e.message
    }

    private fun vibrate(ms: Long): String = try {
        val v = act.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(ms)
        "getar " + ms + "ms"
    } catch (e: Exception) {
        "err: " + e.message
    }

    private fun clipboardGet(): String = try {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "(kosong)"
    } catch (e: Exception) {
        "err: " + e.message
    }

    private fun clipboardSet(t: String): String = try {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("kiplay", t))
        "clipboard: " + t.take(60)
    } catch (e: Exception) {
        "err: " + e.message
    }

    private fun battery(): String = try {
        val bm = act.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val st = act.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = st?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val temp = (st?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        "baterai " + lvl + "% | " + (if (charging) "mengisi" else "tidak mengisi") + " | " + temp + "°C"
    } catch (e: Exception) {
        "err: " + e.message
    }

    private fun memInfo(): String = try {
        val am = act.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        "RAM bebas " + (mi.availMem / 1048576) + " MB / total " + (mi.totalMem / 1048576) + " MB"
    } catch (e: Exception) {
        "err: " + e.message
    }

    private fun storageInfo(): String = try {
        val st = StatFs(Environment.getExternalStorageDirectory().path)
        val free = st.availableBlocksLong * st.blockSizeLong / 1048576
        val total = st.blockCountLong * st.blockSizeLong / 1048576
        "penyimpanan bebas " + free + " MB / total " + total + " MB"
    } catch (e: Exception) {
        "err: " + e.message
    }

    private fun deviceInfo(): String = buildString {
        appendLine("model: " + Build.MANUFACTURER + " " + Build.MODEL)
        appendLine("android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")")
        appendLine("cpu: " + Build.SUPPORTED_ABIS.joinToString())
        appendLine("kernel: " + System.getProperty("os.version"))
        appendLine("root: " + (if (hasRoot()) "ya" else "tidak"))
        appendLine("aksesibilitas: " + (if (KiplayAccessibilityService.inst != null) "aktif" else "mati"))
        append("layar: " + act.resources.displayMetrics.widthPixels + "x" + act.resources.displayMetrics.heightPixels)
    }

    private fun netInfo(): String = try {
        val cm = act.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val jenis = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "seluler"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "lain"
        }
        var ip: String? = null
        try {
            for (ni in java.net.NetworkInterface.getNetworkInterfaces()) {
                for (ia in ni.inetAddresses) {
                    if (!ia.isLoopbackAddress && ia is java.net.Inet4Address) { ip = ia.hostAddress; break }
                }
                if (ip != null) break
            }
        } catch (_: Exception) {}
        "jaringan: " + jenis + " | ip lokal: " + (ip ?: "-")
    } catch (e: Exception) {
        "err: " + e.message
    }

    private fun wifi(on: Boolean): String {
        val r = sh("svc wifi " + (if (on) "enable" else "disable"), hasRoot())
        if (!r.startsWith("err")) return "wifi " + (if (on) "nyala" else "mati") + " (svc)"
        return try {
            @Suppress("DEPRECATION")
            (act.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled = on
            "wifi " + (if (on) "nyala" else "mati")
        } catch (e: Exception) {
            "err: " + e.message
        }
    }

    private fun bluetooth(on: Boolean): String {
        val r = sh("svc bluetooth " + (if (on) "enable" else "disable"), hasRoot())
        if (!r.startsWith("err")) return "bluetooth " + (if (on) "nyala" else "mati") + " (svc)"
        return try {
            val bm = act.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val a: BluetoothAdapter? = bm.adapter
            @Suppress("DEPRECATION")
            if (on) a?.enable() else a?.disable()
            "bluetooth " + (if (on) "nyala" else "mati")
        } catch (e: Exception) {
            "err: " + e.message
        }
    }

    private fun airplane(on: Boolean): String {
        if (!hasRoot()) return "butuh root (atau pakai settings_panel airplane)"
        return sh("settings put global airplane_mode_on " + (if (on) 1 else 0) +
                " && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state " + on, true)
    }

    private fun location(): String = try {
        if (ContextCompat.checkSelfPermission(act, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return "izin lokasi belum ada"
        val lm = act.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val prov = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return "GPS mati"
        }
        val l = lm.getLastKnownLocation(prov) ?: return "belum ada fix lokasi (" + prov + ")"
        "lat " + l.latitude + ", lon " + l.longitude + " | akurasi " + l.accuracy + "m"
    } catch (e: Exception) {
        "err: " + e.message
    }

    private fun contacts(limit: Int): String = try {
        val out = StringBuilder()
        act.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n < limit) {
                out.append(c.getString(0)).append(" — ").append(c.getString(1)).appendLine()
                n++
            }
        }
        out.toString().ifBlank { "(kosong)" }
    } catch (e: Exception) {
        "err: " + e.message
    }

    private fun smsInbox(limit: Int): String = try {
        val out = StringBuilder()
        act.contentResolver.query(Uri.parse("content://sms/inbox"),
            arrayOf("address", "body", "date"), null, null, "date DESC")?.use { c ->
            var n = 0
            while (c.moveToNext() && n < limit) {
                val tgl = SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(Date(c.getLong(2)))
                out.append("[").append(tgl).append("] ").append(c.getString(0))
                    .append(": ").append(c.getString(1)?.take(120)).appendLine()
                n++
            }
        }
        out.toString().ifBlank { "(kosong)" }
    } catch (e: Exception) {
        "err: " + e.message
    }

    private fun sendSms(to: String, body: String): String = try {
        val sm = if (Build.VERSION.SDK_INT >= 31)
            act.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sm.divideMessage(body)
        sm.sendMultipartTextMessage(to, null, parts, null, null)
        "sms ke " + to + " (" + body.length + " char)"
    } catch (e: Exception) {
        "err: " + e.message
    }

    private fun usageStats(): String = try {
        val usm = act.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val now = System.currentTimeMillis()
        val list = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 86_400_000, now)
        if (list.isNullOrEmpty()) return "belum ada izin PACKAGE_USAGE_STATS"
        list.sortedByDescending { it.totalTimeInForeground }.take(12).joinToString("\n") {
            it.packageName + " — " + (it.totalTimeInForeground / 60000) + " menit"
        }
    } catch (e: Exception) {
        "err: " + e.message
    }

    private fun appList(): String = try {
        val pm = act.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName + " | " + it.loadLabel(pm) }
            .distinct().sorted().joinToString("\n").take(20000)
    } catch (e: Exception) {
        "err: " + e.message
    }

    private fun openApp(pkg: String): String = try {
        val i = act.packageManager.getLaunchIntentForPackage(pkg) ?: return "tidak terpasang: " + pkg
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        act.startActivity(i)
        "luncur " + pkg
    } catch (e: Exception) {
        "err: " + e.message
    }

    private fun openUrl(u: String): String {
        var x = u.trim()
        if (x.isEmpty()) return "url kosong"
        if (!x.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://.*"))) x = "https://" + x
        return try {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(x)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "buka " + x
        } catch (e: Exception) {
            "err: " + e.message
        }
    }

    private fun installApk(path: String): String {
        val f = resolve(path)
        if (!f.exists()) return "apk tidak ada: " + f.absolutePath
        val viaShell = sh("pm install -r " + q(f.absolutePath), hasRoot())
        if (viaShell.contains("Success")) return "terpasang: " + f.name
        return try {
            act.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            "buka installer: " + f.name
        } catch (e: Exception) {
            "err: " + e.message + " | " + viaShell.take(200)
        }
    }

    private fun killApp(pkg: String): String =
        sh("am force-stop " + pkg, hasRoot())

    private fun settingsPanel(which: String): String {
        val a = when (which.lowercase(Locale.ROOT)) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bt", "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "app", "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "dev", "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return try {
            act.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "buka setelan: " + which
        } catch (e: Exception) {
            "err: " + e.message
        }
    }

    private fun mediaKey(k: String): String {
        val code = when (k.lowercase(Locale.ROOT)) {
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        return try {
            val am = audio()
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            "media " + k
        } catch (e: Exception) {
            "err: " + e.message
        }
    }

    private fun wakeScreen(): String = try {
        val pm = act.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "kiplay:wake")
        wl.acquire(5000)
        "layar dibangunkan"
    } catch (e: Exception) {
        "err: " + e.message
    }

    // ======================= DISPATCH =======================

    fun dispatch(name: String, argsJson: String): String {
        val a = try { JSONObject(argsJson.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
        fun s(k: String, def: String = "") = a.optString(k, def)
        fun i(k: String, def: Int = 0) = a.optInt(k, def)
        fun d(k: String) = a.optDouble(k, 0.0).toFloat()
        val acc = KiplayAccessibilityService.inst

        return try {
            when (name) {

                // ---- baca & sentuh layar ----
                "ui_dump" -> acc?.dump() ?: "aksesibilitas mati"
                "tap" -> {
                    val x = d("x"); val y = d("y")
                    if (acc != null) ui { acc.tap(x, y) }
                    else sh("input tap " + x.toInt() + " " + y.toInt(), false)
                }
                "long_press" -> {
                    val x = d("x"); val y = d("y")
                    val ms = i("ms", 800).toLong().coerceAtLeast(500)
                    if (acc != null) ui { acc.tap(x, y, ms) }
                    else sh("input swipe " + x.toInt() + " " + y.toInt() + " " +
                            x.toInt() + " " + y.toInt() + " " + ms, false)
                }
                "swipe" -> {
                    if (acc != null) ui { acc.swipe(d("x1"), d("y1"), d("x2"), d("y2")) }
                    else sh("input swipe " + d("x1").toInt() + " " + d("y1").toInt() + " " +
                            d("x2").toInt() + " " + d("y2").toInt() + " " + i("ms", 300), false)
                }
                "tap_text" -> if (acc == null) "aksesibilitas mati" else ui { acc.clickText(s("text")) }
                "set_text" -> if (acc == null) "aksesibilitas mati" else ui { acc.setText(s("text")) }
                "type" -> if (acc != null && acc.setText(s("text"))) "ok"
                         else sh("input text " + q(s("text").replace(" ", "%s")), false)
                "keyevent" -> sh("input keyevent " + i("code", 4), false)
                "back" -> if (acc != null) ui { acc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) }
                          else sh("input keyevent 4", false)
                "home" -> if (acc != null) ui { acc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) }
                          else sh("input keyevent 3", false)
                "recents" -> if (acc != null) ui { acc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS) }
                             else sh("input keyevent 187", false)
                "notif" -> if (acc != null) ui { acc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) }
                           else sh("cmd statusbar expand-notifications", false)
                "quick" -> if (acc != null) ui { acc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) }
                           else sh("cmd statusbar expand-settings", false)
                "lock" -> if (acc != null && Build.VERSION.SDK_INT >= 28)
                             ui { acc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }
                          else sh("input keyevent 26", false)
                "screen_size" -> act.resources.displayMetrics.let { it.widthPixels.toString() + "x" + it.heightPixels }
                "screenshot" -> screenshot()

                // ---- app & url ----
                "open_app" -> openApp(s("pkg"))
                "app_list" -> appList()
                "kill_app" -> killApp(s("pkg"))
                "open_url" -> openUrl(s("url"))
                "install" -> installApk(s("path"))
                "settings_panel" -> settingsPanel(s("which"))
                "request_screen" -> { askScreen(); "dialog izin dibuka" }

                // ---- shell ----
                "shell" -> sh(s("cmd"), false)
                "root" -> sh(s("cmd"), true)
                "root_check" -> if (hasRoot()) "root tersedia" else "tanpa root"

                // ---- file ----
                "ls" -> {
                    val f = resolve(s("path"))
                    if (!f.isDirectory) "bukan folder: " + f.absolutePath
                    else (f.listFiles() ?: emptyArray()).sortedBy { it.name }
                        .joinToString("\n") { (if (it.isDirectory) "[D] " else "[F] ") + it.name + "  " + it.length() }
                        .ifBlank { "(kosong)" }
                }
                "cat" -> readFile(s("path"), i("lines", 400))
                "write" -> writeFile(s("path"), s("content"), false)
                "append" -> writeFile(s("path"), s("content"), true)
                "mkdir" -> resolve(s("path")).let { if (it.mkdirs()) "dibuat: " + it.absolutePath else "gagal/dah ada" }
                "rm" -> {
                    val f = resolve(s("path"))
                    if (f.delete()) "hapus " + f.absolutePath else "gagal hapus"
                }
                "mv" -> {
                    val from = resolve(s("from")); val to = resolve(s("to"))
                    to.parentFile?.mkdirs()
                    if (from.renameTo(to)) "pindah ke " + to.absolutePath else "gagal pindah"
                }
                "cp" -> {
                    val from = resolve(s("from")); val to = resolve(s("to"))
                    to.parentFile?.mkdirs()
                    from.copyTo(to, true)
                    "salin ke " + to.absolutePath
                }
                "find" -> {
                    val root = resolve(s("path"))
                    val pat = s("pattern", "*")
                    (root.walkTopDown().maxDepth(i("depth", 4))
                        .filter { it.name.matches(Regex(pat.replace(".", "\\.").replace("*", ".*"))) }
                        .take(200).joinToString("\n") { it.absolutePath }).ifBlank { "(tidak ada)" }
                }

                // ---- info ----
                "battery" -> battery()
                "mem" -> memInfo()
                "storage" -> storageInfo()
                "device" -> deviceInfo()
                "net" -> netInfo()
                "usage" -> usageStats()
                "location" -> location()
                "contacts" -> contacts(i("limit", 30))
                "sms_read" -> smsInbox(i("limit", 15))

                // ---- radio ----
                "wifi" -> wifi(a.optBoolean("on", true))
                "bt" -> bluetooth(a.optBoolean("on", true))
                "airplane" -> airplane(a.optBoolean("on", true))

                // ---- audio & tampilan ----
                "torch" -> torch(a.optBoolean("on", true))
                "volume_set" -> {
                    val am = audio()
                    val st = streamOf(s("stream", "media"))
                    am.setStreamVolume(st, i("level", 8), AudioManager.FLAG_SHOW_UI)
                    "volume " + s("stream", "media") + " = " + i("level", 8)
                }
                "volume_up" -> {
                    audio().adjustStreamVolume(streamOf(s("stream", "media")),
                        AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); "volume naik"
                }
                "volume_down" -> {
                    audio().adjustStreamVolume(streamOf(s("stream", "media")),
                        AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); "volume turun"
                }
                "mute" -> {
                    audio().adjustStreamVolume(streamOf(s("stream", "media")),
                        AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI); "senyap"
                }
                "brightness" -> setBrightness(i("percent", 60))
                "rotate" -> setRotation(i("deg", 0))
                "media" -> mediaKey(s("action", "toggle"))
                "wake" -> wakeScreen()

                // ---- komunikasi & lain ----
                "clipboard_get" -> clipboardGet()
                "clipboard_set" -> clipboardSet(s("text"))
                "notify" -> notify(s("title", "Mas Kiplay"), s("body", ""))
                "vibrate" -> vibrate(i("ms", 200).toLong())
                "toast" -> { android.widget.Toast.makeText(act, s("text"), android.widget.Toast.LENGTH_SHORT).show(); "toast" }
                "wait" -> { Thread.sleep(i("ms", 500).toLong().coerceAtMost(15000)); "tunggu " + i("ms", 500) + "ms" }
                "alarm" -> {
                    act.startActivity(Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, i("hour", 7))
                        .putExtra(AlarmClock.EXTRA_MINUTES, i("minute", 0))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "alarm " + i("hour", 7) + ":" + i("minute", 0)
                }
                "timer" -> {
                    act.startActivity(Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, i("sec", 60))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "timer " + i("sec", 60) + " detik"
                }
                "call" -> {
                    act.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + s("number")))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "telepon " + s("number")
                }
                "sms_send" -> sendSms(s("to"), s("body"))
                "ring" -> {
                    RingtoneManager.getRingtone(act, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
                    "bunyi notifikasi"
                }

                else -> "tool tak dikenal: " + name
            }
        } catch (e: Exception) {
            "err " + name + ": " + e.message
        }
    }
}

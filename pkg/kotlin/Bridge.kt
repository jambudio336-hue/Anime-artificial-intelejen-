package com.kiplay.agent

import android.app.Activity
import android.app.ActivityManager
import android.app.WallpaperManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL

/**
 * Bridge WebView -> native. Didaftarkan oleh MainActivity sebagai window.MK.
 */
class Bridge(private val activity: Activity) {

    private fun exec(cmd: String, root: Boolean): String = try {
        val argv = if (root) arrayOf("su", "-c", cmd) else arrayOf("sh", "-c", cmd)
        val p = Runtime.getRuntime().exec(argv)
        val out = StringBuilder()
        BufferedReader(InputStreamReader(p.inputStream)).use { r ->
            r.lineSequence().take(400).forEach { out.appendLine(it) }
        }
        BufferedReader(InputStreamReader(p.errorStream)).use { r ->
            r.lineSequence().take(80).forEach { out.appendLine("! $it") }
        }
        p.waitFor()
        out.toString().ifBlank { "(tanpa output)" }
    } catch (e: Exception) { "err: ${e.message}" }

    @JavascriptInterface fun shell(cmd: String): String = exec(cmd, false)
    @JavascriptInterface fun shellRoot(cmd: String): String = exec(cmd, true)

    @JavascriptInterface
    fun openApp(pkg: String): String = try {
        val i = activity.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw IllegalStateException("tidak terpasang")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(i)
        "luncur $pkg"
    } catch (e: Exception) { "err: ${e.message}" }

    @JavascriptInterface
    fun openUrl(u: String): String = try {
        val x = if (u.startsWith("http")) u else "https://$u"
        activity.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(x)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        "buka $x"
    } catch (e: Exception) { "err: ${e.message}" }

    @JavascriptInterface
    fun wall(u: String): String = try {
        val bmp = if (u.startsWith("http")) {
            URL(u).openStream().use { BitmapFactory.decodeStream(it) }
        } else {
            BitmapFactory.decodeFile(u)
        }
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
        fun g(b: Long) = String.format("%.2f GB", b / 1073741824.0)
        buildString {
            appendLine("model: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("brand: ${Build.BRAND}")
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
        try {
            if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(i)
            else activity.startService(i)
        } catch (e: Exception) {
            activity.startService(i)
        }
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
            }
        )
        "alarm $jam:$menit"
    } catch (e: Exception) { "err: ${e.message}" }
}

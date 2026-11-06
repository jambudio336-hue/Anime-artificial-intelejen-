package com.kiplay.agent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Bridge.kt - jembatan WebView ke native (standalone).
 * Daftarkan di MainActivity.onCreate:
 *   bridge.webView.addJavascriptInterface(Bridge(this), "MK")
 */
class Bridge(private val activity: Activity) {

    @JavascriptInterface
    fun openApp(paket: String): String = try {
        val intent = activity.packageManager.getLaunchIntentForPackage(paket)
            ?: throw IllegalStateException("tidak terpasang")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
        "luncur $paket"
    } catch (e: Exception) {
        "err: ${e.message}"
    }

    @JavascriptInterface
    fun openUrl(url: String): String = try {
        val bersih = if (url.startsWith("http")) url else "https://$url"
        activity.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(bersih))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        "buka $bersih"
    } catch (e: Exception) {
        "err: ${e.message}"
    }

    @JavascriptInterface
    fun shell(perintah: String): String = try {
        val proses = Runtime.getRuntime().exec(arrayOf("sh", "-c", perintah))
        val keluaran = StringBuilder()

        BufferedReader(InputStreamReader(proses.inputStream)).use { r ->
            r.lineSequence().take(200).forEach { keluaran.appendLine(it) }
        }
        BufferedReader(InputStreamReader(proses.errorStream)).use { r ->
            r.lineSequence().take(50).forEach { keluaran.appendLine("! $it") }
        }

        proses.waitFor()
        keluaran.toString().ifBlank { "(tanpa output)" }
    } catch (e: Exception) {
        "err: ${e.message}"
    }

    @JavascriptInterface
    fun termux(perintah: String): String = try {
        val intent = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", perintah))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        activity.startService(intent)
        "-> Termux: $perintah"
    } catch (e: Exception) {
        "err: ${e.message}"
    }

    @JavascriptInterface
    fun galeri(): String {
        activity.runOnUiThread {
            activity.startActivityForResult(
                Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
                KODE_GALERI
            )
        }
        return "galeri dibuka"
    }

    @JavascriptInterface
    fun kamera(): String {
        activity.runOnUiThread {
            activity.startActivityForResult(
                Intent(MediaStore.ACTION_IMAGE_CAPTURE),
                KODE_KAMERA
            )
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
    } catch (e: Exception) {
        "err: ${e.message}"
    }

    @JavascriptInterface
    fun info(): String = buildString {
        appendLine("sdk: ${android.os.Build.VERSION.SDK_INT}")
        appendLine("perangkat: ${android.os.Build.MODEL}")
        appendLine("merek: ${android.os.Build.MANUFACTURER}")
        append("cpu: ${Runtime.getRuntime().availableProcessors()} core")
    }

    companion object {
        const val KODE_GALERI = 101
        const val KODE_KAMERA = 102
    }
}

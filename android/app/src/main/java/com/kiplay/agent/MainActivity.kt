package com.kiplay.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import com.getcapacitor.BridgeActivity
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : BridgeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bridge.webView.addJavascriptInterface(Bridge(), "MK")
    }

    inner class Bridge {

        @JavascriptInterface
        fun openApp(pkg: String): String = try {
            val i = packageManager.getLaunchIntentForPackage(pkg)
                ?: throw Exception("tidak terpasang")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            "luncur $pkg"
        } catch (e: Exception) { "err: ${e.message}" }

        @JavascriptInterface
        fun openUrl(url: String): String = try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "buka $url"
        } catch (e: Exception) { "err: ${e.message}" }

        @JavascriptInterface
        fun shell(cmd: String): String = try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = StringBuilder()
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                r.lineSequence().take(200).forEach { out.appendLine(it) }
            }
            p.waitFor()
            out.toString().ifBlank { "(tanpa output)" }
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
            startService(i)
            "→ Termux"
        } catch (e: Exception) { "err: ${e.message}" }

        @JavascriptInterface
        fun pickImage(): String {
            runOnUiThread {
                startActivityForResult(
                    Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI), 101
                )
            }
            return "galeri dibuka"
        }
    }
}

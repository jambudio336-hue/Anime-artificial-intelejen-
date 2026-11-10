package ai.maskiplay.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Layanan otomasi. Dipakai Bridge untuk tap, geser, ngetik, baca pohon UI,
 * dan ambil tangkapan layar. Harus diaktifkan manual sekali oleh pengguna
 * di Setelan > Aksesibilitas.
 */
class KiplayAccessibility : AccessibilityService() {

    companion object {
        @Volatile
        var inst: KiplayAccessibility? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
    }

    override fun onDestroy() {
        if (inst === this) inst = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun tap(x: Float, y: Float): Boolean = try {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 55L))
            .build()
        dispatchGesture(g, null, null)
    } catch (e: Exception) { false }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean = try {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, ms.coerceIn(80L, 2000L)))
            .build()
        dispatchGesture(g, null, null)
    } catch (e: Exception) { false }

    fun global(action: Int): Boolean = try { performGlobalAction(action) } catch (e: Exception) { false }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int, out: MutableList<String>, counter: IntArray) {
        if (node == null || counter[0] > 240) return
        val visible = try { node.isVisibleToUser } catch (e: Exception) { true }
        if (!visible) return
        counter[0]++
        val r = Rect()
        node.getBoundsInScreen(r)
        val label = listOfNotNull(
            node.text?.toString()?.takeIf { it.isNotBlank() },
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        ).joinToString(" | ")
        val flags = buildString {
            if (node.isClickable) append("klik ")
            if (node.isEditable) append("edit ")
            if (node.isScrollable) append("geser ")
        }.trim()
        val id = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        if (label.isNotBlank() || flags.isNotBlank() || id.isNotBlank()) {
            out.add(
                "  ".repeat(depth.coerceAtMost(6)) +
                    "[" + (node.className?.toString()?.substringAfterLast('.') ?: "?") + "]" +
                    " @" + r.left + "," + r.top + "," + r.right + "," + r.bottom +
                    (if (flags.isNotBlank()) " {$flags}" else "") +
                    (if (id.isNotBlank()) " #$id" else "") +
                    (if (label.isNotBlank()) " \"$label\"" else "")
            )
        }
        for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1, out, counter)
    }

    fun dump(): String { return try {
        val root = rootInActiveWindow ?: return "err: pohon UI kosong"
        val out = mutableListOf<String>()
        walk(root, 0, out, intArrayOf(0))
        if (out.isEmpty()) "err: tidak ada elemen terbaca" else out.joinToString("\n").take(20000)
    } catch (e: Exception) { "err: ${e.message}" } }

    fun clickText(needle: String): Boolean { return try {
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val target = needle.lowercase()
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val label = ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).lowercase()
            if (label.contains(target) && n.isVisibleToUser) {
                var c: AccessibilityNodeInfo? = n
                var guard = 0
                while (c != null && guard < 8) {
                    if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    c = c.parent
                    guard++
                }
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        false
    } catch (e: Exception) { false } }

    fun setText(text: String): String { return try {
        val root = rootInActiveWindow ?: return "err: pohon UI kosong"
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val editable = focus?.takeIf { it.isEditable } ?: run {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var found: AccessibilityNodeInfo? = null
            while (queue.isNotEmpty() && found == null) {
                val n = queue.removeFirst()
                if (n.isEditable && n.isVisibleToUser) found = n
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
            found
        }
        if (editable == null) return "err: tidak ada kolom teks aktif"
        editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "ok"
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("mk", text))
        editable.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        "ok (tempel)"
    } catch (e: Exception) { "err: ${e.message}" } }

    fun scroll(dir: String): Boolean { return try {
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isScrollable) {
                val action = if (dir == "up") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                if (n.performAction(action)) return true
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        false
    } catch (e: Exception) { false } }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun shot30(): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(shot: ScreenshotResult) {
                    try {
                        val buffer = shot.hardwareBuffer
                        val raw = Bitmap.wrapHardwareBuffer(buffer, shot.colorSpace)
                        buffer.close()
                        if (raw != null) {
                            val scale = 900f / raw.width.coerceAtLeast(1)
                            val w = if (scale < 1f) (raw.width * scale).toInt() else raw.width
                            val h = if (scale < 1f) (raw.height * scale).toInt() else raw.height
                            val soft = Bitmap.createScaledBitmap(raw, w, h, true)
                            val bos = ByteArrayOutputStream()
                            soft.compress(Bitmap.CompressFormat.JPEG, 62, bos)
                            result = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                        }
                    } catch (_: Exception) {
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) { latch.countDown() }
            })
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: Exception) { return null }
        return result
    }

    fun shot(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) shot30() ?: "err: tangkapan layar gagal"
        else "err: butuh Android 11+"
    } catch (e: Exception) { "err: ${e.message}" }
}

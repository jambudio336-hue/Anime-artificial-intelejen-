package com.kiplay.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class KiplayAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() { inst = this }
    override fun onDestroy() { inst = null; super.onDestroy() }
    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    fun root(): AccessibilityNodeInfo? = rootInActiveWindow

    fun findByText(text: String, exact: Boolean = false): AccessibilityNodeInfo? {
        val r = root() ?: return null
        val needle = text.lowercase()
        fun walk(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            n ?: return null
            val t = n.text?.toString()?.lowercase()
            val d = n.contentDescription?.toString()?.lowercase()
            val hit = if (exact) (t == needle || d == needle)
                      else ((t?.contains(needle) == true) || (d?.contains(needle) == true))
            if (hit && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) walk(n.getChild(i))?.let { return it }
            return null
        }
        return walk(r)
    }

    fun dump(): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            n ?: return
            if (depth > 30) return
            val cls = n.className?.toString()?.substringAfterLast('.') ?: "?"
            val txt = n.text?.toString()?.take(120)
            val desc = n.contentDescription?.toString()?.take(60)
            if (txt != null || desc != null || n.isClickable || n.isEditable) {
                val r = android.graphics.Rect(); n.getBoundsInScreen(r)
                sb.append(" ".repeat(depth)).append("[").append(cls).append("]")
                if (txt != null) sb.append(" text=\"").append(txt).append("\"")
                if (desc != null) sb.append(" desc=\"").append(desc).append("\"")
                if (n.isClickable) sb.append(" clickable")
                if (n.isEditable) sb.append(" editable")
                sb.append(" @").append(r.centerX()).append(",").append(r.centerY()).append("\n")
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root(), 0)
        return sb.toString().ifBlank { "(layar kosong)" }
    }

    fun tap(x: Float, y: Float, ms: Long = 60): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        return dispatchGesture(g, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 300): Boolean {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        return dispatchGesture(g, null, null)
    }

    fun setText(text: String): Boolean {
        val n = findEditable() ?: return false
        val b = android.os.Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun clickText(text: String): Boolean {
        val n = findByText(text) ?: return false
        var cur: AccessibilityNodeInfo? = n
        var guard = 0
        while (cur != null && guard++ < 8) {
            if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent
        }
        val r = android.graphics.Rect(); n.getBoundsInScreen(r)
        return tap(r.centerX().toFloat(), r.centerY().toFloat())
    }

    private fun findEditable(): AccessibilityNodeInfo? {
        val r = root() ?: return null
        fun walk(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            n ?: return null
            if (n.isEditable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) walk(n.getChild(i))?.let { return it }
            return null
        }
        return walk(r)
    }

    companion object { @Volatile var inst: KiplayAccessibilityService? = null }
}

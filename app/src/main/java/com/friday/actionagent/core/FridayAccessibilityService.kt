package com.friday.actionagent.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.friday.actionagent.engines.hub.AutoBrainRouter
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class ScreenElement(
    val label: String,
    val cx: Int,
    val cy: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val viewId: String
)

class FridayAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: FridayAccessibilityService? = null
    }

    var overlay: FloatingAgentOverlay? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        BackgroundVoiceController.init(this)
        overlay = FloatingAgentOverlay(
            context = this,
            onMicClick = { BackgroundVoiceController.startListeningNow() },
            onStopClick = {
                BackgroundVoiceController.setLiveMode(this, false)
                AutoBrainRouter.stopAll()
            }
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        AutoBrainRouter.stopAll()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AutoBrainRouter.stopAll()
        overlay?.hide()
        instance = null
        return super.onUnbind(intent)
    }

    fun currentPackageName(): String = rootInActiveWindow?.packageName?.toString() ?: ""

    fun getScreenElements(): List<ScreenElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val dm = resources.displayMetrics
        val result = mutableListOf<ScreenElement>()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || result.size >= 85 || depth > 24) return
            if (!node.isVisibleToUser) return

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val valid = rect.width() > 6 && rect.height() > 6 &&
                rect.right > 0 && rect.bottom > 0 &&
                rect.left < dm.widthPixels && rect.top < dm.heightPixels

            val txt = node.text?.toString()?.trim()?.replace("\n", " ")?.take(80) ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.replace("\n", " ")?.take(80) ?: ""
            val vid = node.viewIdResourceName?.substringAfterLast("/") ?: ""

            val interesting = txt.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable || node.isScrollable
            if (valid && interesting) {
                val label = when {
                    txt.isNotEmpty() && desc.isNotEmpty() && txt != desc -> "$txt ($desc)"
                    txt.isNotEmpty() -> txt
                    desc.isNotEmpty() -> desc
                    vid.isNotEmpty() -> "#$vid"
                    else -> node.className?.toString()?.substringAfterLast(".") ?: "View"
                }
                result.add(
                    ScreenElement(label, rect.centerX(), rect.centerY(), node.isClickable, node.isEditable, node.isScrollable, vid)
                )
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }

        walk(root, 0)
        return result
    }

    fun dumpScreenUi(): String {
        val dm = resources.displayMetrics
        val pkg = currentPackageName().ifEmpty { "unknown" }
        val elements = getScreenElements()
        val sb = StringBuilder("Screen: ${dm.widthPixels}x${dm.heightPixels} | App: $pkg\n")
        if (elements.isEmpty()) {
            sb.append("No readable UI elements on screen.")
        } else {
            elements.forEachIndexed { idx, el ->
                val flags = buildString {
                    if (el.clickable) append("C")
                    if (el.editable) append("E")
                    if (el.scrollable) append("S")
                }
                sb.append("[${idx + 1}] \"${el.label}\" (${el.cx},${el.cy}) [$flags]\n")
            }
        }
        return sb.toString()
    }

    private suspend fun dispatchGestureAndWait(gesture: GestureDescription): Boolean {
        return withTimeoutOrNull(2500L) {
            suspendCancellableCoroutine { cont ->
                val started = dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null
                )
                if (!started && cont.isActive) cont.resume(false)
            }
        } ?: false
    }

    suspend fun performTap(x: Float, y: Float): Boolean {
        val dm = resources.displayMetrics
        val safeX = x.coerceIn(10f, (dm.widthPixels - 10).toFloat())
        val safeY = y.coerceIn(10f, (dm.heightPixels - 10).toFloat())
        overlay?.moveCursorTo(safeX, safeY, true)
        delay(340)
        val path = Path().apply { moveTo(safeX, safeY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 90L))
            .build()
        val ok = dispatchGestureAndWait(gesture)
        delay(260)
        return ok
    }

    suspend fun performSwipe(direction: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val (sx, sy, ex, ey) = when (direction.lowercase()) {
            "up" -> listOf(w * 0.5f, h * 0.28f, w * 0.5f, h * 0.78f)
            "left" -> listOf(w * 0.82f, h * 0.5f, w * 0.18f, h * 0.5f)
            "right" -> listOf(w * 0.18f, h * 0.5f, w * 0.82f, h * 0.5f)
            else -> listOf(w * 0.5f, h * 0.78f, w * 0.5f, h * 0.26f)
        }
        overlay?.moveCursorTo(sx, sy, false)
        delay(200)
        overlay?.moveCursorTo(ex, ey, false)
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 400L))
            .build()
        val ok = dispatchGestureAndWait(gesture)
        delay(350)
        return ok
    }

    suspend fun clickVisibleText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = targetText.trim()
        if (target.isEmpty()) return false

        val matched = findNodeByText(root, target, exact = true)
            ?: findNodeByText(root, target, exact = false)
            ?: return false

        val rect = Rect()
        matched.getBoundsInScreen(rect)
        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()

        if (cx > 0f && cy > 0f) {
            overlay?.moveCursorTo(cx, cy, true)
            delay(340)
        }

        var clickable: AccessibilityNodeInfo? = matched
        while (clickable != null && !clickable.isClickable) {
            clickable = clickable.parent
        }
        if (clickable != null && clickable.isEnabled && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            delay(300)
            return true
        }
        if (cx > 0f && cy > 0f) return performTap(cx, cy)
        return false
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, target: String, exact: Boolean): AccessibilityNodeInfo? {
        if (node.isVisibleToUser) {
            val txt = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val vid = node.viewIdResourceName?.substringAfterLast("/") ?: ""
            val hit = if (exact) {
                txt.equals(target, ignoreCase = true) || desc.equals(target, ignoreCase = true)
            } else {
                txt.contains(target, ignoreCase = true) || desc.contains(target, ignoreCase = true) || vid.contains(target, ignoreCase = true)
            }
            if (hit) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByText(child, target, exact)
            if (found != null) return found
        }
        return null
    }

    suspend fun performTypeText(x: Float, y: Float, text: String, submit: Boolean): Boolean {
        if (x > 0f && y > 0f) {
            performTap(x, y)
            delay(420)
        }
        val root = rootInActiveWindow ?: return false
        var target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null || !target.isEditable) {
            target = findEditableNode(root, x.toInt(), y.toInt())
        }
        if (target == null) return false

        val rect = Rect()
        target.getBoundsInScreen(rect)
        if (rect.centerX() > 0 && rect.centerY() > 0) {
            overlay?.moveCursorTo(rect.centerX().toFloat(), rect.centerY().toFloat(), true)
            delay(200)
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        var ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("friday", text))
            ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        if (submit) {
            delay(300)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }
        return ok
    }

    private fun findEditableNode(root: AccessibilityNodeInfo, tx: Int, ty: Int): AccessibilityNodeInfo? {
        var fallback: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser && node.isEditable) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (tx > 0 && ty > 0 && r.contains(tx, ty)) {
                    fallback = node
                    return
                }
                if (fallback == null) fallback = node
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return fallback
    }

    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
}

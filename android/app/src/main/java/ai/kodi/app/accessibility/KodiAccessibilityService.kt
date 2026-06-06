package ai.kodi.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class KodiAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.attach(this)
    }

    override fun onDestroy() {
        AccessibilityBridge.detach(this)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    suspend fun openAppPackage(packageName: String): Boolean = withContext(Dispatchers.Main) {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            delay(1200)
            true
        } else {
            false
        }
    }

    suspend fun tapNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return withContext(Dispatchers.Main) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.isEmpty) return@withContext false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val p = Path()
                p.moveTo(r.centerX().toFloat(), r.centerY().toFloat())
                val stroke = GestureDescription.StrokeDescription(p, 0, 80)
                val g = GestureDescription.Builder().addStroke(stroke).build()
                suspendCancellableCoroutine { cont ->
                    dispatchGesture(
                        g,
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (cont.isActive) cont.resume(false)
                            }
                        },
                        null,
                    )
                }
            } else {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
    }

    fun rootOrNull(): AccessibilityNodeInfo? = rootInActiveWindow

    suspend fun waitForRoot(timeoutMs: Long = 3000): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            rootInActiveWindow?.let { return it }
            delay(80)
        }
        return null
    }

    fun findByText(root: AccessibilityNodeInfo?, text: String, partial: Boolean = true): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val needle = text.lowercase()
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val textLower = n.text?.toString()?.lowercase().orEmpty()
            val descLower = n.contentDescription?.toString()?.lowercase().orEmpty()
            val match = if (partial) {
                (textLower.isNotEmpty() && textLower.contains(needle)) ||
                    (descLower.isNotEmpty() && descLower.contains(needle))
            } else {
                textLower == needle || descLower == needle
            }
            if (match) {
                if (n.isClickable) return n
                val p = n.parent
                if (p != null && p.isClickable) return p
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    fun findEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isEditable) return n
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** Best-effort visible text from the active window UI tree (browser page, etc.). */
    fun collectVisibleText(root: AccessibilityNodeInfo?, maxChars: Int = 8000): String {
        if (root == null) return ""
        val sb = StringBuilder()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty() && sb.length < maxChars) {
            val n = q.removeFirst()
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                sb.append(it).append('\n')
            }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                sb.append(it).append('\n')
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { q.add(it) }
            }
        }
        return sb.toString().take(maxChars).trim()
    }

    suspend fun scrollNode(direction: String, node: AccessibilityNodeInfo? = null): Boolean =
        withContext(Dispatchers.Main) {
            val action = if (direction.lowercase() == "down" || direction.lowercase() == "forward") {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            val target = node ?: findScrollable(rootInActiveWindow) ?: return@withContext false
            target.performAction(action)
        }

    fun findScrollable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    suspend fun typeIntoFocusedField(text: String): Boolean = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow ?: return@withContext false
        val field = findEditable(root) ?: return@withContext false
        tapNode(field)
        delay(200)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

}

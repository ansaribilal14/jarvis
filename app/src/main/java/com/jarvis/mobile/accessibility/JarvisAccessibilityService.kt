package com.jarvis.mobile.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.mobile.core.observer.ScreenElement
import com.jarvis.mobile.core.observer.ScreenObservation
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The phone-control backbone. Exposes:
 *  - structured screen observation (accessibility tree -> ScreenObservation)
 *  - semantic actions (click / set text / scroll on nodes)
 *  - gesture fallbacks (coordinate tap / swipe) - used only when semantic fails
 *  - global navigation (back / home / recents / notification shade)
 *  - user-activity detection (manual override divergence signal)
 *  - screenshot (API 30+) for the OCR fallback path
 */
class JarvisAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onServiceConnected() {
        super.onServiceConnected()
        INSTANCE = this
        CONNECTED.value = true
        Logx.i(TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        INSTANCE = null
        CONNECTED.value = false
        Logx.w(TAG, "Accessibility service disconnected")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        INSTANCE = null
        CONNECTED.value = false
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = e.packageName?.toString()
                val cls = e.className?.toString()
                _windowEvents.tryEmit(pkg to cls)
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                _userTouches.tryEmit(System.currentTimeMillis())
            }
        }
    }

    // ---------------------------------------------------------------- state

    companion object {
        const val TAG = "a11y"
        private const val MAX_NODES = 320
        private const val MAX_DEPTH = 48

        @Volatile var INSTANCE: JarvisAccessibilityService? = null
            private set

        val CONNECTED = MutableStateFlow(false)

        private val _windowEvents =
            MutableSharedFlow<Pair<String?, String?>>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val windowEvents: SharedFlow<Pair<String?, String?>> = _windowEvents

        private val _userTouches =
            MutableSharedFlow<Long>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val userTouches: SharedFlow<Long> = _userTouches

        val isReady: Boolean get() = INSTANCE != null
    }

    // -------------------------------------------------------- observation

    fun currentPackage(): String? = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

    /** Build a compact ScreenObservation from the live accessibility tree. */
    fun observe(maxElements: Int = 90): ScreenObservation? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        val pkg = root.packageName?.toString()
        var activityName: String? = null
        runCatching {
            val win = windows.firstOrNull { it.isActive && it.root != null }
            activityName = win?.root?.className?.toString()
        }
        val elements = ArrayList<ScreenElement>(maxElements)
        var truncated = false
        var counter = 0

        fun classify(cn: CharSequence?): String {
            val c = cn?.toString() ?: return "other"
            return when {
                c.contains("Button", true) -> "button"
                c.contains("EditText", true) || c.contains("TextField", true) -> "edittext"
                c.contains("ImageView", true) || c.contains("Image", true) -> "image"
                c.contains("CheckBox", true) -> "checkbox"
                c.contains("Switch", true) || c.contains("Toggle", true) -> "toggle"
                c.contains("Spinner", true) || c.contains("DropDown", true) -> "dropdown"
                c.contains("WebView", true) -> "web"
                c.contains("RecyclerView", true) || c.contains("ListView", true) || c.contains("ScrollView", true) -> "list"
                c.contains("TextView", true) -> "text"
                else -> "other"
            }
        }

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && !truncated) {
            val (node, depth) = queue.removeFirst()
            visited++
            if (visited > MAX_NODES || elements.size >= maxElements) { truncated = true; break }
            if (depth > MAX_DEPTH) continue

            val rect = Rect()
            runCatching { node.getBoundsInScreen(rect) }.getOrNull()
            val visible = rect.width() > 0 && rect.height() > 0 &&
                rect.bottom > 0 && rect.top < 4000
            val meaningful = node.text?.isNotBlank() == true ||
                node.contentDescription?.isNotBlank() == true ||
                node.isClickable || node.isEditable || node.isScrollable

            if (visible && meaningful) {
                counter++
                elements.add(
                    ScreenElement(
                        idx = counter,
                        role = classify(node.className),
                        text = if (node.isPassword) null else node.text?.toString()?.take(120),
                        desc = node.contentDescription?.toString()?.take(80),
                        viewId = node.viewIdResourceName,
                        className = node.className?.toString(),
                        left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom,
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        selected = node.isSelected,
                        isPassword = node.isPassword,
                    )
                )
            }
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                if (child != null) queue.add(child to depth + 1)
            }
        }

        // Stable index order: clickable/interactive first keeps semantic handles compact.
        elements.sortWith(compareByDescending<ScreenElement> { it.clickable }.thenBy { it.top }.thenBy { it.left })
        return ScreenObservation(
            packageName = pkg,
            activityName = activityName,
            elements = elements.mapIndexed { i, e -> e.copy(idx = i + 1) },
            truncated = truncated,
        )
    }

    /** Re-resolve a live node matching an element snapshot (stale-state defense). */
    fun resolveNode(el: ScreenElement): AccessibilityNodeInfo? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            val rect = Rect()
            runCatching { node.getBoundsInScreen(rect) }.getOrNull()
            if (rect.left == el.left && rect.top == el.top && rect.right == el.right && rect.bottom == el.bottom &&
                node.text?.toString() == el.text && node.isClickable == el.clickable
            ) return node
            for (i in 0 until node.childCount) {
                runCatching { node.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return null
    }

    fun findByText(text: String): List<AccessibilityNodeInfo> =
        runCatching { rootInActiveWindow?.findAccessibilityNodeInfosByText(text) }.getOrNull() ?: emptyList()

    // ------------------------------------------------------------ actions

    fun performClick(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)

    fun performSetText(node: AccessibilityNodeInfo, newText: String): Boolean {
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
    }

    fun performFocus(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }.getOrDefault(false)

    fun performScroll(node: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return runCatching { node.performAction(action) }.getOrDefault(false)
    }

    fun selectAllAndCopy(node: AccessibilityNodeInfo): Boolean {
        runCatching {
            val args = android.os.Bundle()
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, node.text?.length ?: 0)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        }
        Thread.sleep(120)
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_COPY) }.getOrDefault(false)
    }

    fun pasteInto(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)

    fun globalBack(): Boolean = runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }.getOrDefault(false)
    fun globalHome(): Boolean = runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }.getOrDefault(false)
    fun globalRecents(): Boolean = runCatching { performGlobalAction(GLOBAL_ACTION_RECENTS) }.getOrDefault(false)
    fun globalNotifications(): Boolean = runCatching { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) }.getOrDefault(false)

    // -------------------------------------------------- gesture fallbacks

    private fun gesturePath(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): GestureDescription {
        val path = android.graphics.Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    fun tapAt(x: Int, y: Int): Boolean {
        val d = gesturePath(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), 40)
        return runCatching { dispatchGesture(d, null, null) }.getOrDefault(false)
    }

    fun longPressAt(x: Int, y: Int): Boolean {
        val d = gesturePath(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), 620)
        return runCatching { dispatchGesture(d, null, null) }.getOrDefault(false)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 320): Boolean {
        val d = gesturePath(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs)
        return runCatching { dispatchGesture(d, null, null) }.getOrDefault(false)
    }

    fun scrollScreen(forward: Boolean): Boolean {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root != null) {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(root)
            var visited = 0
            while (q.isNotEmpty() && visited < 120) {
                val n = q.removeFirst()
                visited++
                if (n.isScrollable) return performScroll(n, forward)
                for (i in 0 until n.childCount) runCatching { n.getChild(i) }.getOrNull()?.let { q.add(it) }
            }
        }
        // Fallback: vertical swipe in the middle of the screen.
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2
        return if (forward) swipe(cx, (metrics.heightPixels * 0.72).toInt(), cx, (metrics.heightPixels * 0.30).toInt())
        else swipe(cx, (metrics.heightPixels * 0.30).toInt(), cx, (metrics.heightPixels * 0.72).toInt())
    }

    // ---------------------------------------------------------- screenshot

    /** API 30+ screenshot via accessibility (no MediaProjection consent loop needed). */
    suspend fun takeScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            runCatching {
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    java.util.concurrent.Executor { it.run() },
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            cont.resume(screenshotResult.toBitmapSafe())
                        }

                        override fun onFailure(errorCode: Int) {
                            Logx.w(TAG, "takeScreenshot error: $errorCode")
                            cont.resume(null)
                        }
                    },
                )
            }.onFailure {
                Logx.e(TAG, "takeScreenshot failed: ${it.message}")
                cont.resume(null)
            }
        }
    }

    private fun android.accessibilityservice.AccessibilityService.ScreenshotResult.toBitmapSafe(): Bitmap? =
        runCatching {
            val hb = hardwareBuffer
            val colorSpace = colorSpace ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
            val bmp = Bitmap.wrapHardwareBuffer(hb, colorSpace)
            val copy = bmp?.copy(Bitmap.Config.ARGB_8888, false)
            hb.close()
            copy
        }.getOrNull()
}

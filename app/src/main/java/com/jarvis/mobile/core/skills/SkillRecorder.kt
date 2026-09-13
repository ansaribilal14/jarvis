package com.jarvis.mobile.core.skills

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Skill recorder: turns the user's live UI actions into replayable [SkillStep]s.
 *
 * v1.9 - Tasker-grade capture, two layers:
 *
 * 1. RAW TOUCH (API 34+): the accessibility service enables touchscreen motion
 *    observation, and EVERY physical tap / long-press / swipe is recorded by
 *    real screen coordinates - even in apps that never emit view-click events
 *    (games, canvases, custom views, WebView). This is the same approach a
 *    macro recorder takes and it simply cannot miss a click.
 * 2. ACCESSIBILITY EVENTS (all versions): TYPE_VIEW_TEXT_CHANGED gives typed
 *    content (raw touch cannot), APP_OPEN comes from window transitions, and
 *    TAP/LONG_PRESS/SCROLL events act as the pre-34 fallback.
 *
 * The recording session is PERSISTED (active flag + JSONL step file), so an
 * aggressive ROM killing the process while the user is in another app no
 * longer wipes the recording - the service resumes it on rebind.
 *
 * JARVIS's own actions are suppressed via [suppress] so a replay never
 * records itself; the system UI shade is filtered.
 */
object SkillRecorder {
    private const val TAG = "skill-rec"
    private const val NOTIF_ID = 4242
    private const val MAX_STEPS = 120
    private const val PREFS = "skill_recorder_session"

    data class RecordingState(
        val active: Boolean = false,
        val steps: List<SkillStep> = emptyList(),
        val finishedSteps: List<SkillStep>? = null, // set after stop - pending review
        val startedAtMs: Long = 0L,
        /** True when raw-motion capture is wired up (API 34+ service connected). */
        val motionCapture: Boolean = false,
    )

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    /** True while the agent or a skill replay performs actions - events then are OURS, not the user's. */
    @Volatile var suppress: Boolean = false

    @Volatile private var lastTextKey: String? = null
    @Volatile private var lastTextAtMs: Long = 0L
    @Volatile private var lastScrollAtMs: Long = 0L
    @Volatile private var lastPkg: String? = null

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ------------------------------------------------------------- persistence

    private fun prefs(): SharedPreferences =
        JarvisApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sessionFile(): File = File(JarvisApp.instance.filesDir, "skills/.session.jsonl")

    private fun setPersistedActive(active: Boolean) {
        runCatching { prefs().edit().putBoolean("active", active).apply() }
    }

    private fun appendStepToFile(step: SkillStep) {
        runCatching {
            sessionFile().parentFile?.mkdirs()
            sessionFile().appendText(json.encodeToString(SkillStep.serializer(), step) + "\n")
        }.onFailure { Logx.w(TAG, "session append failed: ${it.message}") }
    }

    private fun loadStepsFromFile(): List<SkillStep> = runCatching {
        sessionFile().readLines().filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.decodeFromString(SkillStep.serializer(), it) }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun clearSessionFile() {
        runCatching { sessionFile().delete() }
    }

    /**
     * Resume a recording that survived a process kill: the persisted active
     * flag says the user believes they are recording; reload the steps taken
     * so far and keep going. Called from the service on connect.
     */
    fun resumeIfNeeded(): Boolean {
        val wasActive = runCatching { prefs().getBoolean("active", false) }.getOrDefault(false)
        if (!wasActive || _state.value.active) return false
        val steps = loadStepsFromFile()
        _state.value = RecordingState(active = true, steps = steps, startedAtMs = System.currentTimeMillis(), motionCapture = false)
        postNotification(JarvisApp.instance, steps.size)
        Logx.i(TAG, "Recording resumed after restart: ${steps.size} steps")
        return true
    }

    // ------------------------------------------------------------------ control

    fun start() {
        clearSessionFile()
        _state.value = RecordingState(active = true, startedAtMs = System.currentTimeMillis())
        setPersistedActive(true)
        lastPkg = null
        lastTextKey = null
        suppress = false
        postNotification(JarvisApp.instance, 0)
        Logx.i(TAG, "Recording started")
    }

    /** Stop recording; steps stay in [RecordingState.finishedSteps] until reviewed/discarded. */
    fun stop() {
        var cur = _state.value
        if (!cur.active && runCatching { prefs().getBoolean("active", false) }.getOrDefault(false)) {
            // Fresh process (ROM killed us mid-recording) + the user pressed
            // "Stop" before the service reconnected: load the session first.
            resumeIfNeeded()
            cur = _state.value
        }
        if (!cur.active) return
        val steps = if (cur.steps.isEmpty()) loadStepsFromFile() else cur.steps
        _state.value = cur.copy(active = false, steps = steps, finishedSteps = steps, motionCapture = false)
        setPersistedActive(false)
        clearSessionFile()
        cancelNotification(JarvisApp.instance)
        Logx.i(TAG, "Recording stopped: ${steps.size} steps")
    }

    /** Review consumed (saved or discarded) - clears the finished buffer. */
    fun consumeFinished() {
        _state.value = _state.value.copy(finishedSteps = null)
    }

    fun discard() {
        _state.value = _state.value.copy(finishedSteps = null, steps = emptyList())
    }

    /** Called by the accessibility service when raw-motion capture is wired (API 34+). */
    fun setMotionCapture(on: Boolean) {
        if (_state.value.motionCapture != on) {
            _state.value = _state.value.copy(motionCapture = on)
            Logx.i(TAG, "Raw-motion capture ${if (on) "ON - every tap will be recorded" else "off (event fallback)"}")
        }
    }

    // -------------------------------------------------- raw touch layer (34+)

    private val touch = TouchStroke()

    fun onTouchDown(t: Long, x: Float, y: Float, pointers: Int) {
        if (!_state.value.active || suppress) return
        touch.begin(t, x, y, pointers)
    }

    fun onTouchMove(t: Long, x: Float, y: Float, pointers: Int) {
        if (!_state.value.active || suppress) return
        touch.move(t, x, y, pointers)
    }

    fun onTouchUp(t: Long, x: Float, y: Float) {
        val st = _state.value
        if (!st.active || suppress) return
        val (type, ex, ey, durMs) = touch.end(t, x, y) ?: return
        when (type) {
            "TAP", "LONG_PRESS" -> {
                val step = SkillStep(type = type, pkg = currentPkg(), x = ex.toInt(), y = ey.toInt())
                addStep(step)
                enrichAsync(step, ex, ey)
            }
            "SCROLL" -> {
                val now = System.currentTimeMillis()
                if (now - lastScrollAtMs > 500) {
                    lastScrollAtMs = now
                    // ex/ey here are DELTAS (direction), not screen points - never store them as x/y.
                    addStep(SkillStep(type = "SCROLL", pkg = currentPkg(), dir = directionOf(ex, ey)))
                }
            }
        }
    }

    private fun currentPkg(): String? =
        runCatching { JarvisAccessibilityServiceHolder.pkg() }.getOrNull()

    private fun directionOf(dx: Float, dy: Float): String =
        if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) if (dx > 0) "right" else "left"
        else if (dy > 0) "down" else "up"

    /**
     * Semantic enrichment of a raw-touch step: find the element under the tap
     * point and attach its labels, so replay prefers viewId/text matching and
     * only falls back to coordinates when the layout shifted. Runs off-main.
     */
    private fun enrichAsync(step: SkillStep, x: Float?, y: Float?) {
        if (x == null || y == null) return
        scope.launch {
            val obs = runCatching { JarvisAccessibilityServiceHolder.observe(80) }.getOrNull() ?: return@launch
            val el = obs.elements.firstOrNull { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }
                ?: return@launch
            val cur = _state.value
            if (!cur.active) return@launch
            val updated = step.copy(
                viewId = el.viewId ?: step.viewId,
                text = el.text ?: step.text,
                desc = el.desc ?: step.desc,
            )
            if (updated == step) return@launch
            // Replace the just-added step (match by type + coords).
            val idx = cur.steps.indexOfLast { it.type == step.type && it.x == step.x && it.y == step.y }
            if (idx >= 0) {
                val steps = cur.steps.toMutableList().also { it[idx] = updated }
                _state.value = cur.copy(steps = steps)
            }
        }
    }

    /**
     * Streaming stroke classifier: DOWN -> MOVE* -> UP. Multi-pointer strokes
     * (pinch etc.) are ignored. Pure logic - JVM-testable.
     */
    class TouchStroke(private val slopPx: Float = 24f) {
        var downAt = 0L; private set
        var x0 = 0f; private set
        var y0 = 0f; private set
        private var maxDist = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var multiPointer = false
        private var began = false

        fun begin(t: Long, x: Float, y: Float, pointers: Int) {
            began = true; multiPointer = pointers > 1
            downAt = t; x0 = x; y0 = y; lastX = x; lastY = y; maxDist = 0f
        }

        fun move(t: Long, x: Float, y: Float, pointers: Int) {
            if (!began) return
            if (pointers > 1) multiPointer = true
            val d = kotlin.math.hypot((x - x0).toDouble(), (y - y0).toDouble()).toFloat()
            if (d > maxDist) maxDist = d
            lastX = x; lastY = y
        }

        /** Returns (type, x, y, durationMs) or null for ignored strokes. */
        fun end(t: Long, x: Float, y: Float): Quoctuple? {
            if (!began) return null
            began = false
            if (multiPointer) return null
            val dur = (t - downAt).coerceAtLeast(0)
            val dist = maxOf(maxDist, kotlin.math.hypot((x - x0).toDouble(), (y - y0).toDouble()).toFloat())
            return when {
                dist > slopPx -> Quoctuple("SCROLL", x - x0, y - y0, dur)
                dur >= 450 -> Quoctuple("LONG_PRESS", x0, y0, dur)
                dur <= 1500 -> Quoctuple("TAP", x0, y0, dur)
                else -> null // lost-touch ghost stroke
            }
        }

        data class Quoctuple(val type: String, val a: Float, val b: Float, val durMs: Long)
    }

    // ------------------------------------------- accessibility-event fallback

    /**
     * Event sink called from JarvisAccessibilityService. On API 34+ the raw
     * touch layer owns TAP/LONG_PRESS/SCROLL (duplicates are dropped here);
     * TEXT and APP_OPEN always come from events.
     */
    fun onAccessibilityEvent(e: AccessibilityEvent, selfPackage: String) {
        val st = _state.value
        if (!st.active || suppress) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == selfPackage || pkg == "com.android.systemui") return

        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (st.motionCapture) return // raw touch already recorded this tap with real coordinates
                capture(e, pkg, "TAP")?.let { addStep(it) }
            }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                if (st.motionCapture) return
                capture(e, pkg, "LONG_PRESS")?.let { addStep(it) }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Fires per character - debounce per target and keep the final text.
                val key = "$pkg:${e.beforeText ?: ""}:${e.source?.viewIdResourceName ?: ""}"
                val now = System.currentTimeMillis()
                if (key == lastTextKey && now - lastTextAtMs < 1500 && st.steps.lastOrNull()?.type == "TEXT") {
                    val last = st.steps.last()
                    val updated = last.copy(input = e.text?.joinToString("") ?: last.input)
                    _state.value = st.copy(steps = st.steps.dropLast(1) + updated)
                    lastTextAtMs = now
                    postNotification(JarvisApp.instance, _state.value.steps.size)
                } else {
                    lastTextKey = key
                    lastTextAtMs = now
                    capture(e, pkg, "TEXT")?.let { step ->
                        addStep(step.copy(input = step.text ?: e.text?.joinToString("").orEmpty().ifBlank { null }))
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (st.motionCapture) return
                val now = System.currentTimeMillis()
                if (now - lastScrollAtMs < 700) return // one fling = many events; keep one
                lastScrollAtMs = now
                addStep(SkillStep(type = "SCROLL", pkg = pkg, dir = "fwd"))
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val newPkg = e.packageName?.toString()
                if (newPkg != null && newPkg != lastPkg && newPkg != pkgOf(st.steps.lastOrNull())) {
                    lastPkg = newPkg
                    if (st.steps.isNotEmpty()) {
                        addStep(SkillStep(type = "APP_OPEN", pkg = newPkg))
                    }
                }
            }
        }
    }

    private fun pkgOf(step: SkillStep?): String? = step?.pkg

    private fun capture(e: AccessibilityEvent, pkg: String, type: String): SkillStep? {
        val node = e.source ?: return if (type == "TAP") SkillStep(type = type, pkg = pkg) else null
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        val text = node.text?.toString()?.take(60)?.takeIf { it.isNotBlank() }
        val desc = node.contentDescription?.toString()?.take(60)?.takeIf { it.isNotBlank() }
        val viewId = node.viewIdResourceName
        val center = if (rect.width() > 0 && rect.height() > 0) rect.centerX() to rect.centerY() else null
        // Never record password content - the typed text is masked by design.
        val input = if (node.isPassword) "[PROTECTED]" else e.text?.joinToString("")?.take(120)
        return SkillStep(
            type = type,
            pkg = pkg,
            viewId = viewId,
            text = if (node.isPassword) null else text,
            desc = desc,
            x = center?.first,
            y = center?.second,
            input = input,
        )
    }

    private fun addStep(step: SkillStep) {
        val st = _state.value
        if (!st.active) return
        if (st.steps.size >= MAX_STEPS) {
            stop()
            return
        }
        _state.value = st.copy(steps = st.steps + step)
        appendStepToFile(step)
        postNotification(JarvisApp.instance, _state.value.steps.size)
    }

    // ------------------------------------------------------------ notification

    fun postNotification(ctx: Context, stepCount: Int) {
        runCatching {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, com.jarvis.mobile.MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stopIntent = PendingIntent.getBroadcast(
                ctx, 1,
                Intent(ctx, SkillRecReceiver::class.java).setAction(SkillRecReceiver.ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n: Notification = android.app.Notification.Builder(ctx, JarvisApp.CH_AGENT)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Recording skill")
                .setContentText("$stepCount steps captured - every tap is recorded, then stop")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop recording", stopIntent)
                .build()
            nm.notify(NOTIF_ID, n)
        }.onFailure { Logx.w(TAG, "Recording notification failed: ${it.message}") }
    }

    private fun cancelNotification(ctx: Context) {
        runCatching { ctx.getSystemService(android.app.NotificationManager::class.java).cancel(NOTIF_ID) }
    }
}

/**
 * Indirection so the recorder can enrich raw-touch steps and read the current
 * app without a hard dependency cycle on the accessibility service class.
 */
object JarvisAccessibilityServiceHolder {
    @Volatile var pkgProvider: () -> String? = { null }
    @Volatile var observeProvider: (Int) -> com.jarvis.mobile.core.observer.ScreenObservation? = { null }

    fun pkg(): String? = pkgProvider()
    fun observe(max: Int): com.jarvis.mobile.core.observer.ScreenObservation? = observeProvider(max)
}

/** Manifest-registered receiver backing the "Stop recording" notification action. */
class SkillRecReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ACTION_STOP) SkillRecorder.stop()
    }

    companion object {
        const val ACTION_STOP = "com.jarvis.mobile.STOP_SKILL_RECORDING"
    }
}

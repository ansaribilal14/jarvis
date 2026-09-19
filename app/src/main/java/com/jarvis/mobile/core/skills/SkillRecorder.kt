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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Skill recorder: turns the user's live UI actions into replayable [SkillStep]s.
 *
 * v2.0 - Layered capture (v1.9's motionEventSources consumed touches; v1.10's
 * TouchInteractionController depended on touch-exploration semantics that most
 * ROMs never deliver without hijacking the tap - both reported "records
 * nothing" on real devices). The capture stack now NEVER intercepts or alters
 * a user interaction, and no flag can silence the fallback:
 *
 * 1. PRECISION TOUCH (primary, all Android versions): the raw kernel touch
 *    stream (`getevent -t`) read through Shizuku (shell identity, root-free -
 *    the AutoX-root technique). Every contact in every app with real screen
 *    coordinates; taps, long-presses and swipes are classified from the
 *    stream; the user's touches are observed, never consumed.
 * 2. APP EVENTS (all devices, always): TYPE_VIEW_CLICKED / LONG_CLICKED give
 *    semantic labels, TYPE_VIEW_TEXT_CHANGED gives typed content, list scrolls
 *    and app switches are captured too.
 *
 * A pure-JVM [RawTapMerger] fuses the layers: a raw DOWN plus a matching click
 * event within [RawTapMerger.windowMs] becomes ONE semantic step with real
 * coordinates; a raw DOWN that no event claims commits as a coordinate TAP.
 *
 * The recording session is PERSISTED (active flag + JSONL step file), so an
 * aggressive ROM killing the process mid-recording no longer wipes it - the
 * service resumes on rebind. JARVIS's own actions are suppressed via [suppress];
 * JARVIS itself and the system shade are filtered.
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
        /** True when the precision touch stream is delivering (Shizuku). */
        val precisionActive: Boolean = false,
        /** Raw touch DOWNs seen this session - live proof that capture works. */
        val rawTaps: Int = 0,
        /** Honest capture description for the UI: what IS being recorded right now. */
        val captureLayer: String = "EVENTS",
    )

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    /** True while the agent or a skill replay performs actions - events then are OURS, not the user's. */
    @Volatile var suppress: Boolean = false

    @Volatile private var lastTextKey: String? = null
    @Volatile private var lastTextAtMs: Long = 0L
    @Volatile private var lastScrollAtMs: Long = 0L
    @Volatile private var lastPkg: String? = null

    /** Most recent precision-capture DOWN (px) - direction math for stream gestures. */
    @Volatile private var lastRawDown: Pair<Int, Int>? = null

    /** Called by [TouchStreamRecorder] before classifying a gesture. */
    fun lastRawDownPx(): Pair<Int, Int>? = lastRawDown

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()

    private val merger = RawTapMerger()

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
        _state.value = RecordingState(active = true, steps = steps, startedAtMs = System.currentTimeMillis())
        postNotification(JarvisApp.instance, steps.size)
        // Precision capture died with the old process - re-arm it now.
        JarvisAccessibilityServiceHolder.precisionEnable?.invoke()
        com.jarvis.mobile.service.RecordBubble.show(JarvisApp.instance)
        Logx.i(TAG, "Recording resumed after restart: ${steps.size} steps")
        return true
    }

    // ------------------------------------------------------------------ control

    fun start() {
        clearSessionFile()
        synchronized(stateLock) {
            _state.value = RecordingState(active = true, startedAtMs = System.currentTimeMillis())
        }
        setPersistedActive(true)
        lastPkg = null
        lastTextKey = null
        suppress = false
        merger.reset()
        // Arm precision touch capture (Shizuku touch stream); safe no-op + honest
        // fallback status when unavailable.
        JarvisAccessibilityServiceHolder.precisionEnable?.invoke()
        postNotification(JarvisApp.instance, 0)
        com.jarvis.mobile.service.RecordBubble.show(JarvisApp.instance)
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
        // Restore normal touch BEFORE anything else - recording state must never
        // keep the touch pipeline interposed.
        JarvisAccessibilityServiceHolder.precisionDisable?.invoke()
        com.jarvis.mobile.service.RecordBubble.hide(JarvisApp.instance)
        val flushed = merger.flush()
        val live = synchronized(stateLock) { _state.value.steps }
        val steps = (if (live.isEmpty()) loadStepsFromFile() else live) + flushed
        synchronized(stateLock) {
            _state.value = _state.value.copy(active = false, steps = steps, finishedSteps = steps, precisionActive = false)
        }
        setPersistedActive(false)
        clearSessionFile()
        cancelNotification(JarvisApp.instance)
        Logx.i(TAG, "Recording stopped: ${steps.size} steps")
    }

    /** Review consumed (saved or discarded) - clears the finished buffer. */
    fun consumeFinished() {
        synchronized(stateLock) {
            _state.value = _state.value.copy(finishedSteps = null)
        }
    }

    fun discard() {
        synchronized(stateLock) {
            _state.value = _state.value.copy(finishedSteps = null, steps = emptyList())
        }
    }

    /** Called by the touch-stream recorder when precision capture is delivering events. */
    fun setPrecision(on: Boolean, layer: String? = null) {
        synchronized(stateLock) {
            if (_state.value.precisionActive != on) {
                _state.value = _state.value.copy(
                    precisionActive = on,
                    captureLayer = if (on) {
                        when (layer) {
                            "built_in" -> "PRECISION (built-in shell)+EVENTS"
                            "shizuku" -> "PRECISION (Shizuku)+EVENTS"
                            else -> "PRECISION+EVENTS"
                        }
                    } else "EVENTS",
                )
                Logx.i(TAG, if (on) "Precision touch capture ACTIVE - every tap is recorded"
                             else "Precision touch capture unavailable - app-event capture only")
            }
        }
    }

    // ------------------------------------------------- precision touch layer (34+)

    /**
     * A physical touch DOWN was observed (after the service already delegated the
     * interaction back to the system, so the user's tap behaves 100% normally).
     * Registered as a pending tap; merged with a matching click event if one
     * arrives, else committed as a coordinate TAP after the merge window.
     */
    fun onRawDown(downAtMs: Long, x: Float, y: Float) {
        val st = _state.value
        if (!st.active || suppress) return
        val pkg = currentPkg()
        if (pkg == JarvisApp.instance.packageName || pkg == "com.android.systemui") return
        lastRawDown = x.toInt() to y.toInt()
        val swept = merger.onRawDown(downAtMs, x.toInt(), y.toInt())
        swept.forEach { commitRawStep(it) }
        synchronized(stateLock) {
            _state.value = _state.value.copy(rawTaps = _state.value.rawTaps + 1)
        }
        // Schedule the sweep that commits unclaimed pendings.
        scope.launch {
            delay(RawTapMerger.WINDOW_MS + 120)
            merger.sweep(System.currentTimeMillis()).forEach { commitRawStep(it) }
        }
    }

    /**
     * A COMPLETE gesture classified from the precision stream (long-press or
     * swipe; taps stay with the merger so a click event can still label them).
     * The matching pending tap is cancelled first so nothing double-records.
     */
    fun onRawGesture(step: SkillStep) {
        val st = _state.value
        if (!st.active || suppress) return
        val down = lastRawDown
        if (down != null) merger.cancelNear(down.first, down.second)
        lastRawDown = null
        addStep(step.copy(pkg = step.pkg ?: currentPkg()))
    }

    private fun commitRawStep(step: SkillStep) {
        addStep(step)
        enrichAsync(step, step.x?.toFloat(), step.y?.toFloat())
        lastRawDown = null
    }

    private fun currentPkg(): String? =
        runCatching { JarvisAccessibilityServiceHolder.pkg() }.getOrNull()

    /**
     * Semantic enrichment of a raw-coordinate step: find the element under the tap
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
            synchronized(stateLock) {
                val cur2 = _state.value
                val idx = cur2.steps.indexOfLast { it.type == step.type && it.x == step.x && it.y == step.y }
                if (idx >= 0) {
                    _state.value = cur2.copy(steps = cur2.steps.toMutableList().also { it[idx] = updated })
                }
            }
        }
    }

    // ------------------------------------------- accessibility-event layer

    /**
     * Event sink called from JarvisAccessibilityService for every accessibility
     * event while recording. Click/long-click events are FUSED with pending raw
     * taps; TEXT and APP_OPEN always come from events. JARVIS and the system UI
     * are filtered here.
     */
    fun onAccessibilityEvent(e: AccessibilityEvent, selfPackage: String) {
        val st = _state.value
        if (!st.active || suppress) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == selfPackage || pkg == "com.android.systemui") return
        val now = System.currentTimeMillis()

        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = e.source
                val rect = Rect()
                runCatching { node?.getBoundsInScreen(rect) }
                val hasBounds = rect.width() > 0 && rect.height() > 0
                val steps = merger.onEventClick(
                    now = now,
                    kind = "TAP",
                    pkg = pkg,
                    viewId = node?.viewIdResourceName,
                    text = node?.text?.toString()?.take(60)?.takeIf { it.isNotBlank() },
                    desc = node?.contentDescription?.toString()?.take(60)?.takeIf { it.isNotBlank() },
                    x = if (hasBounds) rect.centerX() else null,
                    y = if (hasBounds) rect.centerY() else null,
                )
                applyMerger(steps)
            }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val node = e.source
                val rect = Rect()
                runCatching { node?.getBoundsInScreen(rect) }
                val hasBounds = rect.width() > 0 && rect.height() > 0
                val steps = merger.onEventClick(
                    now = now,
                    kind = "LONG_PRESS",
                    pkg = pkg,
                    viewId = node?.viewIdResourceName,
                    text = node?.text?.toString()?.take(60)?.takeIf { it.isNotBlank() },
                    desc = node?.contentDescription?.toString()?.take(60)?.takeIf { it.isNotBlank() },
                    x = if (hasBounds) rect.centerX() else null,
                    y = if (hasBounds) rect.centerY() else null,
                )
                applyMerger(steps)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Fires per character - debounce per target and keep the final text.
                val key = "$pkg:${e.beforeText ?: ""}:${e.source?.viewIdResourceName ?: ""}"
                if (key == lastTextKey && now - lastTextAtMs < 1500 && st.steps.lastOrNull()?.type == "TEXT") {
                    val last = st.steps.last()
                    val updated = last.copy(input = e.text?.joinToString("") ?: last.input)
                    synchronized(stateLock) {
                        val cur = _state.value
                        _state.value = cur.copy(steps = cur.steps.dropLast(1) + updated)
                    }
                    lastTextAtMs = now
                } else {
                    lastTextKey = key
                    lastTextAtMs = now
                    capture(e, pkg, "TEXT")?.let { step ->
                        addStep(step.copy(input = step.text ?: e.text?.joinToString("").orEmpty().ifBlank { null }, t = now))
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // The scroll event both records direction AND cancels a pending raw
                // tap (that DOWN was the start of the fling, not a tap).
                merger.onEventScroll(now).forEach { commitRawStep(it) }
                if (now - lastScrollAtMs < 700) return // one fling = many events; keep one
                lastScrollAtMs = now
                addStep(SkillStep(type = "SCROLL", pkg = pkg, dir = "fwd", t = now))
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val newPkg = e.packageName?.toString()
                if (newPkg != null && newPkg != lastPkg && newPkg != pkgOf(st.steps.lastOrNull())) {
                    lastPkg = newPkg
                    if (st.steps.isNotEmpty()) {
                        addStep(SkillStep(type = "APP_OPEN", pkg = newPkg, t = now))
                    }
                }
            }
        }
    }

    /** Commit merger outputs: replacements update the matching committed step in place. */
    private fun applyMerger(steps: List<RawTapMerger.Out>) {
        steps.forEach { out ->
            when (out) {
                is RawTapMerger.Out.Add -> addStep(out.step)
                is RawTapMerger.Out.Replace -> synchronized(stateLock) {
                    val cur = _state.value
                    val idx = cur.steps.indexOfFirst { it === out.old || (it.t == out.old.t && it.x == out.old.x && it.y == out.old.y && it.type == out.old.type) }
                    if (idx >= 0) _state.value = cur.copy(steps = cur.steps.toMutableList().also { it[idx] = out.new })
                }
            }
        }
    }

    private fun pkgOf(step: SkillStep?): String? = step?.pkg

    private fun capture(e: AccessibilityEvent, pkg: String, type: String): SkillStep? {
        val node = e.source ?: return if (type == "TEXT") null else SkillStep(type = type, pkg = pkg)
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
        synchronized(stateLock) {
            val st = _state.value
            if (!st.active) return
            if (st.steps.size >= MAX_STEPS) {
                stop()
                return
            }
            _state.value = st.copy(steps = st.steps + step)
        }
        appendStepToFile(step)
        postNotification(JarvisApp.instance, _state.value.steps.size)
    }

    /** Exposed for the floating REC bubble live counter. */
    fun stepCount(): Int = _state.value.steps.size

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
 * Fuses the two capture layers into single steps. Pure JVM logic: every call
 * takes an explicit `now` timestamp, so it is fully deterministic and testable.
 *
 * - A raw DOWN opens a pending tap.
 * - A click/long-click event within [WINDOW_MS] and [RADIUS_PX] of a pending tap
 *   MERGES with it: semantic labels + real coordinates in one step.
 * - A scroll event cancels pendings (that DOWN was the start of the fling).
 * - A pending nobody claims within the window commits as a coordinate TAP.
 * - An event arriving for a raw tap that ALREADY committed (late label) replaces
 *   the coordinate-only step with the semantic one (Out.Replace).
 */
class RawTapMerger(
    private val windowMs: Long = WINDOW_MS,
    private val radiusPx: Int = RADIUS_PX,
    private val dedupMs: Long = DEDUP_MS,
) {
    private data class Pending(val t: Long, val x: Int, val y: Int)

    sealed interface Out {
        data class Add(val step: SkillStep) : Out
        data class Replace(val old: SkillStep, val new: SkillStep) : Out
    }

    private val lock = Any()
    private val pending = ArrayList<Pending>()
    private val recent = ArrayList<SkillStep>() // committed coordinate taps, for late-event replaces

    fun onRawDown(now: Long, x: Int, y: Int): List<SkillStep> = synchronized(lock) {
        val swept = sweepLocked(now)
        pending.add(Pending(now, x, y))
        swept
    }

    fun onEventClick(
        now: Long,
        kind: String,
        pkg: String?,
        viewId: String?,
        text: String?,
        desc: String?,
        x: Int?,
        y: Int?,
    ): List<Out> = synchronized(lock) {
        val outs = ArrayList<Out>()
        sweepLocked(now).forEach { outs.add(Out.Add(it)) }

        // 1) Merge with a pending raw tap near the event point.
        val candidates = pending.withIndex()
            .filter { now - it.value.t <= windowMs && near(it.value, x, y) }
        val merged = if (x == null || y == null) candidates.maxByOrNull { it.value.t }
        else candidates.minByOrNull { Math.abs(it.value.x - x) + Math.abs(it.value.y - y) }
        if (merged != null) {
            pending.removeAt(merged.index)
            outs.add(Out.Add(SkillStep(kind, pkg, viewId, text, desc, merged.value.x, merged.value.y, t = now)))
            remember(outs.lastOrNull())
            return@synchronized outs
        }

        // 2) Event with no pending: was a raw tap already committed nearby (late label)?
        if (x != null && y != null) {
            val prior = recent.lastOrNull {
                it.type == "TAP" && it.viewId == null && now - (it.t ?: 0) <= dedupMs &&
                    Math.abs(it.x!! - x) + Math.abs(it.y!! - y) <= radiusPx * 2
            }
            if (prior != null) {
                recent.remove(prior)
                outs.add(Out.Replace(prior, SkillStep(kind, pkg, viewId, text, desc, x, y, t = now)))
                return@synchronized outs
            }
        }

        // 3) Standalone event step (keyboard clicks, precision capture off, pre-34).
        outs.add(Out.Add(SkillStep(kind, pkg, viewId, text, desc, x, y, t = now)))
        outs
    }

    /** A scroll event: cancel pendings in the window (fling start) and sweep the rest. */
    fun onEventScroll(now: Long): List<SkillStep> = synchronized(lock) {
        pending.removeAll { now - it.t <= windowMs }
        sweepLocked(now)
    }

    /**
     * Drop the pending tap near (x,y) - the precision stream reclassified that
     * DOWN as a long-press/swipe which commits itself; without this the pending
     * would later commit as a duplicate tap.
     */
    fun cancelNear(x: Int, y: Int) = synchronized(lock) {
        pending.removeAll { Math.abs(it.x - x) + Math.abs(it.y - y) <= radiusPx * 2 }
    }

    /** Commit pendings older than the window; drop anything still inside it. */
    fun sweep(now: Long): List<SkillStep> = synchronized(lock) { sweepLocked(now) }

    /** Recording stopped: commit every pending immediately. */
    fun flush(): List<SkillStep> = synchronized(lock) {
        val outs = pending.map { commitRaw(it, Long.MAX_VALUE) }
        pending.clear()
        outs
    }

    fun reset() = synchronized(lock) {
        pending.clear(); recent.clear()
    }

    private fun sweepLocked(now: Long): List<SkillStep> {
        val due = pending.filter { now - it.t > windowMs }
        if (due.isEmpty()) return emptyList()
        pending.removeAll(due)
        return due.map { commitRaw(it, now) }
    }

    private fun commitRaw(p: Pending, now: Long): SkillStep {
        val step = SkillStep("TAP", x = p.x, y = p.y, t = if (now == Long.MAX_VALUE) p.t else now)
        recent.add(step)
        if (recent.size > 8) recent.removeAt(0)
        return step
    }

    /** Track merged/standalone event steps too, so a later duplicate can be spotted. */
    private fun remember(out: Out?) {
        (out as? Out.Add)?.let { if (it.step.type == "TAP") recent.add(it.step) }
    }

    private fun near(p: Pending, x: Int?, y: Int?): Boolean =
        x == null || y == null || (Math.abs(p.x - x) + Math.abs(p.y - y)) <= radiusPx

    companion object {
        const val WINDOW_MS = 1500L
        const val RADIUS_PX = 48
        const val DEDUP_MS = 2500L
    }
}

/**
 * Indirection so the recorder can enrich raw-touch steps, read the current app
 * and toggle precision capture without a dependency cycle on the service class.
 */
object JarvisAccessibilityServiceHolder {
    @Volatile var pkgProvider: () -> String? = { null }
    @Volatile var observeProvider: (Int) -> com.jarvis.mobile.core.observer.ScreenObservation? = { null }

    /** Arm precision touch capture (register TIC + touch-exploration flag). Null when service is down. */
    @Volatile var precisionEnable: (() -> Unit)? = null

    /** Restore normal touch (flag off + callbacks unregistered). Always safe to call. */
    @Volatile var precisionDisable: (() -> Unit)? = null

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

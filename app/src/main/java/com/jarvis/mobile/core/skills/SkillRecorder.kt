package com.jarvis.mobile.core.skills

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Skills v3 live capture (docs/SKILLS_V3.md section 3.3, secondary path).
 *
 * The v3 doctrine: capture is a CONVENIENCE, never a load-bearing privilege.
 * This recorder turns what apps voluntarily REPORT - view clicks, long-clicks,
 * text changes, scrolls, app switches - into [SkillAction]s, with none of the
 * v1/v2 machinery that tried (and failed four times) to observe raw touches:
 * no motionEventSources, no TouchInteractionController, no Shizuku getevent,
 * no in-app ADB. For apps that report nothing, the answer is the builder's
 * "Pick on screen" flow (screenshot + point), not more privilege machinery.
 *
 * Every capture is instantly visible (state.lastCapture + the REC bubble) and
 * every un-reportable tap is counted and surfaced - silence is impossible.
 */
object SkillRecorder {
    private const val TAG = "skill-rec"
    private const val NOTIFICATION_ID = 10010
    const val ACTION_STOP = "com.jarvis.mobile.STOP_SKILL_RECORDING"
    const val MAX_STEPS = 200

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class RecState(
        val active: Boolean = false,
        val steps: List<SkillAction> = emptyList(),
        val finishedSteps: List<SkillAction>? = null,
        val eventsSeen: Int = 0,          // every candidate event that arrived
        val unusableEvents: Int = 0,      // events with no node/bounds (app hides controls)
        val lastCapture: String? = null,  // instant feedback: "Tapped \"Send\""
        val warnings: List<String> = emptyList(),
        val suspectNoCapture: Boolean = false, // watchdog: in-app, no events for a while
        val currentApp: String? = null,
    )

    private val _state = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = _state.asStateFlow()

    /** Agent replays / the grill-me interview must not record themselves. */
    @Volatile var suppress: Boolean = false

    private var steps = mutableListOf<SkillAction>()
    private var startedAtMs = 0L
    private var lastWindowPkg: String? = null
    private var lastStepKey: Pair<String, Long>? = null // (type+label, time) dedup window
    private var scope: CoroutineScope? = null
    private var prefs: SharedPreferences? = null

    // ------------------------------------------------------------- lifecycle

    fun start() {
        if (_state.value.active) return
        val ctx = JarvisApp.instance
        prefs = ctx.getSharedPreferences("skill_recorder_session", Context.MODE_PRIVATE)
        steps = mutableListOf()
        startedAtMs = System.currentTimeMillis()
        lastWindowPkg = null
        lastStepKey = null
        sessionFile(ctx).delete()
        prefs!!.edit().putBoolean("active", true).apply()
        _state.value = RecState(active = true, currentApp = currentApp())
        startWatchdog()
        postNotification(ctx)
        RecordBubble.show(ctx)
        Logx.i(TAG, "Recording started (app-event capture)")
    }

    fun stop() {
        if (!_state.value.active) return
        val ctx = JarvisApp.instance
        scope?.cancel(); scope = null
        val captured = steps.toList()
        prefs?.edit()?.putBoolean("active", false)?.apply()
        sessionFile(ctx).delete()
        cancelNotification(ctx)
        RecordBubble.hide(ctx)
        _state.value = _state.value.copy(
            active = false, finishedSteps = captured, suspectNoCapture = false,
            lastCapture = null, warnings = _state.value.warnings,
        )
        Logx.i(TAG, "Recording stopped: ${captured.size} actions")
    }

    fun discard() {
        val ctx = JarvisApp.instance
        scope?.cancel(); scope = null
        steps = mutableListOf()
        prefs?.edit()?.putBoolean("active", false)?.apply()
        sessionFile(ctx).delete()
        cancelNotification(ctx)
        RecordBubble.hide(ctx)
        _state.value = RecState()
    }

    /** Process-death recovery: reload captured steps and keep recording. */
    fun resumeIfNeeded() {
        val ctx = JarvisApp.instance
        val p = ctx.getSharedPreferences("skill_recorder_session", Context.MODE_PRIVATE)
        prefs = p
        if (!p.getBoolean("active", false) || _state.value.active) return
        val f = sessionFile(ctx)
        if (f.exists()) {
            runCatching {
                steps = f.readLines().filter { it.isNotBlank() }
                    .mapNotNull { line -> runCatching { json.decodeFromString<SkillAction>(line) }.getOrNull() }
                    .toMutableList()
            }.onFailure {
                steps = mutableListOf()
                Logx.w(TAG, "session restore failed: ${it.message}")
            }
        }
        startedAtMs = System.currentTimeMillis()
        _state.value = RecState(active = true, steps = steps.toList(), currentApp = currentApp())
        startWatchdog()
        postNotification(ctx)
        RecordBubble.show(ctx)
        Logx.i(TAG, "Recording resumed after process death (${steps.size} actions kept)")
    }

    // ------------------------------------------------------------ event feed

    /** Called by JarvisAccessibilityService for every accessibility event. */
    fun onAccessibilityEvent(e: AccessibilityEvent, selfPkg: String) {
        if (!_state.value.active || suppress) return
        val pkg = e.packageName?.toString()
        if (pkg == selfPkg || pkg == "com.android.systemui") return
        bump { it.copy(eventsSeen = it.eventsSeen + 1, currentApp = pkg ?: it.currentApp) }

        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChange(pkg)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onNodeEvent("UI_CLICK", "Tap", e.source)
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> onNodeEvent("UI_LONG_PRESS", "Long-press", e.source)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> onTextEvent(e.source, e.text)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onScrollEvent(e)
        }
    }

    /** App switches ALWAYS become LAUNCH_APP actions - including the first one
     *  (the v1/v2 bug where the opening app was never recorded). */
    private fun onWindowChange(pkg: String?) {
        if (pkg == null || pkg == lastWindowPkg) return
        lastWindowPkg = pkg
        add(
            SkillAction(
                id = newActionId(),
                type = "LAUNCH_APP",
                appPackage = pkg,
            ),
            label = "Open ${pkg.substringBefore('.')}",
        )
    }

    private fun onNodeEvent(type: String, verb: String, src: AccessibilityNodeInfo?) {
        if (src == null) {
            // The app fired the event without a node - nothing to anchor to.
            markUnusable()
            return
        }
        val rect = Rect()
        runCatching { src.getBoundsInScreen(rect) }.getOrNull()
        if (rect.isEmpty) {
            markUnusable()
            return
        }
        if (runCatching { src.isPassword }.getOrDefault(false)) {
            warn("Skipped a password field - JARVIS never records passwords. Add the text action in the builder.")
            return
        }
        val metrics = MetricsHolder.get()
        val target = ElementTarget(
            mode = "MIXED",
            text = src.text?.toString()?.takeIf { it.isNotBlank() }?.take(80),
            desc = src.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.take(60),
            viewId = src.viewIdResourceName,
            className = src.className?.toString()?.substringAfterLast('.'),
            fx = if (metrics.first > 0) rect.centerX().toFloat() / metrics.first else null,
            fy = if (metrics.second > 0) rect.centerY().toFloat() / metrics.second else null,
            pkg = lastWindowPkg,
        )
        add(
            SkillAction(id = newActionId(), type = type, target = target),
            label = "$verb ${target.describe()}",
        )
    }

    private fun onTextEvent(src: AccessibilityNodeInfo?, texts: List<CharSequence>?) {
        if (src == null) {
            markUnusable()
            return
        }
        if (runCatching { src.isPassword }.getOrDefault(false)) {
            warn("Skipped a password field - JARVIS never records passwords. Add the text action in the builder.")
            return
        }
        val value = texts?.joinToString("")?.takeIf { it.isNotBlank() } ?: return
        val rect = Rect()
        runCatching { src.getBoundsInScreen(rect) }.getOrNull()
        val metrics = MetricsHolder.get()
        // Debounce: IMEs fire one event per character - keep the LAST value per field.
        val key = "TEXT:" + (src.viewIdResourceName ?: "${rect.left},${rect.top}") + ":" + lastWindowPkg
        val now = System.currentTimeMillis()
        val existing = steps.indexOfLast { it.type == "UI_TEXT" && it.target?.viewId == src.viewIdResourceName && it.target?.pkg == lastWindowPkg }
        val target = ElementTarget(
            mode = "MIXED",
            viewId = src.viewIdResourceName,
            text = null,
            desc = null,
            fx = if (metrics.first > 0 && !rect.isEmpty) rect.centerX().toFloat() / metrics.first else null,
            fy = if (metrics.second > 0 && !rect.isEmpty) rect.centerY().toFloat() / metrics.second else null,
            pkg = lastWindowPkg,
        )
        val action = SkillAction(id = newActionId(), type = "UI_TEXT", target = target, input = value.take(200))
        lastStepKey = key to now
        if (existing >= 0) {
            steps[existing] = action
        } else {
            steps.add(action)
        }
        bump { s -> s.copy(steps = steps.toList(), lastCapture = "Type \"${value.take(24)}\"") }
        persistSession()
        updateNotification()
    }

    private fun onScrollEvent(e: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        val (k, t) = lastStepKey ?: ("" to 0L)
        if (k == "SCROLL" && now - t < 700) return // debounce fling bursts
        lastStepKey = "SCROLL" to now
        // scrollDeltaY > 0 = scrolling back toward earlier content (API 28+).
        val dy = runCatching { e.scrollDeltaY }.getOrDefault(0f)
        val dir = if (dy > 0f) "up" else "down"
        add(
            SkillAction(id = newActionId(), type = "SCROLL", dir = dir, pkg = lastWindowPkg),
            label = "Scroll $dir",
        )
    }

    // -------------------------------------------------------------- plumbing

    private fun add(action: SkillAction, label: String) {
        val now = System.currentTimeMillis()
        val (k, t) = lastStepKey ?: ("" to 0L)
        if (k == "${action.type}:$label" && now - t < 500) return // duplicate tap burst
        lastStepKey = "${action.type}:$label" to now
        if (steps.size >= MAX_STEPS) {
            warn("Recording stopped at the $MAX_STEPS-action limit - the rest of the task was not captured.")
            stop()
            return
        }
        steps.add(action)
        bump { it.copy(steps = steps.toList(), lastCapture = label, suspectNoCapture = false) }
        persistSession()
        updateNotification()
    }

    private fun markUnusable() {
        bump {
            val count = it.unusableEvents + 1
            val w = if (count >= 3 && !it.warnings.any { m -> m.startsWith("This app") }) {
                "This app is not reporting taps (games/canvas apps often don't). " +
                    "Build the skill with 'Pick on screen' instead, or the steps will be incomplete."
            } else it.warnings
            it.copy(unusableEvents = count, warnings = w)
        }
    }

    private fun warn(message: String) {
        bump { it.copy(warnings = (it.warnings + message).distinct().takeLast(3)) }
        Logx.w(TAG, message)
    }

    private fun bump(f: (RecState) -> RecState) {
        _state.value = f(_state.value)
    }

    private fun currentApp(): String? = JarvisAccessibilityServiceHolder.pkgProvider?.invoke()

    /** Watchdog: recording with NOTHING captured while the user is elsewhere. */
    private fun startWatchdog() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        s.launch {
            delay(12_000)
            while (isActive && _state.value.active) {
                val st = _state.value
                if (st.steps.isEmpty() && st.unusableEvents == 0 &&
                    System.currentTimeMillis() - startedAtMs > 12_000
                ) {
                    bump {
                        it.copy(
                            suspectNoCapture = true,
                            warnings = (it.warnings + "Nothing captured yet. If you already tapped around: this app (or the " +
                                "accessibility service) is not sending events. Check the a11y toggle, or use 'Pick on screen'.").takeLast(3),
                        )
                    }
                }
                delay(5_000)
            }
        }
    }

    // ----------------------------------------------------------- persistence

    private fun sessionFile(ctx: Context) = File(File(ctx.filesDir, "skills").apply { mkdirs() }, ".session.jsonl")

    private fun persistSession() {
        runCatching {
            val f = sessionFile(JarvisApp.instance)
            f.writeText(steps.joinToString("\n") { json.encodeToString(SkillAction.serializer(), it) })
        }.onFailure { Logx.w(TAG, "session persist failed: ${it.message}") }
    }

    private fun newActionId(): String = "r${System.currentTimeMillis()}${(0..999).random()}"

    // ---------------------------------------------------------- notification

    private fun postNotification(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val stopPi = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, SkillRecReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, JarvisApp.CH_AGENT)
            .setContentTitle("Recording skill")
            .setContentText("${steps.size} actions captured - use your phone normally, then stop")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop recording", stopPi)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    private fun updateNotification() = postNotification(JarvisApp.instance)

    private fun cancelNotification(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }
}

/** Screen metrics accessor wired by the accessibility service (fraction math). */
object MetricsHolder {
    @Volatile var provider: (() -> Pair<Int, Int>)? = null
    fun get(): Pair<Int, Int> = provider?.invoke() ?: (0 to 0)
}

/** Broadcast receiver backing the "Stop recording" notification action. */
class SkillRecReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SkillRecorder.ACTION_STOP) SkillRecorder.stop()
    }
}

/** Bridges the recorder to the accessibility service without a hard dependency cycle. */
object JarvisAccessibilityServiceHolder {
    var pkgProvider: (() -> String?)? = null
    var observeProvider: ((Int) -> com.jarvis.mobile.core.observer.ScreenObservation?)? = null
}

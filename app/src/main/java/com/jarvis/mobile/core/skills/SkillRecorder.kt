package com.jarvis.mobile.core.skills

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Skill recorder: turns the user's live UI actions into replayable [SkillStep]s.
 *
 * While active, the accessibility service feeds events here; we keep ONLY
 * meaningful interactions (taps, long-press, text entry, scrolls, app switches)
 * from OTHER apps - never JARVIS itself, never the system UI shade.
 * The agent's own actions are suppressed via [suppress] so a recording made
 * during a task does not contain JARVIS pressing its own buttons.
 */
object SkillRecorder {
    private const val TAG = "skill-rec"
    private const val NOTIF_ID = 4242
    private const val MAX_STEPS = 60

    data class RecordingState(
        val active: Boolean = false,
        val steps: List<SkillStep> = emptyList(),
        val finishedSteps: List<SkillStep>? = null, // set after stop - pending review
        val startedAtMs: Long = 0L,
    )

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    /** True while the agent or a skill replay performs actions - events then are OURS, not the user's. */
    @Volatile var suppress: Boolean = false

    @Volatile private var lastTextKey: String? = null
    @Volatile private var lastTextAtMs: Long = 0L
    @Volatile private var lastScrollAtMs: Long = 0L
    @Volatile private var lastPkg: String? = null

    fun start() {
        _state.value = RecordingState(active = true, startedAtMs = System.currentTimeMillis())
        lastPkg = null
        suppress = false
        postNotification(JarvisApp.instance, 0)
        Logx.i(TAG, "Recording started")
    }

    /** Stop recording; steps stay in [RecordingState.finishedSteps] until reviewed/discarded. */
    fun stop() {
        val cur = _state.value
        if (!cur.active) return
        _state.value = cur.copy(active = false, finishedSteps = cur.steps)
        cancelNotification(JarvisApp.instance)
        Logx.i(TAG, "Recording stopped: ${cur.steps.size} steps")
    }

    /** Review consumed (saved or discarded) - clears the finished buffer. */
    fun consumeFinished() {
        _state.value = _state.value.copy(finishedSteps = null)
    }

    fun discard() {
        _state.value = _state.value.copy(finishedSteps = null, steps = emptyList())
    }

    /**
     * Event sink called from JarvisAccessibilityService. Event types available are
     * declared in accessibility_service_config.xml (clicked/textChanged already included).
     */
    fun onAccessibilityEvent(e: AccessibilityEvent, selfPackage: String) {
        val st = _state.value
        if (!st.active || suppress) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == selfPackage || pkg == "com.android.systemui") return

        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                capture(e, pkg, "TAP")?.let { addStep(it) }
            }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
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
                .setContentText("$stepCount steps captured - use your phone normally, then stop")
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

/** Manifest-registered receiver backing the "Stop recording" notification action. */
class SkillRecReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ACTION_STOP) SkillRecorder.stop()
    }

    companion object {
        const val ACTION_STOP = "com.jarvis.mobile.STOP_SKILL_RECORDING"
    }
}

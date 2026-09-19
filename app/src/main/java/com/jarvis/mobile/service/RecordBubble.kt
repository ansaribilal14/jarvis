package com.jarvis.mobile.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.skills.SkillRecorder
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Floating REC pill (AutoX-style overlay controls): shown while a recording is
 * active, draggable, ALWAYS on top so the user sees proof that capture is live
 * from any app. Two layers of feedback:
 *  - the pill shows the live step count (every captured tap increments it), so
 *    "is it recording?" is answered at a glance instead of blind trust;
 *  - tapping it expands a compact card with the honest capture-layer status and
 *    a Stop button (the recorder notification keeps its own stop action too).
 *
 * Views are attached from the application context with TYPE_APPLICATION_OVERLAY
 * (the same window type AutoX's floating menu uses); the process is guaranteed
 * alive while the accessibility service is bound - which recording requires
 * anyway - and the bubble re-attaches automatically after a process kill via
 * SkillRecorder.resumeIfNeeded().
 */
object RecordBubble {

    private const val TAG = "rec-bubble"
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var pill: LinearLayout? = null
    private var pillLabel: TextView? = null
    private var expanded: LinearLayout? = null
    private var statusView: TextView? = null
    private var stepsView: TextView? = null
    private var started = false

    fun show(context: Context) {
        if (pill != null || started) return
        if (!android.provider.Settings.canDrawOverlays(context)) {
            Logx.i(TAG, "overlay permission missing - REC bubble skipped (notification still active)")
            return
        }
        started = true
        main.post { attach(context) }
        scope.launch {
            SkillRecorder.state.collect { st ->
                main.post {
                    if (pill == null) return@post
                    pillLabel?.text = "REC ${st.steps.size}"
                    statusView?.text = statusText(st)
                    stepsView?.text = "${st.steps.size} steps captured"
                }
            }
        }
    }

    fun hide(context: Context) {
        started = false
        main.post {
            collapse()
            pill?.let { runCatching { wm().removeView(it) } }
            pill = null
            pillLabel = null
        }
    }

    private fun wm(): WindowManager =
        JarvisApp.instance.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @SuppressLint("ClickableViewAccessibility")
    private fun attach(context: Context) {
        runCatching {
            val density = context.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(24)
                y = dp(96)
            }

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(0xE612161C.toInt())
                    setStroke(dp(1), 0xFFE4574F.toInt())
                }
                setPadding(dp(10), dp(6), dp(12), dp(6))
                gravity = Gravity.CENTER_VERTICAL
            }
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) }
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE4574F.toInt()) }
            }
            val label = TextView(context).apply {
                text = "REC 0"
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            row.addView(dot)
            row.addView(label)

            var downX = 0f; var downY = 0f; var moved = false
            row.setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; moved = false; true }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downX; val dy = ev.rawY - downY
                        if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) moved = true
                        params.x = (ev.rawX - v.width / 2).toInt().coerceAtLeast(0)
                        params.y = (ev.rawY - v.height / 2).toInt().coerceAtLeast(0)
                        runCatching { wm().updateViewLayout(v, params) }
                        true
                    }
                    MotionEvent.ACTION_UP -> { if (!moved) toggleExpand(context); true }
                    else -> false
                }
            }

            wm().addView(row, params)
            pill = row
            pillLabel = label
            Logx.i(TAG, "REC bubble shown")
        }.onFailure { Logx.w(TAG, "bubble attach failed: ${it.message}"); started = false }
    }

    private fun statusText(st: SkillRecorder.RecordingState): String = when {
        st.precisionActive ->
            "Precision touch capture LIVE - every tap in every app is recorded (${st.rawTaps} raw touches seen). Every tap and drag is drawn on screen."
        else ->
            "App-event capture - buttons, typing, scrolls, app switches. For raw-tap precision + ink visualization, set up the built-in shell (Skills screen) - no other app needed."
    }

    private fun toggleExpand(context: Context) {
        if (expanded != null) { collapse(); return }
        runCatching {
            val density = context.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(0xEE12161C.toInt())
                    setStroke(dp(1), 0xFFE4574F.toInt())
                }
            }
            val status = TextView(context).apply {
                setTextColor(0xFFE8EFF2.toInt())
                textSize = 12f
                text = statusText(SkillRecorder.state.value)
            }
            val steps = TextView(context).apply {
                setTextColor(0xFF93A5AF.toInt())
                textSize = 12f
                text = "${SkillRecorder.state.value.steps.size} steps captured"
                setPadding(0, dp(4), 0, 0)
            }
            val stop = TextView(context).apply {
                text = "Stop recording"
                setTextColor(0xFFE4574F.toInt())
                textSize = 13f
                setPadding(0, dp(10), 0, 0)
                setOnClickListener {
                    collapse()
                    SkillRecorder.stop()
                }
            }
            card.addView(status)
            card.addView(steps)
            card.addView(stop)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.CENTER }

            wm().addView(card, params)
            expanded = card
            statusView = status
            stepsView = steps
        }.onFailure { Logx.w(TAG, "expand failed: ${it.message}") }
    }

    private fun collapse() {
        expanded?.let { runCatching { wm().removeView(it) } }
        expanded = null
        statusView = null
        stepsView = null
    }
}

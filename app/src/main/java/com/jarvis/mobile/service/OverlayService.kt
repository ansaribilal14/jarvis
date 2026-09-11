package com.jarvis.mobile.service

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.agent.AgentEngine
import com.jarvis.mobile.util.Logx

/**
 * Minimal Jarvis floating bubble (spec: BUBBLE / OVERLAY).
 * Draggable orb; tap opens a compact input panel to dispatch a task from any
 * app. Non-obstructive: collapses to a 40dp dot.
 */
class OverlayService : androidx.lifecycle.LifecycleService() {

    private var wm: WindowManager? = null
    private var orb: View? = null
    private var panel: LinearLayout? = null

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onCreate() {
        super.onCreate()
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showOrb()
        Logx.i("overlay", "Bubble shown")
    }

    private fun startAsForeground() {
        val notif = androidx.core.app.NotificationCompat.Builder(this, JarvisApp.CH_AGENT)
            .setSmallIcon(com.jarvis.mobile.R.drawable.ic_tile_orb)
            .setContentText("Jarvis bubble active")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(43, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(43, notif)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOrb() {
        val params = WindowManager.LayoutParams(
            dp(40), dp(40),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(160)
        }
        val orbView = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(0xFF3FD8C2.toInt(), 0xFF12776B.toInt())
                setStroke(dp(1), 0x66000000)
            }
            alpha = 0.9f
        }
        var downX = 0f; var downY = 0f; var moved = false
        orbView.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = (ev.rawX - v.width / 2).toInt().coerceAtLeast(0)
                    params.y = (ev.rawY - v.height / 2).toInt().coerceAtLeast(0)
                    wm?.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) { togglePanel(); true } else false
                }
                else -> false
            }
        }
        orb = orbView
        runCatching { wm?.addView(orbView, params) }.onFailure { Logx.e("overlay", "addView failed: ${it.message}") }
    }

    private fun togglePanel() {
        if (panel != null) {
            hidePanel()
        } else {
            showPanel()
        }
    }

    private fun showPanel() {
        val ctx = this
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xEE0E1113.toInt())
                setStroke(dp(1), 0x333FD8C2)
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val input = EditText(ctx).apply {
            hint = "Ask Jarvis…"
            setTextColor(0xFFEAF4F2.toInt())
            setHintTextColor(0xFF5A6B78.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFF161B22.toInt())
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            textSize = 14f
            maxLines = 2
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val send = Button(ctx).apply {
            text = "Run"
            textSize = 13f
            setTextColor(0xFF062A25.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFF3FD8C2.toInt())
            }
            setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isNotBlank()) {
                    AgentEngine.runGoal(text, source = "QUICK")
                    hidePanel()
                }
            }
        }
        val close = Button(ctx).apply {
            text = "✕"
            textSize = 13f
            setTextColor(0xFF9FB3BC.toInt())
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFF161B22.toInt()) }
            setOnClickListener { hidePanel() }
        }
        row.addView(send, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row.addView(close, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        card.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        layout.addView(card)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }
        panel = layout
        runCatching { wm?.addView(layout, params) }
        input.requestFocus()
    }

    private fun hidePanel() {
        panel?.let { runCatching { wm?.removeView(it) } }
        panel = null
    }

    override fun onDestroy() {
        hidePanel()
        orb?.let { runCatching { wm?.removeView(it) } }
        orb = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java)) }
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}

package com.jarvis.mobile.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.util.Logx

/** Ink trail fade-out time; a finished stroke disappears after this long. */
private const val FADE_MS = 700L
/** At most this many finished strokes stay on screen while fading. */
private const val MAX_FINISHED_STROKES = 4

/**
 * Live recording visualization (the "show what is being recorded" layer):
 * every touch captured by the precision stream is painted over the screen in
 * real time - a glowing dot for the finger, a fading ink trail for every
 * drag, a burst for every tap. Pure passthrough (NOT_TOUCHABLE): the user's
 * touches are NEVER intercepted, only visualized.
 *
 * Two overlay windows:
 *  - the ink canvas: full-screen, edge to edge, no input;
 *  - the instruction card: an auto-fading "everything is recording" banner
 *    shown when a recording starts from the home screen / another app.
 *
 * Events arrive from the getevent reader thread ([TouchStreamRecorder]);
 * rendering happens on the main thread only.
 */
object RecordingInk {

    private const val TAG = "rec-ink"
    private const val ACCENT = 0xFFE4574F.toInt() // same red as the REC bubble
    private const val INSTRUCTIONS_MS = 6_000L

    private val main = Handler(Looper.getMainLooper())
    private var inkView: InkView? = null
    private var instructionsView: View? = null

    // ------------------------------------------------------------- lifecycle

    fun show(context: Context) {
        main.post {
            if (inkView != null) return@post
            if (!android.provider.Settings.canDrawOverlays(context)) {
                Logx.i(TAG, "overlay permission missing - ink visualization skipped")
                return@post
            }
            runCatching {
                val v = InkView(context)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                )
                wm().addView(v, params)
                inkView = v
            }.onFailure { Logx.w(TAG, "ink attach failed: ${it.message}") }
        }
    }

    fun hide() {
        main.post {
            inkView?.let { runCatching { wm().removeView(it) } }
            inkView = null
            hideInstructions()
        }
    }

    // -------------------------------------------------------- event feed (IO)

    fun onDown(x: Int, y: Int) = main.post { inkView?.beginStroke(x.toFloat(), y.toFloat()) }
    fun onMove(x: Int, y: Int) = main.post { inkView?.extendStroke(x.toFloat(), y.toFloat()) }
    fun onUp(x: Int, y: Int) = main.post { inkView?.endStroke(x.toFloat(), y.toFloat()) }

    // ------------------------------------------------------ instruction card

    /** Auto-fading banner: "everything is recording - your taps are highlighted". */
    fun showInstructions(context: Context, title: String, body: String) {
        main.post {
            hideInstructions()
            if (!android.provider.Settings.canDrawOverlays(context)) return@post
            runCatching {
                val density = context.resources.displayMetrics.density
                fun dp(v: Int) = (v * density).toInt()

                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(14), dp(18), dp(14))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(0xE612161C.toInt())
                        setStroke(dp(1), ACCENT)
                    }
                    alpha = 0f
                }
                val t = TextView(context).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 15f
                }
                val b = TextView(context).apply {
                    text = body
                    setTextColor(0xFFB9C4CC.toInt())
                    textSize = 12.5f
                    setPadding(0, dp(4), 0, 0)
                }
                card.addView(t)
                card.addView(b)

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(48)
                }

                wm().addView(card, params)
                instructionsView = card
                card.animate().alpha(1f).setDuration(250).start()
                main.postDelayed({
                    card.animate().alpha(0f).setDuration(600).withEndAction {
                        runCatching { wm().removeView(card) }
                        if (instructionsView === card) instructionsView = null
                    }.start()
                }, INSTRUCTIONS_MS)
            }.onFailure { Logx.w(TAG, "instructions attach failed: ${it.message}") }
        }
    }

    private fun hideInstructions() {
        instructionsView?.let { runCatching { wm().removeView(it) } }
        instructionsView = null
    }

    private fun wm(): WindowManager =
        JarvisApp.instance.getSystemService(Context.WINDOW_SERVICE) as WindowManager
}

/**
 * The ink canvas: strokes with age-based fade + a glowing fingertip dot.
 * Self-invalidates while anything is on screen.
 */
@SuppressLint("ViewConstructor")
private class InkView(context: Context) : View(context) {

    private class Stroke(val points: ArrayList<Float> = ArrayList()) {
        var endedAt: Long = 0L // 0 = still active
    }

    private val lock = Any()
    private val strokes = ArrayList<Stroke>()
    private var active: Stroke? = null
    private val animator = Runnable {
        if (needsFrame()) postInvalidateOnAnimation()
    }

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 9f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xE6E4574F.toInt()
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 22f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0x33E4574F.toInt()
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xF2E4574F.toInt()
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = 0x80E4574F.toInt()
    }

    fun beginStroke(x: Float, y: Float) {
        synchronized(lock) {
            active = Stroke().apply { points.add(x); points.add(y) }
            strokes.add(active!!)
            trimLocked()
        }
        invalidateSelf()
    }

    fun extendStroke(x: Float, y: Float) {
        synchronized(lock) {
            val s = active ?: return
            s.points.add(x)
            s.points.add(y)
        }
        invalidateSelf()
    }

    fun endStroke(x: Float, y: Float) {
        synchronized(lock) {
            val s = active ?: return
            s.points.add(x)
            s.points.add(y)
            s.endedAt = System.currentTimeMillis()
            active = null
        }
        invalidateSelf()
    }

    private fun trimLocked() {
        val finished = strokes.count { it.endedAt > 0 }
        if (finished > MAX_FINISHED_STROKES) {
            val firstFinished = strokes.indexOfFirst { it.endedAt > 0 }
            if (firstFinished >= 0) strokes.removeAt(firstFinished)
        }
    }

    private fun invalidateSelf() {
        mainHandler.removeCallbacks(animator)
        invalidate()
        postInvalidateOnAnimation()
    }

    private fun needsFrame(): Boolean {
        val now = System.currentTimeMillis()
        return synchronized(lock) { strokes.any { it.endedAt == 0L || now - it.endedAt < FADE_MS } }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        val expired = ArrayList<Stroke>()
        synchronized(lock) {
            strokes.forEach { stroke ->
                val alpha = if (stroke.endedAt == 0L) 1f
                else {
                    val age = now - stroke.endedAt
                    if (age >= FADE_MS) 0f else 1f - age.toFloat() / FADE_MS
                }
                if (alpha <= 0f) {
                    if (stroke.endedAt > 0L) expired.add(stroke)
                    return@forEach
                }
                val pts = stroke.points.toFloatArray()
                if (pts.size >= 4) {
                    inkPaint.alpha = (inkPaint.colorAlpha() * alpha).toInt()
                    glowPaint.alpha = (glowAlphaBase * alpha).toInt()
                    if (pts.size == 4) {
                        canvas.drawLine(pts[0], pts[1], pts[2], pts[3], glowPaint)
                        canvas.drawLine(pts[0], pts[1], pts[2], pts[3], inkPaint)
                    } else {
                        canvas.drawPoints(pts, glowPaint) // joints
                        canvas.drawLines(pts, glowPaint)
                        canvas.drawLines(pts, inkPaint)
                    }
                }
                val cx = pts[pts.size - 2]
                val cy = pts[pts.size - 1]
                if (stroke.endedAt == 0L) {
                    // fingertip glow
                    haloPaint.alpha = (haloAlphaBase * alpha).toInt()
                    canvas.drawCircle(cx, cy, 34f, haloPaint)
                    dotPaint.alpha = (dotAlphaBase * alpha).toInt()
                    canvas.drawCircle(cx, cy, 12f, dotPaint)
                }
            }
            strokes.removeAll(expired)
        }
        if (needsFrame()) postInvalidateOnAnimation() else mainHandler.post(animator)
    }

    private fun Paint.colorAlpha(): Int = (this.color ushr 24) and 0xff
    private val glowAlphaBase = 0x33
    private val dotAlphaBase = 0xF2
    private val haloAlphaBase = 0x80
    private val mainHandler = android.os.Handler(Looper.getMainLooper())
}

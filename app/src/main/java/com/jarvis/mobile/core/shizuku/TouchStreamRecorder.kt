package com.jarvis.mobile.core.shizuku

import android.content.res.Configuration
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.skills.JarvisAccessibilityServiceHolder
import com.jarvis.mobile.core.skills.SkillRecorder
import com.jarvis.mobile.core.skills.SkillStep
import com.jarvis.mobile.util.Logx
import java.io.BufferedReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live `getevent -t` touch-stream recorder running through [ShizukuBridge].
 *
 * This is the layer that finally makes "record every single click" TRUE on
 * every device: the kernel touchscreen stream carries every contact with real
 * coordinates, in every app, whether or not the app emits accessibility
 * events. Reading it never consumes a single touch - the user interacts 100%
 * normally while recording (unlike the v1.9/v1.10 framework-interception
 * attempts this replaces).
 */
object TouchStreamRecorder {

    private const val TAG = "touch-stream"
    private const val TAP_MAX_MS = 350L
    private const val LONGPRESS_MIN_MS = 550L
    private const val MOVE_PX = 40

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile private var decoder: TouchStreamAnalyzer? = null

    fun isRunning(): Boolean = running.get()

    /**
     * Start the stream. Returns false (with a log) when Shizuku is not ready or
     * no touchscreen can be probed - the caller keeps the event layer running
     * either way.
     */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        if (!ShizukuBridge.ready()) {
            running.set(false)
            Logx.w(TAG, "Shizuku not ready - precision capture stays off")
            return false
        }
        val device = probeTouchDevice()
        if (device == null) {
            running.set(false)
            Logx.w(TAG, "no touchscreen found via getevent -pl")
            return false
        }
        val metrics = JarvisApp.instance.resources.displayMetrics
        val rot = JarvisApp.instance.resources.configuration.orientation
        val (w, h) = if (rot == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            maxOf(metrics.widthPixels, metrics.heightPixels) to minOf(metrics.widthPixels, metrics.heightPixels)
        else metrics.widthPixels to metrics.heightPixels
        decoder = TouchStreamAnalyzer(w, h, device.maxX, device.maxY)

        val t = Thread({
            var reader: BufferedReader? = null
            var process: Process? = null
            try {
                val p = Shizuku.newProcess(arrayOf("getevent", "-t", device.path), null, "/")
                process = p
                reader = p.inputStream.bufferedReader()
                SkillRecorder.setPrecision(true)
                reader.forEachLine { line ->
                    if (!running.get()) return@forEachLine
                    val now = System.currentTimeMillis()
                    decoder?.onLine(line, now)?.forEach { onSignal(it) }
                }
            } catch (e: Exception) {
                Logx.w(TAG, "stream ended: ${e.message}")
            } finally {
                runCatching { reader?.close() }
                runCatching { process?.destroy() }
                decoder?.flush(System.currentTimeMillis())?.forEach { onSignal(it) }
                SkillRecorder.setPrecision(false)
                running.set(false)
                Logx.i(TAG, "touch stream stopped")
            }
        }, "jarvis-getevent")
        t.isDaemon = true
        t.start()
        thread = t
        Logx.i(TAG, "precision capture live: ${device.name} (${device.maxX}x${device.maxY} raw)")
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // The reader thread exits on its next line or process death; destroy() is
        // also attempted from its finally block, but do it here too for latency.
        thread?.interrupt()
        thread = null
        decoder = null
    }

    /** Probe `getevent -pl` for the touchscreen with the widest X range. */
    private fun probeTouchDevice(): TouchStreamDecoder.TouchDevice? {
        val out = ShizukuBridge.exec("getevent", "-pl") ?: return null
        return TouchStreamDecoder.parseDeviceProbe(out)
    }

    /** Convert a decoded touch primitive into recorder steps (Tasker classification). */
    private fun onSignal(sig: TouchStreamDecoder.TouchEvent) {
        when (sig) {
            is TouchStreamDecoder.TouchEvent.Down -> {
                // Open a pending tap: the accessibility-event fusion layer can still
                // upgrade it to a semantic step if the app reports the click.
                SkillRecorder.onRawDown(System.currentTimeMillis(), sig.x.toFloat(), sig.y.toFloat())
            }
            is TouchStreamDecoder.TouchEvent.Up -> {
                val step = classify(sig) ?: return // movement without direction info: skip
                when (step.type) {
                    "TAP" -> Unit // pending tap from Down already covers it; fusion decides
                    else -> SkillRecorder.onRawGesture(step) // LONG_PRESS / SWIPE commit directly
                }
            }
        }
    }

    private fun classify(sig: TouchStreamDecoder.TouchEvent.Up): SkillStep? {
        // We need the Down position for direction math; the recorder's last raw
        // down is the source of truth (kept by the merger), but the decoder also
        // reported movedPx - re-derive using the merger's knowledge indirectly by
        // asking the recorder for the most recent down point.
        val down = SkillRecorder.lastRawDownPx() ?: return null
        val (dx, dy) = sig.x - down.first to sig.y - down.second
        val moved = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
        return when {
            sig.movedPx >= MOVE_PX && moved >= MOVE_PX -> {
                val dir = when {
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) -> if (dx > 0) "right" else "left"
                    else -> if (dy > 0) "fwd" else "back"
                }
                SkillStep(type = "SCROLL", dir = dir, x = down.first + dx / 2, y = down.second + dy / 2, t = System.currentTimeMillis())
            }
            sig.durationMs >= LONGPRESS_MIN_MS -> SkillStep(type = "LONG_PRESS", x = sig.x, y = sig.y, t = System.currentTimeMillis())
            sig.durationMs <= TAP_MAX_MS -> SkillStep(type = "TAP", x = sig.x, y = sig.y, t = System.currentTimeMillis())
            else -> null // mid-duration, non-moving: ambiguous press - skip (event layer may label it)
        }
    }
}

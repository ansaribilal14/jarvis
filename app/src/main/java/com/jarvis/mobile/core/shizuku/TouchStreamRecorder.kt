package com.jarvis.mobile.core.shizuku

import android.content.res.Configuration
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.skills.SkillRecorder
import com.jarvis.mobile.core.skills.SkillStep
import com.jarvis.mobile.service.RecordingInk
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live `getevent -t` touch-stream recorder running through the privileged
 * shell facade ([PrivilegedShell]): the BUILT-IN shell (in-app wireless
 * debugging pairing, started by JARVIS itself) or the external Shizuku app.
 * This is the layer that makes "record every single click" TRUE on every
 * device: the kernel touchscreen stream carries every contact with real
 * coordinates, in every app, whether or not the app emits accessibility
 * events. Reading it never consumes a single touch - the user interacts 100%
 * normally while recording.
 *
 * The same decoded stream also drives [RecordingInk] - the live on-screen
 * visualization of every tap and drag, so the user SEES what is being
 * recorded while it happens.
 */
object TouchStreamRecorder {

    private const val TAG = "touch-stream"
    private const val TAP_MAX_MS = 350L
    private const val LONGPRESS_MIN_MS = 550L
    private const val MOVE_PX = 40

    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var decoder: TouchStreamAnalyzer? = null
    @Volatile private var handle: PrivShell.StreamHandle? = null

    fun isRunning(): Boolean = running.get()

    /**
     * Start the stream. Never throws; on any failure the honest "precision off"
     * state is reported and the event layer stays the capture layer.
     */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        scope.launch {
            try {
                val acquired = PrivilegedShell.acquire()
                    ?: throw IllegalStateException(PrivilegedShell.missingReason())
                val shell = acquired.shell
                Logx.i(TAG, "precision capture via ${acquired.layer}")

                val probe = shell.exec("getevent", "-pl")
                    ?: throw IllegalStateException("device probe failed (shell not ready?)")
                val device = TouchStreamDecoder.parseDeviceProbe(probe)
                    ?: throw IllegalStateException("no touchscreen found via getevent -pl")
                val metrics = JarvisApp.instance.resources.displayMetrics
                val landscape = JarvisApp.instance.resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE
                val w = if (landscape) maxOf(metrics.widthPixels, metrics.heightPixels) else metrics.widthPixels
                val h = if (landscape) minOf(metrics.widthPixels, metrics.heightPixels) else metrics.heightPixels
                decoder = TouchStreamAnalyzer(w, h, device.maxX, device.maxY)

                val stream = shell.stream("getevent", "-t", device.path)
                    ?: throw IllegalStateException("could not open getevent stream")
                handle = stream.second
                RecordingInk.show(JarvisApp.instance)
                SkillRecorder.setPrecision(true, layer = acquired.layer.name.lowercase())
                Logx.i(TAG, "precision capture live: ${device.name} (${device.maxX}x${device.maxY} raw)")
                stream.first.forEachLine { line ->
                    if (!running.get()) return@forEachLine
                    val now = System.currentTimeMillis()
                    decoder?.onLine(line, now)?.forEach { onSignal(it) }
                }
            } catch (e: Exception) {
                Logx.w(TAG, "touch stream ended: ${e.message}")
            } finally {
                decoder?.flush(System.currentTimeMillis())?.forEach { onSignal(it) }
                SkillRecorder.setPrecision(false)
                RecordingInk.hide()
                running.set(false)
            }
        }
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        handle?.stop() // closes the stream (unblocks the reader) + kills the shell process
        handle = null
        decoder = null
        RecordingInk.hide()
    }

    /** Convert a decoded touch primitive into recorder steps + live ink. */
    private fun onSignal(sig: TouchStreamDecoder.TouchEvent) {
        when (sig) {
            is TouchStreamDecoder.TouchEvent.Down -> {
                RecordingInk.onDown(sig.x, sig.y)
                // Open a pending tap: the accessibility-event fusion layer can still
                // upgrade it to a semantic step if the app reports the click.
                SkillRecorder.onRawDown(System.currentTimeMillis(), sig.x.toFloat(), sig.y.toFloat())
            }
            is TouchStreamDecoder.TouchEvent.Move -> {
                RecordingInk.onMove(sig.x, sig.y)
            }
            is TouchStreamDecoder.TouchEvent.Up -> {
                RecordingInk.onUp(sig.x, sig.y)
                val step = classify(sig) ?: return // ambiguous mid-duration press: skip
                when (step.type) {
                    "TAP" -> Unit // the pending tap from Down already covers it; fusion decides
                    else -> SkillRecorder.onRawGesture(step) // LONG_PRESS / SCROLL commit directly
                }
            }
        }
    }

    private fun classify(sig: TouchStreamDecoder.TouchEvent.Up): SkillStep? {
        val down = SkillRecorder.lastRawDownPx() ?: return null
        val dx = sig.x - down.first
        val dy = sig.y - down.second
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
            else -> null
        }
    }
}

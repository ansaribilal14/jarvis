package com.jarvis.mobile.core.skills

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.jarvis.mobile.service.RecordingInk
import com.jarvis.mobile.util.Logx

/**
 * Where a recording begins: the launcher home screen, or a specific app the
 * user picks from the dropdown. Tasker-style macros conceptually "start
 * somewhere" - this makes the start explicit instead of silently recording
 * whatever screen JARVIS happened to be on.
 */
sealed interface RecordStartTarget {
    data object Home : RecordStartTarget
    data class App(val pkg: String, val label: String) : RecordStartTarget
}

/**
 * Orchestrates a recording that starts OUTSIDE of JARVIS:
 *
 *   tap "Record" -> pick start (home / app) -> Start
 *     1. recorder arms (capture is live before we leave the app)
 *     2. navigate home or launch the chosen app
 *     3. show the auto-fading "everything is recording" instructions card
 *     4. every tap/drag is now visualized on screen by [RecordingInk]
 *
 * Stopping stays where it always was: the floating REC bubble, the
 * notification action, or the Skills screen.
 */
object RecordLauncher {

    private const val TAG = "record-launch"
    private const val NAVIGATE_DELAY_MS = 350L
    private const val INSTRUCTIONS_DELAY_MS = 1_200L

    fun begin(context: Context, target: RecordStartTarget) {
        SkillRecorder.start()
        val main = Handler(Looper.getMainLooper())
        main.postDelayed({
            runCatching { navigate(context, target) }
                .onFailure { Logx.w(TAG, "navigation to start failed: ${it.message}") }
        }, NAVIGATE_DELAY_MS)
        main.postDelayed({
            RecordingInk.showInstructions(
                context,
                "Recording everything",
                when (target) {
                    is RecordStartTarget.Home ->
                        "You are on the home screen - every tap and swipe you make is recorded and shown as red ink. " +
                            "Open your app and perform the task; tap the red REC bubble (or the notification) to stop."
                    is RecordStartTarget.App ->
                        "${target.label} is recording - every tap and swipe is captured and drawn as red ink. " +
                            "Do the task now; tap the red REC bubble (or the notification) when done."
                },
            )
        }, INSTRUCTIONS_DELAY_MS)
    }

    private fun navigate(context: Context, target: RecordStartTarget) {
        val intent: Intent = when (target) {
            is RecordStartTarget.Home -> Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            is RecordStartTarget.App ->
                context.packageManager.getLaunchIntentForPackage(target.pkg)?.apply {
                    flags = (flags or Intent.FLAG_ACTIVITY_NEW_TASK) and
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED.inv()
                } ?: error("no launch intent for ${target.pkg}")
        }
        context.startActivity(intent)
    }
}

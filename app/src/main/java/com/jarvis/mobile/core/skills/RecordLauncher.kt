package com.jarvis.mobile.core.skills

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.jarvis.mobile.service.RecordBubble
import com.jarvis.mobile.util.Logx

/**
 * Where a recording begins: the launcher home screen, or a specific app the
 * user picks from the dropdown. Makes the start explicit instead of silently
 * recording whatever screen JARVIS happened to be on.
 *
 * Skills v3: the recorder captures what apps REPORT (clicks, typing, scrolls,
 * app switches) and confirms every capture in the REC bubble the moment it
 * lands. The old "red ink follows every tap" promise is gone - it belonged to
 * the raw-touch mechanisms that never shipped working (docs/SKILLS_V3.md).
 */
sealed interface RecordStartTarget {
    data object Home : RecordStartTarget
    data class App(val pkg: String, val label: String) : RecordStartTarget
}

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
            RecordBubble.showInstructions(
                context,
                "Recording",
                when (target) {
                    is RecordStartTarget.Home ->
                        "Every reported tap, text field, scroll and app switch is captured - check the REC bubble to see captures land. " +
                            "Use your phone normally; tap the REC bubble (or the notification) to stop."
                    is RecordStartTarget.App ->
                        "${target.label} is being recorded - buttons, typing, scrolls and app switches are captured. " +
                            "Do the task now; tap the REC bubble (or the notification) when done."
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

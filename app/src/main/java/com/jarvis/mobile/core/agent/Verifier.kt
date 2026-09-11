package com.jarvis.mobile.core.agent

import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.notifications.NotificationCache
import com.jarvis.mobile.core.tools.PlannedAction
import com.jarvis.mobile.core.tools.ToolRegistry
import com.jarvis.mobile.core.tools.Verification
import com.jarvis.mobile.util.Logx

/**
 * The dedicated verifier (spec: VERIFIER).
 * The planner may NEVER declare success - only verified evidence counts.
 */
object Verifier {

    fun verify(action: PlannedAction, result: com.jarvis.mobile.core.tools.ToolResult): String {
        if (result.status != com.jarvis.mobile.core.tools.ToolStatus.SUCCESS) {
            return "FAILED: ${result.message}"
        }
        return when (result.verified) {
            Verification.VERIFIED -> "VERIFIED: ${result.message}"
            Verification.UNVERIFIED -> "UNVERIFIED: ${result.message} (Android did not expose a confirmation signal; treat as probably-successful but report honestly)"
            Verification.FAILED -> "FAILED: ${result.message}"
            Verification.COULD_NOT_VERIFY -> "COULD_NOT_VERIFY: ${result.message}"
        }
    }

    /** Post-action observation summary fed back to the planner. */
    suspend fun observeAfter(): String? {
        val svc = com.jarvis.mobile.accessibility.JarvisAccessibilityService.INSTANCE ?: return null
        val obs = runCatching { svc.observe() }.getOrNull() ?: return null
        return obs.toCompact(maxElements = 24)
    }

    /** Is the action's expected outcome visibly present now? Used for honest reporting. */
    fun outcomeTrusted(action: PlannedAction, result: com.jarvis.mobile.core.tools.ToolResult): Boolean =
        result.verified == Verification.VERIFIED

    fun logStep(idx: Int, action: PlannedAction, verdict: String) {
        Logx.i("verify", "step $idx ${action.tool} → $verdict")
    }
}

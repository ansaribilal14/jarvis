package com.jarvis.mobile.core.tools

import kotlinx.serialization.json.JsonObject

/** Risk classification drives the confirmation policy (spec: HIGH-RISK ACTIONS). */
enum class Risk { LOW, MEDIUM, HIGH }

enum class ToolStatus { SUCCESS, FAILED, BLOCKED, REQUIRES_CONFIRMATION, UNAVAILABLE }

enum class Verification { VERIFIED, UNVERIFIED, FAILED, COULD_NOT_VERIFY }

data class ParamSpec(
    val name: String,
    val type: String, // string, int, bool
    val required: Boolean = true,
    val desc: String,
)

data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ParamSpec> = emptyList(),
    val risk: Risk,
    val needsAccessibility: Boolean = false,
)

data class ToolResult(
    val status: ToolStatus,
    val message: String,
    val verified: Verification = Verification.UNVERIFIED,
    val detail: String? = null,          // extra structured info for the planner
    val recoveryHint: String? = null,
) {
    companion object {
        fun ok(msg: String, v: Verification = Verification.UNVERIFIED, detail: String? = null) =
            ToolResult(ToolStatus.SUCCESS, msg, v, detail)
        fun fail(msg: String, hint: String? = null) =
            ToolResult(ToolStatus.FAILED, msg, Verification.FAILED, null, hint)
        fun blocked(msg: String, hint: String? = null) =
            ToolResult(ToolStatus.BLOCKED, msg, Verification.FAILED, null, hint)
        fun unavailable(msg: String, hint: String? = null) =
            ToolResult(ToolStatus.UNAVAILABLE, msg, Verification.COULD_NOT_VERIFY, null, hint)
    }
}

/** Base class for every executable tool in the registry. */
abstract class Tool(val spec: ToolSpec) {
    open fun available(): Boolean = !spec.needsAccessibility || accessibilityReady()

    protected fun accessibilityReady(): Boolean =
        com.jarvis.mobile.accessibility.JarvisAccessibilityService.isReady

    abstract suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

/**
 * Per-execution context handed to tools: the observation the planner acted on
 * (for semantic re-resolution against fresh state) and the execution policy.
 */
class ToolContext(
    val observation: com.jarvis.mobile.core.observer.ScreenObservation?,
    val isRoutine: Boolean = false,
)

/** A resolved call the planner wants to run. */
data class PlannedAction(
    val tool: String,
    val args: JsonObject,
    val thought: String? = null,
)

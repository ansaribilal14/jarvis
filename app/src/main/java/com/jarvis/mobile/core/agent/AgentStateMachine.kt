package com.jarvis.mobile.core.agent

/**
 * Legal status transitions for the agent runtime (expert-review deferred item:
 * explicit runtime state machine). The engine keeps its fail-open behavior - an
 * unexpected transition is logged loudly and still applied so a task can never
 * wedge on a matrix edge case - but every violation is now visible in logcat
 * instead of silently corrupting the UI state.
 *
 * Terminal states (COMPLETED/FAILED/STOPPED) may only go back to work via a new
 * task start (THINKING/ACTING) or reset to IDLE; they can never flow into
 * VERIFYING or WAITING_CONFIRMATION.
 */
object AgentStateMachine {

    val legalNext: Map<AgentStatus, Set<AgentStatus>> = mapOf(
        AgentStatus.IDLE to setOf(
            AgentStatus.IDLE, AgentStatus.THINKING, AgentStatus.ACTING, AgentStatus.STOPPED,
        ),
        AgentStatus.THINKING to setOf(
            AgentStatus.THINKING, AgentStatus.ACTING, AgentStatus.VERIFYING,
            AgentStatus.WAITING_CONFIRMATION, AgentStatus.COMPLETED, AgentStatus.FAILED, AgentStatus.STOPPED,
        ),
        AgentStatus.ACTING to setOf(
            AgentStatus.THINKING, AgentStatus.ACTING, AgentStatus.VERIFYING,
            AgentStatus.WAITING_CONFIRMATION, AgentStatus.COMPLETED, AgentStatus.FAILED, AgentStatus.STOPPED,
        ),
        AgentStatus.VERIFYING to setOf(
            AgentStatus.THINKING, AgentStatus.ACTING, AgentStatus.VERIFYING,
            AgentStatus.WAITING_CONFIRMATION, AgentStatus.COMPLETED, AgentStatus.FAILED, AgentStatus.STOPPED,
        ),
        AgentStatus.WAITING_CONFIRMATION to setOf(
            AgentStatus.THINKING, AgentStatus.ACTING, AgentStatus.VERIFYING,
            AgentStatus.WAITING_CONFIRMATION, AgentStatus.COMPLETED, AgentStatus.FAILED, AgentStatus.STOPPED,
        ),
        AgentStatus.COMPLETED to setOf(
            AgentStatus.COMPLETED, AgentStatus.IDLE, AgentStatus.THINKING, AgentStatus.ACTING, AgentStatus.STOPPED,
        ),
        AgentStatus.FAILED to setOf(
            AgentStatus.FAILED, AgentStatus.IDLE, AgentStatus.THINKING, AgentStatus.ACTING, AgentStatus.STOPPED,
        ),
        AgentStatus.STOPPED to setOf(
            AgentStatus.STOPPED, AgentStatus.IDLE, AgentStatus.THINKING, AgentStatus.ACTING, AgentStatus.STOPPED,
        ),
    )

    fun isLegal(from: AgentStatus, to: AgentStatus): Boolean =
        legalNext[from]?.contains(to) == true
}

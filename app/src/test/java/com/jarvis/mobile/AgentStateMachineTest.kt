package com.jarvis.mobile

import com.jarvis.mobile.core.agent.AgentStatus
import com.jarvis.mobile.core.agent.AgentStateMachine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the agent runtime state machine. The matrix is consulted on
 * every status change (fail-open telemetry in the engine) - these tests pin
 * the invariants that keep the live UI honest.
 */
class AgentStateMachineTest {

    @Test
    fun `every status has a defined row`() {
        for (s in AgentStatus.values()) {
            assertTrue("missing matrix row for $s", AgentStateMachine.legalNext.containsKey(s))
            assertTrue("row for $s must not be empty", AgentStateMachine.legalNext[s]!!.isNotEmpty())
        }
    }

    @Test
    fun `terminal states can never enter VERIFYING or WAITING_CONFIRMATION`() {
        for (terminal in listOf(AgentStatus.COMPLETED, AgentStatus.FAILED, AgentStatus.STOPPED)) {
            assertFalse(AgentStateMachine.isLegal(terminal, AgentStatus.VERIFYING))
            assertFalse(AgentStateMachine.isLegal(terminal, AgentStatus.WAITING_CONFIRMATION))
        }
    }

    @Test
    fun `terminal states can start a new task or reset`() {
        for (terminal in listOf(AgentStatus.COMPLETED, AgentStatus.FAILED, AgentStatus.STOPPED)) {
            assertTrue(AgentStateMachine.isLegal(terminal, AgentStatus.THINKING))
            assertTrue(AgentStateMachine.isLegal(terminal, AgentStatus.IDLE))
        }
    }

    @Test
    fun `IDLE cannot jump straight to VERIFYING`() {
        assertFalse(AgentStateMachine.isLegal(AgentStatus.IDLE, AgentStatus.VERIFYING))
    }

    @Test
    fun `working states may finish any way`() {
        for (working in listOf(
            AgentStatus.THINKING, AgentStatus.ACTING, AgentStatus.VERIFYING,
            AgentStatus.WAITING_CONFIRMATION,
        )) {
            assertTrue(AgentStateMachine.isLegal(working, AgentStatus.COMPLETED))
            assertTrue(AgentStateMachine.isLegal(working, AgentStatus.FAILED))
            assertTrue(AgentStateMachine.isLegal(working, AgentStatus.STOPPED))
        }
    }

    @Test
    fun `user stop is legal from IDLE (stop button races task start)`() {
        assertTrue(AgentStateMachine.isLegal(AgentStatus.IDLE, AgentStatus.STOPPED))
    }

    @Test
    fun `self-transitions are legal (status re-commits with progress fields)`() {
        for (s in AgentStatus.values()) {
            assertTrue("self transition $s", AgentStateMachine.isLegal(s, s))
        }
    }
}

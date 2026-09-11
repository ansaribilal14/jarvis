package com.jarvis.mobile

import com.jarvis.mobile.core.tools.PlannedAction
import com.jarvis.mobile.core.planner.Planner
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerJsonTest {

    @Test
    fun `parses plain json action`() {
        val text = """{"thought":"open it","action":{"tool":"open_app","args":{"app":"Chrome"}}}"""
        val d = Planner.parseDecision(text)
        assertNotNull(d.action)
        assertEquals("open_app", d.action!!.tool)
        assertEquals("Chrome", com.jarvis.mobile.util.JsonX.run { d.action!!.args.str("app") })
    }

    @Test
    fun `parses json wrapped in markdown fences and prose`() {
        val text = """
            Here is my plan:
            ```json
            {"action":{"tool":"tap","args":{"text":"Send"}}}
            ```
            Let me know.
        """.trimIndent()
        val d = Planner.parseDecision(text)
        assertNotNull(d.action)
        assertEquals("tap", d.action!!.tool)
    }

    @Test
    fun `final response without action`() {
        val text = """{"response":"The message was sent and verified."}"""
        val d = Planner.parseDecision(text)
        assertNull(d.action)
        assertEquals("The message was sent and verified.", d.response)
    }

    @Test
    fun `hallucinated tool still parses - registry check happens in engine`() {
        val text = """{"action":{"tool":"nuke_everything","args":{}}}"""
        val d = Planner.parseDecision(text)
        assertNotNull(d.action)
        assertEquals("nuke_everything", d.action!!.tool)
    }

    @Test
    fun `plain prose treated as final response not action`() {
        val d = Planner.parseDecision("I could not find the app you mentioned.")
        assertNull(d.action)
        assertTrue(d.response!!.contains("could not find"))
    }

    @Test
    fun `nested braces inside strings do not break extraction`() {
        val text = """{"thought":"type {x}","action":{"tool":"type_text","args":{"text":"hello {world}"}}}"""
        val d = Planner.parseDecision(text)
        assertNotNull(d.action)
        assertEquals("hello {world}", com.jarvis.mobile.util.JsonX.run { d.action!!.args.str("text") })
    }
}

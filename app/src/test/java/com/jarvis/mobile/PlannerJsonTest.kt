package com.jarvis.mobile

import com.jarvis.mobile.core.tools.PlannedAction
import com.jarvis.mobile.core.tools.ParamSpec
import com.jarvis.mobile.core.tools.ToolSpec
import com.jarvis.mobile.core.planner.Planner
import com.jarvis.mobile.util.JsonX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerJsonTest {

    private val openAppSpec = ToolSpec(
        "open_app", "test",
        listOf(ParamSpec("app", "string", true, "app name")),
        com.jarvis.mobile.core.tools.Risk.LOW,
    )
    private val tapSpec = ToolSpec("tap", "test", emptyList(), com.jarvis.mobile.core.tools.Risk.LOW)
    private val typeSpec = ToolSpec(
        "type_text", "test",
        listOf(ParamSpec("text", "string", true, "text")),
        com.jarvis.mobile.core.tools.Risk.MEDIUM,
    )

    private val specs = mapOf("open_app" to openAppSpec, "tap" to tapSpec, "type_text" to typeSpec)
    private fun parse(text: String) = Planner.parseDecision(text) { specs[it] }

    @Test
    fun `parses plain json action`() {
        val text = """{"thought":"open it","action":{"tool":"open_app","args":{"app":"Chrome"}}}"""
        val d = parse(text)
        assertNotNull(d.action)
        assertEquals("open_app", d.action!!.tool)
        assertEquals("Chrome", JsonX.run { d.action!!.args.str("app") })
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
        val d = parse(text)
        assertNotNull(d.action)
        assertEquals("tap", d.action!!.tool)
    }

    @Test
    fun `final response without action`() {
        val text = """{"response":"The message was sent and verified."}"""
        val d = parse(text)
        assertNull(d.action)
        assertEquals("The message was sent and verified.", d.response)
    }

    @Test
    fun `unknown tool is rejected with honest response`() {
        val text = """{"action":{"tool":"nuke_everything","args":{}}}"""
        val d = parse(text)
        assertNull(d.action)
        assertTrue(d.response!!.contains("invalid action"))
    }

    @Test
    fun `missing required args rejected`() {
        val text = """{"action":{"tool":"open_app","args":{}}}"""
        val d = parse(text)
        assertNull(d.action)
        assertTrue(d.response!!.contains("required arguments"))
    }

    @Test
    fun `plain prose treated as final response not action`() {
        val d = parse("I could not find the app you mentioned.")
        assertNull(d.action)
        assertTrue(d.response!!.contains("could not find"))
    }

    @Test
    fun `nested braces inside strings do not break extraction`() {
        val text = """{"thought":"type {x}","action":{"tool":"type_text","args":{"text":"hello {world}"}}}"""
        val d = parse(text)
        assertNotNull(d.action)
        assertEquals("hello {world}", JsonX.run { d.action!!.args.str("text") })
    }
}

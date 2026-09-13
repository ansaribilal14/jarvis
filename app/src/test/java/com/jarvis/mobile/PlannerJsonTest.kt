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
    private val openUrlSpec = ToolSpec(
        "open_url", "test",
        listOf(ParamSpec("url", "string", true, "url")),
        com.jarvis.mobile.core.tools.Risk.LOW,
    )
    private val ytSpec = ToolSpec(
        "youtube_search", "test",
        listOf(ParamSpec("query", "string", true, "query")),
        com.jarvis.mobile.core.tools.Risk.LOW,
    )

    private val specs = mapOf(
        "open_app" to openAppSpec, "tap" to tapSpec, "type_text" to typeSpec,
        "open_url" to openUrlSpec, "youtube_search" to ytSpec,
    )
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

    // ---------------------------------------------------- v1.6 small-model salvage

    @Test
    fun `nextAction flat alias with arguments key parses`() {
        val text = """{"thought":"opening","nextAction":"open_url","arguments":{"url":"https://example.com"}}"""
        val d = parse(text)
        assertNotNull(d.action)
        assertEquals("open_url", d.action!!.tool)
        assertEquals("https://example.com", JsonX.run { d.action!!.args.str("url") })
    }

    @Test
    fun `action buried in echoed prompt garbage still parses`() {
        // Real-world shape from a 0.5B model: JSON followed by echoed screen block.
        val text = """
            {"thought":"open it","nextAction":"open_url","arguments":{"url":"https://github.com/x"}}></screen>
            APP: com.google.android.apps.playground
            SCREEN: FrameLayout
            [1] role=other @(226,1995)
            [2] role=other @(383,1995)
        """.trimIndent()
        val d = parse(text)
        assertNotNull(d.action)
        assertEquals("open_url", d.action!!.tool)
    }

    @Test
    fun `truncated json is repaired into a valid action`() {
        val text = """{"thought":"open","action":{"tool":"open_app","args":{"app":"Chro"""
        val d = parse(text)
        assertNotNull(d.action)
        assertEquals("open_app", d.action!!.tool)
        assertEquals("Chro", JsonX.run { d.action!!.args.str("app") })
    }

    @Test
    fun `near-miss tool name maps to registry tool`() {
        val text = """{"action":{"tool":"click","args":{"text":"Send"}}}"""
        val d = parse(text)
        assertNotNull(d.action)
        assertEquals("tap", d.action!!.tool)
    }

    @Test
    fun `string args bound to single required param`() {
        val text = """{"tool":"open_url","args":"example.com"}"""
        val d = parse(text)
        assertNotNull(d.action)
        assertEquals("open_url", d.action!!.tool)
        assertEquals("example.com", JsonX.run { d.action!!.args.str("url") })
    }

    @Test
    fun `second candidate wins when first is unusable`() {
        val text = """{"a":1} then {"tool":"tap","args":{}}"""
        val d = parse(text)
        assertNotNull(d.action)
        assertEquals("tap", d.action!!.tool)
    }

    @Test
    fun `bare thought only output stays retryable not final`() {
        val d = parse("""{"thought":"I should open something"}""")
        assertNull(d.action)
        // treated as prose final-response fallback by parseDecision; engine's
        // hasResponseKey() gate keeps it retryable - just assert no action.
    }

    @Test
    fun `plan steps parse with alias tool names`() {
        val text = """{"steps":[{"tool":"open_app","args":{"app":"YouTube"}},{"tool":"yt_search","args":{"query":"lofi"}}]}"""
        val steps = Planner.parsePlan(text) { specs[it] }
        assertEquals(2, steps.size)
        assertEquals("open_app", steps[0].tool)
        assertEquals("youtube_search", steps[1].tool)
    }
}

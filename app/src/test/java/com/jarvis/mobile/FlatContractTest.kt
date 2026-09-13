package com.jarvis.mobile

import com.jarvis.mobile.core.planner.DecisionGrammar
import com.jarvis.mobile.core.planner.Planner
import com.jarvis.mobile.core.skills.SkillRecorder
import com.jarvis.mobile.core.tools.ParamSpec
import com.jarvis.mobile.core.tools.Risk
import com.jarvis.mobile.core.tools.ToolSpec
import com.jarvis.mobile.util.JsonX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.9 flat action contract (MobileAgent-style) + raw-touch stroke classifier
 * + grammar coverage of the flat shapes.
 */
class FlatContractTest {

    private val specs = mapOf<String, ToolSpec>(
        "open_app" to ToolSpec(
            "open_app", "test",
            listOf(ParamSpec("app", "string", true, "app name")),
            Risk.LOW,
        ),
        "tap" to ToolSpec("tap", "test", emptyList(), Risk.LOW),
        "long_press" to ToolSpec(
            "long_press", "test",
            listOf(
                ParamSpec("elementIdx", "int", false, "idx"),
                ParamSpec("x", "int", false, "x"),
                ParamSpec("y", "int", false, "y"),
            ),
            Risk.LOW,
        ),
        "type_text" to ToolSpec(
            "type_text", "test",
            listOf(ParamSpec("text", "string", true, "text")),
            Risk.MEDIUM,
        ),
        "set_alarm" to ToolSpec(
            "set_alarm", "test",
            listOf(ParamSpec("hour", "int", true, "hour"), ParamSpec("minute", "int", true, "minute")),
            Risk.LOW,
        ),
    )

    private fun parse(text: String) = Planner.parseDecision(text) { specs[it] }

    // ------------------------------------------------------------ flat actions

    @Test
    fun `flat tap by coordinates`() {
        val d = parse("""{"type":"tap","x":540,"y":148,"description":"Search"}""")
        assertNotNull(d.action)
        assertEquals("tap", d.action!!.tool)
        assertEquals(540, JsonX.run { d.action!!.args.int("x") })
        assertEquals(148, JsonX.run { d.action!!.args.int("y") })
    }

    @Test
    fun `flat tap by text when no coords`() {
        val d = parse("""{"type":"click","text":"Send"}""")
        assertEquals("tap", d.action!!.tool)
        assertEquals("Send", JsonX.run { d.action!!.args.str("text") })
    }

    @Test
    fun `flat long press by coordinates`() {
        val d = parse("""{"type":"long_press","x":100,"y":200}""")
        assertEquals("long_press", d.action!!.tool)
        assertEquals(100, JsonX.run { d.action!!.args.int("x") })
    }

    @Test
    fun `flat double tap maps to double_tap tool`() {
        val d = parse("""{"type":"double_tap","x":500,"y":900}""")
        assertEquals("double_tap", d.action!!.tool)
    }

    @Test
    fun `flat type_text`() {
        val d = parse("""{"type":"type_text","text":"hello world"}""")
        assertEquals("type_text", d.action!!.tool)
        assertEquals("hello world", JsonX.run { d.action!!.args.str("text") })
    }

    @Test
    fun `flat scroll directions`() {
        assertEquals(true, JsonX.run { parse("""{"type":"scroll","direction":"down"}""").action!!.args.bool("forward") })
        assertEquals(false, JsonX.run { parse("""{"type":"scroll","direction":"up"}""").action!!.args.bool("forward") })
        assertEquals("left", JsonX.run { parse("""{"type":"scroll","direction":"left"}""").action!!.args.str("direction") })
        assertEquals("right", JsonX.run { parse("""{"type":"scroll","direction":"right"}""").action!!.args.str("direction") })
    }

    @Test
    fun `flat buttons map to press tools`() {
        assertEquals("press_back", parse("""{"type":"button","name":"back"}""").action!!.tool)
        assertEquals("press_home", parse("""{"type":"button","name":"home"}""").action!!.tool)
        assertEquals("press_recents", parse("""{"type":"button","name":"recents"}""").action!!.tool)
        assertEquals("press_recents", parse("""{"type":"press_button","button":"overview"}""").action!!.tool)
    }

    @Test
    fun `flat open_app`() {
        val d = parse("""{"type":"open_app","app":"WhatsApp"}""")
        assertEquals("open_app", d.action!!.tool)
        assertEquals("WhatsApp", JsonX.run { d.action!!.args.str("app") })
    }

    @Test
    fun `flat wait default and explicit`() {
        assertEquals(1000, JsonX.run { parse("""{"type":"wait"}""").action!!.args.int("ms") })
        assertEquals(2500, JsonX.run { parse("""{"type":"wait","ms":2500}""").action!!.args.int("ms") })
    }

    @Test
    fun `flat tool escape hatch validates registered tools`() {
        val d = parse("""{"type":"tool","name":"set_alarm","args":{"hour":7,"minute":30}}""")
        assertEquals("set_alarm", d.action!!.tool)
        assertEquals(7, JsonX.run { d.action!!.args.int("hour") })
    }

    @Test
    fun `flat unknown tool stops honestly not crashes`() {
        val d = parse("""{"type":"tool","name":"format_disk","args":{}}""")
        assertNull(d.action)
        assertNotNull(d.response)
    }

    @Test
    fun `flat done becomes final response`() {
        val d = parse("""{"type":"done","summary":"Opened WhatsApp settings"}""")
        assertNull(d.action)
        assertEquals("Opened WhatsApp settings", d.response)
    }

    @Test
    fun `flat done without summary still finishes`() {
        val d = parse("""{"type":"done"}""")
        assertNull(d.action)
        assertEquals("Task finished.", d.response)
    }

    @Test
    fun `legacy nested contract still parses`() {
        val d = parse("""{"thought":"go","action":{"tool":"open_app","args":{"app":"Chrome"}}}""")
        assertEquals("open_app", d.action!!.tool)
    }

    @Test
    fun `garbage without type falls back to legacy pipeline`() {
        val d = parse("""{"tool":"open_app","args":{"app":"Chrome"}}""")
        assertEquals("open_app", d.action!!.tool)
    }

    // -------------------------------------------------- touch stroke classifier

    private fun classifyStroke(
        events: List<Triple<Long, Pair<Float, Float>, Int>>,
        up: Pair<Long, Pair<Float, Float>>,
        slop: Float = 24f,
    ): SkillRecorder.TouchStroke.Quoctuple? {
        val s = SkillRecorder.TouchStroke(slop)
        val first = events.first()
        s.begin(first.first, first.second.first, first.second.second, first.third)
        events.drop(1).forEach { (t, xy, ptr) -> s.move(t, xy.first, xy.second, ptr) }
        return s.end(up.first, up.second.first, up.second.second)
    }

    @Test
    fun `short small stroke is a tap`() {
        val r = classifyStroke(
            events = listOf(Triple(1000L, 500f to 300f, 1)),
            up = 1090L to (501f to 302f),
        )
        assertEquals("TAP", r!!.type)
        assertEquals(500f, r.a, 0.01f)
        assertEquals(300f, r.b, 0.01f)
    }

    @Test
    fun `long small stroke is a long press`() {
        val r = classifyStroke(
            events = listOf(Triple(1000L, 500f to 300f, 1)),
            up = 1700L to (500f to 301f),
        )
        assertEquals("LONG_PRESS", r!!.type)
    }

    @Test
    fun `moved stroke is a scroll with dominant direction`() {
        val down = SkillRecorder.TouchStroke(24f)
        down.begin(1000L, 500f to 800f, 1)
        down.move(1050L, 500f to 650f, 1)
        down.move(1100L, 500f to 450f, 1)
        val r = down.end(1150L, 500f to 400f)
        assertEquals("SCROLL", r!!.type)
        // dy negative (finger moved up the screen) -> direction "up"
        assertTrue(r.a == 0f && r.b < 0f)
    }

    @Test
    fun `multi-pointer stroke is ignored`() {
        val s = SkillRecorder.TouchStroke(24f)
        s.begin(1000L, 500f to 300f, 1)
        s.move(1050L, 500f to 300f, 2) // second finger lands
        val r = s.end(1100L, 500f to 300f)
        assertNull(r)
    }

    // ------------------------------------------------------------- grammar

    @Test
    fun `grammar embeds flat action vocabulary`() {
        val g = DecisionGrammar.decisionGrammar(specs.values.toList())
        assertNotNull(g)
        listOf("\"tap\"", "\"done\"", "\"type_text\"", "\"direction\"", "\"open_app\"", "\"summary\"")
            .forEach { token -> assertTrue("grammar must contain $token", g!!.contains(token)) }
    }

    @Test
    fun `grammar keeps legacy nested shapes`() {
        val g = DecisionGrammar.decisionGrammar(specs.values.toList())!!
        assertTrue(g.contains("actionobj"))
        assertTrue(g.contains("argsobj"))
    }
}

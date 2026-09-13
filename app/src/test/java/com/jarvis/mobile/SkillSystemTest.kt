package com.jarvis.mobile

import com.jarvis.mobile.core.planner.DeterministicPlanner
import com.jarvis.mobile.core.skills.QAPair
import com.jarvis.mobile.core.skills.SkillDefinition
import com.jarvis.mobile.core.skills.SkillStep
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7 skill-recorder + /grill-me contract tests, plus the deterministic-planner
 * "open" anchor fix from the expert review (compound goals must not become one
 * giant open_app argument).
 */
class SkillSystemTest {

    // ----------------------------------------------------- serialization round-trip

    @Test
    fun skillJsonRoundTrip() {
        val skill = SkillDefinition(
            id = "skill-1",
            name = "Order my usual",
            description = "Open the app, search, tap the first result",
            notes = "• Never pays without asking\n  → confirmed",
            steps = listOf(
                SkillStep(type = "APP_OPEN", pkg = "com.example.app"),
                SkillStep(type = "TAP", pkg = "com.example.app", text = "Search", x = 540, y = 120),
                SkillStep(type = "TEXT", pkg = "com.example.app", input = "cold brew", text = "Search"),
                SkillStep(type = "WAIT", waitMs = 1200),
                SkillStep(type = "TAP", pkg = "com.example.app", text = "cold brew", y = 480),
            ),
            source = "GRILLED",
            interview = listOf(QAPair("What should it be called?", "Order my usual")),
        )
        val encoded = Json.encodeToString(SkillDefinition.serializer(), skill)
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(SkillDefinition.serializer(), encoded)
        assertEquals(skill, decoded)
        // Key fields survive verbatim (these drive replay matching).
        assertEquals("com.example.app", decoded.steps[1].pkg)
        assertEquals("cold brew", decoded.steps[2].input)
        assertEquals(540, decoded.steps[1].x)
    }

    @Test
    fun stepDescribe_isHumanReadable() {
        val s = SkillStep(type = "TEXT", input = "hello there", text = "Message")
        assertTrue(s.describe().contains("hello there"))
        assertTrue(s.describe().contains("Message"))
        assertEquals("Press back", SkillStep(type = "BACK").describe())
    }

    // ------------------------------------------------- planner: open rule anchoring

    @Test
    fun openRule_simpleGoal_stillWorks() {
        val d = DeterministicPlanner.decide("open chrome", null)
        assertEquals("open_app", d.action?.tool)
        assertEquals("chrome", d.action?.args?.get("app")?.toString()?.trim('"'))
    }

    @Test
    fun openRule_compoundGoal_isNotSwallowed() {
        // Regression: used to become open_app("chrome and set an alarm").
        val d = DeterministicPlanner.decide("open chrome and set an alarm", null)
        assertNull("compound goal must not become one open_app argument", d.action)
    }

    @Test
    fun openRule_thenGoal_isNotSwallowed() {
        val d = DeterministicPlanner.decide("open whatsapp then message dad", null)
        assertNull(d.action)
    }

    @Test
    fun openRule_thePrefix_stillWorks() {
        val d = DeterministicPlanner.decide("open the settings app", null)
        assertEquals("open_app", d.action?.tool)
        val app = d.action?.args?.get("app")?.toString()?.trim('"') ?: ""
        assertTrue(app.equals("settings", ignoreCase = true))
    }
}

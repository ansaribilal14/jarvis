package com.jarvis.mobile

import com.jarvis.mobile.core.planner.DeterministicPlanner
import com.jarvis.mobile.core.skills.ElementTarget
import com.jarvis.mobile.core.skills.QAPair
import com.jarvis.mobile.core.skills.SkillAction
import com.jarvis.mobile.core.skills.SkillDefinition
import com.jarvis.mobile.core.skills.SkillStep
import com.jarvis.mobile.core.skills.SkillStore
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
    fun skillActionsRoundTrip() {
        val skill = SkillDefinition(
            id = "skill-1",
            name = "Order my usual",
            description = "Open the app, search, tap the first result",
            notes = "• Never pays without asking\n  → confirmed",
            actions = listOf(
                SkillAction(id = "a1", type = "LAUNCH_APP", appPackage = "com.example.app"),
                SkillAction(
                    id = "a2", type = "UI_CLICK",
                    target = ElementTarget(mode = "MIXED", text = "Search", fx = 0.5f, fy = 0.1f, pkg = "com.example.app"),
                ),
                SkillAction(
                    id = "a3", type = "UI_TEXT",
                    target = ElementTarget(mode = "NODE", viewId = "search_edit_text"),
                    input = "cold brew",
                ),
                SkillAction(id = "a4", type = "WAIT", waitMs = 1200),
                SkillAction(id = "a5", type = "SCROLL", dir = "down", amount = 2),
            ),
            source = "GRILLED",
            interview = listOf(QAPair("What should it be called?", "Order my usual")),
        )
        val encoded = Json.encodeToString(SkillDefinition.serializer(), skill)
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(SkillDefinition.serializer(), encoded)
        assertEquals(skill, decoded)
        // Key fields survive verbatim (these drive replay matching).
        assertEquals("com.example.app", decoded.actions[0].appPackage)
        assertEquals("Search", decoded.actions[1].target?.text)
        assertEquals("cold brew", decoded.actions[2].input)
        assertEquals(0.5f, decoded.actions[1].target?.fx)
    }

    @Test
    fun legacySkillJson_parsesAndMigrates() {
        // A pre-2.3 skill file shape: steps, no actions field at all.
        val legacyJson = """
            {
              "id": "skill-old",
              "name": "Old recording",
              "steps": [
                {"type": "APP_OPEN", "pkg": "com.example.app"},
                {"type": "TAP", "pkg": "com.example.app", "text": "Search", "x": 540, "y": 120},
                {"type": "TEXT", "pkg": "com.example.app", "input": "cold brew", "text": "Search"},
                {"type": "SCROLL", "dir": "fwd"},
                {"type": "WAIT", "waitMs": 1200},
                {"type": "BACK"}
              ],
              "source": "RECORDED"
            }
        """.trimIndent()
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString(SkillDefinition.serializer(), legacyJson)
        assertNull("legacy steps stay nullable after parse", decoded.actions.firstOrNull() ?: null)
        assertTrue(decoded.actions.isEmpty())
        val actions = decoded.stepList()
        assertEquals(6, actions.size)
        assertEquals("LAUNCH_APP", actions[0].type)
        assertEquals("com.example.app", actions[0].appPackage)
        assertEquals("UI_CLICK", actions[1].type)
        assertEquals("Search", actions[1].target?.text)
        assertEquals(540, actions[1].target?.pxX)
        assertEquals("UI_TEXT", actions[2].type)
        assertEquals("cold brew", actions[2].input)
        assertEquals("SCROLL", actions[3].type)
        assertEquals("down", actions[3].dir) // fwd -> down
        assertEquals("WAIT", actions[4].type)
        assertEquals(1200L, actions[4].waitMs)
        assertEquals("BACK", actions[5].type)
        // Normalization (what SkillStore does on load/save) sets actions + drops steps.
        val normalized = SkillStore.normalize(decoded)
        assertEquals(6, normalized.actions.size)
        assertNull(normalized.steps)
        assertEquals(6, normalized.stepList().size)
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

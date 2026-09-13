package com.jarvis.mobile

import com.jarvis.mobile.core.safety.RiskClassifier
import com.jarvis.mobile.core.tools.ParamSpec
import com.jarvis.mobile.core.tools.Risk
import com.jarvis.mobile.core.tools.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the confirmation risk ladder. This ladder is the last line
 * before real-world side effects - the escalation rules pinned here are:
 * typing in a messaging app = sending a message, ANY interaction inside a
 * finance app = high risk, HIGH can never be auto-approved.
 */
class RiskClassifierTest {

    private fun spec(name: String, risk: Risk) = ToolSpec(name, name, risk = risk)

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `low tool in a neutral app stays low`() {
        val r = RiskClassifier.classify(spec("tap", Risk.LOW), args("{}"), "com.android.chrome")
        assertEquals(Risk.LOW, r)
    }

    @Test
    fun `typing in whatsapp escalates to medium`() {
        val r = RiskClassifier.classify(
            spec("type_text", Risk.LOW),
            args("""{"text":"hello"}"""),
            "com.whatsapp",
        )
        assertEquals(Risk.MEDIUM, r)
    }

    @Test
    fun `typing matches messaging packages as substrings`() {
        val r = RiskClassifier.classify(spec("type_text", Risk.LOW), args("{}"), "com.instagram.android")
        assertEquals(Risk.MEDIUM, r)
    }

    @Test
    fun `any interactive tool inside a finance app escalates to high`() {
        for (name in listOf("tap", "type_text", "long_press", "swipe")) {
            val r = RiskClassifier.classify(spec(name, Risk.LOW), args("{}"), "net.phonepe.app")
            assertEquals("tool=$name", Risk.HIGH, r)
        }
    }

    @Test
    fun `finance escalation is substring based (gpay, paytm)`() {
        val r = RiskClassifier.classify(spec("tap", Risk.LOW), args("{}"), "com.google.android.apps.gpay")
        assertEquals(Risk.HIGH, r)
    }

    @Test
    fun `non-interactive tools do not escalate inside finance apps`() {
        val r = RiskClassifier.classify(spec("read_notifications", Risk.LOW), args("{}"), "com.paytm")
        assertEquals(Risk.LOW, r)
    }

    @Test
    fun `sharing never goes below medium`() {
        val fromLow = RiskClassifier.classify(spec("share_content", Risk.LOW), args("{}"), null)
        assertEquals(Risk.MEDIUM, fromLow)
        val fromHigh = RiskClassifier.classify(spec("share_file", Risk.HIGH), args("{}"), null)
        assertEquals(Risk.HIGH, fromHigh)
    }

    @Test
    fun `null package behaves like neutral`() {
        val r = RiskClassifier.classify(spec("type_text", Risk.LOW), args("{}"), null)
        assertEquals(Risk.LOW, r)
    }

    @Test
    fun `confirmation policy - low never, medium honors autoapprove, high always`() {
        assertFalse(RiskClassifier.requiresConfirmation(Risk.LOW, autoApproveMedium = false))
        assertTrue(RiskClassifier.requiresConfirmation(Risk.MEDIUM, autoApproveMedium = false))
        assertFalse(RiskClassifier.requiresConfirmation(Risk.MEDIUM, autoApproveMedium = true))
        assertTrue("HIGH must confirm even with auto-approve", RiskClassifier.requiresConfirmation(Risk.HIGH, autoApproveMedium = true))
    }

    @Test
    fun `why-explanation always says something for every branch`() {
        val params = listOf(ParamSpec("text", "string", true, "x"))
        val s = ToolSpec("type_text", "t", params, Risk.MEDIUM)
        for (r in Risk.values()) {
            val why = RiskClassifier.whyConfirmation(s, r, args("""{"text":"hi"}"""))
            assertTrue(why.isNotBlank())
        }
    }
}

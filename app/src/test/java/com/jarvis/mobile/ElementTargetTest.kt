package com.jarvis.mobile

import com.jarvis.mobile.core.observer.ScreenElement
import com.jarvis.mobile.core.observer.ScreenObservation
import com.jarvis.mobile.core.skills.ElementResolver
import com.jarvis.mobile.core.skills.ElementTarget
import com.jarvis.mobile.core.skills.SkillAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Skills-v3 targeting contract (docs/SKILLS_V3.md section 3.2):
 * the resolve ladder, the fractional-point math, and the describe() strings
 * the UI and run log show to the user.
 */
class ElementTargetTest {

    private fun el(
        idx: Int,
        text: String? = null,
        desc: String? = null,
        viewId: String? = null,
        left: Int = 0,
        top: Int = idx * 100,
        right: Int = 100,
        bottom: Int = top + 80,
    ) = ScreenElement(
        idx = idx, role = "button", text = text, desc = desc, viewId = viewId,
        className = "android.widget.Button",
        left = left, top = top, right = right, bottom = bottom,
        clickable = true, editable = false, scrollable = false,
        selected = false, isPassword = false,
    )

    private fun obs(vararg elements: ScreenElement, pkg: String = "com.example") =
        ScreenObservation(pkg, null, elements.toList())

    // ------------------------------------------------------------ resolve ladder

    @Test
    fun `viewId wins first`() {
        val o = obs(el(1, text = "Send", viewId = "com.a:id/wrong"), el(2, text = "Other", viewId = "com.a:id/send_btn"))
        val r = ElementResolver.resolve(o, ElementTarget(viewId = "com.a:id/send_btn"))
        assertEquals(2, r!!.idx)
    }

    @Test
    fun `viewId suffix match works`() {
        val o = obs(el(1, viewId = "com.vendor.app:id/action_send"))
        val r = ElementResolver.resolve(o, ElementTarget(viewId = "action_send"))
        assertEquals(1, r!!.idx)
    }

    @Test
    fun `exact text beats contains`() {
        val o = obs(el(1, text = "Settings overview"), el(2, text = "Settings"))
        val r = ElementResolver.resolve(o, ElementTarget(text = "Settings"))
        assertEquals(2, r!!.idx)
    }

    @Test
    fun `text contains fallback is case-insensitive`() {
        val o = obs(el(1, text = "Confirm Your Purchase"))
        val r = ElementResolver.resolve(o, ElementTarget(text = "confirm your"))
        assertEquals(1, r!!.idx)
    }

    @Test
    fun `desc matches when text is missing`() {
        val o = obs(el(1, desc = "Voice search"))
        val r = ElementResolver.resolve(o, ElementTarget(desc = "voice search"))
        assertEquals(1, r!!.idx)
    }

    @Test
    fun `point falls back to bounds containment`() {
        val o = obs(el(1, text = "A"), el(2, text = "B", left = 500, top = 500, right = 700, bottom = 600))
        val r = ElementResolver.resolve(o, ElementTarget(fx = 0.6f, fy = 0.55f), screenW = 1000, screenH = 1000)
        assertEquals(2, r!!.idx)
    }

    @Test
    fun `legacy pixels resolve as point`() {
        val o = obs(el(1, text = "A", left = 500, top = 500, right = 700, bottom = 600))
        val r = ElementResolver.resolve(o, ElementTarget(pxX = 540, pxY = 520), screenW = 1000, screenH = 1000)
        assertEquals(1, r!!.idx)
    }

    @Test
    fun `nothing matches returns null`() {
        val o = obs(el(1, text = "A"))
        assertNull(ElementResolver.resolve(o, ElementTarget(text = "Zzz")))
    }

    @Test
    fun `coordinate sentinel text is not treated as a label`() {
        val o = obs(el(1, text = "@540,120]"), el(2, text = "Search"))
        val r = ElementResolver.resolve(o, ElementTarget(text = "@540,120]"))
        assertNull("sentinel must not match by text", r)
    }

    // -------------------------------------------------------- fractional points

    @Test
    fun `fraction to pixel math is exact`() {
        val t = ElementTarget(fx = 0.5f, fy = 0.25f)
        val (x, y) = t.pointPx(1080, 2400)!!
        assertEquals(540, x)
        assertEquals(600, y)
    }

    @Test
    fun `fractions are clamped to the screen`() {
        val t = ElementTarget(fx = 1.4f, fy = -0.2f)
        val (x, y) = t.pointPx(1000, 1000)!!
        assertEquals(1000, x)
        assertEquals(0, y)
    }

    @Test
    fun `legacy px wins when fractions absent`() {
        val t = ElementTarget(pxX = 42, pxY = 77)
        assertEquals(42 to 77, t.pointPx(1080, 2400))
    }

    @Test
    fun `same fraction survives a different screen size (rotation or device change)`() {
        val t = ElementTarget(fx = 0.9f, fy = 0.9f)
        val portrait = t.pointPx(1080, 2400)!!
        val landscape = t.pointPx(2400, 1080)!!
        assertEquals(972, portrait.first)
        assertEquals(2160, landscape.first) // 0.9 * 2400
    }

    // ------------------------------------------------------------------ describe

    @Test
    fun `describe prefers label over point`() {
        val t = ElementTarget(text = "Send message", fx = 0.5f, fy = 0.5f)
        assertTrue(t.describe().contains("Send message"))
    }

    @Test
    fun `describe shows point when nothing else`() {
        val t = ElementTarget(fx = 0.75f, fy = 0.5f)
        assertTrue(t.describe().contains("point"))
    }

    @Test
    fun `action describe covers the catalog`() {
        // Package rendering is best-effort (no PackageManager on JVM): first segment.
        assertEquals("Open com", SkillAction(id = "1", type = "LAUNCH_APP", appPackage = "com.whatsapp").describe())
        assertEquals("Tap \"Send\"", SkillAction(id = "2", type = "UI_CLICK", target = ElementTarget(text = "Send")).describe())
        assertTrue(SkillAction(id = "3", type = "UI_TEXT", target = ElementTarget(viewId = "search_box"), input = "hello").describe().contains("hello"))
        assertEquals("Scroll down x3", SkillAction(id = "4", type = "SCROLL", dir = "down", amount = 3).describe())
        assertEquals("Press Back", SkillAction(id = "5", type = "BACK").describe())
        assertTrue(SkillAction(id = "6", type = "WAIT", waitMs = 2000).describe().contains("2"))
    }

    @Test
    fun `user note overrides generated describe`() {
        val a = SkillAction(id = "7", type = "UI_CLICK", target = ElementTarget(text = "X"), note = "Confirm the order")
        assertEquals("Confirm the order", a.describe())
    }
}

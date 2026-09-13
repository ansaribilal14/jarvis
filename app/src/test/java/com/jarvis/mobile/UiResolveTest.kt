package com.jarvis.mobile

import com.jarvis.mobile.core.observer.ScreenElement
import com.jarvis.mobile.core.observer.ScreenObservation
import com.jarvis.mobile.core.tools.impl.UiResolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the shared element resolver used by every UI tool.
 * UiResolve is the grounding link between what the planner chose and what is
 * actually on screen - these tests pin its priority ladder.
 */
class UiResolveTest {

    private fun el(
        idx: Int,
        text: String? = null,
        desc: String? = null,
        viewId: String? = null,
        clickable: Boolean = true,
        top: Int = idx * 100,
    ) = ScreenElement(
        idx = idx, role = "button", text = text, desc = desc, viewId = viewId,
        className = "android.widget.Button",
        left = 0, top = top, right = 100, bottom = top + 80,
        clickable = clickable, editable = false, scrollable = false,
        selected = false, isPassword = false,
    )

    private fun obs(vararg elements: ScreenElement, pkg: String = "com.example") =
        ScreenObservation(pkg, null, elements.toList())

    @Test
    fun `elementIdx matches by planned identity not raw index`() {
        val planned = obs(el(1, text = "Old label A"), el(2, text = "Old label B"))
        // Fresh screen: same elements, shuffled indexes.
        val fresh = obs(el(7, text = "Old label B"), el(8, text = "Old label A"))
        val r = UiResolve.resolve(fresh, planned, elementIdx = 2, text = null, viewId = null)
        assertNotNull(r)
        assertEquals("Old label B", r!!.text)
    }

    @Test
    fun `elementIdx falls back to fresh index when identity is gone`() {
        val planned = obs(el(1, text = "Gone"))
        val fresh = obs(el(1, text = "Different"), el(2, text = "Other"))
        val r = UiResolve.resolve(fresh, planned, elementIdx = 2, text = null, viewId = null)
        assertNotNull(r)
        assertEquals("Other", r!!.text)
    }

    @Test
    fun `exact text wins over contains`() {
        val fresh = obs(el(1, text = "Settings overview"), el(2, text = "Settings"))
        val r = UiResolve.resolve(fresh, null, elementIdx = null, text = "Settings", viewId = null)
        assertNotNull(r)
        assertEquals("Settings", r!!.text)
    }

    @Test
    fun `contains matches when exact is absent`() {
        val fresh = obs(el(1, text = "Search settings"))
        val r = UiResolve.resolve(fresh, null, elementIdx = null, text = "settings", viewId = null)
        assertNotNull(r)
        assertEquals("Search settings", r!!.text)
    }

    @Test
    fun `description is searched too`() {
        val fresh = obs(el(1, desc = "Play button"))
        val r = UiResolve.resolve(fresh, null, elementIdx = null, text = "Play button", viewId = null)
        assertNotNull(r)
    }

    @Test
    fun `viewId matches by suffix`() {
        val fresh = obs(el(1, viewId = "com.example:id/confirm_button"))
        val r = UiResolve.resolve(fresh, null, elementIdx = null, text = null, viewId = "confirm_button")
        assertNotNull(r)
    }

    @Test
    fun `no match returns null`() {
        val fresh = obs(el(1, text = "A"), el(2, text = "B"))
        val r = UiResolve.resolve(fresh, null, elementIdx = null, text = "missing", viewId = null)
        assertNull(r)
    }

    @Test
    fun `blank hints never match`() {
        val fresh = obs(el(1, text = "A"))
        assertNull(UiResolve.resolve(fresh, null, elementIdx = null, text = "", viewId = ""))
    }
}

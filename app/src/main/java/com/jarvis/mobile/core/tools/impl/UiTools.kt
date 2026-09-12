package com.jarvis.mobile.core.tools.impl

import com.jarvis.mobile.core.observer.ScreenElement
import com.jarvis.mobile.core.observer.ScreenObservation
import com.jarvis.mobile.core.tools.Tool
import com.jarvis.mobile.core.tools.ToolContext
import com.jarvis.mobile.core.tools.ToolResult
import com.jarvis.mobile.core.tools.ToolSpec
import com.jarvis.mobile.core.tools.Verification

/** Element resolution shared by UI tools: semantic first, coordinates last. */
object UiResolve {

    /**
     * Resolve the target element from a fresh observation.
     * Priority: elementIdx (against the planner's observation matched by label) >
     * exact text > contains text > description > viewId.
     */
    fun resolve(
        fresh: ScreenObservation,
        planned: ScreenObservation?,
        elementIdx: Int?,
        text: String?,
        viewId: String?,
    ): ScreenElement? {
        if (elementIdx != null) {
            // The planner's index refers to the observation it saw. Find the same
            // element in the fresh observation by identity, then by index fallback.
            val plannedEl = planned?.elements?.firstOrNull { it.idx == elementIdx }
            if (plannedEl != null) {
                fresh.elements.firstOrNull {
                    it.text == plannedEl.text && it.desc == plannedEl.desc && it.viewId == plannedEl.viewId && it.clickable == plannedEl.clickable
                }?.let { return it }
            }
            return fresh.elements.firstOrNull { it.idx == elementIdx }
                ?: fresh.elements.getOrNull(elementIdx - 1)
        }
        if (!text.isNullOrBlank()) {
            fresh.findText(text)?.let { return it }
            fresh.findContains(text)?.let { return it }
        }
        if (!viewId.isNullOrBlank()) {
            fresh.elements.firstOrNull { it.viewId?.endsWith(viewId) == true }?.let { return it }
        }
        return null
    }
}

class TapTool : Tool(
    ToolSpec(
        "tap", "Tap an on-screen element. Pass elementIdx from read_screen, or the visible text of the target. As a last resort pass exact screen coordinates x/y (only for targets you can see in the screen block).",
        listOf(
            com.jarvis.mobile.core.tools.ParamSpec("elementIdx", "int", false, "index from the last screen observation"),
            com.jarvis.mobile.core.tools.ParamSpec("text", "string", false, "visible text of the target"),
            com.jarvis.mobile.core.tools.ParamSpec("viewId", "string", false, "view id resource name"),
            com.jarvis.mobile.core.tools.ParamSpec("x", "int", false, "last-resort: exact x coordinate of a visible target"),
            com.jarvis.mobile.core.tools.ParamSpec("y", "int", false, "last-resort: exact y coordinate of a visible target"),
        ),
        com.jarvis.mobile.core.tools.Risk.LOW,
        needsAccessibility = true,
    ),
) {
    override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
        val svc = T.svc() ?: return ToolResult.unavailable("Accessibility service not connected. Enable it in system settings.", "guide-user-accessibility")
        val fresh = T.freshObserve() ?: return ToolResult.fail("Cannot read the screen right now.")
        val el = UiResolve.resolve(fresh, ctx.observation, T.int(args, "elementIdx"), T.str(args, "text"), T.str(args, "viewId"))
            ?: run {
                // Coordinate last resort (MobileAgent-style): the model must cite a
                // point it actually saw; we clamp to the visible screen.
                val x = T.int(args, "x")
                val y = T.int(args, "y")
                if (x == null || y == null) return ToolResult.fail(
                    "Target not found on the current screen (${fresh.packageName}). Scroll or re-read the screen.",
                    "reobserve-or-scroll",
                )
                val m = svc.resources.displayMetrics
                val cx = x.coerceIn(0, m.widthPixels - 1)
                val cy = y.coerceIn(0, m.heightPixels - 1)
                val ok = svc.tapAt(cx, cy)
                return if (ok) {
                    val changed = T.awaitWindowChange(2500) != null
                    ToolResult(
                        com.jarvis.mobile.core.tools.ToolStatus.SUCCESS,
                        "Tapped at ($cx,$cy) by coordinates.",
                        if (changed) Verification.VERIFIED else Verification.UNVERIFIED,
                        detail = "pkg=${fresh.packageName}",
                    )
                } else ToolResult.fail("Could not dispatch tap gesture at ($cx,$cy).")
            }
        if (el.isPassword) return ToolResult.blocked("Target is a protected password field - JARVIS will not interact with it.", "password-policy")
        val node = svc.resolveNode(el) ?: run {
            // Coordinate fallback (controlled, verified): bounds are still current from `fresh`.
            val ok = svc.tapAt(el.centerX, el.centerY)
            return if (ok) {
                val changed = T.awaitWindowChange(2500) != null
                ToolResult(
                    com.jarvis.mobile.core.tools.ToolStatus.SUCCESS,
                    "Tapped \"${el.label()}\" by coordinates (fallback).",
                    if (changed) Verification.VERIFIED else Verification.UNVERIFIED,
                    detail = "pkg=${fresh.packageName}",
                )
            } else ToolResult.fail("Could not dispatch tap gesture.")
        }
        val clicked = svc.performClick(node)
        if (!clicked) return ToolResult.fail("Element \"${el.label()}\" is not clickable right now.", "reobserve")
        val changed = T.awaitWindowChange(2500) != null
        return ToolResult(
            com.jarvis.mobile.core.tools.ToolStatus.SUCCESS,
            "Tapped \"${el.label()}\".",
            if (changed) Verification.VERIFIED else Verification.UNVERIFIED,
            detail = "pkg=${fresh.packageName}",
        )
    }
}

class DoubleTapTool : Tool(
    ToolSpec(
        "double_tap", "Double-tap a point on screen. This is the LIKE gesture in Instagram/YouTube/Facebook feeds and reels.",
        listOf(
            com.jarvis.mobile.core.tools.ParamSpec("elementIdx", "int", false, "index from read_screen (e.g. the video/image element)"),
            com.jarvis.mobile.core.tools.ParamSpec("text", "string", false, "visible text of the target"),
            com.jarvis.mobile.core.tools.ParamSpec("x", "int", false, "exact x of a visible target (e.g. center of the video)"),
            com.jarvis.mobile.core.tools.ParamSpec("y", "int", false, "exact y of a visible target"),
        ),
        com.jarvis.mobile.core.tools.Risk.LOW,
        needsAccessibility = true,
    ),
) {
    override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
        val svc = T.svc() ?: return ToolResult.unavailable("Accessibility service not connected.")
        val fresh = T.freshObserve()
        val el = fresh?.let { o -> UiResolve.resolve(o, ctx.observation, T.int(args, "elementIdx"), T.str(args, "text"), null) }
        val (x, y, what) = if (el != null) {
            Triple(el.centerX, el.centerY, "\"${el.label()}\"")
        } else {
            val px = T.int(args, "x")
            val py = T.int(args, "y")
            if (px == null || py == null) {
                // Default: center of the visible content area (feeds put the media there).
                val m = svc.resources.displayMetrics
                Triple(m.widthPixels / 2, (m.heightPixels * 0.42).toInt(), "screen center (no target given)")
            } else {
                val m = svc.resources.displayMetrics
                Triple(px.coerceIn(0, m.widthPixels - 1), py.coerceIn(0, m.heightPixels - 1), "($px,$py)")
            }
        }
        val ok = svc.doubleTapAt(x, y)
        return if (ok) ToolResult.ok("Double-tapped $what at ($x,$y) - like gesture sent.", Verification.UNVERIFIED)
        else ToolResult.fail("Could not dispatch double-tap gesture.")
    }
}

class LongPressTool : Tool(
    ToolSpec(
        "long_press", "Long-press an on-screen element.",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("elementIdx", "int", false, "index from read_screen")),
        com.jarvis.mobile.core.tools.Risk.LOW,
        needsAccessibility = true,
    ),
) {
    override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
        val svc = T.svc() ?: return ToolResult.unavailable("Accessibility service not connected.")
        val fresh = T.freshObserve() ?: return ToolResult.fail("Cannot read the screen.")
        val el = UiResolve.resolve(fresh, ctx.observation, T.int(args, "elementIdx"), T.str(args, "text"), T.str(args, "viewId"))
            ?: return ToolResult.fail("Target not found on the current screen.", "reobserve-or-scroll")
        val ok = svc.longPressAt(el.centerX, el.centerY)
        return if (ok) ToolResult.ok("Long-pressed \"${el.label()}\".", Verification.UNVERIFIED)
        else ToolResult.fail("Could not dispatch long-press gesture.")
    }
}

class TypeTextTool : Tool(
    ToolSpec(
        "type_text", "Type text into an editable field. Refuses password/OTP fields.",
        listOf(
            com.jarvis.mobile.core.tools.ParamSpec("text", "string", true, "the text to enter"),
            com.jarvis.mobile.core.tools.ParamSpec("elementIdx", "int", false, "index of the field (uses focused field if omitted)"),
        ),
        com.jarvis.mobile.core.tools.Risk.MEDIUM,
        needsAccessibility = true,
    ),
) {
    override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
        val svc = T.svc() ?: return ToolResult.unavailable("Accessibility service not connected.")
        val text = T.str(args, "text") ?: return ToolResult.fail("Missing required arg: text.")
        val fresh = T.freshObserve() ?: return ToolResult.fail("Cannot read the screen.")

        val field = fresh.elements.firstOrNull { it.idx == (T.int(args, "elementIdx") ?: -1) }
            ?: fresh.elements.firstOrNull { it.editable }
            ?: return ToolResult.fail("No editable field found on the current screen (${fresh.packageName}).", "open-app-first")

        if (field.isPassword) return ToolResult.blocked("Refusing to type into a password field (safety policy).", "password-policy")
        val looksOtp = listOf("otp", "one time", "verification code", "confirm code").any {
            (field.text ?: "").contains(it, true) || (field.desc ?: "").contains(it, true) || (field.viewId ?: "").contains(it, true)
        }
        if (looksOtp) return ToolResult.blocked("Refusing to type into an OTP/verification field (safety policy).", "otp-policy")

        fun policyCheck(el: ScreenElement): ToolResult? {
            if (el.isPassword) return ToolResult.blocked("Refusing to type into a password field (safety policy).", "password-policy")
            val otp = listOf("otp", "one time", "verification code", "confirm code").any {
                (el.text ?: "").contains(it, true) || (el.desc ?: "").contains(it, true) || (el.viewId ?: "").contains(it, true)
            }
            return if (otp) ToolResult.blocked("Refusing to type into an OTP/verification field (safety policy).", "otp-policy") else null
        }
        policyCheck(field)?.let { return it }

        val node = svc.resolveNode(field)
        var ok = node?.let { svc.performSetText(it, text) } == true
        // Custom editors (Instagram, Snapchat) often reject ACTION_SET_TEXT on an
        // untapped field. Tap the field first, then set text on the focused editor.
        if (!ok) {
            svc.tapAt(field.centerX, field.centerY)
            T.settle(420)
            val focused = runCatching {
                svc.rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            }.getOrNull()
            if (focused != null) {
                val fe = fresh.elements.firstOrNull { it.editable && it.isPassword }
                if (fe != null) return ToolResult.blocked("Refusing to type into a password field (safety policy).", "password-policy")
                ok = svc.performSetText(focused, text)
            }
        }
        if (!ok) return ToolResult.fail(
            "Field did not accept direct text entry (app may use a custom editor).",
            "tap-field-then-retry",
        )
        // Verify by re-reading the field.
        T.settle(240)
        val after = T.freshObserve(0)
        val typed = after?.findContains(text.take(20)) != null
        return ToolResult(
            com.jarvis.mobile.core.tools.ToolStatus.SUCCESS,
            "Typed \"${text.take(40)}${if (text.length > 40) "…" else ""}\".",
            if (typed) Verification.VERIFIED else Verification.UNVERIFIED,
        )
    }
}

class ClearTextTool : Tool(
    ToolSpec(
        "clear_text", "Clear an editable text field.",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("elementIdx", "int", false, "index of the field")),
        com.jarvis.mobile.core.tools.Risk.MEDIUM,
        needsAccessibility = true,
    ),
) {
    override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
        val svc = T.svc() ?: return ToolResult.unavailable("Accessibility service not connected.")
        val fresh = T.freshObserve() ?: return ToolResult.fail("Cannot read the screen.")
        val field = fresh.elements.firstOrNull { it.idx == (T.int(args, "elementIdx") ?: -1) }
            ?: fresh.elements.firstOrNull { it.editable }
            ?: return ToolResult.fail("No editable field found.")
        if (field.isPassword) return ToolResult.blocked("Refusing to clear a password field.", "password-policy")
        val node = svc.resolveNode(field) ?: return ToolResult.fail("Field is gone from the screen.", "reobserve")
        val ok = svc.performSetText(node, "")
        return if (ok) ToolResult.ok("Cleared the field.", Verification.VERIFIED)
        else ToolResult.fail("Field did not accept clear operation.")
    }
}

class ScrollTool : Tool(
    ToolSpec(
        "scroll", "Scroll the screen or a list.",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("forward", "bool", false, "true = down/forward (default true)")),
        com.jarvis.mobile.core.tools.Risk.LOW,
        needsAccessibility = true,
    ),
) {
    override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
        val svc = T.svc() ?: return ToolResult.unavailable("Accessibility service not connected.")
        val forward = T.bool(args, "forward") ?: true
        val before = T.freshObserve(0)?.elements?.map { it.text to it.top } ?: emptyList()
        val ok = svc.scrollScreen(forward)
        if (!ok) return ToolResult.fail("Could not scroll.")
        val after = T.freshObserve(420)?.elements?.map { it.text to it.top } ?: emptyList()
        val changed = before != after
        return ToolResult(
            com.jarvis.mobile.core.tools.ToolStatus.SUCCESS,
            if (changed) "Scrolled ${if (forward) "forward" else "back"}." else "Scrolled (screen may already be at the ${if (forward) "end" else "top"}).",
            if (changed) Verification.VERIFIED else Verification.UNVERIFIED,
            recoveryHint = if (!changed && forward) "list-end" else null,
        )
    }
}

class SwipeTool : Tool(
    ToolSpec(
        "swipe", "Perform a swipe gesture between two screen points.",
        listOf(
            com.jarvis.mobile.core.tools.ParamSpec("x1", "int", true, "start x"),
            com.jarvis.mobile.core.tools.ParamSpec("y1", "int", true, "start y"),
            com.jarvis.mobile.core.tools.ParamSpec("x2", "int", true, "end x"),
            com.jarvis.mobile.core.tools.ParamSpec("y2", "int", true, "end y"),
        ),
        com.jarvis.mobile.core.tools.Risk.LOW,
        needsAccessibility = true,
    ),
) {
    override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
        val svc = T.svc() ?: return ToolResult.unavailable("Accessibility service not connected.")
        val x1 = T.int(args, "x1") ?: return ToolResult.fail("Missing x1.")
        val y1 = T.int(args, "y1") ?: return ToolResult.fail("Missing y1.")
        val x2 = T.int(args, "x2") ?: return ToolResult.fail("Missing x2.")
        val y2 = T.int(args, "y2") ?: return ToolResult.fail("Missing y2.")
        val ok = svc.swipe(x1, y1, x2, y2)
        return if (ok) ToolResult.ok("Swiped ($x1,$y1)→($x2,$y2).", Verification.UNVERIFIED)
        else ToolResult.fail("Could not dispatch swipe gesture.")
    }
}

class CopyTextTool : Tool(
    ToolSpec(
        "copy_text", "Select all text in a field and copy it to the clipboard.",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("elementIdx", "int", false, "index of the field")),
        com.jarvis.mobile.core.tools.Risk.LOW,
        needsAccessibility = true,
    ),
) {
    override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
        val svc = T.svc() ?: return ToolResult.unavailable("Accessibility service not connected.")
        val fresh = T.freshObserve() ?: return ToolResult.fail("Cannot read the screen.")
        val field = fresh.elements.firstOrNull { it.idx == (T.int(args, "elementIdx") ?: -1) && it.editable }
            ?: fresh.elements.firstOrNull { it.editable }
            ?: return ToolResult.fail("No editable field found.")
        val node = svc.resolveNode(field) ?: return ToolResult.fail("Field is gone from the screen.")
        val ok = svc.selectAllAndCopy(node)
        return if (ok) ToolResult.ok("Copied field text to the clipboard.", Verification.UNVERIFIED)
        else ToolResult.fail("Copy did not succeed (app may restrict it).")
    }
}

class PasteTool : Tool(
    ToolSpec(
        "paste_text", "Paste the current clipboard content into an editable field.",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("elementIdx", "int", false, "index of the field")),
        com.jarvis.mobile.core.tools.Risk.MEDIUM,
        needsAccessibility = true,
    ),
) {
    override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
        val svc = T.svc() ?: return ToolResult.unavailable("Accessibility service not connected.")
        val fresh = T.freshObserve() ?: return ToolResult.fail("Cannot read the screen.")
        val field = fresh.elements.firstOrNull { it.idx == (T.int(args, "elementIdx") ?: -1) && it.editable }
            ?: fresh.elements.firstOrNull { it.editable }
            ?: return ToolResult.fail("No editable field found.")
        if (field.isPassword) return ToolResult.blocked("Refusing to paste into a password field.", "password-policy")
        val node = svc.resolveNode(field) ?: return ToolResult.fail("Field is gone from the screen.")
        val ok = svc.pasteInto(node)
        return if (ok) ToolResult.ok("Pasted clipboard into the field.", Verification.UNVERIFIED)
        else ToolResult.fail("Paste did not succeed (clipboard may be empty or restricted).")
    }
}

class ReadScreenTool : Tool(
    ToolSpec(
        "read_screen", "Read the current screen as structured elements.",
        emptyList(),
        com.jarvis.mobile.core.tools.Risk.LOW,
        needsAccessibility = true,
    ),
) {
    override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
        val obs = T.freshObserve() ?: return ToolResult.unavailable("Cannot read the screen - accessibility service not connected.")
        return ToolResult.ok("Read screen of ${obs.packageName}.", Verification.VERIFIED, detail = obs.toCompact())
    }
}

class FindElementTool : Tool(
    ToolSpec(
        "find_element", "Search the current screen for an element by text or description.",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("text", "string", true, "text to search for")),
        com.jarvis.mobile.core.tools.Risk.LOW,
        needsAccessibility = true,
    ),
) {
    override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
        val q = T.str(args, "text") ?: return ToolResult.fail("Missing required arg: text.")
        val obs = T.freshObserve() ?: return ToolResult.unavailable("Cannot read the screen.")
        val matches = obs.elements.filter {
            it.text?.contains(q, true) == true || it.desc?.contains(q, true) == true
        }
        return if (matches.isEmpty()) {
            ToolResult.fail("\"$q\" not found on screen.", "scroll-then-retry")
        } else {
            val det = matches.take(6).joinToString("; ") { "[${it.idx}] ${it.role} \"${(it.text ?: it.desc)?.take(40)}\"" }
            ToolResult.ok("Found ${matches.size} match(es).", Verification.VERIFIED, detail = det)
        }
    }
}

class PressBackTool : Tool(
    ToolSpec("press_back", "Press the system Back button.", emptyList(), com.jarvis.mobile.core.tools.Risk.LOW, needsAccessibility = true),
) {
    override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
        val svc = T.svc() ?: return ToolResult.unavailable("Accessibility service not connected.")
        val ok = svc.globalBack()
        return if (ok) ToolResult.ok("Pressed Back.", Verification.UNVERIFIED) else ToolResult.fail("Back action rejected.")
    }
}

class PressHomeTool : Tool(
    ToolSpec("press_home", "Go to the home screen.", emptyList(), com.jarvis.mobile.core.tools.Risk.LOW, needsAccessibility = true),
) {
    override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
        val svc = T.svc() ?: return ToolResult.unavailable("Accessibility service not connected.")
        val ok = svc.globalHome()
        if (!ok) return ToolResult.fail("Home action rejected.")
        T.settle(500)
        val pkg = T.svc()?.currentPackage()
        val verified = pkg != null && (pkg.contains("launcher", true) || pkg == "com.google.android.apps.nexuslauncher")
        return ToolResult.ok("Pressed Home.", if (verified) Verification.VERIFIED else Verification.UNVERIFIED)
    }
}

class WaitTool : Tool(
    ToolSpec(
        "wait", "Wait for the screen or app to settle.",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("ms", "int", false, "milliseconds (default 800, max 5000)")),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
        val ms = (T.int(args, "ms") ?: 800).coerceIn(100, 5000)
        kotlinx.coroutines.delay(ms.toLong())
        return ToolResult.ok("Waited ${ms}ms.", Verification.VERIFIED)
    }
}

class WaitForChangeTool : Tool(
    ToolSpec(
        "wait_for_change", "Wait until the screen changes (window transition or content update).",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("timeoutMs", "int", false, "max wait (default 6000)")),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
        val t = T.int(args, "timeoutMs")?.toLong()?.coerceIn(500, 15000) ?: 6000
        val changed = T.awaitWindowChange(t)
        return if (changed != null) {
            ToolResult.ok("Screen changed (app=${changed.first}).", Verification.VERIFIED)
        } else {
            ToolResult(com.jarvis.mobile.core.tools.ToolStatus.FAILED, "Screen did not change within ${t}ms.", Verification.FAILED, recoveryHint = "try-scroll-or-replan")
        }
    }
}

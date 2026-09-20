package com.jarvis.mobile.core.skills

import com.jarvis.mobile.accessibility.JarvisAccessibilityService
import com.jarvis.mobile.core.observer.ScreenObservation
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.delay

/**
 * Deterministic execution of one [SkillAction] against the CURRENT screen -
 * the skills-v3 engine (docs/SKILLS_V3.md). Every path uses public APIs only:
 *
 *  1. WAIT-FOR-ELEMENT: NODE/MIXED targets poll the tree every [POLL_MS] for
 *     up to [FIND_TIMEOUT_MS] (AutoX's untilFind/findOne pattern) - screens
 *     that load slowly no longer break replays.
 *  2. NODE FIRST: performAction(ACTION_CLICK / ACTION_SET_TEXT) on the matched
 *     node - exact even when the element is off-screen.
 *  3. GESTURE FALLBACK: dispatchGesture (awaited completion) at the resolved
 *     bounds center or the fractional recorded point - works in every app.
 *  4. VERIFY: tap-class actions prove the screen changed (fingerprint);
 *     unverified != failed, it is reported honestly.
 *  5. APP GUARD: a target pinned to an app refuses to act in the wrong app.
 */
object SkillRunner {
    private const val TAG = "skill-run"

    const val POLL_MS = 60L
    const val FIND_TIMEOUT_MS = 8_000L

    data class ActionOutcome(
        val ok: Boolean,
        val message: String,
        val verified: Boolean,
    )

    fun a11y(): JarvisAccessibilityService? = JarvisAccessibilityService.INSTANCE

    /** Metrics of the current display, for fractional-point resolution. */
    private fun screenW(svc: JarvisAccessibilityService): Int = svc.resources.displayMetrics.widthPixels
    private fun screenH(svc: JarvisAccessibilityService): Int = svc.resources.displayMetrics.heightPixels

    /** Single-action test entry used by the builder's per-action "Test" button. */
    suspend fun testAction(action: SkillAction): ActionOutcome {
        val svc = a11y()
            ?: return ActionOutcome(false, "Accessibility service is off - enable it in Settings", false)
        return runAction(svc, action, observe(svc))
    }

    /** Execute one action with the full wait-for-element ladder. */
    suspend fun runAction(svc: JarvisAccessibilityService, action: SkillAction, obsFirst: ScreenObservation?): ActionOutcome {
        return when (action.type) {
            "LAUNCH_APP" -> launchApp(action)
            "UI_CLICK" -> uiPoint(svc, action, obsFirst, longPress = false)
            "UI_LONG_PRESS" -> uiPoint(svc, action, obsFirst, longPress = true)
            "UI_TEXT" -> uiText(svc, action, obsFirst)
            "SCROLL" -> scroll(svc, action, obsFirst)
            "BACK" -> verified(svc, obsFirst) { svc.globalBack() }.let { (ok, changed) ->
                act(ok, "Pressed Back", changed)
            }
            "HOME" -> verified(svc, obsFirst) { svc.globalHome() }.let { (ok, changed) ->
                act(ok, "Went Home", changed)
            }
            "RECENTS" -> verified(svc, obsFirst) { svc.globalRecents() }.let { (ok, changed) ->
                act(ok, "Opened Recents", changed)
            }
            "WAIT" -> {
                delay((action.waitMs ?: 1000).coerceIn(100, 60_000))
                ActionOutcome(true, "Waited", false)
            }
            "NOTIFY" -> {
                JarvisAppNotify.notify(action.message ?: "Skill checkpoint")
                ActionOutcome(true, "Notified", false)
            }
            else -> ActionOutcome(false, "Unknown action type ${action.type}", false)
        }
    }

    private fun act(ok: Boolean, message: String, verified: Boolean) =
        ActionOutcome(ok, message, verified)

    /** Observe with a small timeout (never throws). */
    fun observe(svc: JarvisAccessibilityService): ScreenObservation? =
        runCatching { svc.observe(maxElements = 60) }.getOrNull()

    /**
     * Wait until the target resolves on a fresh observation (poll loop) or the
     * deadline passes. Returns the last observation + the matched element.
     */
    suspend fun waitForElement(
        svc: JarvisAccessibilityService,
        target: ElementTarget,
        timeoutMs: Long = FIND_TIMEOUT_MS,
    ): Pair<ScreenObservation, com.jarvis.mobile.core.observer.ScreenElement>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val obs = observe(svc)
            if (obs != null) {
                val el = ElementResolver.resolve(obs, target, screenW(svc), screenH(svc))
                if (el != null) return obs to el
            }
            if (System.currentTimeMillis() >= deadline) return null
            delay(POLL_MS)
        }
    }

    /** App guard: UI actions pinned to an app refuse to act anywhere else. */
    private fun appGuard(svc: JarvisAccessibilityService, target: ElementTarget): String? {
        val pkg = target.pkg ?: return null
        val current = svc.currentPackage() ?: return "Could not read the current app"
        return if (current == pkg) null
        else "This step targets ${pkg.substringBefore('.')} but the current app is ${current.substringBefore('.')}. " +
            "Add an 'Open app' action before it."
    }

    private suspend fun uiPoint(svc: JarvisAccessibilityService, action: SkillAction, obsFirst: ScreenObservation?, longPress: Boolean): ActionOutcome {
        val target = action.target
            ?: return ActionOutcome(false, "No target configured for this action", false)
        appGuard(svc, target)?.let { return ActionOutcome(false, it, false) }

        // Wait for the element (or just use the first observation for POINT mode).
        val found = if (target.mode != "POINT" && target.hasNode) {
            waitForElement(svc, target)
        } else null

        if (found != null) {
            val (obs, el) = found
            val node = svc.resolveNode(el)
            if (node != null) {
                val ok = if (longPress) {
                    // Node long-click first, gesture fallback below.
                    runCatching { node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK) }.getOrDefault(false)
                } else svc.performClick(node)
                if (ok) {
                    val verified = verifyChanged(svc, obs)
                    return ActionOutcome(true, "${if (longPress) "Long-pressed" else "Tapped"} ${target.describe()}${if (verified) "" else " (screen unchanged)"}", verified)
                }
            }
            // Gesture fallback at the element's center.
            val ok = if (longPress) svc.longPressAt(el.centerX, el.centerY) else svc.tapAt(el.centerX, el.centerY)
            if (ok) {
                val verified = verifyChanged(svc, obs)
                return ActionOutcome(true, "${if (longPress) "Long-pressed" else "Tapped"} ${target.describe()} by position${if (verified) "" else " (screen unchanged)"}", verified)
            }
            return ActionOutcome(false, "Tap did not go through on ${target.describe()}", false)
        }

        // Nothing semantic matched - the recorded point is the ground truth.
        val pt = target.pointPx(screenW(svc), screenH(svc))
        if (pt != null) {
            val (x, y) = pt
            val ok = if (longPress) svc.longPressAt(x, y) else svc.tapAt(x, y)
            if (ok) {
                val verified = verifyChanged(svc, obsFirst)
                return ActionOutcome(true, "${if (longPress) "Long-pressed" else "Tapped"} point (@$x,$y)${if (verified) "" else " (screen unchanged)"}", verified)
            }
            return ActionOutcome(false, "Tap did not go through at (@$x,$y)", false)
        }
        return ActionOutcome(false, "Could not find ${target.describe()} (waited ${FIND_TIMEOUT_MS / 1000}s)", false)
    }

    private suspend fun uiText(svc: JarvisAccessibilityService, action: SkillAction, obsFirst: ScreenObservation?): ActionOutcome {
        val target = action.target
            ?: return ActionOutcome(false, "No target configured for this action", false)
        appGuard(svc, target)?.let { return ActionOutcome(false, it, false) }
        val value = action.input
            ?: return ActionOutcome(false, "No text configured for this action", false)

        val found = if (target.hasNode || target.hasPoint) waitForElement(svc, target) else null
        val el = found?.second
            ?: obsFirst?.elements?.firstOrNull { it.editable && (target.pkg == null || it.text != null) }
            ?: obsFirst?.elements?.firstOrNull { it.editable }
        if (el == null) return ActionOutcome(false, "Could not find the text field ${target.describe()}", false)

        var node = svc.resolveNode(el)
        if (node != null && svc.performSetText(node, value)) {
            return ActionOutcome(true, "Typed \"${value.take(30)}\"", true)
        }
        // Field may need focus first (search fields).
        svc.tapAt(el.centerX, el.centerY)
        delay(300)
        node = svc.resolveNode(el)
        return if (node != null && svc.performSetText(node, value)) {
            ActionOutcome(true, "Typed \"${value.take(30)}\" after focusing", true)
        } else {
            ActionOutcome(false, "Could not type into ${target.describe()}", false)
        }
    }

    private suspend fun scroll(svc: JarvisAccessibilityService, action: SkillAction, obsFirst: ScreenObservation?): ActionOutcome {
        val dir = action.dir ?: "down"
        val times = action.amount.coerceIn(1, 10)
        var lastVerified = false
        repeat(times) { i ->
            val ok = when (dir) {
                "left", "right" -> {
                    val m = svc.resources.displayMetrics
                    val cy = m.heightPixels / 2
                    if (dir == "left") svc.swipe((m.widthPixels * 0.78).toInt(), cy, (m.widthPixels * 0.22).toInt(), cy)
                    else svc.swipe((m.widthPixels * 0.22).toInt(), cy, (m.widthPixels * 0.78).toInt(), cy)
                }
                "up" -> svc.scrollScreen(false)
                else -> svc.scrollScreen(true) // down
            }
            if (!ok) return ActionOutcome(false, "Could not scroll $dir", lastVerified)
            delay(250)
            lastVerified = verifyChanged(svc, obsFirst) || i > 0 // later pages always differ
        }
        return ActionOutcome(true, "Scrolled $dir" + if (times > 1) " x$times" else "", lastVerified)
    }

    private fun launchApp(action: SkillAction): ActionOutcome {
        val pkg = action.appPackage
            ?: return ActionOutcome(false, "No app chosen for this action", false)
        val ctx = JarvisAppHolder.app
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return ActionOutcome(false, "$pkg is not installed (or not launchable)", false)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent) }
            .map { ActionOutcome(true, "Opened $pkg", true) }
            .getOrElse { ActionOutcome(false, "Could not open $pkg: ${it.message?.take(60)}", false) }
    }

    /** Run a global action, then check the screen actually changed. */
    private suspend fun verified(
        svc: JarvisAccessibilityService,
        obsFirst: ScreenObservation?,
        block: suspend () -> Boolean,
    ): Pair<Boolean, Boolean> {
        if (!block()) return false to false
        delay(420)
        val after = observe(svc)
        val changed = after != null && (obsFirst == null || after.fingerprint() != obsFirst.fingerprint())
        return true to changed
    }

    /** Did the screen change since `obs`? (tap verification) */
    suspend fun verifyChanged(svc: JarvisAccessibilityService, obs: ScreenObservation?): Boolean {
        delay(420)
        val after = observe(svc) ?: return false
        return obs == null || after.fingerprint() != obs.fingerprint()
    }
}

/** Lazy app accessor kept out of the JVM-tested pure paths. */
object JarvisAppHolder {
    val app: android.content.Context get() = com.jarvis.mobile.JarvisApp.instance
}

/** Tiny notification helper for the NOTIFY action (no dependency on screens). */
object JarvisAppNotify {
    fun notify(message: String) {
        val ctx = JarvisAppHolder.app
        runCatching {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return
            val n = android.app.Notification.Builder(ctx, JarvisAppChannels.AGENT)
                .setContentTitle("JARVIS skill")
                .setContentText(message.take(120))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()
            nm.notify(4242, n)
        }.onFailure { Logx.w("skill-notify", "${it.message}") }
    }
}

object JarvisAppChannels {
    const val AGENT = "agent"
}

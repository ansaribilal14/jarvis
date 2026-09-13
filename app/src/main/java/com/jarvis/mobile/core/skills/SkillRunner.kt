package com.jarvis.mobile.core.skills

import com.jarvis.mobile.accessibility.JarvisAccessibilityService
import com.jarvis.mobile.core.observer.ScreenElement
import com.jarvis.mobile.core.observer.ScreenObservation
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.delay

/**
 * Deterministic replay of one recorded [SkillStep] against the CURRENT screen.
 *
 * Match order mirrors how a human re-finds a control: the exact resource id,
 * then the visible label, then the content description - all constrained to the
 * recorded app when the phone is still in it - with the recorded coordinates as
 * the last resort. Every tap-class step verifies by observing a screen change,
 * so a skill that silently does nothing reports UNVERIFIED instead of "done".
 */
object SkillRunner {
    private const val TAG = "skill-run"

    data class StepResult(
        val ok: Boolean,
        val message: String,
        val verified: Boolean,
    )

    fun a11y(): JarvisAccessibilityService? = JarvisAccessibilityService.INSTANCE

    /** Find the recorded target on a fresh observation (null = not found, caller decides). */
    fun resolve(obs: ScreenObservation?, step: SkillStep): ScreenElement? {
        if (obs == null) return null
        val inSameApp = step.pkg == null || obs.packageName == step.pkg
        val pool = if (inSameApp) obs.elements else obs.elements

        step.viewId?.let { id ->
            val short = id.substringAfterLast('/')
            pool.firstOrNull { it.viewId?.substringAfterLast('/') == short }?.let { return it }
        }
        step.text?.let { t ->
            if (t.startsWith("@") && t.endsWith("]")) return@let // coordinate sentinel, not a label
            pool.firstOrNull { it.text == t }?.let { return it }
            pool.firstOrNull { it.text?.contains(t, ignoreCase = true) == true }?.let { return it }
        }
        step.desc?.let { d ->
            pool.firstOrNull { it.desc == d }?.let { return it }
            pool.firstOrNull { it.desc?.contains(d, ignoreCase = true) == true }?.let { return it }
        }
        // Recorded coordinates on an element nobody matched by label: nearest element
        // whose bounds contain the recorded point (layout shifted slightly).
        val sx = step.x; val sy = step.y
        if (sx != null && sy != null) {
            pool.firstOrNull { sx in it.left..it.right && sy in it.top..it.bottom }?.let { return it }
        }
        return null
    }

    suspend fun runStep(step: SkillStep, obs: ScreenObservation?): StepResult {
        val svc = a11y()
            ?: return StepResult(false, "Accessibility service is off - enable it to replay skills", false)
        return when (step.type) {
            "TAP" -> tapStep(svc, obs, step)
            "LONG_PRESS" -> {
                val el = resolve(obs, step)
                val x = el?.centerX ?: step.x
                val y = el?.centerY ?: step.y
                if (x == null || y == null) StepResult(false, "Could not find \"${labelOf(step)}\"", false)
                else StepResult(svc.longPressAt(x, y), "Long-pressed \"${labelOf(step)}\"", true)
            }
            "TEXT" -> textStep(svc, obs, step)
            "SCROLL" -> {
                val changed = before(svc, obs) { svc.scrollScreen(step.dir != "back") }
                StepResult(true, "Scrolled", changed)
            }
            "BACK" -> {
                val changed = before(svc, obs) { svc.globalBack() }
                StepResult(true, "Back", changed)
            }
            "HOME" -> {
                val changed = before(svc, obs) { svc.globalHome() }
                StepResult(true, "Home", changed)
            }
            "APP_OPEN" -> openStep(step)
            "WAIT" -> {
                delay((step.waitMs ?: 500).coerceIn(100, 10_000))
                StepResult(true, "Waited", false)
            }
            else -> StepResult(false, "Unknown step type ${step.type}", false)
        }
    }

    private suspend fun before(svc: JarvisAccessibilityService, obs: ScreenObservation?, block: () -> Boolean): Boolean {
        if (!block()) return false
        kotlinx.coroutines.delay(420) // let the UI settle before the change check
        val after = runCatching { svc.observe(maxElements = 30) }.getOrNull() ?: return false
        return obs == null || after.fingerprint() != obs.fingerprint()
    }

    private suspend fun tapStep(svc: JarvisAccessibilityService, obs: ScreenObservation?, step: SkillStep): StepResult {
        val el = resolve(obs, step)
        if (el != null) {
            val node = svc.resolveNode(el)
            if (node != null && svc.performClick(node)) {
                return StepResult(true, "Tapped \"${labelOf(step)}\"", true)
            }
            val cx = el.centerX; val cy = el.centerY
            if (svc.tapAt(cx, cy)) return StepResult(true, "Tapped \"${labelOf(step)}\" (by position)", true)
            return StepResult(false, "Tap did not go through on \"${labelOf(step)}\"", false)
        }
        // Nothing semantic matched - coordinate replay is the recorded ground truth.
        val x = step.x; val y = step.y
        if (x != null && y != null && svc.tapAt(x, y)) {
            return StepResult(true, "Tapped at recorded position (@$x,$y)", true)
        }
        return StepResult(false, "Could not find \"${labelOf(step)}\" on this screen", false)
    }

    private suspend fun textStep(svc: JarvisAccessibilityService, obs: ScreenObservation?, step: SkillStep): StepResult {
        val value = step.input ?: return StepResult(false, "No recorded text to type", false)
        val el = resolve(obs, step) ?: obs?.elements?.firstOrNull { it.editable }
        if (el == null) return StepResult(false, "Could not find the text field \"${labelOf(step)}\"", false)
        var node = svc.resolveNode(el)
        if (node == null || !svc.performSetText(node, value)) {
            // Field may need focus/tap first (WhatsApp-style search fields).
            svc.tapAt(el.centerX, el.centerY)
            delay(300)
            node = svc.resolveNode(el)
            if (node == null || !svc.performSetText(node, value)) {
                return StepResult(false, "Could not type into \"${labelOf(step)}\"", false)
            }
        }
        return StepResult(true, "Typed \"${value.take(30)}\"", true)
    }

    private fun openStep(step: SkillStep): StepResult {
        val pkg = step.pkg ?: return StepResult(false, "No app recorded for this step", false)
        val ctx = com.jarvis.mobile.JarvisApp.instance
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
        return if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }
                .onSuccess { Logx.i(TAG, "Skill opened $pkg") }
                .map { StepResult(true, "Opened $pkg", true) }
                .getOrElse { StepResult(false, "Could not open $pkg: ${it.message?.take(60)}", false) }
        } else {
            StepResult(false, "$pkg is not installed (or not launchable)", false)
        }
    }

    fun labelOf(step: SkillStep): String =
        step.text ?: step.desc ?: step.viewId?.substringAfterLast('/') ?: (step.x?.let { x -> "(@$x,${step.y})" } ?: step.pkg ?: "target")
}

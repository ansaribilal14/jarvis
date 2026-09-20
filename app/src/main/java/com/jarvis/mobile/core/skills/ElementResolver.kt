package com.jarvis.mobile.core.skills

import com.jarvis.mobile.core.observer.ScreenElement
import com.jarvis.mobile.core.observer.ScreenObservation

/**
 * Pure matching ladder for [ElementTarget] against an observed screen.
 *
 * Order mirrors how a human re-finds a control (and AutoX's proven practice):
 * exact view id -> exact label -> label contains -> description -> the element
 * whose bounds contain the target point. Kept free of Android classes so the
 * whole ladder is unit-testable on the JVM.
 */
object ElementResolver {

    /** Resolve a target against a fresh observation. Null = not on this screen. */
    fun resolve(obs: ScreenObservation?, target: ElementTarget?, screenW: Int = 1080, screenH: Int = 2400): ScreenElement? {
        if (obs == null || target == null) return null
        val pool = obs.elements

        // 1. view id (suffix match - apps keep their own prefix)
        target.viewId?.takeIf { it.isNotBlank() }?.let { id ->
            val short = id.substringAfterLast('/')
            pool.firstOrNull { it.viewId?.substringAfterLast('/') == short }?.let { return it }
        }
        // 2. visible label: exact, then contains (coordinate sentinels are not labels)
        target.text?.takeIf { it.isNotBlank() }?.let { t ->
            if (!(t.startsWith("@") && t.endsWith("]"))) {
                pool.firstOrNull { it.text == t }?.let { return it }
                pool.firstOrNull { it.text?.contains(t, ignoreCase = true) == true }?.let { return it }
            }
        }
        // 3. content description: exact, then contains
        target.desc?.takeIf { it.isNotBlank() }?.let { d ->
            pool.firstOrNull { it.desc == d }?.let { return it }
            pool.firstOrNull { it.desc?.contains(d, ignoreCase = true) == true }?.let { return it }
        }
        // 4. point: the element whose bounds contain the resolved point
        target.pointPx(screenW, screenH)?.let { (px, py) ->
            pool.firstOrNull { px in it.left..it.right && py in it.top..it.bottom }?.let { return it }
        }
        return null
    }

    /** Point-only resolution (POINT mode): element under the fractional point, if any. */
    fun elementAtPoint(obs: ScreenObservation?, px: Int, py: Int): ScreenElement? {
        if (obs == null) return null
        return obs.elements.firstOrNull { px in it.left..it.right && py in it.top..it.bottom }
    }
}

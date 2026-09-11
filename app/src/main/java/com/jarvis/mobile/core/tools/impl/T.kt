package com.jarvis.mobile.core.tools.impl

import com.jarvis.mobile.accessibility.JarvisAccessibilityService
import com.jarvis.mobile.core.observer.ScreenObservation
import com.jarvis.mobile.util.JsonX
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** Shared helpers for tool implementations. */
object T {
    fun str(args: JsonObject, key: String): String? = JsonX.run { args.str(key) }
    fun int(args: JsonObject, key: String): Int? = JsonX.run { args.int(key) }
    fun bool(args: JsonObject, key: String): Boolean? = JsonX.run { args.bool(key) }
    fun dbl(args: JsonObject, key: String): Double? = JsonX.run { args.dbl(key) }

    fun svc(): JarvisAccessibilityService? = JarvisAccessibilityService.INSTANCE

    /** Fresh observation with a short settle delay (never trust stale screen state). */
    suspend fun freshObserve(settleMs: Long = 260): ScreenObservation? {
        kotlinx.coroutines.delay(settleMs)
        val s = svc() ?: return null
        var obs = s.observe()
        if (obs == null) {
            kotlinx.coroutines.delay(220)
            obs = s.observe()
        }
        return obs
    }

    /** Wait for a window state/content change (used for verification). */
    suspend fun awaitWindowChange(timeoutMs: Long = 3500): Pair<String?, String?>? =
        withTimeoutOrNull(timeoutMs) {
            JarvisAccessibilityService.windowEvents.first()
        }

    suspend fun settle(ms: Long = 400) = kotlinx.coroutines.delay(ms)
}

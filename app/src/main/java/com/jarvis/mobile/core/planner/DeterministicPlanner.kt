package com.jarvis.mobile.core.planner

import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.observer.ScreenObservation
import com.jarvis.mobile.core.tools.PlannedAction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Deterministic non-LLM handler (spec: MODEL FALLBACK LADDER, final rung).
 * Covers the most common single-step requests with regex intents so the app
 * remains genuinely useful before any model is downloaded - and reports
 * honestly when a request is beyond its scope.
 */
object DeterministicPlanner {

    data class Rule(val regex: Regex, val build: (MatchResult) -> PlannedAction?)

    private val rules = listOf(
        Rule(Regex("(?i)open (the )?(.+?)( app)?$")) { m ->
            m.groupValues[2].trim().takeIf { it.isNotBlank() }?.let {
                PlannedAction("open_app", buildJsonObject { put("app", it) })
            }
        },
        Rule(Regex("(?i)set brightness to (\\d+)")) { m ->
            PlannedAction("control_brightness", buildJsonObject { put("value", m.groupValues[1].toInt()) })
        },
        Rule(Regex("(?i)(turn )?(wifi|wi-fi) (on|off)")) { m ->
            PlannedAction("control_wifi", buildJsonObject { put("on", m.groupValues[3].equals("on", true)) })
        },
        Rule(Regex("(?i)(turn )?bluetooth (on|off)")) { m ->
            PlannedAction("control_bluetooth", buildJsonObject { put("on", m.groupValues[2].equals("on", true)) })
        },
        Rule(Regex("(?i)(turn )?(the )?(flashlight|torch) (on|off)")) { m ->
            PlannedAction("control_flashlight", buildJsonObject { put("on", m.groupValues[4].equals("on", true)) })
        },
        Rule(Regex("(?i)set volume to (\\d+)")) { m ->
            PlannedAction("control_volume", buildJsonObject { put("value", m.groupValues[1].toInt()) })
        },
        Rule(Regex("(?i)(volume (up|down|mute))")) { m ->
            PlannedAction("control_volume", buildJsonObject { put("direction", m.groupValues[2].lowercase()) })
        },
        Rule(Regex("(?i)read (my )?notifications")) { _ -> PlannedAction("read_notifications", buildJsonObject { }) },
        Rule(Regex("(?i)(what.?s|show) (my |the )?calendar")) { _ ->
            PlannedAction("read_calendar", buildJsonObject { put("days", 2) })
        },
        Rule(Regex("(?i)go (back|home)")) { m ->
            PlannedAction(if (m.groupValues[1].equals("back", true)) "press_back" else "press_home", buildJsonObject { })
        },
        Rule(Regex("(?i)where am i")) { _ -> PlannedAction("get_location", buildJsonObject { }) },
        Rule(Regex("(?i)search (the web )?for (.+)")) { m ->
            PlannedAction("launch_intent", buildJsonObject { put("kind", "search"); put("value", m.groupValues[2]) })
        },
        Rule(Regex("(?i)find (a |the )?file (.+)")) { m ->
            PlannedAction("find_file", buildJsonObject { put("name", m.groupValues[3]) })
        },
    )

    /**
     * Returns an action if the request is covered, else a final-response decision.
     * [hint]: why the rule engine answered (route reason / timeout / error) so the
     * fallback message reflects the truth instead of a generic claim.
     */
    fun decide(goal: String, screen: ScreenObservation?, hint: String? = null): Planner.Decision {
        for (rule in rules) {
            val m = rule.regex.find(goal)
            if (m != null) {
                rule.build(m)?.let { return Planner.Decision(it, null, "rule:${rule.regex.pattern.take(30)}") }
            }
        }
        // Open app is the most common; try fuzzy app resolution for "open X ..." patterns missed above.
        return Planner.Decision(null, fallbackMessage(hint), "rules:unmatched")
    }

    /**
     * State-aware fallback: the OLD message always claimed "no local model
     * downloaded yet", which was FALSE and deeply confusing when the user had a
     * model activated (LLM timeout path) or the app had restarted (model not yet
     * back in RAM). Report the REAL state instead.
     */
    private fun fallbackMessage(hint: String?): String {
        val capabilities = "I can still run simple commands like \"open Chrome\", \"set brightness to 40\", " +
            "\"turn wifi off\", \"read my notifications\" or \"set volume to 5\"."
        val mm = runCatching { JarvisApp.instance.container.modelManager }.getOrNull()
        if (mm != null) {
            if (mm.llama.isReady()) {
                // Model IS loaded - the LLM just failed/timed out for this round.
                return (hint?.let { "$it. " } ?: "My local model could not complete that reasoning round. ") +
                    "$capabilities Try again, or use a faster/smaller model or API mode for heavy tasks."
            }
            val activeId = mm.activeId()
            if (activeId != null || mm.isLoading()) {
                return "Your model ($activeId) is set but not loaded into memory right now - it unloads after " +
                    "being idle or when the app restarts, and JARVIS is reloading it automatically. " +
                    "You can also activate it from the Models tab. Meanwhile: $capabilities"
            }
            val hasFile = mm.importedFiles().isNotEmpty() ||
                com.jarvis.mobile.core.model.ModelCatalog.MODELS.any { mm.isDownloaded(it) }
            if (hasFile) {
                return "A model is downloaded on this device but none is active. Activate one in the Models tab " +
                    "to unlock full natural-language control. Meanwhile: $capabilities"
            }
        }
        return "I'm running on built-in rules right now (no local model on this device yet). $capabilities " +
            "Download a model in the Models tab - or turn on API mode - to unlock full natural-language control."
    }

    fun available(): Boolean = true

    fun note(): String = "deterministic-rule-engine"
}

/** Facts block used in prompts (memory retrieval + live device state). */
suspend fun factsBlock(): String? {
    val device = deviceFactsLine()
    val facts = runCatching {
        JarvisApp.instance.container.memory.factsSnapshot().take(10)
    }.getOrNull()
    val parts = buildList {
        if (device != null) add("DEVICE NOW: $device")
        if (!facts.isNullOrEmpty()) {
            add("USER FACTS (user-provided, may help):\n" + facts.joinToString("\n") { "- ${it.key}: ${it.value.take(60)}" })
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

/** One compact line of live device truth for the model (battery, clock, thermal). */
private fun deviceFactsLine(): String? = runCatching {
    val ctx = JarvisApp.instance
    val bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
    val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 1..100 }
    val charging = runCatching { bm?.isCharging ?: false }.getOrDefault(false)
    val time = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
    val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
    val thermal = when (pm?.currentThermalStatus ?: -1) {
        0, 1 -> "normal"
        2 -> "light throttle"
        3, 4 -> "throttling"
        else -> "unknown"
    }
    buildString {
        append(time)
        if (level != null) append(" · battery $level%").append(if (charging) " (charging)" else "")
        append(" · thermal $thermal")
    }
}.getOrNull()

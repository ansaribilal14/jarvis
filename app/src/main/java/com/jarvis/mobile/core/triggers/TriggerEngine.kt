package com.jarvis.mobile.core.triggers

import android.content.Context
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.agent.AgentEngine
import com.jarvis.mobile.core.skills.SkillDefinition
import com.jarvis.mobile.core.skills.SkillStore
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires skills automatically from live system events (Easer's Slot/Lotus shape:
 * dumb sources, one smart core). Sources call the on*() entry points; the
 * engine owns matching, cooldowns, dedup and the honest run log.
 *
 * Every entry point is process-safe: skills are re-read from the store, so a
 * trigger that survived a process death still resolves its skill by id.
 */
object TriggerEngine {

    private const val TAG = "triggers"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** skillId -> last fire time (ms). In-memory only: a reboot resetting cooldowns is fine. */
    private val lastFired = HashMap<String, Long>()
    private val lock = Any()

    /** Ring of recent trigger events for the Skills UI (OpenTasker RunLog, mini). */
    data class FireEvent(val at: Long, val trigger: String, val skill: String, val fired: Boolean, val why: String)

    private val _log = ArrayDeque<FireEvent>()
    val recentLog: List<FireEvent> get() = synchronized(lock) { _log.toList() }

    private fun log(trigger: String, skill: String, fired: Boolean, why: String) {
        synchronized(lock) {
            _log.addFirst(FireEvent(System.currentTimeMillis(), trigger, skill, fired, why))
            if (_log.size > 24) _log.removeLast()
        }
    }

    // ------------------------------------------------------------- entry points

    /** The named app moved to foreground / left foreground. */
    fun onAppEvent(context: Context, pkg: String, opened: Boolean) {
        scope.launch {
            val want = if (opened) "APP_OPEN" else "APP_CLOSE"
            SkillStore.list(context).forEach { skill ->
                val t = skill.trigger ?: return@forEach
                if (t.type == want && (t.pkg == null || t.pkg == pkg)) {
                    fire(skill, "$want $pkg")
                }
            }
        }
    }

    /** A notification was posted (title/text already extracted). */
    fun onNotification(context: Context, pkg: String, title: String, text: String) {
        scope.launch {
            SkillStore.list(context).forEach { skill ->
                val t = skill.trigger ?: return@forEach
                if (t.type != "NOTIFICATION") return@forEach
                if (t.pkg != null && t.pkg != pkg) return@forEach
                val needle = t.text?.takeIf { it.isNotBlank() }
                val hit = needle == null || title.contains(needle, true) || text.contains(needle, true)
                if (hit) fire(skill, "NOTIFICATION $pkg", extras = "title=$title; text=$text")
            }
        }
    }

    /** A time alarm went off for [skillId]. */
    fun onTimeFired(context: Context, skillId: String) {
        scope.launch {
            val skill = SkillStore.get(context, skillId) ?: run {
                log("TIME", skillId, fired = false, why = "skill deleted")
                return@launch
            }
            if (skill.trigger?.type != "TIME") return@launch
            fire(skill, "TIME")
            TimeTriggerScheduler.arm(context, skill) // re-arm for tomorrow
        }
    }

    /** Battery-low / battery-okay broadcast. */
    fun onBatteryLow(context: Context) {
        scope.launch {
            SkillStore.list(context).forEach { skill ->
                if (skill.trigger?.type == "BATTERY_LOW") fire(skill, "BATTERY_LOW")
            }
        }
    }

    // ----------------------------------------------------------------- engine

    private fun fire(skill: SkillDefinition, trigger: String, extras: String? = null) {
        val now = System.currentTimeMillis()
        val cooldownMs = (skill.trigger?.cooldownSec ?: 60).coerceAtLeast(0) * 1000
        synchronized(lock) {
            lastFired[skill.id]?.let { last ->
                if (now - last < cooldownMs) {
                    log(trigger, skill.name, fired = false, why = "cooldown ${((now - last) / 1000)}s/${skill.trigger?.cooldownSec ?: 60}s")
                    return
                }
            }
            lastFired[skill.id] = now
        }
        if (AgentEngine.isRunning()) {
            log(trigger, skill.name, fired = false, why = "agent busy")
            return
        }
        val substituted = extras?.let { substitute(skill, it) } ?: skill
        val ok = AgentEngine.runSkill(substituted)
        Logx.i(TAG, "trigger[$trigger] -> skill \"${skill.name}\": ${if (ok) "fired" else "rejected"}")
        log(trigger, skill.name, fired = ok, why = if (ok) "running" else "agent refused")
    }

    /**
     * Minimal Easer-style dynamics: a NOTIFICATION trigger can feed the payload
     * into TEXT steps via placeholders. "{{title}}" and "{{text}}" inside a
     * step's input are replaced with the actual notification content, so one
     * skill can forward any matching notification.
     */
    private fun substitute(skill: SkillDefinition, extras: String): SkillDefinition {
        val title = extras.substringAfter("title=").substringBefore("; text=")
        val text = extras.substringAfter("text=", "")
        var used = false
        val actions = skill.stepList().map { action ->
            if (action.type != "UI_TEXT" || action.input.isNullOrBlank()) return@map action
            val input = action.input!!
            if (!input.contains("{{title}}") && !input.contains("{{text}}")) return@map action
            used = true
            action.copy(input = input.replace("{{title}}", title).replace("{{text}}", text))
        }
        return if (used) skill.copy(actions = actions) else skill
    }
}

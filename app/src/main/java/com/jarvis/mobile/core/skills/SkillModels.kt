package com.jarvis.mobile.core.skills

import android.content.Context
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.util.Logx
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One recorded user action. Semantic first (what + where), coordinates as the
 * fallback match key - so a replay can survive small layout shifts while still
 * acting exactly where the user acted when nothing else matches.
 */
@Serializable
data class SkillStep(
    val type: String, // TAP, LONG_PRESS, TEXT, SCROLL, BACK, HOME, APP_OPEN, WAIT
    val pkg: String? = null,
    val viewId: String? = null,
    val text: String? = null,   // visible label / partial text of the target
    val desc: String? = null,   // content description of the target
    val x: Int? = null,
    val y: Int? = null,
    val input: String? = null,  // typed text (TEXT steps)
    val dir: String? = null,    // scroll direction: fwd / back
    val waitMs: Long? = null,   // WAIT duration / observed gap before this step
) {
    /** Human-readable one-liner for lists, drafts and the activity feed. */
    fun describe(): String = when (type) {
        "TAP" -> "Tap ${label()}"
        "LONG_PRESS" -> "Long-press ${label()}"
        "TEXT" -> "Type \"${input?.take(40) ?: "?"}\" into ${label()}"
        "SCROLL" -> "Scroll ${dir ?: "fwd"}"
        "BACK" -> "Press back"
        "HOME" -> "Go home"
        "APP_OPEN" -> "Open ${pkg?.substringBefore('.') ?: "app"}"
        "WAIT" -> "Wait ${waitMs ?: 500}ms"
        else -> type
    }

    private fun label(): String = when {
        !text.isNullOrBlank() -> "\"${text.take(24)}\""
        !desc.isNullOrBlank() -> "\"${desc.take(24)}\""
        viewId != null -> viewId?.substringAfterLast('/') ?: "?"
        x != null && y != null -> "(@$x,$y)"
        else -> "target"
    }
}

/** One question the /grill-me interviewer asked, with the user's answer. */
@Serializable
data class QAPair(val q: String, val a: String)

/**
 * A replayable skill: recorded (or AI-refined) steps plus the interview context.
 * Stored as JSON in app-private storage - inspectable, editable, deletable.
 */
@Serializable
data class SkillDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val notes: String = "",
    val steps: List<SkillStep>,
    val source: String = "RECORDED", // RECORDED | GRILLED
    val interview: List<QAPair> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val lastRunAtMs: Long? = null,
    val runCount: Int = 0,
)

/**
 * Local persistence for skills: one JSON file per skill under filesDir/skills.
 * Deliberately not Room - skills are few, human-edited documents; files need no
 * schema migration and are trivially inspectable.
 */
object SkillStore {
    private const val TAG = "skills"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun dir(ctx: Context): File = File(ctx.filesDir, "skills").apply { mkdirs() }

    fun list(ctx: Context): List<SkillDefinition> =
        dir(ctx).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString<SkillDefinition>(f.readText()) }
                    .onFailure { Logx.w(TAG, "Corrupt skill file ${f.name}: ${it.message}") }
                    .getOrNull()
            }
            ?.sortedByDescending { it.createdAtMs }
            ?: emptyList()

    fun save(ctx: Context, skill: SkillDefinition) {
        runCatching {
            File(dir(ctx), "${skill.id}.json").writeText(json.encodeToString(SkillDefinition.serializer(), skill))
            Logx.i(TAG, "Saved skill \"${skill.name}\" (${skill.steps.size} steps, ${skill.source})")
        }.onFailure { Logx.e(TAG, "Skill save failed: ${it.message}") }
    }

    fun get(ctx: Context, id: String): SkillDefinition? =
        list(ctx).firstOrNull { it.id == id }

    fun delete(ctx: Context, id: String) {
        runCatching { File(dir(ctx), "$id.json").delete() }
    }

    /** Bump run stats after a replay attempt (kept even on partial failures - honest usage history). */
    fun markRun(ctx: Context, id: String) {
        get(ctx, id)?.let { save(ctx, it.copy(lastRunAtMs = System.currentTimeMillis(), runCount = it.runCount + 1)) }
    }

    fun newId(): String = "skill-${System.currentTimeMillis()}"
}

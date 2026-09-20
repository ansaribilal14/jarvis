package com.jarvis.mobile.core.skills

import android.content.Context
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.triggers.SkillTrigger
import com.jarvis.mobile.util.Logx
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/*
 * Skills v3 data model (docs/SKILLS_V3.md).
 *
 * A skill is a small program: an ordered list of typed ACTIONS backed entirely
 * by public APIs (MacroDroid's model: build actions, don't record touches).
 * The legacy v1.x `SkillStep` shape is still parsed from old JSON files and
 * migrated to actions on load - nothing the user saved is lost.
 */

/**
 * LEGACY (pre-2.3) capture format. Kept only so old skill files parse; new
 * code produces [SkillAction]. Migration lives in [stepsToActions].
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
    val t: Long? = null,        // capture time (ms, device clock) - dedup + ordering aid
) {
    /** Human-readable one-liner for lists, drafts and the activity feed. */
    fun describe(): String = when (type) {
        "TAP" -> "Tap ${label()}"
        "LONG_PRESS" -> "Long-press ${label()}"
        "TEXT" -> "Type \"${input?.take(40) ?: "?"}\" into ${label()}"
        "SCROLL" -> "Scroll ${dir ?: "down"}"
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
 * WHERE + WHAT an action should act on.
 *
 * mode NODE   - match by viewId/text/desc (works whenever the app exposes nodes)
 * mode POINT  - fractional screen coordinates, resolved to pixels at run time
 *               (works in EVERY app - including games and canvas views - and
 *               survives rotation/DPI changes because fractions are relative)
 * mode MIXED  - try nodes first, fall back to the point
 */
@Serializable
data class ElementTarget(
    val mode: String = "NODE",
    val text: String? = null,
    val desc: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val fx: Float? = null,   // 0..1 fraction of screen WIDTH
    val fy: Float? = null,   // 0..1 fraction of screen HEIGHT
    val pxX: Int? = null,    // legacy absolute pixels (pre-2.3 skills)
    val pxY: Int? = null,
    val pkg: String? = null, // the app this target lives in (run-time guard)
) {
    val hasNode: Boolean get() = !viewId.isNullOrBlank() || !text.isNullOrBlank() || !desc.isNullOrBlank()
    val hasPoint: Boolean get() = (fx != null && fy != null) || (pxX != null && pxY != null)

    fun describe(): String = when {
        !text.isNullOrBlank() -> "\"${text.take(28)}\""
        !desc.isNullOrBlank() -> "\"${desc.take(28)}\""
        !viewId.isNullOrBlank() -> viewId!!.substringAfterLast('/')
        fx != null && fy != null -> "(point ${"%.2f".format(fx)},${"%.2f".format(fy)})"
        pxX != null && pxY != null -> "(point $pxX,$pxY)"
        else -> "target"
    }

    /** Resolve to absolute pixels for the CURRENT screen metrics. */
    fun pointPx(screenW: Int, screenH: Int): Pair<Int, Int>? = when {
        fx != null && fy != null ->
            ((fx.coerceIn(0f, 1f) * screenW).toInt() to (fy.coerceIn(0f, 1f) * screenH).toInt())
        pxX != null && pxY != null -> (pxX to pxY)
        else -> null
    }
}

/**
 * One step of a skill program. Every type is backed by a public API and is
 * executed by [SkillRunner] with wait-for-element polling, node-first action,
 * gesture fallback, verification and an honest run log.
 */
@Serializable
data class SkillAction(
    val id: String,
    val type: String,                 // LAUNCH_APP, UI_CLICK, UI_LONG_PRESS, UI_TEXT, SCROLL, BACK, HOME, RECENTS, WAIT, NOTIFY
    val appPackage: String? = null,   // LAUNCH_APP: the app to open
    val target: ElementTarget? = null,// UI_*: what to act on
    val input: String? = null,        // UI_TEXT: what to type
    val dir: String? = null,          // SCROLL: down / up / left / right
    val amount: Int = 1,              // SCROLL: how many screen-heights/pages
    val waitMs: Long? = null,         // WAIT: duration
    val message: String? = null,      // NOTIFY: text to show
    val note: String? = null,         // user's own label for this action
) {
    fun describe(): String {
        if (!note.isNullOrBlank()) return note
        return when (type) {
            "LAUNCH_APP" -> "Open ${appPackage?.substringBefore('.') ?: "app"}"
            "UI_CLICK" -> "Tap ${target?.describe() ?: "?"}"
            "UI_LONG_PRESS" -> "Long-press ${target?.describe() ?: "?"}"
            "UI_TEXT" -> "Type \"${input?.take(30) ?: "?"}\" into ${target?.describe() ?: "field"}"
            "SCROLL" -> "Scroll ${dir ?: "down"}" + if (amount > 1) " x${amount}" else ""
            "BACK" -> "Press Back"
            "HOME" -> "Go Home"
            "RECENTS" -> "Open Recents"
            "WAIT" -> "Wait ${((waitMs ?: 1000) / 1000.0)}s"
            "NOTIFY" -> "Notify: ${message?.take(30) ?: ""}"
            else -> type
        }
    }

    fun copyWith(id: String = this.id, type: String = this.type): SkillAction = copy(id = id, type = type)
}

/**
 * A runnable skill. `actions` is the program; `steps` is the legacy field kept
 * nullable purely to parse pre-2.3 files. Use [stepList] everywhere in code.
 */
@Serializable
data class SkillDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val notes: String = "",
    val actions: List<SkillAction> = emptyList(),
    val steps: List<SkillStep>? = null, // legacy, always null after a v2.3 save
    val source: String = "BUILT",       // BUILT | RECORDED | GRILLED
    val interview: List<QAPair> = emptyList(),
    val trigger: SkillTrigger? = null,  // null = manual run only
    val createdAtMs: Long = System.currentTimeMillis(),
    val lastRunAtMs: Long? = null,
    val runCount: Int = 0,
    val lastRunOk: Boolean? = null,
    val lastRunLog: List<String> = emptyList(),
) {
    /** The executable program: actions, or legacy steps migrated on the fly. */
    fun stepList(): List<SkillAction> =
        if (actions.isNotEmpty()) actions
        else steps.orEmpty().mapIndexed { i, s -> stepsToAction(s, i) }
}

// ------------------------------------------------------------------ migration

/** Legacy step type -> v3 action type. */
fun legacyTypeToActionType(t: String): String? = when (t) {
    "TAP" -> "UI_CLICK"
    "LONG_PRESS" -> "UI_LONG_PRESS"
    "TEXT" -> "UI_TEXT"
    "SCROLL" -> "SCROLL"
    "BACK" -> "BACK"
    "HOME" -> "HOME"
    "APP_OPEN" -> "LAUNCH_APP"
    "WAIT" -> "WAIT"
    else -> null
}

/** Map one legacy step to a v3 action (pure - unit-tested). */
fun stepsToAction(s: SkillStep, index: Int): SkillAction {
    val type = legacyTypeToActionType(s.type) ?: "UI_CLICK"
    val target = if (type == "UI_CLICK" || type == "UI_LONG_PRESS" || type == "UI_TEXT") {
        ElementTarget(
            mode = if (s.text.isNullOrBlank() && s.desc.isNullOrBlank() && s.viewId.isNullOrBlank()) "POINT" else "MIXED",
            text = s.text?.takeIf { !it.startsWith("@") },
            desc = s.desc,
            viewId = s.viewId,
            pxX = s.x,
            pxY = s.y,
            pkg = s.pkg,
        )
    } else null
    val dir = if (type == "SCROLL") when (s.dir) {
        "fwd", "down", null -> "down"
        "back" -> "up"
        else -> s.dir
    } else null
    return SkillAction(
        id = "m$index",
        type = type,
        appPackage = if (type == "LAUNCH_APP") s.pkg else null,
        target = target,
        input = s.input,
        dir = dir,
        waitMs = s.waitMs,
    )
}

/** Migrate a whole legacy recording to the v3 program (pure - unit-tested). */
fun stepsToActions(steps: List<SkillStep>): List<SkillAction> =
    steps.mapIndexed { i, s -> stepsToAction(s, i) }

/** Inverse bridge: v3 actions -> legacy steps (for the /grill-me step pipeline). */
fun actionsToSteps(actions: List<SkillAction>): List<SkillStep> = actions.map { a ->
    SkillStep(
        type = when (a.type) {
            "UI_CLICK" -> "TAP"
            "UI_LONG_PRESS" -> "LONG_PRESS"
            "UI_TEXT" -> "TEXT"
            "LAUNCH_APP" -> "APP_OPEN"
            "SCROLL" -> "SCROLL"
            "BACK" -> "BACK"
            "HOME" -> "HOME"
            "WAIT" -> "WAIT"
            else -> "TAP"
        },
        pkg = a.appPackage ?: a.target?.pkg,
        viewId = a.target?.viewId,
        text = a.target?.text,
        desc = a.target?.desc,
        x = a.target?.pxX,
        y = a.target?.pxY,
        input = a.input,
        dir = when (a.dir) {
            "down" -> "fwd"
            "up" -> "back"
            else -> a.dir
        },
        waitMs = a.waitMs,
    )
}

/**
 * Local persistence for skills: one JSON file per skill under filesDir/skills.
 * Deliberately not Room - skills are few, human-edited documents; files need no
 * schema migration and are trivially inspectable. Load/save NORMALIZE: legacy
 * step-only files are migrated to actions transparently.
 */
object SkillStore {
    private const val TAG = "skills"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun dir(ctx: Context): File = File(ctx.filesDir, "skills").apply { mkdirs() }

    /** Parse + normalize one skill file: legacy steps become actions. */
    fun normalize(decoded: SkillDefinition): SkillDefinition =
        if (decoded.actions.isEmpty() && !decoded.steps.isNullOrEmpty()) {
            decoded.copy(actions = stepsToActions(decoded.steps), steps = null)
        } else decoded

    fun list(ctx: Context): List<SkillDefinition> =
        dir(ctx).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString<SkillDefinition>(f.readText()) }
                    .onFailure { Logx.w(TAG, "Corrupt skill file ${f.name}: ${it.message}") }
                    .getOrNull()
            }
            ?.map { normalize(it) }
            ?.sortedByDescending { it.createdAtMs }
            ?: emptyList()

    fun save(ctx: Context, skill: SkillDefinition) {
        runCatching {
            val clean = skill.copy(
                actions = if (skill.actions.isNotEmpty()) skill.actions else stepsToActions(skill.steps.orEmpty()),
                steps = null,
            )
            File(dir(ctx), "${skill.id}.json").writeText(json.encodeToString(SkillDefinition.serializer(), clean))
            Logx.i(TAG, "Saved skill \"${skill.name}\" (${clean.actions.size} actions, ${clean.source})")
        }.onFailure { Logx.e(TAG, "Skill save failed: ${it.message}") }
    }

    fun get(ctx: Context, id: String): SkillDefinition? =
        list(ctx).firstOrNull { it.id == id }

    fun delete(ctx: Context, id: String) {
        runCatching { File(dir(ctx), "$id.json").delete() }
    }

    /** Bump run stats + persist the run log after a replay attempt (honest history). */
    fun markRun(ctx: Context, id: String, ok: Boolean? = null, log: List<String>? = null) {
        get(ctx, id)?.let {
            save(
                ctx,
                it.copy(
                    lastRunAtMs = System.currentTimeMillis(),
                    runCount = it.runCount + 1,
                    lastRunOk = ok ?: it.lastRunOk,
                    lastRunLog = log ?: it.lastRunLog,
                ),
            )
        }
    }

    fun newId(): String = "skill-${System.currentTimeMillis()}"
}

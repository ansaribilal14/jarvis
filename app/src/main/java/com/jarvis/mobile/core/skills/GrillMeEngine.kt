package com.jarvis.mobile.core.skills

import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.model.ModelCatalog
import com.jarvis.mobile.core.model.PromptTemplates
import com.jarvis.mobile.core.routing.ModelRouter
import com.jarvis.mobile.util.JsonX
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * /grill-me - the built-in requirements interviewer.
 *
 * Instead of saving a raw recording as-is, JARVIS interrogates the user with
 * targeted, step-by-step questions (which app, what varies between runs, what
 * must never happen without asking, what to do when a control is missing),
 * THEN writes the full skill (name, description, guardrail notes, refined
 * steps). The user reviews/edits/iterates the draft before it is saved.
 *
 * Runs on whatever brain is available: the local model, API mode, or a
 * deterministic question ladder when neither can - the interview always works.
 */
object GrillMeEngine {
    private const val TAG = "grill-me"
    private const val MAX_QUESTIONS = 5

    data class GrillState(
        val active: Boolean = false,
        val steps: List<SkillStep> = emptyList(),
        val qa: List<QAPair> = emptyList(),
        val currentQuestion: String? = null,
        val questionIndex: Int = 0,
        val thinking: Boolean = false,
        val done: Boolean = false,
        val draft: SkillDefinition? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(GrillState())
    val state: StateFlow<GrillState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Deterministic ladder used when no LLM is available - still a real interview. */
    private val cannedQuestions = listOf(
        "What should this skill be called, and in one line - what is it for?",
        "Which app does it start in, and does a specific screen need to be open first?",
        "Any text it types (messages, search terms) - identical every run, or will you fill it in each time?",
        "If a button or field it needs is missing - should it scroll to look for it, or stop and tell you?",
        "What must it NEVER do without asking you first (send, delete, pay, share)?",
    )

    fun start(steps: List<SkillStep>) {
        if (steps.isEmpty()) {
            _state.value = GrillState(error = "Nothing recorded yet.")
            return
        }
        SkillRecorder.suppress = true // the interview UI must not be recorded
        _state.value = GrillState(active = true, steps = steps)
        askNext()
    }

    fun answer(text: String) {
        val st = _state.value
        val q = st.currentQuestion ?: return
        _state.value = st.copy(qa = st.qa + QAPair(q, text.trim()), currentQuestion = null)
        askNext()
    }

    fun skip() {
        val st = _state.value
        val q = st.currentQuestion ?: return
        _state.value = st.copy(qa = st.qa + QAPair(q, "(no answer)"), currentQuestion = null)
        askNext()
    }

    /** Write the full skill draft now (ends the interview). */
    fun finish() {
        val st = _state.value.copy(thinking = true, currentQuestion = null)
        _state.value = st
        scope.launch { writeDraft() }
    }

    fun cancel() {
        _state.value = GrillState()
        SkillRecorder.suppress = false
    }

    /** Called when the draft has been consumed by the editor screen. */
    fun clear() {
        _state.value = GrillState()
        SkillRecorder.suppress = false
    }

    // ------------------------------------------------------------- internals

    private fun askNext() {
        val st = _state.value
        if (st.qa.size >= MAX_QUESTIONS) {
            finish()
            return
        }
        _state.value = st.copy(thinking = true)
        scope.launch {
            val index = _state.value.qa.size
            val question = llmQuestion(index) ?: cannedQuestions.getOrNull(index) ?: cannedQuestions.last()
            _state.value = _state.value.copy(
                thinking = false,
                currentQuestion = question,
                questionIndex = index + 1,
            )
        }
    }

    private suspend fun llmQuestion(index: Int): String? {
        val st = _state.value
        val stepsJson = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.serializer<List<SkillStep>>(), st.steps,
        )
        val qaText = st.qa.joinToString("\n") { "Q: ${it.q}\nA: ${it.a}" }
        val system = "You are JARVIS's requirements interviewer (/grill-me). You interrogate the user " +
            "ONE question at a time about a recorded phone automation so the final skill is unambiguous. " +
            "Ask about things that change how the skill must run: which app/screen it assumes, what varies per run, " +
            "dynamic vs fixed text, failure behavior, and dangerous actions needing confirmation. " +
            "Be concrete and reference the recorded steps. Ask SHORT questions a person can answer in one line. " +
            "OUTPUT EXACTLY ONE JSON OBJECT and nothing else: {\"question\":\"...\"}"
        val user = "RECORDED STEPS:\n$stepsJson\n\nANSWERS SO FAR:\n${qaText.ifBlank { "(none yet)" }}\n\n" +
            "Ask question number ${index + 1} of $MAX_QUESTIONS. Do not repeat anything already answered. " +
            "OUTPUT ONE JSON: {\"question\":\"...\"}"
        val obj = llm(system, user, maxTokens = 90)
            ?: return null
        for (cand in JsonX.jsonCandidates(obj)) {
            val q = cand.str("question")
            if (!q.isNullOrBlank()) return q.take(300)
        }
        return null
    }

    private suspend fun writeDraft() {
        val st = _state.value
        val base = SkillDefinition(
            id = SkillStore.newId(),
            name = suggestedName(st),
            description = suggestedDescription(st),
            notes = notesFromInterview(st),
            steps = st.steps,
            source = "GRILLED",
            interview = st.qa,
        )
        val refined = llmRefine(st) ?: base
        _state.value = GrillState(done = true, steps = st.steps, qa = st.qa, draft = refined)
        Logx.i(TAG, "Draft written: \"${refined.name}\" (${refined.steps.size} steps, LLM=${refined !== base})")
    }

    /** The first Q&A usually names the skill; fall back to the first meaningful step. */
    private fun suggestedName(st: GrillState): String {
        st.qa.firstOrNull()?.a?.takeIf { it.isNotBlank() && it != "(no answer)" }?.let { full ->
            // "Call it X - it does Y" / "X, it does Y" -> take the best-looking first clause.
            val first = full.split(" - ", " — ", ", ", ". ").first().trim()
            val name = first.substringBefore(" it ").trim().removePrefix("Call it ").removePrefix("Maybe ")
            if (name.isNotBlank()) return name.take(48)
        }
        return st.steps.firstOrNull { it.type == "APP_OPEN" }?.pkg?.substringAfterLast('.')
            ?.replaceFirstChar { it.uppercase() }?.let { "$it skill" } ?: "My skill"
    }

    private fun suggestedDescription(st: GrillState): String =
        st.qa.firstOrNull()?.a?.take(200)?.takeIf { it.isNotBlank() && it != "(no answer)" }
            ?: st.steps.joinToString(" → ") { it.describe() }.take(200)

    private fun notesFromInterview(st: GrillState): String = buildString {
        st.qa.drop(1).forEach { qa ->
            if (qa.a.isNotBlank() && qa.a != "(no answer)") append("• ${qa.q}\n  → ${qa.a}\n")
        }
    }.trim()

    /**
     * Ask the model to rewrite the steps using the interview answers (add waits,
     * adjust match labels, note per-run text). Every returned step is validated
     * against the allowed types; anything malformed falls back to the recording.
     */
    private suspend fun llmRefine(st: GrillState): SkillDefinition? {
        val stepsJson = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.serializer<List<SkillStep>>(), st.steps,
        )
        val qaText = st.qa.joinToString("\n") { "Q: ${it.q}\nA: ${it.a}" }
        val system = "You convert a recorded phone-automation into a final, runnable SKILL using the user's " +
            "interview answers. Keep the recorded step ORDER. Allowed step types ONLY: " +
            "TAP, LONG_PRESS, TEXT, SCROLL, BACK, HOME, APP_OPEN, WAIT. " +
            "Rules: TAP/TEXT steps keep pkg+text (or desc/viewId); TEXT uses input for what to type; " +
            "WAIT takes waitMs 300-3000; you may INSERT a WAIT between steps where the user described loading time; " +
            "you may INSERT a SCROLL before a TAP if the user said the control needs scrolling; " +
            "never invent taps to apps or screens the recording does not touch. " +
            "OUTPUT EXACTLY ONE JSON OBJECT: {\"name\":\"...\",\"description\":\"...\",\"notes\":\"guardrails from the answers\",\"steps\":[{...}]}"
        val user = "RECORDED STEPS:\n$stepsJson\n\nINTERVIEW:\n${qaText.ifBlank { "(no answers captured)" }}\n\n" +
            "Write the final skill JSON now."
        val raw = llm(system, user, maxTokens = 500) ?: return null
        return parseSkillJson(raw, st)
    }

    private fun parseSkillJson(raw: String, st: GrillState): SkillDefinition? {
        val obj = JsonX.jsonCandidates(raw).firstOrNull { it.containsKey("steps") || it.containsKey("name") }
            ?: return null
        val allowed = setOf("TAP", "LONG_PRESS", "TEXT", "SCROLL", "BACK", "HOME", "APP_OPEN", "WAIT")
        val steps = runCatching {
            obj["steps"]?.jsonArray?.mapNotNull { el ->
                val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                val type = o.str("type")?.uppercase() ?: return@mapNotNull null
                if (type !in allowed) return@mapNotNull null
                SkillStep(
                    type = type,
                    pkg = o.str("pkg"),
                    viewId = o.str("viewId"),
                    text = o.str("text"),
                    desc = o.str("desc"),
                    x = o.num("x")?.toIntOrNull(),
                    y = o.num("y")?.toIntOrNull(),
                    input = o.str("input"),
                    dir = o.str("dir"),
                    waitMs = o.num("waitMs")?.toLongOrNull(),
                )
            }
        }.getOrNull() ?: emptyList()
        if (steps.size !in 1..40) return null
        val name = obj.str("name")?.take(48)
            ?: st.qa.firstOrNull()?.a?.take(48) ?: "My skill"
        return SkillDefinition(
            id = SkillStore.newId(),
            name = name,
            description = obj.str("description")?.take(300) ?: suggestedDescription(st),
            notes = obj.str("notes")?.take(500) ?: notesFromInterview(st),
            steps = steps,
            source = "GRILLED",
            interview = st.qa,
        )
    }

    /** Null-safe string field: non-strings and JSON nulls become null. */
    private fun JsonObject.str(key: String): String? =
        runCatching { (this[key] as? JsonPrimitive)?.content }.getOrNull()
            ?.takeIf { it != "null" }

    private fun JsonObject.num(key: String): String? =
        runCatching { (this[key] as? JsonPrimitive)?.content }.getOrNull()
            ?.takeIf { it != "null" && it.toDoubleOrNull() != null }

    // ------------------------------------------------------------ LLM access

    private suspend fun llm(system: String, user: String, maxTokens: Int): String? {
        val c = JarvisApp.instance.container
        val router = ModelRouter(c.settings, c.modelManager.llama, c.remoteProvider)
        val route = withTimeoutOrNull(10_000) { router.decide() } ?: return null
        if (route.route == ModelRouter.Route.RULES) return null
        val template = c.modelManager.llama.activeModel?.template ?: ModelCatalog.ChatTemplate.CHATML
        val prompt = PromptTemplates.render(template, system, user)
        val timeoutMs = if (route.route == ModelRouter.Route.LOCAL) 120_000L else 60_000L
        return withTimeoutOrNull(timeoutMs) {
            router.generate(prompt, maxTokens, stopSequences = listOf("\n\n", "\nUSER", "\nQ:"))
                .first.getOrNull()
        }
    }
}

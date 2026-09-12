package com.jarvis.mobile.core.agent

import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.accessibility.JarvisAccessibilityService
import com.jarvis.mobile.core.observer.ScreenObservation
import com.jarvis.mobile.core.planner.DeterministicPlanner
import com.jarvis.mobile.core.planner.Planner
import com.jarvis.mobile.core.planner.factsBlock
import com.jarvis.mobile.core.routing.ModelRouter
import com.jarvis.mobile.core.safety.InjectionGuard
import com.jarvis.mobile.core.safety.RiskClassifier
import com.jarvis.mobile.core.tools.PlannedAction
import com.jarvis.mobile.core.tools.ToolContext
import com.jarvis.mobile.core.tools.ToolRegistry
import com.jarvis.mobile.core.tools.ToolResult
import com.jarvis.mobile.core.tools.ToolStatus
import com.jarvis.mobile.core.tools.Verification
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Everything the live agent UI needs (spec: LIVE AGENT UI). */
data class StepUi(
    val idx: Int,
    val tool: String,
    val label: String,
    val status: String, // PENDING RUNNING SUCCESS FAILED BLOCKED SKIPPED
    val verdict: String? = null,
)

/** Human-readable line in the live activity feed ("something is happening" visibility). */
data class AgentEvent(
    val atMs: Long, // wall clock
    val elapsedMs: Long, // relative to task start
    val text: String,
    val kind: String, // info ok warn err model
)

data class AgentUiState(
    val status: AgentStatus = AgentStatus.IDLE,
    val goal: String = "",
    val steps: List<StepUi> = emptyList,
    val activeTool: String? = null,
    val currentApp: String? = null,
    val route: String = "LOCAL",
    val finalResponse: String? = null,
    val confirmation: ConfirmationRequest? = null,
    val pausedForUser: Boolean = false,
    // Live-progress additions (v1.2): user must always know what is happening.
    val startedAtMs: Long = 0L,
    val elapsedMs: Long = 0L,
    val stepIndex: Int = 0,
    val stepBudget: Int = 0,
    val thinkingDetail: String? = null,
    val events: List<AgentEvent> = emptyList,
)

enum class AgentStatus { IDLE, THINKING, ACTING, VERIFYING, WAITING_CONFIRMATION, COMPLETED, FAILED, STOPPED }

/**
 * The agent engine. Real observe → decide → act → verify → adapt loop with
 * bounded budgets, global stop, user-override detection and honest outcomes.
 * Every phase transition emits a live event + elapsed clock, so slow on-device
 * inference is never a silent black box.
 */
object AgentEngine {

    private lateinit var c: JarvisApp.Container
    private lateinit var router: ModelRouter

    private val _state = MutableStateFlow(AgentUiState)
    val state: StateFlow<AgentUiState> = _state
    private val stateLock = Any

    /** Single serialized state writer - prevents ticker/loop/event lost-update races. */
    private fun commit(f: (AgentUiState) -> AgentUiState) {
        synchronized(stateLock) { _state.value = f(_state.value) }
    }

    private val _confirmations = MutableSharedFlow<ConfirmationRequest>(extraBufferCapacity = 4)
    val confirmations: SharedFlow<ConfirmationRequest> = _confirmations

    private var job: Job? = null
    @Volatile private var stopped = false
    @Volatile private var diverged = false
    @Volatile private var slowWarned = false

    /** Live activity feed for the running task (member-level so all phases can log). */
    private val taskEvents = mutableListOf<AgentEvent>
    @Volatile private var taskStartMs = 0L

    private fun event(text: String, kind: String = "info") {
        synchronized(stateLock) {
            taskEvents.add(AgentEvent(System.currentTimeMillis, System.currentTimeMillis - taskStartMs, text, kind))
            if (taskEvents.size > 60) taskEvents.removeAt(0)
            _state.value = _state.value.copy(events = taskEvents.toList)
        }
        Logx.i(TAG, "event[$kind] $text")
    }

    fun init(container: JarvisApp.Container) {
        c = container
        router = ModelRouter(container.settings, container.modelManager.llama, container.remoteProvider)
        // Confirmation bridge: engine surfaces requests to UI + notification layer.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            ConfirmationManager.requests.collect { req ->
                _confirmations.emit(req)
                commit { it.copy(status = AgentStatus.WAITING_CONFIRMATION, confirmation = req) }
            }
        }
        // User-touch divergence detection (spec: HUMAN OVERRIDE).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            JarvisAccessibilityService.userTouches.collect {
                if (_state.value.status == AgentStatus.ACTING || _state.value.status == AgentStatus.VERIFYING) {
                    diverged = true
                    Logx.w(TAG, "User touched the screen during execution - will re-observe before next action")
                }
            }
        }
        // Idle model unloader for battery management.
        c.modelManager.startIdleWatchdog
    }

    fun isRunning: Boolean = job?.isActive == true

    fun stop {
        stopped = true
        router.cancelLocal
        ConfirmationManager.cancelAll
        job?.cancel
        commit {
            it.copy(
                status = AgentStatus.STOPPED,
                confirmation = null,
                finalResponse = it.finalResponse ?: "Stopped by user.",
            )
        }
        Logx.w(TAG, "Agent STOPPED by user")
        c.voiceOutput.speak("Stopped.")
        com.jarvis.mobile.service.AgentForegroundService.stopAll
    }

    fun answerConfirmation(id: String, approved: Boolean) = ConfirmationManager.answer(id, approved)

    fun runGoal(goal: String, source: String = "TEXT") {
        if (isRunning) {
            Logx.w(TAG, "Task requested while busy: ignored (stop first)")
            return
        }
        // Direct intents: pure information requests are answered WITHOUT any AI -
        // no router, no model, nothing to hang or hallucinate. Runs even when no
        // model is loaded and even while the local model is busy.
        if (source != "ROUTINE" && isDirectNotificationRequest(goal)) {
            runDirectNotifications(goal, source)
            return
        }
        stopped = false
        diverged = false
        slowWarned = false
        job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            execute(goal, source)
        }
    }

    /** Strict whole-utterance matcher: ONLY pure notification-reading requests bypass the AI. */
    private fun isDirectNotificationRequest(goal: String): Boolean {
        val g = goal.trim.lowercase(Locale.US).removeSuffix("?").replace("!", "").trim
        if (g.length > 48) return false // anything longer has extra clauses -> normal agent path
        return Regex(
            "^(please |jarvis |can you |could you )*(read|show|check|list|see|get|pull|any|what are|what's|whats)" +
                "( me)?( my| the| recent| new| latest| any| all| me the| me my)* ?notifications?$",
            RegexOption.IGNORE_CASE,
        ).matches(g)
    }

    /**
     * Zero-AI fast path for "read my notifications": executes the notification
     * reader directly, shows + speaks the result in ~1 second. Deterministic by
     * design - it cannot get stuck at the model, cannot route anywhere, and
     * cannot hallucinate notification contents (they come from the OS listener).
     */
    private fun runDirectNotifications(goal: String, source: String) {
        stopped = false
        diverged = false
        slowWarned = false
        job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            val startMs = System.currentTimeMillis
            taskStartMs = startMs
            synchronized(stateLock) { taskEvents.clear }
            val taskId = c.memory.startTask(goal, source)
            c.memory.addChat("USER", goal, taskId)
            val ticker = launch {
                while (isActive) {
                    delay(1000)
                    commit { it.copy(elapsedMs = System.currentTimeMillis - startMs) }
                }
            }
            commit {
                it.copy(
                    status = AgentStatus.THINKING, goal = goal, finalResponse = null, route = "DIRECT",
                    startedAtMs = startMs, elapsedMs = 0, stepIndex = 1, stepBudget = 1,
                    thinkingDetail = null, events = emptyList, confirmation = null,
                )
            }
            event("Direct request - no AI needed, reading notifications now", "ok")
            Logx.i(TAG, "Direct intent: notifications (source=$source)")
            val tool = ToolRegistry.get("read_notifications")
            val result = if (tool == null) ToolResult.fail("Notification reader unavailable.")
            else withTimeoutOrNull(10_000) {
                tool.execute(kotlinx.serialization.json.JsonObject(emptyMap), ToolContext(null))
            } ?: ToolResult.fail("Reading notifications timed out.")
            val response = buildString {
                append(result.message)
                if (!result.detail.isNullOrBlank) {
                    append("\n")
                    append(result.detail)
                }
                if (result.status != ToolStatus.SUCCESS && result.recoveryHint == "open-notification-listener-settings") {
                    append("\n(Settings > Apps > Special access > Notification access > JARVIS)")
                }
            }
            val ok = result.status == ToolStatus.SUCCESS
            commit {
                it.copy(
                    status = if (ok) AgentStatus.COMPLETED else AgentStatus.FAILED,
                    finalResponse = response,
                    thinkingDetail = null,
                    elapsedMs = System.currentTimeMillis - startMs,
                )
            }
            event(if (ok) "Notifications read directly (no AI)" else "Could not read notifications", if (ok) "ok" else "err")
            c.memory.finishTask(taskId, if (ok) "COMPLETED" else "FAILED", response.take(300), 1)
            c.memory.addChat("JARVIS", response, taskId)
            c.voiceOutput.speak(response.take(180))
            ticker.cancel
        }
    }

    // ------------------------------------------------------------------ loop

    private suspend fun execute(goal: String, source: String) = coroutineScope {
        val startMs = System.currentTimeMillis
        taskStartMs = startMs
        synchronized(stateLock) { taskEvents.clear }
        val taskId = c.memory.startTask(goal, source)
        c.memory.addChat("USER", goal, taskId)
        val rawMax = runCatching { c.settings.maxActions.first }.getOrDefault(12)
        val unlimited = rawMax <= 0 // 0 (or negative) = unlimited; loop guards still protect the user
        val maxActions = if (unlimited) Int.MAX_VALUE else rawMax
        val budgetLabel = if (unlimited) "unlimited" else "max $rawMax"
        var actionsUsed = 0
        val history = mutableListOf<Pair<PlannedAction, String>>
        val steps = mutableListOf<StepUi>

        // Anti-repeat / anti-stuck state (v1.3).
        var lastSig: String? = null
        var repeatCount = 0
        var consecFails = 0
        var interpFails = 0
        var afterNote: String? = null

        fun update(f: (AgentUiState) -> AgentUiState) {
            synchronized(stateLock) { _state.value = f(_state.value).copy(steps = steps.toList) }
        }

        // 1 Hz heartbeat: elapsed clock + live generation stats folded into the UI,
        // so a 60s local inference shows "writing · 34 tokens · 4.2 tok/s" instead of a frozen label.
        val ticker = launch {
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis
                val st = _state.value
                var detail: String? = null
                if (st.status == AgentStatus.THINKING) {
                    val g = c.modelManager.llama.genState.value
                    detail = when {
                        g.generating && g.phase == 0 && g.promptTotal > 0 ->
                            "Reading context ${g.promptDone}/${g.promptTotal} tokens…"
                        g.generating && g.phase == 1 ->
                            "Writing · ${g.outTokens} tokens · ${fmt1(g.tokensPerSec)} tok/s"
                        g.generating -> "Waking the model…"
                        else -> null
                    }
                    if (g.generating && g.outTokens == 0 && now - startMs > 90_000 && !slowWarned) {
                        slowWarned = true
                        event("Model is slow to respond (large context / thermal throttling) - still running, not frozen.", "warn")
                    }
                }
                commit { it.copy(elapsedMs = now - startMs, thinkingDetail = detail) }
            }
        }

        update {
            it.copy(
                status = AgentStatus.THINKING, goal = goal, finalResponse = null, route = "…",
                startedAtMs = startMs, elapsedMs = 0, stepIndex = 0, stepBudget = if (unlimited) -1 else rawMax,
                thinkingDetail = null, events = emptyList,
            )
        }
        event("Task received: \"$goal\" (actions: $budgetLabel)", "ok")
        com.jarvis.mobile.service.AgentForegroundService.start("Working on: $goal")
        c.voiceOutput.speak("Working on it.")
        Logx.i(TAG, "Task start: \"$goal\" (source=$source, actions=$budgetLabel)")

        try {
            var finalResponse: String? = null

            // PLAN-FIRST: compound goals get a one-shot upfront
            // JSON plan, then every step still runs through the grounded loop
            // (real screen before every action - no blind plan execution).
            var planSteps = ArrayDeque<PlannedAction>
            var planTotal = 0
            runCatching {
                val routeNow = router.decide
                if (routeNow.route != ModelRouter.Route.RULES && source != "ROUTINE" && Planner.isCompoundGoal(goal)) {
                    event("Compound goal - drafting a plan first…")
                    val prompt = com.jarvis.mobile.core.model.PromptTemplates.render(
                        c.modelManager.llama.activeModel?.template
                            ?: com.jarvis.mobile.core.model.ModelCatalog.ChatTemplate.CHATML,
                        Planner.planSystemPrompt(InjectionGuard.inspect(null), factsBlock),
                        Planner.planUserPrompt(goal),
                    )
                    val steps = llmGenerate(prompt, maxTokens = 500)?.let { Planner.parsePlan(it) } ?: emptyList
                    if (steps.size >= 2) {
                        planSteps = ArrayDeque(steps)
                        planTotal = steps.size
                        event("Plan: " + steps.mapIndexed { i, s -> "${i + 1}) ${s.tool}" }.joinToString(" → "), "ok")
                    } else {
                        event("No usable plan produced - continuing step-by-step")
                    }
                }
            }

            while (actionsUsed < maxActions && !stopped) {
                // 1. OBSERVE -------------------------------------------------
                update { it.copy(status = AgentStatus.THINKING, pausedForUser = diverged, thinkingDetail = null) }
                event("Observing screen…")
                var screen: ScreenObservation? = null
                if (diverged) {
                    Logx.i(TAG, "Divergence: re-observing fresh state")
                    event("You touched the screen - re-observing fresh state", "warn")
                    diverged = false
                }
                if (JarvisAccessibilityService.isReady) {
                    screen = withTimeoutOrNull(2500) {
                        JarvisAccessibilityService.INSTANCE?.observe
                    }
                }
                if (screen == null) event("Accessibility observation unavailable (service off?)", "warn")
                val suspicion = InjectionGuard.inspect(screen)
                if (suspicion.level == InjectionGuard.Level.SUSPICIOUS) {
                    Logx.w(TAG, "Prompt-injection pattern detected on screen: ${suspicion.reasons}")
                    event("Suspicious on-screen instructions ignored (prompt-injection guard)", "warn")
                }

                // 2. DECIDE --------------------------------------------------
                event("Thinking about the next move…")
                val stepWarnings = buildList {
                    if (interpFails >= 1) add("Your previous output was NOT a valid action JSON. Respond with EXACTLY ONE JSON object per the OUTPUT CONTRACT - no prose, no markdown.")
                    if (repeatCount >= 1) add("You already tried this exact action ${repeatCount + 1} time(s) and it did not work. Choose a DIFFERENT approach or finish honestly.")
                    if (consecFails >= 2) add("$consecFails actions in a row failed. Re-read the <screen> block carefully and change strategy.")
                }
                val planNote = if (planSteps.isNotEmpty) {
                    val cur = planSteps.first
                    "step ${planTotal - planSteps.size + 1} of $planTotal: ${cur.tool}(${cur.args.toString.take(120)})"
                } else null
                val decision = decide(goal, screen, history, suspicion, actionsUsed, budgetLabel, stepWarnings, afterNote, planNote)
                update { it.copy(route = decision.routeName) }

                // Unparseable model output: retry instead of dying (small local models do this).
                if (decision.action == null && decision.response == null) {
                    interpFails++
                    event("Model output was not a valid action - retrying ($interpFails/3)", "warn")
                    if (interpFails >= 3) {
                        finalResponse = "I could not produce a valid action plan from the model output after $interpFails attempts. " +
                            "For multi-step app tasks a larger model (or a remote provider in Settings) works better."
                        event("Giving up: repeated invalid model output", "err")
                        break
                    }
                    delay(400)
                    continue
                }
                interpFails = 0

                if (decision.action == null) {
                    event("Final answer ready", "ok")
                    finalResponse = decision.response
                    break
                }
                val action = decision.action
                event("Planned: ${action.tool}", "model")

                // Loop guard: identical action (tool + args) repeated = the model is guessing.
                val sig = action.tool + ":" + action.args.toString
                if (sig == lastSig) repeatCount++ else { repeatCount = 0; lastSig = sig }
                if (repeatCount >= 3) {
                    finalResponse = "I repeated the same action (\"${action.tool}\") ${repeatCount + 1} times without progress, " +
                        "so I stopped to avoid looping forever. Last result: ${history.lastOrNull?.second?.take(140) ?: "none"}."
                    event("Loop guard: same action kept repeating - stopped honestly", "err")
                    break
                }
                if (repeatCount >= 1) event("Repeat guard: same action as last step (attempt ${repeatCount + 1})", "warn")

                val tool = ToolRegistry.get(action.tool)
                if (tool == null || !tool.available) {
                    finalResponse = "The action \"${action.tool}\" is not available right now (missing permission or service)."
                    history.add(action to "BLOCKED: tool unavailable")
                    steps.add(StepUi(steps.size + 1, action.tool, action.tool, "BLOCKED"))
                    event("\"${action.tool}\" unavailable - blocked", "err")
                    consecFails++
                    continue
                }

                // 3. SAFETY / CONFIRMATION -----------------------------------
                val risk = RiskClassifier.classify(tool.spec, action.args, screen?.packageName)
                val autoApprove = runCatching { c.settings.autoApproveMedium.first }.getOrDefault(false)
                val needsConfirm = RiskClassifier.requiresConfirmation(risk, autoApprove) &&
                    source != "ROUTINE" // routines run only LOW-risk tools anyway
                if (needsConfirm) {
                    event("Risky action - asking for your confirmation", "warn")
                    val approved = ConfirmationManager.request(
                        what = "Run \"${tool.spec.name}\"",
                        target = screen?.packageName ?: "system",
                        details = action.args.toString.take(220),
                        why = RiskClassifier.whyConfirmation(tool.spec, risk, action.args),
                        risk = risk.name,
                    )
                    update { it.copy(confirmation = null) }
                    if (!approved) {
                        finalResponse = "You declined \"${tool.spec.name}\". Task stopped safely."
                        steps.add(StepUi(steps.size + 1, action.tool, action.tool, "SKIPPED", "declined"))
                        history.add(action to "DECLINED by user")
                        event("You declined the action - task stopped safely", "warn")
                        break
                    }
                    event("Confirmed - continuing", "ok")
                }

                // 4. ACT -----------------------------------------------------
                update { it.copy(status = AgentStatus.ACTING, activeTool = action.tool, currentApp = screen?.packageName) }
                actionsUsed++
                update { it.copy(stepIndex = actionsUsed) }
                steps.add(StepUi(steps.size + 1, action.tool, action.tool, "RUNNING"))
                update { it }
                event("Running ${action.tool} (step $actionsUsed of $maxActions)…")

                val result: ToolResult = try {
                    withTimeoutOrNull(30_000) {
                        tool.execute(action.args, ToolContext(screen, isRoutine = source == "ROUTINE"))
                    } ?: ToolResult.fail("Action timed out after 30s.", "replan-or-skip")
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Logx.e(TAG, "Tool ${action.tool} crashed: ${t.message}")
                    ToolResult.fail("Action crashed: ${t.message?.take(80)}")
                }

                // 5. VERIFY --------------------------------------------------
                update { it.copy(status = AgentStatus.VERIFYING) }
                val verdict = Verifier.verify(action, result)
                Verifier.logStep(steps.size, action, verdict)
                steps[steps.size - 1] = steps.last.copy(
                    status = when (result.status) {
                        ToolStatus.SUCCESS -> "SUCCESS"
                        ToolStatus.FAILED -> "FAILED"
                        ToolStatus.BLOCKED -> "BLOCKED"
                        ToolStatus.REQUIRES_CONFIRMATION -> "BLOCKED"
                        ToolStatus.UNAVAILABLE -> "BLOCKED"
                    },
                    verdict = result.message.take(120),
                )
                update { it }
                event(
                    when (result.status) {
                        ToolStatus.SUCCESS -> "Done: ${result.message.take(90)}"
                        else -> "Step ${action.tool} did not succeed: ${result.message.take(90)}"
                    },
                    if (result.status == ToolStatus.SUCCESS) "ok" else "err",
                )
                c.memory.addStep(taskId, steps.size, action.tool, action.args.toString, steps.last.status, result.message, verdict)
                history.add(action to verdict)

                // Plan progression: consume the current plan step; if the model
                // deviated from the planned tool, that is an adaptation - keep
                // the remaining plan but say so honestly.
                if (planSteps.isNotEmpty) {
                    val planned = planSteps.removeFirstOrNull
                    if (planned != null && planned.tool != action.tool) {
                        event("Adapted away from plan step (${planned.tool} → ${action.tool}) - continuing with remaining plan")
                    }
                }

                // Honest failure accounting + fresh eyes for the next decision.
                if (result.status == ToolStatus.SUCCESS && result.verified != Verification.FAILED) consecFails = 0 else consecFails++
                if (consecFails >= 5) {
                    finalResponse = "Five actions in a row did not work (last: ${result.message.take(120)}). " +
                        "I stopped instead of guessing. The screen currently shows: ${screen?.packageName ?: "unknown app"}."
                    event("Giving up: 5 consecutive failures", "err")
                    break
                }
                afterNote = runCatching { Verifier.observeAfter }.getOrNull

                // 6. ADAPT ---------------------------------------------------
                if (result.status != ToolStatus.SUCCESS) {
                    Logx.w(TAG, "Step failed (${action.tool}): ${result.message.take(120)} → replanning")
                    event("Replanning after failure…", "warn")
                    delay(400)
                } else {
                    update { it.copy(currentApp = screen?.packageName) }
                }
            }

            if (finalResponse == null && !unlimited && actionsUsed >= maxActions) {
                finalResponse = "I used my action budget ($maxActions actions) without completing the task. Stopped safely."
                event("Action budget exhausted - stopping safely", "warn")
            }
            finish(taskId, goal, source, finalResponse, actionsUsed)
        } catch (ce: CancellationException) {
            event("Task stopped by user", "warn")
            c.memory.finishTask(taskId, "STOPPED", "Stopped by user.", actionsUsed)
        } catch (t: Throwable) {
            Logx.e(TAG, "Engine crashed: ${t.message}")
            c.memory.finishTask(taskId, "FAILED", "Engine error: ${t.message?.take(100)}", actionsUsed)
            commit { it.copy(status = AgentStatus.FAILED, finalResponse = "Something went wrong inside me: ${t.message?.take(120)}") }
            com.jarvis.mobile.service.AgentForegroundService.stopAll
        } finally {
            ticker.cancel
        }
    }

    private data class RoutedDecision(
        val action: PlannedAction?,
        val response: String?,
        val routeName: String,
        val warnings: List<String> = emptyList,
    )

    private suspend fun decide(
        goal: String,
        screen: ScreenObservation?,
        history: List<Pair<PlannedAction, String>>,
        suspicion: InjectionGuard.Verdict,
        actionsUsed: Int,
        budgetLabel: String,
        extraWarnings: List<String>,
        afterNote: String?,
        planNote: String? = null,
    ): RoutedDecision {
        val route = router.decide
        updateRoute(route.route.name)
        event("Route: ${route.route.name.lowercase} (${route.reason.take(70)})")
        return when (route.route) {
            ModelRouter.Route.RULES -> {
                val d = DeterministicPlanner.decide(goal, screen)
                RoutedDecision(d.action, d.response, route.route.name)
            }
            else -> {
                val system = Planner.systemPrompt(suspicion, factsBlock)
                val user = Planner.userPrompt(
                    goal, screen, history, route.reason,
                    actionsUsed = actionsUsed,
                    budgetLabel = budgetLabel,
                    warnings = extraWarnings,
                    afterNote = afterNote,
                    planNote = planNote,
                )
                val template = c.modelManager.llama.activeModel?.template
                    ?: com.jarvis.mobile.core.model.ModelCatalog.ChatTemplate.CHATML
                val prompt = com.jarvis.mobile.core.model.PromptTemplates.render(template, system, user)
                // Hard deadline: a hung/slow native call must NEVER freeze the task
                // (v1.3.0 hung forever at "Waking the model"). Local gets a longer
                // budget than remote; on timeout we cancel inference and fall back
                // to the deterministic planner so the task still makes progress.
                val timeoutMs = if (route.route == ModelRouter.Route.LOCAL) 240_000L else 90_000L
                val genRef = java.util.concurrent.atomic.AtomicReference<Pair<Result<String>, ModelRouter.Decision>?>(null)
                val genJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                    genRef.set(runCatching { router.generate(prompt, maxTokens = 300) }.fold(
                        onSuccess = { it },
                        onFailure = { Result.failure<String>(it) to ModelRouter.Decision(route.route, "error: ${it.message}") },
                    ))
                }
                val completed = withTimeoutOrNull(timeoutMs) {
                    while (genRef.get == null) delay(200)
                    true
                }
                if (completed != true) {
                    if (route.route == ModelRouter.Route.LOCAL) router.cancelLocal
                    genJob.cancel
                    Logx.w(TAG, "LLM timed out after ${timeoutMs / 1000}ms; falling back to rules")
                    event("Model took longer than ${timeoutMs / 1000}s - falling back to rule engine", "warn")
                    val d = DeterministicPlanner.decide(goal, screen)
                    return RoutedDecision(d.action, d.response, ModelRouter.Route.RULES.name)
                }
                val (result, _) = genRef.get!!
                // Honest timing stats for the live feed (local route fills genState).
                val g = c.modelManager.llama.genState.value
                if (route.route == ModelRouter.Route.LOCAL && g.outTokens > 0) {
                    event("Model wrote ${g.outTokens} tokens in ${fmtSecs(g.elapsedMs)} (${fmt1(g.tokensPerSec)} tok/s)", "model")
                }
                result.fold(
                    onSuccess = { text ->
                        val parsed = Planner.parseDecision(text)
                        when {
                            parsed.action != null -> RoutedDecision(parsed.action, null, route.route.name)
                            // A deliberate final answer: the JSON carried a "response" key.
                            hasResponseKey(parsed.raw) ->
                                RoutedDecision(null, parsed.response?.takeIf { it.isNotBlank } ?: "Task finished.", route.route.name)
                            // Prose, hallucinated tool, missing args, or garbage → retryable, not fatal.
                            else -> RoutedDecision(
                                null, null, route.route.name,
                                listOf(
                                    "Your previous output was NOT a valid action. Respond with EXACTLY ONE JSON object: " +
                                        "{\"thought\":\"...\",\"action\":{\"tool\":\"<name>\",\"args\":{...}}} using ONLY tools " +
                                        "from AVAILABLE TOOLS with all their required arguments - no prose, no markdown.",
                                ),
                            )
                        }
                    },
                    onFailure = { t ->
                        Logx.w(TAG, "LLM route failed (${t.message}); falling back to rules")
                        event("LLM failed (${t.message?.take(60)}) - falling back to rule engine", "warn")
                        val d = DeterministicPlanner.decide(goal, screen)
                        RoutedDecision(d.action, d.response, ModelRouter.Route.RULES.name)
                    },
                )
            }
        }
    }

    /** True when the model's raw output JSON explicitly chose the final-"response" form. */
    private fun hasResponseKey(raw: String): Boolean =
        runCatching { com.jarvis.mobile.util.JsonX.firstJsonObject(raw)?.containsKey("response") }.getOrDefault(false) == true

    /**
     * LLM generation with the hard deadline (240s local / 90s remote).
     * Returns null on timeout/absence so callers can degrade gracefully
     * instead of hanging - used by the plan-first stage and kept separate
     * from [decide]'s inline flow.
     */
    private suspend fun llmGenerate(prompt: String, maxTokens: Int): String? {
        val route = router.decide
        if (route.route == ModelRouter.Route.RULES) return null
        val timeoutMs = if (route.route == ModelRouter.Route.LOCAL) 240_000L else 90_000L
        val genRef = java.util.concurrent.atomic.AtomicReference<Result<String>?>(null)
        val genJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            genRef.set(runCatching { router.generate(prompt, maxTokens).first }.fold(
                onSuccess = { it },
                onFailure = { Result.failure<String>(it) },
            ))
        }
        val completed = withTimeoutOrNull(timeoutMs) {
            while (genRef.get == null) delay(200)
            true
        }
        if (completed != true) {
            if (route.route == ModelRouter.Route.LOCAL) router.cancelLocal
            genJob.cancel
            event("Model took longer than ${timeoutMs / 1000}s - skipping this stage", "warn")
            return null
        }
        return genRef.get?.getOrNull
    }

    private suspend fun finish(taskId: Long, goal: String, source: String, response: String?, actionsUsed: Int) {
        val honest = response ?: "Task finished."
        val status = when {
            _state.value.status == AgentStatus.STOPPED -> "STOPPED"
            stepsFailed -> "PARTIAL"
            else -> "COMPLETED"
        }
        c.memory.finishTask(taskId, status, honest.take(300), actionsUsed)
        c.memory.addChat("JARVIS", honest, taskId)
        commit {
            it.copy(
                status = if (status == "COMPLETED") AgentStatus.COMPLETED else if (status == "STOPPED") AgentStatus.STOPPED else AgentStatus.FAILED,
                finalResponse = honest,
                activeTool = null,
                confirmation = null,
                thinkingDetail = null,
            )
        }
        Logx.i(TAG, "Task done ($status): ${honest.take(140)}")
        c.voiceOutput.speak(honest.take(180))
        com.jarvis.mobile.service.AgentForegroundService.stopAll
    }

    private fun stepsFailed: Boolean = _state.value.steps.any { it.status == "FAILED" || it.status == "BLOCKED" }

    private fun updateRoute(label: String) {
        commit { it.copy(route = label) }
    }

    private fun fmt1(v: Float): String = String.format(Locale.US, "%.1f", v)

    private fun fmtSecs(ms: Long): String {
        val s = ms / 1000
        return if (s >= 60) "${s / 60}m${s % 60}s" else "${s}s"
    }

    private const val TAG = "engine"
}

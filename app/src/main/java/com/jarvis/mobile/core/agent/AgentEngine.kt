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
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** Everything the live agent UI needs (spec: LIVE AGENT UI). */
data class StepUi(
    val idx: Int,
    val tool: String,
    val label: String,
    val status: String, // PENDING RUNNING SUCCESS FAILED BLOCKED SKIPPED
    val verdict: String? = null,
)

data class AgentUiState(
    val status: AgentStatus = AgentStatus.IDLE,
    val goal: String = "",
    val steps: List<StepUi> = emptyList(),
    val activeTool: String? = null,
    val currentApp: String? = null,
    val route: String = "LOCAL",
    val finalResponse: String? = null,
    val confirmation: ConfirmationRequest? = null,
    val pausedForUser: Boolean = false,
)

enum class AgentStatus { IDLE, THINKING, ACTING, VERIFYING, WAITING_CONFIRMATION, COMPLETED, FAILED, STOPPED }

/**
 * The agent engine. Real observe → decide → act → verify → adapt loop with
 * bounded budgets, global stop, user-override detection and honest outcomes.
 */
object AgentEngine {

    private lateinit var c: JarvisApp.Container
    private lateinit var router: ModelRouter

    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state

    private val _confirmations = MutableSharedFlow<ConfirmationRequest>(extraBufferCapacity = 4)
    val confirmations: SharedFlow<ConfirmationRequest> = _confirmations

    private var job: Job? = null
    @Volatile private var stopped = false
    @Volatile private var diverged = false

    fun init(container: JarvisApp.Container) {
        c = container
        router = ModelRouter(container.settings, container.modelManager.llama, container.remoteProvider)
        // Confirmation bridge: engine surfaces requests to UI + notification layer.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            ConfirmationManager.requests.collect { req ->
                _confirmations.emit(req)
                _state.value = _state.value.copy(status = AgentStatus.WAITING_CONFIRMATION, confirmation = req)
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
        c.modelManager.startIdleWatchdog()
    }

    fun isRunning(): Boolean = job?.isActive == true

    fun stop() {
        stopped = true
        router.cancelLocal()
        ConfirmationManager.cancelAll()
        job?.cancel()
        _state.value = _state.value.copy(
            status = AgentStatus.STOPPED,
            confirmation = null,
            finalResponse = _state.value.finalResponse ?: "Stopped by user.",
        )
        Logx.w(TAG, "Agent STOPPED by user")
        c.voiceOutput.speak("Stopped.")
        com.jarvis.mobile.service.AgentForegroundService.stopAll()
    }

    fun answerConfirmation(id: String, approved: Boolean) = ConfirmationManager.answer(id, approved)

    fun runGoal(goal: String, source: String = "TEXT") {
        if (isRunning()) {
            Logx.w(TAG, "Task requested while busy: ignored (stop first)")
            return
        }
        stopped = false
        diverged = false
        job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            execute(goal, source)
        }
    }

    // ------------------------------------------------------------------ loop

    private suspend fun execute(goal: String, source: String) {
        val taskId = c.memory.startTask(goal, source)
        c.memory.addChat("USER", goal, taskId)
        val maxActions = runCatching { c.settings.maxActions.first() }.getOrDefault(12)
        var actionsUsed = 0
        val history = mutableListOf<Pair<PlannedAction, String>>()
        val steps = mutableListOf<StepUi>()

        fun update(f: (AgentUiState) -> AgentUiState) {
            _state.value = f(_state.value).copy(steps = steps.toList())
        }

        update { it.copy(status = AgentStatus.THINKING, goal = goal, finalResponse = null, route = "…") }
        com.jarvis.mobile.service.AgentForegroundService.start("Working on: $goal")
        c.voiceOutput.speak("Working on it.")
        Logx.i(TAG, "Task start: \"$goal\" (source=$source, budget=$maxActions)")

        try {
            var finalResponse: String? = null

            while (actionsUsed < maxActions && !stopped) {
                // 1. OBSERVE -------------------------------------------------
                update { it.copy(status = AgentStatus.THINKING, pausedForUser = diverged) }
                var screen: ScreenObservation? = null
                if (diverged) {
                    Logx.i(TAG, "Divergence: re-observing fresh state")
                    diverged = false
                }
                if (JarvisAccessibilityService.isReady) {
                    screen = withTimeoutOrNull(2500) {
                        JarvisAccessibilityService.INSTANCE?.observe()
                    }
                }
                val suspicion = InjectionGuard.inspect(screen)
                if (suspicion.level == InjectionGuard.Level.SUSPICIOUS) {
                    Logx.w(TAG, "Prompt-injection pattern detected on screen: ${suspicion.reasons}")
                }

                // 2. DECIDE --------------------------------------------------
                val decision = decide(goal, screen, history, suspicion)
                update { it.copy(route = router.routeLabel()) }

                if (decision.action == null) {
                    finalResponse = decision.response
                    break
                }
                val action = decision.action
                val tool = ToolRegistry.get(action.tool)
                if (tool == null || !tool.available()) {
                    finalResponse = "The action \"${action.tool}\" is not available right now (missing permission or service)."
                    history.add(action to "BLOCKED: tool unavailable")
                    steps.add(StepUi(steps.size + 1, action.tool, action.tool, "BLOCKED"))
                    continue
                }

                // 3. SAFETY / CONFIRMATION -----------------------------------
                val risk = RiskClassifier.classify(tool.spec, action.args, screen?.packageName)
                val autoApprove = runCatching { c.settings.autoApproveMedium.first() }.getOrDefault(false)
                val needsConfirm = RiskClassifier.requiresConfirmation(risk, autoApprove) &&
                    source != "ROUTINE" // routines run only LOW-risk tools anyway
                if (needsConfirm) {
                    val approved = ConfirmationManager.request(
                        what = "Run \"${tool.spec.name}\"",
                        target = screen?.packageName ?: "system",
                        details = action.args.toString().take(220),
                        why = RiskClassifier.whyConfirmation(tool.spec, risk, action.args),
                        risk = risk.name,
                    )
                    update { it.copy(confirmation = null) }
                    if (!approved) {
                        finalResponse = "You declined \"${tool.spec.name}\". Task stopped safely."
                        steps.add(StepUi(steps.size + 1, action.tool, action.tool, "SKIPPED", "declined"))
                        history.add(action to "DECLINED by user")
                        break
                    }
                }

                // 4. ACT -----------------------------------------------------
                update { it.copy(status = AgentStatus.ACTING, activeTool = action.tool, currentApp = screen?.packageName) }
                actionsUsed++
                steps.add(StepUi(steps.size + 1, action.tool, action.tool, "RUNNING"))
                update { }

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
                steps[steps.size - 1] = steps.last().copy(
                    status = when (result.status) {
                        ToolStatus.SUCCESS -> "SUCCESS"
                        ToolStatus.FAILED -> "FAILED"
                        ToolStatus.BLOCKED -> "BLOCKED"
                        ToolStatus.REQUIRES_CONFIRMATION -> "BLOCKED"
                        ToolStatus.UNAVAILABLE -> "BLOCKED"
                    },
                    verdict = result.message.take(120),
                )
                update { }
                c.memory.addStep(taskId, steps.size, action.tool, action.args.toString(), steps.last().status, result.message, verdict)
                history.add(action to verdict)

                // 6. ADAPT ---------------------------------------------------
                if (result.status != ToolStatus.SUCCESS) {
                    Logx.w(TAG, "Step failed (${action.tool}): ${result.message.take(120)} → replanning")
                    delay(400)
                } else {
                    update { it.copy(currentApp = screen?.packageName) }
                }
            }

            if (finalResponse == null && actionsUsed >= maxActions) {
                finalResponse = "I used my action budget ($maxActions actions) without completing the task. Stopped safely."
            }
            finish(taskId, goal, source, finalResponse, actionsUsed)
        } catch (ce: CancellationException) {
            c.memory.finishTask(taskId, "STOPPED", "Stopped by user.", actionsUsed)
        } catch (t: Throwable) {
            Logx.e(TAG, "Engine crashed: ${t.message}")
            c.memory.finishTask(taskId, "FAILED", "Engine error: ${t.message?.take(100)}", actionsUsed)
            _state.value = _state.value.copy(status = AgentStatus.FAILED, finalResponse = "Something went wrong inside me: ${t.message?.take(120)}")
            com.jarvis.mobile.service.AgentForegroundService.stopAll()
        }
    }

    private suspend fun decide(
        goal: String,
        screen: ScreenObservation?,
        history: List<Pair<PlannedAction, String>>,
        suspicion: InjectionGuard.Verdict,
    ): Planner.Decision {
        val snap = c.settings.snapshot()
        val route = router.decide()
        updateRoute(route.route.name)
        return when (route.route) {
            ModelRouter.Route.RULES -> DeterministicPlanner.decide(goal, screen)
            else -> {
                val system = Planner.systemPrompt(suspicion, factsBlock())
                val user = Planner.userPrompt(goal, screen, history, route.reason)
                val template = c.modelManager.llama.activeModel?.template
                    ?: com.jarvis.mobile.core.model.ModelCatalog.ChatTemplate.CHATML
                val prompt = com.jarvis.mobile.core.model.PromptTemplates.render(template, system, user)
                val (result, _) = router.generate(prompt, maxTokens = 220)
                result.fold(
                    onSuccess = { text ->
                        val parsed = Planner.parseDecision(text)
                        if (parsed.action == null && parsed.response.isNullOrBlank()) {
                            Planner.Decision(null, "I could not interpret the model output.", text)
                        } else parsed
                    },
                    onFailure = { t ->
                        Logx.w(TAG, "LLM route failed (${t.message}); falling back to rules")
                        DeterministicPlanner.decide(goal, screen)
                    },
                )
            }
        }
    }

    private suspend fun finish(taskId: Long, goal: String, source: String, response: String?, actionsUsed: Int) {
        val honest = response ?: "Task finished."
        val status = when {
            _state.value.status == AgentStatus.STOPPED -> "STOPPED"
            stepsFailed() -> "PARTIAL"
            else -> "COMPLETED"
        }
        c.memory.finishTask(taskId, status, honest.take(300), actionsUsed)
        c.memory.addChat("JARVIS", honest, taskId)
        _state.value = _state.value.copy(
            status = if (status == "COMPLETED") AgentStatus.COMPLETED else if (status == "STOPPED") AgentStatus.STOPPED else AgentStatus.FAILED,
            finalResponse = honest,
            activeTool = null,
            confirmation = null,
        )
        Logx.i(TAG, "Task done ($status): ${honest.take(140)}")
        c.voiceOutput.speak(honest.take(180))
        com.jarvis.mobile.service.AgentForegroundService.stopAll()
    }

    private fun stepsFailed(): Boolean = _state.value.steps.any { it.status == "FAILED" || it.status == "BLOCKED" }

    private fun updateRoute(label: String) {
        _state.value = _state.value.copy(route = label)
    }

    private const val TAG = "engine"
}

private suspend fun ModelRouter.routeLabel(): String = decide().route.name

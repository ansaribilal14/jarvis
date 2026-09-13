package com.jarvis.mobile.core.routing

import com.jarvis.mobile.core.model.LlamaCppProvider
import com.jarvis.mobile.core.model.RemoteOpenAiProvider
import com.jarvis.mobile.data.settings.SettingsRepository
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.flow.first

/**
 * Agent router (spec: AGENT ROUTER / MODEL FALLBACK LADDER):
 *   local model → optional remote → deterministic rule engine (always available).
 */
class ModelRouter(
    private val settings: SettingsRepository,
    private val llama: LlamaCppProvider,
    private val remote: RemoteOpenAiProvider,
) {
    enum class Route { LOCAL, REMOTE, RULES }

    data class Decision(val route: Route, val reason: String)

    suspend fun decide(): Decision {
        val snap = settings.snapshot()
        // API mode (e.g. NVIDIA NIM free models) takes priority: the user
        // explicitly turned it on, so never fall back to a slower local path
        // or ask them to activate a local model first.
        if (snap.apiMode) {
            remote.refreshConfig()
            if (remote.isReady()) {
                return Decision(Route.REMOTE, "API mode: ${snap.remoteModel.ifBlank { "remote" }}")
            }
        }
        return when {
            llama.isReady() -> Decision(Route.LOCAL, "on-device model: ${llama.activeModel?.id}")
            snap.localOnly -> Decision(Route.RULES, "LOCAL ONLY mode - no cloud, model not loaded; using deterministic engine")
            else -> {
                remote.refreshConfig()
                if (remote.isReady()) Decision(Route.REMOTE, "remote provider configured")
                else if (snap.apiMode) Decision(Route.RULES, "API mode is on but no key/endpoint is set; using deterministic engine")
                else Decision(Route.RULES, "no model loaded and no remote provider; using deterministic engine")
            }
        }
    }

    suspend fun generate(
        prompt: String,
        maxTokens: Int,
        stopSequences: List<String> = emptyList(),
        grammar: String? = null,
    ): Pair<Result<String>, Decision> {
        val d = decide()
        return when (d.route) {
            // Grammar-constrained decoding is a local-runtime feature: the native
            // sampler owns it. Remote providers ignore it (API mode has its own
            // JSON-mode controls we do not manage here).
            Route.LOCAL -> llama.generate(prompt, maxTokens, stopSequences, grammar) to d
            Route.REMOTE -> remote.generate(prompt, maxTokens, stopSequences) to d
            Route.RULES -> Result.failure<String>(IllegalStateException("no LLM route available")) to d
        }
    }

    fun cancelLocal() = llama.cancel()

    companion object {
        init {
            Logx.i("router", "ModelRouter ready")
        }
    }
}

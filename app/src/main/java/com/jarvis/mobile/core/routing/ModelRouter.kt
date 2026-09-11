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
        return when {
            llama.isReady() -> Decision(Route.LOCAL, "on-device model: ${llama.activeModel?.id}")
            snap.localOnly -> Decision(Route.RULES, "LOCAL ONLY mode - no cloud, model not loaded; using deterministic engine")
            else -> {
                remote.refreshConfig()
                if (remote.isReady()) Decision(Route.REMOTE, "remote provider configured")
                else Decision(Route.RULES, "no model loaded and no remote provider; using deterministic engine")
            }
        }
    }

    suspend fun generate(prompt: String, maxTokens: Int): Pair<Result<String>, Decision> {
        val d = decide()
        return when (d.route) {
            Route.LOCAL -> llama.generate(prompt, maxTokens) to d
            Route.REMOTE -> remote.generate(prompt, maxTokens) to d
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

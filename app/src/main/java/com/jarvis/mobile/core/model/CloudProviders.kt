package com.jarvis.mobile.core.model

/**
 * Shared catalog of OpenAI-compatible cloud providers (single source of truth,
 * consumed by the API-mode drawer and tests).
 *
 * DISCIPLINE: every model slug in these presets was verified LIVE against the
 * provider's real chat/completions endpoint before shipping (v2.2.0). The
 * previous presets shipped dead slugs (NVIDIA NIM retired the whole
 * llama-3.1/3.3 family on 2026-08-26 - requests failed with HTTP 410), so a
 * model list here now means "this exact id answered with content", not
 * "this id appears in a marketing list". The drawer's "Fetch live model list"
 * button re-checks against the provider at runtime anyway.
 */
data class CloudProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val keyLabel: String,
    val keyHint: String,
    val models: List<String>,
    val note: String,
    /** When true the /models list should be filtered to :free slugs (OpenRouter). */
    val freeOnly: Boolean = false,
)

object CloudProviders {

    /** Verified live on NVIDIA NIM (2026-09-20): real content replies. */
    val NIM_MODELS = listOf(
        "openai/gpt-oss-20b",                 // 3.3s round-trip, clean output
        "deepseek-ai/deepseek-v4-flash-0731", // 27s cold start, clean output
        "z-ai/glm-5.3-flash",                 // reasoning model, slow first call
    )

    /** Verified live on OpenRouter (2026-09-20), all :free tier, 0.4-2.4s replies. */
    val OPENROUTER_MODELS = listOf(
        "deepseek/deepseek-v4-flash-0731:free",
        "inclusionai/ling-3.0-flash-vl:free",
        "nex-agi/nex-n2.5-mini:free",
        "nex-agi/nex-n2.5-pro:free",
        "poolside/laguna-s-2.1:free",
    )

    val PRESETS: List<CloudProviderPreset> = listOf(
        CloudProviderPreset(
            "nim", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1",
            "NVIDIA API key (nvapi-…)", "nvapi-…", NIM_MODELS,
            "Free key at build.nvidia.com (Sign in → any model → Get API key).",
        ),
        CloudProviderPreset(
            "openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
            "OpenRouter key (sk-or-…)", "sk-or-…", OPENROUTER_MODELS,
            "Free \":free\" models - key at openrouter.ai/keys (no card needed).",
            freeOnly = true,
        ),
        CloudProviderPreset(
            "novita", "Novita", "https://api.novita.ai/v3/openai",
            "Novita key", "44-char key", listOf("zai-org/glm-5.3-flash"),
            "Save the key, then tap \"Fetch live model list\" - the catalog is large.",
        ),
        CloudProviderPreset(
            "sambanova", "SambaNova", "https://api.sambanova.ai/v1",
            "SambaNova key", "no fixed prefix", listOf("DeepSeek-V3.1"),
            "Fast free tier - key at cloud.sambanova.ai/apis.",
        ),
        CloudProviderPreset(
            "groq", "Groq", "https://api.groq.com/openai/v1",
            "Groq key (gsk-…)", "gsk-…", listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant"),
            "Very fast inference - key at console.groq.com/keys.",
        ),
        CloudProviderPreset(
            "deepseek", "DeepSeek", "https://api.deepseek.com/v1",
            "DeepSeek API key (sk-…)", "sk-…", listOf("deepseek-chat", "deepseek-reasoner"),
            "Very cheap, very strong - key at platform.deepseek.com.",
        ),
        CloudProviderPreset(
            "together", "Together", "https://api.together.xyz/v1",
            "Together key", "64-char key", emptyList(),
            "Save the key, then tap \"Fetch live model list\".",
        ),
        CloudProviderPreset(
            "ollama", "Ollama (LAN)", "http://192.168.1.10:11434/v1",
            "Ollama key (usually blank)", "ollama / blank",
            listOf("qwen2.5:7b", "llama3.2:3b", "qwen2.5-coder:7b", "mistral:7b"),
            "Runs on your own PC - edit the endpoint below to http://<your-pc-ip>:11434/v1.",
        ),
        CloudProviderPreset(
            "custom", "Custom", "",
            "API key", "any OpenAI-compatible key", emptyList(),
            "Any OpenAI-compatible /v1/chat/completions endpoint - paste the base URL below.",
        ),
    )

    fun byId(id: String): CloudProviderPreset? = PRESETS.firstOrNull { it.id == id }

    /** Best-effort provider identification from a stored base URL (drawer state restore). */
    fun guessId(baseUrl: String): String? {
        val b = baseUrl.trim()
        if (b.isBlank()) return null
        return when {
            b.contains("integrate.api.nvidia.com") -> "nim"
            b.contains("openrouter.ai") -> "openrouter"
            b.contains("api.novita.ai") -> "novita"
            b.contains("sambanova.ai") -> "sambanova"
            b.contains("api.groq.com") -> "groq"
            b.contains("deepseek.com") -> "deepseek"
            b.contains("together.") -> "together"
            b.contains("11434") -> "ollama"
            else -> "custom"
        }
    }
}

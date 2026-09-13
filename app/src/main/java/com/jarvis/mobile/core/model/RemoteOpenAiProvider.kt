package com.jarvis.mobile.core.model

import com.jarvis.mobile.data.settings.SecureVault
import com.jarvis.mobile.data.settings.SettingsRepository
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OPTIONAL remote accelerator (OpenAI-compatible).
 * - Classic custom endpoints use legacy /completions.
 * - API mode (NVIDIA NIM and any /v1 endpoint) uses /chat/completions.
 * Disabled by default and blocked entirely while LOCAL ONLY mode is on.
 */
class RemoteOpenAiProvider(
    private val settings: SettingsRepository,
    private val vault: SecureVault,
) : LlmProvider {

    override val id = "remote"
    override val displayName = "Remote provider (optional)"
    override val isLocal = false

    private var baseUrl = ""
    private var model = ""

    suspend fun refreshConfig() {
        baseUrl = settings.remoteBaseUrl.first().trim().trimEnd('/')
        model = settings.remoteModel.first().trim()
    }

    override fun isReady(): Boolean = baseUrl.isNotBlank() && vault.remoteApiKey.isNotBlank()

    /** One-shot key/endpoint check used by the API-mode sidebar ("Test key"). */
    suspend fun verifyKey(): Result<String> = withContext(Dispatchers.IO) {
        refreshConfig()
        if (!isReady()) return@withContext Result.failure(IllegalStateException("Paste an API key first"))
        generate("Reply with exactly: OK", 8).mapCatching { it.ifBlank { "empty reply" } }
    }

    override suspend fun generate(prompt: String, maxTokens: Int, stopSequences: List<String>): Result<String> = withContext(Dispatchers.IO) {
        if (settings.localOnly.first()) {
            return@withContext Result.failure(IllegalStateException("LOCAL ONLY mode is enabled - remote inference blocked"))
        }
        refreshConfig()
        if (!isReady()) return@withContext Result.failure(IllegalStateException("Remote provider not configured"))
        // /v1-style endpoints (NVIDIA NIM, OpenAI) speak chat/completions;
        // bare custom endpoints keep the legacy /completions shape.
        val chat = settings.apiMode.first() || baseUrl.endsWith("/v1")
        try {
            val body = if (chat) {
                JSONObject().apply {
                    put("model", model.ifBlank { "meta/llama-3.1-8b-instruct" })
                    put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                    put("max_tokens", maxTokens)
                    put("temperature", 0.25)
                    // OpenAI-compatible stop sequences keep remote models from
                    // echoing the prompt after the JSON too.
                    if (stopSequences.isNotEmpty()) {
                        put("stop", JSONArray(stopSequences.take(4)))
                    }
                }
            } else {
                JSONObject().apply {
                    put("model", model.ifBlank { "default" })
                    put("prompt", prompt)
                    put("max_tokens", maxTokens)
                    put("temperature", 0.25)
                    if (stopSequences.isNotEmpty()) {
                        put("stop", JSONArray(stopSequences.take(4)))
                    }
                }
            }
            val path = if (chat) "/chat/completions" else "/completions"
            val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = if (chat) 75_000 else 60_000 // 75s < 90s engine deadline: timeout cancels, never stacks
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${vault.remoteApiKey}")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) return@withContext Result.failure(IllegalStateException("Remote error $code: ${text.take(140)}"))
            val json = JSONObject(text)
            val choice = json.getJSONArray("choices").getJSONObject(0)
            val out = choice.optJSONObject("message")?.optString("content", "").orEmpty()
                .ifBlank { choice.optString("text", "").ifBlank { choice.optString("content", "") } }
            Result.success(out)
        } catch (t: Throwable) {
            Logx.w("remote", "Remote generation failed: ${t.message}")
            Result.failure(t)
        }
    }
}

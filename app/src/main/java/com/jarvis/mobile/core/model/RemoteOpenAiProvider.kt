package com.jarvis.mobile.core.model

import com.jarvis.mobile.data.settings.SecureVault
import com.jarvis.mobile.data.settings.SettingsRepository
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OPTIONAL remote accelerator (OpenAI-compatible /chat/completions).
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

    override suspend fun generate(prompt: String, maxTokens: Int): Result<String> = withContext(Dispatchers.IO) {
        if (settings.localOnly.first()) {
            return@withContext Result.failure(IllegalStateException("LOCAL ONLY mode is enabled - remote inference blocked"))
        }
        if (!isReady()) return@withContext Result.failure(IllegalStateException("Remote provider not configured"))
        try {
            val body = JSONObject().apply {
                put("model", model.ifBlank { "default" })
                put("prompt", prompt)
                put("max_tokens", maxTokens)
                put("temperature", 0.25)
            }
            val conn = URL("$baseUrl/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${vault.remoteApiKey}")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) return@withContext Result.failure(IllegalStateException("Remote error $code: ${text.take(140)}"))
            val json = JSONObject(text)
            val choice = json.getJSONArray("choices").getJSONObject(0)
            val out = choice.optString("text", "").ifBlank {
                choice.optJSONObject("message")?.optString("content", "") ?: ""
            }
            Result.success(out)
        } catch (t: Throwable) {
            Logx.w("remote", "Remote generation failed: ${t.message}")
            Result.failure(t)
        }
    }
}

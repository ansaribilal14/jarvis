package com.jarvis.mobile.core.remote

import android.content.Context
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.agent.AgentEngine
import com.jarvis.mobile.core.agent.AgentStatus
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Telegram remote control (adapted from the user's MobileAgent demo project):
 * long-polls a Telegram bot and turns messages into agent tasks, so the phone
 * can be driven from anywhere. Security model:
 *  - the bot token lives in the encrypted vault, never in DataStore/logs;
 *  - the first chat that messages the bot binds to it (persisted) and ONLY
 *    that chat can issue commands afterwards;
 *  - HIGH-risk actions still require on-phone confirmation (safety spec).
 *
 * Commands: any text = agent task · /status = live state · /stop = stop agent.
 */
object TelegramRemote {

    private const val TAG = "tg-remote"
    private const val API = "https://api.telegram.org/bot"
    private val json = Json { ignoreUnknownKeys = true }

    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null
    private var watchJob: Job? = null
    @Volatile private var running = false
    @Volatile private var offset = 0
    @Volatile private var boundChatId: Long = 0
    @Volatile private var pendingTelegramTask = false
    @Volatile private var lastReportedStatus: AgentStatus = AgentStatus.IDLE

    fun isRunning(): Boolean = running

    fun start(context: Context) {
        stop()
        val bootstrap = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        bootstrap.launch {
            val container = JarvisApp.instance.container
            val token = container.vault.telegramBotToken
            val enabled = runCatching { container.settings.telegramRemoteEnabled.first() }.getOrDefault(false)
            if (!enabled || token.isBlank()) {
                Logx.i(TAG, "Remote control disabled or token missing - not starting")
                return@launch
            }
            boundChatId = runCatching { container.settings.telegramChatId.first().toLongOrNull() ?: 0L }.getOrDefault(0L)
            offset = runCatching { container.settings.telegramOffset.first() }.getOrDefault(0)
            running = true
            scope = bootstrap

            pollJob = bootstrap.launch {
                Logx.i(TAG, "Telegram polling started (bound chat: ${if (boundChatId != 0L) boundChatId else "first sender"})")
                var failures = 0
                while (isActive && running) {
                    try {
                        pollOnce(token)
                        failures = 0
                    } catch (t: Throwable) {
                        failures++
                        Logx.e(TAG, "Poll error (${failures}): ${t.message}")
                        kotlinx.coroutines.delay(if (failures > 3) 15_000L else 4_000L)
                    }
                }
            }

            // Report task outcomes back to the chat that started them.
            watchJob = bootstrap.launch {
                AgentEngine.state.collect { st ->
                    val terminal = st.status in setOf(AgentStatus.COMPLETED, AgentStatus.FAILED, AgentStatus.STOPPED)
                    if (terminal && pendingTelegramTask &&
                        lastReportedStatus !in setOf(AgentStatus.COMPLETED, AgentStatus.FAILED, AgentStatus.STOPPED)
                    ) {
                        pendingTelegramTask = false
                        val verdict = when (st.status) {
                            AgentStatus.COMPLETED -> "Task completed."
                            AgentStatus.FAILED -> "Task failed."
                            else -> "Task stopped."
                        }
                        val body = st.finalResponse?.takeIf { it.isNotBlank() } ?: "(no detail reported)"
                        sendToBoundChat("$verdict\n$body")
                    }
                    lastReportedStatus = st.status
                }
            }
        }
    }

    fun stop() {
        running = false
        pollJob?.cancel()
        watchJob?.cancel()
        pollJob = null
        watchJob = null
        scope?.cancel()
        scope = null
    }

    /** Re-read settings and restart (called after the user saves Telegram settings). */
    fun restart(context: Context) = start(context)

    private suspend fun pollOnce(token: String) {
        val url = "$API$token/getUpdates?timeout=30&offset=$offset" +
            "&allowed_updates=${URLEncoder.encode("[\"message\"]", "UTF-8")}"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 40_000
        try {
            val code = conn.responseCode
            val body = runCatching { conn.inputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                ?: runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                ?: ""
            if (code != 200) {
                Logx.e(TAG, "getUpdates HTTP $code: ${body.take(200)}")
                kotlinx.coroutines.delay(5_000)
                return
            }
            val root = json.parseToJsonElement(body).jsonObject
            if (root["ok"]?.jsonPrimitive?.content != "true") return
            val results = root["result"]?.jsonArray ?: return
            for (r in results) {
                val upd = r.jsonObject
                val updateId = (upd["update_id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: continue
                offset = updateId + 1
                runCatching { JarvisApp.instance.container.settings.setTelegramOffset(updateId + 1) }
                val message = upd["message"]?.jsonObject ?: continue
                val chatId = message["chat"]?.jsonObject?.get("id")?.jsonPrimitive?.content?.toLongOrNull() ?: continue
                val text = message["text"]?.jsonPrimitive?.content ?: continue
                handleMessage(text.trim(), chatId)
            }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun handleMessage(text: String, chatId: Long) {
        // First-contact binding: the first chat to message the bot owns it.
        if (boundChatId == 0L) {
            boundChatId = chatId
            runCatching { JarvisApp.instance.container.settings.setTelegramChatId(chatId.toString()) }
            send(chatId, "JARVIS remote control bound to this chat. Send any text to run a task on the phone, or /status, /stop.")
            return
        }
        if (chatId != boundChatId) {
            Logx.w(TAG, "Ignored message from unauthorized chat $chatId")
            return
        }

        when {
            text.equals("/status", true) -> send(chatId, statusText())
            text.equals("/stop", true) -> {
                AgentEngine.stop()
                send(chatId, "Stopping the current task.")
            }
            text.startsWith("/", false) -> send(chatId, "Unknown command. Send any text to run a task, or /status, /stop.")
            else -> {
                if (AgentEngine.isRunning()) {
                    send(chatId, "A task is already running - send /stop first.")
                    return
                }
                send(chatId, "Task received, starting: $text")
                pendingTelegramTask = true
                lastReportedStatus = AgentStatus.IDLE
                AgentEngine.runGoal(text, source = "TELEGRAM")
            }
        }
    }

    private fun statusText(): String {
        val st = AgentEngine.state.value
        val container = JarvisApp.instance.container
        val model = container.modelManager.activeId() ?: "none loaded"
        val a11y = com.jarvis.mobile.accessibility.JarvisAccessibilityService.isReady
        val s = st.elapsedMs / 1000
        val clock = "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        val gen = container.modelManager.llama.genState.value
        val genBit = if (gen.generating) {
            when (gen.phase) {
                0 -> "\nInference: reading context ${gen.promptDone}/${gen.promptTotal} tokens"
                else -> "\nInference: ${gen.outTokens} tokens written"
            }
        } else ""
        return buildString {
            append("Agent: ${st.status.name.lowercase()}")
            if (st.elapsedMs > 0) append(" · $clock")
            if (st.goal.isNotBlank()) append("\nGoal: ${st.goal.take(180)}")
            if (st.stepBudget > 0) append("\nProgress: step ${st.stepIndex}/${st.stepBudget}")
            genBit.takeIf { it.isNotBlank() }?.let { append(it) }
            append("\nModel: $model")
            append("\nAccessibility: ${if (a11y) "on" else "off"}")
            if (st.status == AgentStatus.WAITING_CONFIRMATION) append("\nWaiting for your confirmation ON THE PHONE screen.")
        }
    }

    private fun sendToBoundChat(text: String) {
        if (boundChatId != 0L) send(boundChatId, text)
    }

    /** Fire-and-forget sendMessage; safe to call from any thread. */
    fun send(chatId: Long, text: String) {
        val token = runCatching { JarvisApp.instance.container.vault.telegramBotToken }.getOrDefault("")
        if (token.isBlank()) return
        runCatching {
            val conn = URL("$API$token/sendMessage").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = buildJsonObject {
                put("chat_id", chatId)
                put("text", text.take(3500))
            }.toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) Logx.e(TAG, "sendMessage HTTP $code")
            conn.disconnect()
        }.onFailure { Logx.e(TAG, "sendMessage failed: ${it.message}") }
    }
}

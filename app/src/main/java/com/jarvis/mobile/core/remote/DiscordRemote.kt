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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * Discord notification channel (outbound). Uses a bot token the user pastes in
 * Settings to push agent task outcomes and the connection test to one channel.
 * Security model mirrors TelegramRemote:
 *  - the bot token lives in the encrypted vault, never in DataStore/logs;
 *  - messages go directly from the phone to discord.com, nothing proxied.
 *
 * Honest scope: this is a ONE-WAY channel (phone → Discord). Inbound Discord
 * commands would need the Gateway WebSocket, which is out of scope while we
 * stay dependency-free - remote control remains Telegram's job.
 */
object DiscordRemote {

    private const val TAG = "dc-remote"
    private const val API = "https://discord.com/api/v10"

    private var scope: CoroutineScope? = null
    private var watchJob: Job? = null
    @Volatile private var running = false
    @Volatile private var lastReportedStatus: AgentStatus = AgentStatus.IDLE

    fun isRunning(): Boolean = running

    /** One message POST. Returns Result with the message id on success. */
    fun send(token: String, channelId: String, text: String): Result<String> = runCatching {
        require(token.isNotBlank()) { "Bot token is missing" }
        require(channelId.isNotBlank()) { "Channel ID is missing" }
        val conn = URL("$API/channels/${channelId.trim()}/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bot ${token.trim()}")
        val payload = buildJsonObject {
            put("content", text.take(2000))
        }.toString()
        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) error("Discord HTTP $code: ${body.take(140)}")
        // cheap id extraction without a full parse dependency
        val id = Regex("\"id\"\\s*:\\s*\"(\\d+)\"").find(body)?.groupValues?.get(1) ?: ""
        id
    }

    /** Send a test message from the Settings screen; surfaces the reply as text. */
    suspend fun verify(token: String, channelId: String): Result<String> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            send(token, channelId, "JARVIS connected - task results will land in this channel.")
                .map { id -> if (id.isNotBlank()) "Test message delivered (id $id)" else "Test message delivered" }
        }

    /** List text channels the bot can see, per guild, for the Settings picker. */
    suspend fun fetchChannels(token: String): Result<List<ChannelInfo>> =
        kotlinx.coroutines.withContext(Dispatchers.IO) { fetchChannelsBlocking(token.trim()) }

    data class ChannelInfo(val guildName: String, val channelId: String, val channelName: String)

    private fun fetchChannelsBlocking(token: String): Result<List<ChannelInfo>> = runCatching {
        val guilds = getJson(token, "$API/users/@me/guilds")
        val ga = guilds.optJSONArray("body") ?: error(guilds.optString("error").ifBlank { "guild list failed" })
        val out = mutableListOf<ChannelInfo>()
        for (g in 0 until minOf(ga.length(), 3)) {
            val gid = ga.getJSONObject(g).optString("id")
            val gname = ga.getJSONObject(g).optString("name").ifBlank { "guild" }
            val chans = getJson(token, "$API/guilds/$gid/channels")
            val ca = chans.optJSONArray("body") ?: continue
            for (c in 0 until ca.length()) {
                val ch = ca.getJSONObject(c)
                // 0 = GUILD_TEXT (5 = announcement text also accepts messages)
                if (ch.optInt("type") == 0) {
                    out += ChannelInfo(gname, ch.optString("id"), ch.optString("name"))
                    if (out.size >= 24) return@runCatching out
                }
            }
        }
        out
    }

    /** GET a URL with bot auth, returning either {"body": array} or {"error": msg}. */
    private fun getJson(token: String, url: String): org.json.JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bot ${token.trim()}")
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return if (code in 200..299) {
            org.json.JSONObject().put("body", org.json.JSONArray(body))
        } else {
            org.json.JSONObject().put("error", "Discord HTTP $code: ${body.take(120)}")
        }
    }

    /** Start the outcome watcher (no-op when disabled or token missing). */
    fun start(context: Context) {
        stop()
        val bootstrap = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        bootstrap.launch {
            val container = JarvisApp.instance.container
            val enabled = runCatching { container.settings.discordEnabled.first() }.getOrDefault(false)
            val token = container.vault.discordBotToken
            val channelId = runCatching { container.settings.discordChannelId.first() }.getOrDefault("")
            if (!enabled || token.isBlank() || channelId.isBlank()) {
                Logx.i(TAG, "Discord push disabled or token/channel missing - not starting")
                return@launch
            }
            running = true
            scope = bootstrap
            watchJob = bootstrap.launch {
                Logx.i(TAG, "Discord outcome watcher started (channel $channelId)")
                AgentEngine.state.collect { st ->
                    val terminal = st.status in setOf(AgentStatus.COMPLETED, AgentStatus.FAILED, AgentStatus.STOPPED)
                    if (terminal && lastReportedStatus !in setOf(AgentStatus.COMPLETED, AgentStatus.FAILED, AgentStatus.STOPPED)) {
                        val verdict = when (st.status) {
                            AgentStatus.COMPLETED -> "Task completed."
                            AgentStatus.FAILED -> "Task failed."
                            else -> "Task stopped."
                        }
                        val body = st.finalResponse?.takeIf { it.isNotBlank() } ?: "(no detail reported)"
                        val t = container.vault.discordBotToken
                        val c = runCatching { container.settings.discordChannelId.first() }.getOrDefault("")
                        if (t.isNotBlank() && c.isNotBlank()) {
                            send(t, c, "**JARVIS** $verdict\n$body")
                        }
                    }
                    lastReportedStatus = st.status
                }
            }
        }
    }

    fun stop() {
        running = false
        watchJob?.cancel()
        watchJob = null
        scope?.cancel()
        scope = null
        lastReportedStatus = AgentStatus.IDLE
    }

    fun restart(context: Context) = start(context)
}

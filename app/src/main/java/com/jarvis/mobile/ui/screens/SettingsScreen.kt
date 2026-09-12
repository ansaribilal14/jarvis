package com.jarvis.mobile.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.service.OverlayService
import com.jarvis.mobile.ui.components.SectionCard
import com.jarvis.mobile.ui.components.StatusChip
import com.jarvis.mobile.ui.theme.Accent
import kotlinx.coroutines.launch

/** Settings (spec: APP SETTINGS) - everything discoverable, nothing buried. */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val container = JarvisApp.instance.container
    val s = container.settings
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val localOnly by s.localOnly.collectAsState(initial = true)
    val voiceIn by s.voiceInput.collectAsState(initial = true)
    val voiceOut by s.voiceOutput.collectAsState(initial = true)
    val overlay by s.overlayEnabled.collectAsState(initial = false)
    val autoApprove by s.autoApproveMedium.collectAsState(initial = false)
    val pauseOnTouch by s.pauseOnUserTouch.collectAsState(initial = true)
    val theme by s.theme.collectAsState(initial = "SYSTEM")
    val threads by s.inferenceThreads.collectAsState(initial = 0)
    val maxActions by s.maxActions.collectAsState(initial = 12)
    val remoteUrl by s.remoteBaseUrl.collectAsState(initial = "")
    val remoteModel by s.remoteModel.collectAsState(initial = "")
    val tgEnabled by s.telegramRemoteEnabled.collectAsState(initial = false)
    val tgChatId by s.telegramChatId.collectAsState(initial = "")
    val clipboard = LocalClipboardManager.current

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SectionCard("Privacy & inference") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("LOCAL ONLY mode", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "When on: no remote model calls, no cloud screen analysis, no cloud transcription, no telemetry. Everything stays on this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = localOnly, onCheckedChange = { v -> scope.launch { s.setLocalOnly(v) } })
            }
            if (!localOnly) {
                var url by remember(remoteUrl) { mutableStateOf(remoteUrl) }
                var model by remember(remoteModel) { mutableStateOf(remoteModel) }
                OutlinedTextField(url, { url = it }, label = { Text("Remote base URL (OpenAI-compatible)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(model, { model = it }, label = { Text("Remote model name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { scope.launch { s.setRemoteBaseUrl(url.trim()); s.setRemoteModel(model.trim()) } }) { Text("Save") }
                    Text("API key is stored in the encrypted vault (Security section below it is never logged).", style = MaterialTheme.typography.labelMedium)
                }
            }
            StatusChip(if (localOnly) "no data leaves this device" else "remote fallback allowed", if (localOnly) com.jarvis.mobile.ui.components.ChipState.ACCENT else com.jarvis.mobile.ui.components.ChipState.WARN)
        }

        SectionCard("Telegram remote control") {
            SettingToggle("Run tasks from Telegram", tgEnabled) { on ->
                scope.launch {
                    s.setTelegramRemoteEnabled(on)
                    if (on) com.jarvis.mobile.core.remote.TelegramRemote.restart(context)
                    else com.jarvis.mobile.core.remote.TelegramRemote.stop()
                }
            }
            Text(
                "Send tasks to this phone from anywhere via your own bot. Create a bot with @BotFather, paste its token here, and message it once to bind this chat. The token is stored in the encrypted vault.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var tgToken by remember { mutableStateOf(container.vault.telegramBotToken) }
            OutlinedTextField(
                tgToken,
                { tgToken = it },
                label = { Text("Bot token from @BotFather") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    scope.launch {
                        container.vault.telegramBotToken = tgToken.trim()
                        if (tgEnabled) com.jarvis.mobile.core.remote.TelegramRemote.restart(context)
                    }
                }) { Text("Save token") }
            }
            if (tgChatId.isNotBlank()) {
                Text("Bound chat id: $tgChatId", style = MaterialTheme.typography.labelMedium, color = Accent)
                TextButton(onClick = {
                    scope.launch {
                        s.setTelegramChatId("")
                        com.jarvis.mobile.core.remote.TelegramRemote.restart(context)
                    }
                }) { Text("Unbind chat (rebind on next message)") }
            } else {
                Text("No chat bound yet - send any message to your bot and it will bind automatically.", style = MaterialTheme.typography.labelMedium, color = Accent)
            }
        }

        SectionCard("Appearance") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("SYSTEM", "DARK", "LIGHT").forEach { mode ->
                    FilterChip(selected = theme == mode, onClick = { scope.launch { s.setTheme(mode) } }, label = { Text(mode.lowercase()) })
                }
            }
        }

        SectionCard("Voice") {
            SettingToggle("Voice input (push-to-talk mic)", voiceIn) { v -> scope.launch { s.setVoiceInput(v) } }
            SettingToggle("Speak status updates", voiceOut) { v -> scope.launch { s.setVoiceOutput(v) } }
        }

        SectionCard("Automation & safety") {
            SettingToggle("Pause when I touch the screen", pauseOnTouch) { v -> scope.launch { s.setPauseOnUserTouch(v) } }
            SettingToggle("Auto-approve medium-risk actions", autoApprove) { v -> scope.launch { s.setAutoApproveMedium(v) } }
            Text(
                "HIGH-risk actions (payments, account changes, sensitive sharing) ALWAYS require confirmation - this cannot be disabled.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Model & performance") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Inference threads (0 = auto)", Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 2, 4).forEach { t ->
                        FilterChip(selected = threads == t, onClick = { scope.launch { s.setInferenceThreads(t) } }, label = { Text("$t") })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Max actions per task", Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(6, 12, 20).forEach { v ->
                        FilterChip(selected = maxActions == v, onClick = { scope.launch { s.setMaxActions(v) } }, label = { Text("$v") })
                    }
                }
            }
        }

        SectionCard("Floating bubble") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Show Jarvis bubble over other apps", style = MaterialTheme.typography.titleMedium)
                    if (!android.provider.Settings.canDrawOverlays(context)) {
                        Text("Needs the \"Display over other apps\" permission.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Switch(
                    checked = overlay,
                    onCheckedChange = { on ->
                        if (on && !android.provider.Settings.canDrawOverlays(context)) {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")),
                            )
                        } else {
                            scope.launch { s.setOverlayEnabled(on) }
                            if (on) OverlayService.start(context) else OverlayService.stop(context)
                        }
                    },
                )
            }
        }

        SectionCard("Permissions") {
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibility service") }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Notification access") }
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${context.packageName}")),
                )
            }) { Text("Modify system settings") }
        }

        SectionCard("Diagnostics") {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(com.jarvis.mobile.util.Logx.dump()))
            }) { Text("Copy execution log to clipboard") }
            Text(
                "The log contains actions, observations and results - never model reasoning, never credentials.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

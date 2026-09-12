package com.jarvis.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.agent.AgentEngine
import com.jarvis.mobile.core.agent.AgentStatus
import com.jarvis.mobile.core.voice.SpeechInput
import com.jarvis.mobile.ui.components.ConfirmDialog
import com.jarvis.mobile.ui.components.EmptyState
import com.jarvis.mobile.ui.components.StatusChip
import com.jarvis.mobile.ui.components.stepColor
import com.jarvis.mobile.ui.theme.Accent
import com.jarvis.mobile.ui.theme.Danger
import kotlinx.coroutines.flow.collectLatest

private val quickCommands = listOf(
    "Open Settings",
    "Read my notifications",
    "Set brightness to 30",
    "Open Chrome and search local AI models",
)

@Composable
fun HomeScreen(openTab: (String) -> Unit) {
    val context = LocalContext.current
    val container = JarvisApp.instance.container
    val agentState by AgentEngine.state.collectAsState()
    val loadState by container.modelManager.loadState.collectAsState()
    var input by remember { mutableStateOf("") }
    var voiceHint by remember { mutableStateOf("") }

    val loadedModelId = (loadState as? com.jarvis.mobile.core.model.ModelManager.LoadState.Loaded)?.modelId
        ?: (loadState as? com.jarvis.mobile.core.model.ModelManager.LoadState.Loading)?.modelId

    val speech = remember { SpeechInput(context) }
    val voiceEnabled by container.settings.voiceInput.collectAsState(initial = true)

    LaunchedEffect(Unit) {
        speech.events.collectLatest { ev ->
            when (ev) {
                is SpeechInput.VoiceEvent.Partial -> voiceHint = ev.text
                is SpeechInput.VoiceEvent.Final -> {
                    voiceHint = ""
                    if (ev.text.isNotBlank()) AgentEngine.runGoal(ev.text, source = "VOICE")
                }
                is SpeechInput.VoiceEvent.Error -> voiceHint = ev.message
                else -> {}
            }
        }
    }

    val busy = agentState.status !in setOf(AgentStatus.IDLE, AgentStatus.COMPLETED, AgentStatus.FAILED, AgentStatus.STOPPED)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { openTab(com.jarvis.mobile.ui.navigation.Dest.Models.route) },
        ) {
            StatusChip(agentState.route, if (agentState.route == "LOCAL") com.jarvis.mobile.ui.components.ChipState.ACCENT else com.jarvis.mobile.ui.components.ChipState.NEUTRAL)
            Spacer(Modifier.width(8.dp))
            StatusChip(
                when {
                    loadState is com.jarvis.mobile.core.model.ModelManager.LoadState.Loading && loadedModelId != null -> "loading $loadedModelId…"
                    loadedModelId != null -> loadedModelId!!
                    else -> "no model loaded - tap here"
                },
                when {
                    loadedModelId != null -> com.jarvis.mobile.ui.components.ChipState.OK
                    loadState is com.jarvis.mobile.core.model.ModelManager.LoadState.Loading -> com.jarvis.mobile.ui.components.ChipState.ACCENT
                    else -> com.jarvis.mobile.ui.components.ChipState.WARN
                },
            )
        }

        Spacer(Modifier.height(26.dp))

        // Orb (the single animated effect - only while active)
        val active = busy
        Box(contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(modifier = Modifier.size(148.dp)) {}
            com.jarvis.mobile.ui.components.JarvisOrb(
                active = active,
                accent = Accent,
                modifier = Modifier.size(148.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = when (agentState.status) {
                AgentStatus.IDLE -> "Ready"
                AgentStatus.THINKING -> "Thinking"
                AgentStatus.ACTING -> "Acting: ${agentState.activeTool ?: ""}"
                AgentStatus.VERIFYING -> "Verifying"
                AgentStatus.WAITING_CONFIRMATION -> "Waiting for your confirmation"
                AgentStatus.COMPLETED -> "Completed"
                AgentStatus.FAILED -> "Failed"
                AgentStatus.STOPPED -> "Stopped"
            },
            style = MaterialTheme.typography.titleLarge,
        )
        if (voiceHint.isNotBlank()) {
            Text("“$voiceHint”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(22.dp))

        // Live plan / result panel
        if (agentState.steps.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(agentState.goal, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    agentState.steps.takeLast(8).forEach { step ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(stepColor(step.status)))
                            Text(
                                "${step.tool}  ${step.verdict?.let { "· $it" } ?: ""}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    agentState.finalResponse?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (busy) {
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            IconButton(
                                onClick = { AgentEngine.stop() },
                                modifier = Modifier.clip(CircleShape).background(Danger.copy(alpha = 0.15f)),
                            ) { Icon(Icons.Filled.Stop, "Stop agent", tint = Danger) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        } else if (agentState.finalResponse != null) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Text(agentState.finalResponse ?: "", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(16.dp))
        }

        // Quick commands
        if (!busy) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                quickCommands.forEach { cmd ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        onClick = { AgentEngine.runGoal(cmd, source = "QUICK") },
                    ) {
                        Text(cmd, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        Spacer(Modifier.weight(1f))

        // Input bar
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Tell Jarvis what to do…") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
                maxLines = 3,
            )
            Spacer(Modifier.width(8.dp))
            if (voiceEnabled) {
                IconButton(
                    onClick = { if (speech.listening.value) speech.stop() else speech.start() },
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(if (speech.listening.collectAsState().value) Accent else MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Icon(Icons.Filled.Mic, "Speak", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = {
                    if (input.isNotBlank()) {
                        AgentEngine.runGoal(input.trim(), source = "TEXT")
                        input = ""
                    }
                },
                modifier = Modifier.size(52.dp).clip(CircleShape).background(Accent),
            ) { Icon(Icons.Filled.Send, "Send", tint = MaterialTheme.colorScheme.background) }
        }
        Spacer(Modifier.height(10.dp))
    }

    agentState.confirmation?.let { req ->
        ConfirmDialog(req) { id, approved -> AgentEngine.answerConfirmation(id, approved) }
    }
}

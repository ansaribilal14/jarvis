package com.jarvis.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.adb.SelfHostShell
import com.jarvis.mobile.core.agent.AgentEngine
import com.jarvis.mobile.core.shizuku.ShizukuBridge
import com.jarvis.mobile.core.skills.SkillDefinition
import com.jarvis.mobile.core.skills.SkillRecorder
import com.jarvis.mobile.core.skills.SkillStore
import com.jarvis.mobile.core.triggers.TimeTriggerScheduler
import com.jarvis.mobile.core.triggers.TriggerEngine
import com.jarvis.mobile.ui.theme.Accent
import com.jarvis.mobile.ui.theme.Danger

/**
 * Skills hub: start/stop the recorder, review a finished recording
 * (edit as-is, or run /grill-me), and run/edit/delete saved skills.
 */
@Composable
fun SkillsScreen(openTab: (String) -> Unit) {
    val context = JarvisApp.instance
    val rec by SkillRecorder.state.collectAsState()
    val agentState by AgentEngine.state.collectAsState()
    val shizuku by ShizukuBridge.state.collectAsState()
    val selfhost by SelfHostShell.state.collectAsState()
    var skills by remember { mutableStateOf(SkillStore.list(context)) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var triggerLog by remember { mutableStateOf(TriggerEngine.recentLog) }
    var showRecordStart by remember { mutableStateOf(false) }
    var showSelfHostSetup by remember { mutableStateOf(false) }
    var showShizukuRow by remember { mutableStateOf(false) }

    // HomeScreen's "Record a skill" chip lands here with the dialog open.
    LaunchedEffect(RecordStartBus.openRecordStart.value) {
        if (RecordStartBus.openRecordStart.value) {
            RecordStartBus.openRecordStart.value = false
            showRecordStart = true
        }
    }

    fun refresh() {
        skills = SkillStore.list(context)
        triggerLog = TriggerEngine.recentLog
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Skills", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Record yourself doing something once - JARVIS replays it whenever you want. " +
                "Recorded actions stay on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ------------------------------------------------------ recorder card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(if (rec.active) Danger else MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                    Text(
                        when {
                            rec.active -> "Recording… ${rec.steps.size} steps"
                            rec.finishedSteps != null -> "Recording finished - ${rec.finishedSteps?.size ?: 0} steps"
                            else -> "Skill recorder"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                when {
                    rec.active -> {
                        // Honest capture-mode status - the user should never have to
                        // guess whether anything is being recorded.
                        Text(
                            when {
                                rec.precisionActive -> "${rec.captureLayer} - every tap in every app is recorded (${rec.rawTaps} raw touches seen), drawn as live ink"
                                else -> "App-event capture - buttons, typing, scrolls, app switches. Set up the built-in precision shell below for tap-by-tap capture (even in apps that hide from accessibility)."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (rec.precisionActive) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (rec.steps.isNotEmpty()) {
                            rec.steps.takeLast(4).forEach { step ->
                                Text(
                                    "• ${step.describe()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Button(
                            onClick = { SkillRecorder.stop() },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Danger),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Stop recording") }
                        Text(
                            "Use your phone normally - switch apps, tap, type. Tap Stop (here or in the notification) when done.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    rec.finishedSteps != null -> {
                        val steps = rec.finishedSteps ?: emptyList()
                        if (steps.isNotEmpty()) {
                            steps.take(5).forEach { step ->
                                Text(
                                    "• ${step.describe()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (steps.size > 5) Text("… +${steps.size - 5} more", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    com.jarvis.mobile.core.skills.GrillMeEngine.start(steps)
                                    openTab("grill_me")
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("/grill-me") }
                            OutlinedButton(
                                onClick = {
                                    SkillDraftBus.draft.value = SkillDefinition(
                                        id = SkillStore.newId(),
                                        name = "My skill",
                                        steps = steps,
                                    )
                                    openTab("skill_draft")
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Review & edit") }
                        }
                        OutlinedButton(onClick = { SkillRecorder.discard() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Discard recording")
                        }
                    }
                    else -> {
                        if (!com.jarvis.mobile.accessibility.JarvisAccessibilityService.CONNECTED.value) {
                            Text(
                                "⚠ Enable JARVIS in Settings → Accessibility first - recording needs it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Danger,
                            )
                        }
                        Button(
                            onClick = { showRecordStart = true },
                            enabled = AgentEngine.isRunning().not(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Record a skill") }
                        Text(
                            "You choose where to start: the home screen or any app. A red REC bubble floats over everything with a live step counter, and every tap and drag you make is drawn on screen as red ink - you always SEE what is being recorded. Come back and tap Stop when done.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ------------------------------------------------- precision setup row
        if (selfhost.status == SelfHostShell.Status.READY || shizuku.status == ShizukuBridge.Status.READY) {
            Text(
                when {
                    selfhost.status == SelfHostShell.Status.READY ->
                        "Built-in precision shell ready - every tap records with exact coordinates. No other app involved."
                    else -> "Shizuku connected - precision touch capture is available on this device."
                },
                style = MaterialTheme.typography.labelSmall,
                color = com.jarvis.mobile.ui.theme.Ok,
            )
        } else {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Record EVERY tap - even in games and canvas apps", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "One-time setup: JARVIS pairs with this phone's own Wireless debugging and starts its own privileged " +
                            "shell from inside the app - nothing else to install. Afterwards, the raw touchscreen stream is " +
                            "recorded with exact coordinates and every tap/drag is drawn on screen while you record.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (selfhost.status) {
                        SelfHostShell.Status.PAIRED -> Text(
                            "Paired - tap Connect to start the built-in shell.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelfHostShell.Status.CONNECTING -> Text(
                            "Connecting: ${selfhost.reason}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelfHostShell.Status.ERROR -> Text(
                            selfhost.reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = Danger,
                        )
                        else -> Unit
                    }
                    Button(onClick = { showSelfHostSetup = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            when (selfhost.status) {
                                SelfHostShell.Status.PAIRED -> "Connect built-in shell"
                                else -> "Set up (built-in, no other app)"
                            },
                        )
                    }
                    androidx.compose.material3.TextButton(
                        onClick = { showShizukuRow = !showShizukuRow },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (showShizukuRow) "Hide the Shizuku app option" else "Prefer the Shizuku app instead?",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (showShizukuRow) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        val i = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                        if (i != null) context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                        else context.startActivity(
                                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/"))
                                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(if (shizuku.status == ShizukuBridge.Status.NOT_INSTALLED) "Get Shizuku" else "Open Shizuku") }
                            OutlinedButton(
                                onClick = {
                                    ShizukuBridge.refresh()
                                    ShizukuBridge.requestPermission()
                                },
                                enabled = shizuku.status == ShizukuBridge.Status.NOT_AUTHORIZED || shizuku.status == ShizukuBridge.Status.READY,
                                modifier = Modifier.weight(1f),
                            ) { Text("Connect") }
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------- trigger log
        if (triggerLog.isNotEmpty()) {
            Text("RECENT TRIGGERS", style = MaterialTheme.typography.labelSmall, color = Accent)
            triggerLog.take(5).forEach { ev ->
                Text(
                    "• ${java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US).format(ev.at)} - ${ev.trigger} → ${ev.skill}${if (ev.fired) "" else " (skipped: ${ev.why})"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // -------------------------------------------------------- saved skills
        if (skills.isEmpty()) {
            Text(
                "No skills saved yet. Record one above - then run it any time with one tap.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        skills.forEach { skill ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(skill.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        SourceBadge(skill.source)
                    }
                    if (skill.description.isNotBlank()) {
                        Text(
                            skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    Text(
                        "${skill.steps.size} steps · run ${skill.runCount}x" +
                            (skill.lastRunAtMs?.let { " · last ${java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(it)}" } ?: "") +
                            (skill.trigger?.let { " · ${it.describe()}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { AgentEngine.runSkill(skill); refresh() },
                            enabled = !AgentEngine.isRunning(),
                            modifier = Modifier.weight(1f),
                        ) { Text("Run") }
                        OutlinedButton(
                            onClick = { SkillDraftBus.draft.value = skill; openTab("skill_draft") },
                            modifier = Modifier.weight(1f),
                        ) { Text("Edit") }
                        OutlinedButton(
                            onClick = { confirmDelete = skill.id },
                            modifier = Modifier.weight(1f),
                        ) { Text("Delete", color = Danger) }
                    }
                    skill.notes.takeIf { it.isNotBlank() }?.let {
                        Text(
                            "Guardrails: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                        )
                    }
                }
            }
        }
    }

    confirmDelete?.let { id ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete skill?") },
            text = { Text("The recording and its interview notes are removed from this phone. Cannot be undone.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    TimeTriggerScheduler.cancel(context, id)
                    SkillStore.delete(context, id)
                    confirmDelete = null
                    refresh()
                }) { Text("Delete", color = Danger) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) { Text("Keep") }
            },
        )
    }

    if (showRecordStart) {
        RecordStartDialog(
            onDismiss = { showRecordStart = false },
            onStarted = { showRecordStart = false },
        )
    }

    if (showSelfHostSetup) {
        SelfHostSetupDialog(onDismiss = { showSelfHostSetup = false })
    }
}

@Composable
private fun SourceBadge(source: String) {
    val (label, color) = if (source == "GRILLED") ("/grill-me" to Accent) else ("recorded" to Color(0xFF8A8A8E))
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Draft hand-off between screens (mirrors ApiDrawerBus pattern). */
object SkillDraftBus {
    val draft = androidx.compose.runtime.mutableStateOf<com.jarvis.mobile.core.skills.SkillDefinition?>(null)
}

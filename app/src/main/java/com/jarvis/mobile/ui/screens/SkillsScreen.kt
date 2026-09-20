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
import com.jarvis.mobile.core.agent.AgentEngine
import com.jarvis.mobile.core.skills.SkillDefinition
import com.jarvis.mobile.core.skills.SkillRecorder
import com.jarvis.mobile.core.skills.SkillStore
import com.jarvis.mobile.core.triggers.TimeTriggerScheduler
import com.jarvis.mobile.core.triggers.TriggerEngine
import com.jarvis.mobile.ui.theme.Accent
import com.jarvis.mobile.ui.theme.Danger
import com.jarvis.mobile.ui.theme.Ok

/**
 * Skills hub - skills v3 (docs/SKILLS_V3.md):
 *  - BUILD first (MacroDroid model): catalog actions, Pick-on-screen targeting,
 *    per-action test, honest run log;
 *  - QUICK RECORD as the secondary path: app-reported events only, every
 *    capture confirmed live, hard-gated on accessibility being connected.
 * The v1/v2 privilege-machinery setup cards (Shizuku / built-in shell) are GONE.
 */
@Composable
fun SkillsScreen(openTab: (String) -> Unit) {
    val context = JarvisApp.instance
    val rec by SkillRecorder.state.collectAsState()
    val agentState by AgentEngine.state.collectAsState()
    var skills by remember { mutableStateOf(SkillStore.list(context)) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var triggerLog by remember { mutableStateOf(TriggerEngine.recentLog) }
    var showRecordStart by remember { mutableStateOf(false) }
    var expandedLog by remember { mutableStateOf<String?>(null) }

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
            "Build skills from proven actions and aim them at the exact element - or quick-record what apps report. " +
                "Everything stays on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ------------------------------------------------------ build card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Build a skill", style = MaterialTheme.typography.titleMedium)
                Text(
                    "MacroDroid-style: add actions from the catalog (open app, tap, type, scroll, wait…) and aim each one " +
                        "with 'Pick on screen' - a screenshot you tap, so it works in EVERY app, including games. " +
                        "Test each action in place before you save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        SkillDraftBus.draft.value = SkillDefinition(id = SkillStore.newId(), name = "My skill")
                        openTab("skill_builder")
                    },
                    enabled = AgentEngine.isRunning().not(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("New skill") }
            }
        }

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
                            rec.active -> "Recording… ${rec.steps.size} actions"
                            rec.finishedSteps != null -> "Recording finished - ${rec.finishedSteps?.size ?: 0} actions"
                            else -> "Quick record"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                when {
                    rec.active -> {
                        Text(
                            "App-event capture: buttons, typing, scrolls, app switches - confirmed live in the REC bubble " +
                                "(${rec.eventsSeen} events seen, ${rec.unusableEvents} not reportable by this app).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        rec.warnings.forEach { w ->
                            Text("⚠ $w", style = MaterialTheme.typography.labelSmall, color = Danger)
                        }
                        if (rec.steps.isNotEmpty()) {
                            rec.steps.takeLast(4).forEach { action ->
                                Text(
                                    "• ${action.describe()}",
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
                    }
                    rec.finishedSteps != null -> {
                        val finished = rec.finishedSteps ?: emptyList()
                        if (finished.isNotEmpty()) {
                            finished.take(5).forEach { action ->
                                Text(
                                    "• ${action.describe()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (finished.size > 5) Text("… +${finished.size - 5} more", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(
                                "Nothing was captured. The app probably doesn't report taps to accessibility - " +
                                    "build it with 'New skill' → 'Pick on screen' instead.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Danger,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    com.jarvis.mobile.core.skills.GrillMeEngine.startFinished(finished)
                                    openTab("grill_me")
                                },
                                enabled = finished.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) { Text("/grill-me") }
                            OutlinedButton(
                                onClick = {
                                    SkillDraftBus.draft.value = SkillDefinition(
                                        id = SkillStore.newId(),
                                        name = "My skill",
                                        actions = finished,
                                        source = "RECORDED",
                                    )
                                    openTab("skill_builder")
                                },
                                enabled = finished.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) { Text("Review & edit") }
                        }
                        OutlinedButton(onClick = { SkillRecorder.discard() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Discard recording")
                        }
                    }
                    else -> {
                        val a11yOn = com.jarvis.mobile.accessibility.JarvisAccessibilityService.CONNECTED.value
                        if (!a11yOn) {
                            Text(
                                "⚠ Enable JARVIS in Settings → Accessibility first - recording needs it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Danger,
                            )
                        }
                        OutlinedButton(
                            onClick = { showRecordStart = true },
                            enabled = a11yOn && AgentEngine.isRunning().not(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Quick record") }
                        Text(
                            "Captures what apps report (buttons, fields, scrolls, app switches) and shows each capture " +
                                "the moment it lands. For games/canvas apps use 'New skill' → 'Pick on screen'.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                "No skills yet. Build one above - then run it any time with one tap.",
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
                        skill.lastRunOk?.let { ok ->
                            Text(if (ok) "✓" else "✗", color = if (ok) Ok else Danger, style = MaterialTheme.typography.titleMedium)
                        }
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
                        "${skill.stepList().size} actions · run ${skill.runCount}x" +
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
                            onClick = { SkillDraftBus.draft.value = skill; openTab("skill_builder") },
                            modifier = Modifier.weight(1f),
                        ) { Text("Edit") }
                        OutlinedButton(
                            onClick = { confirmDelete = skill.id },
                            modifier = Modifier.weight(1f),
                        ) { Text("Delete", color = Danger) }
                    }
                    if (skill.lastRunLog.isNotEmpty()) {
                        Text(
                            if (expandedLog == skill.id) "Hide last run ▲" else "Last run log ▼",
                            style = MaterialTheme.typography.labelSmall,
                            color = Accent,
                            modifier = Modifier.clickable { expandedLog = if (expandedLog == skill.id) null else skill.id },
                        )
                        if (expandedLog == skill.id) {
                            skill.lastRunLog.forEach { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
            text = { Text("The skill and its run log are removed from this phone. Cannot be undone.") },
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
}

@Composable
private fun SourceBadge(source: String) {
    val (label, color) = when (source) {
        "GRILLED" -> "/grill-me" to Accent
        "RECORDED" -> "recorded" to Color(0xFF8A8A8E)
        else -> "built" to Ok
    }
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

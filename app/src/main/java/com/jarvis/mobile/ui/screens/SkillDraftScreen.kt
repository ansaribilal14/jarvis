package com.jarvis.mobile.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.agent.AgentEngine
import com.jarvis.mobile.core.skills.SkillDefinition
import com.jarvis.mobile.core.skills.SkillStep
import com.jarvis.mobile.core.skills.SkillStore
import com.jarvis.mobile.core.triggers.SkillTrigger
import com.jarvis.mobile.core.triggers.TimeTriggerScheduler
import com.jarvis.mobile.ui.theme.Accent
import com.jarvis.mobile.ui.theme.Danger

private val STEP_TYPES = listOf("TAP", "LONG_PRESS", "TEXT", "SCROLL", "BACK", "HOME", "APP_OPEN", "WAIT")

/**
 * Full-skill review before saving: the /grill-me draft (or a raw recording, or
 * an existing skill) lands here - every field is editable, every step can be
 * retargeted, reordered or deleted. Nothing persists until the user saves.
 */
@Composable
fun SkillDraftScreen(onClose: () -> Unit) {
    val original by remember { mutableStateOf(SkillDraftBus.draft.value) }

    // Nothing to edit -> leave immediately.
    LaunchedEffect(original) { if (original == null) onClose() }

    if (original == null) return

    var name by remember { mutableStateOf(original!!.name) }
    var description by remember { mutableStateOf(original!!.description) }
    var notes by remember { mutableStateOf(original!!.notes) }
    val steps = remember { androidx.compose.runtime.mutableStateListOf<SkillStep>().apply { addAll(original!!.steps) } }
    var editIndex by remember { mutableStateOf<Int?>(null) } // null = closed, -1 = adding new
    var trigger by remember { mutableStateOf(original!!.trigger) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Review skill", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (original!!.source == "GRILLED") "Written from your /grill-me interview - edit anything before saving."
            else "Recorded steps - rename, retarget or trim anything before saving.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent),
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("What it does") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent),
        )
        if (notes.isNotBlank() || original!!.interview.isNotEmpty()) {
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Guardrail notes (from the interview)") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent),
            )
        }

        // -------------------------------------------------- trigger editor
        TriggerEditor(trigger) { trigger = it }

        Text("STEPS (${steps.size})", style = MaterialTheme.typography.labelSmall, color = Accent)
        steps.forEachIndexed { i, step ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${i + 1}. ${step.describe()}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { editIndex = i }) { Text("Edit") }
                    TextButton(onClick = { steps.removeAt(i) }) { Text("✕", color = Danger) }
                }
            }
        }

        OutlinedButton(onClick = { editIndex = -1 }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Add step")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val def = buildDefinition(name, description, notes, steps.toList(), trigger)
                    SkillStore.save(JarvisApp.instance, def)
                    TimeTriggerScheduler.arm(JarvisApp.instance, def)
                    SkillDraftBus.draft.value = null
                    onClose()
                },
                enabled = steps.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("Save skill") }
            OutlinedButton(
                onClick = {
                    val def = buildDefinition(name, description, notes, steps.toList(), trigger)
                    SkillStore.save(JarvisApp.instance, def)
                    TimeTriggerScheduler.arm(JarvisApp.instance, def)
                    SkillDraftBus.draft.value = null
                    AgentEngine.runSkill(def)
                    onClose()
                },
                enabled = steps.isNotEmpty() && !AgentEngine.isRunning(),
                modifier = Modifier.weight(1f),
            ) { Text("Save & run") }
        }
        Spacer(Modifier.height(10.dp))
    }

    // ---------------------------------------------------------- step editor
    editIndex?.let { idx ->
        val editing = steps.getOrNull(idx)
        StepEditorDialog(
            initial = editing,
            onDismiss = { editIndex = null },
            onSave = { newStep ->
                if (idx >= 0 && idx < steps.size) steps[idx] = newStep else steps.add(newStep)
                editIndex = null
            },
        )
    }
}

private fun buildDefinition(
    name: String,
    description: String,
    notes: String,
    steps: List<SkillStep>,
    trigger: SkillTrigger?,
): SkillDefinition = SkillDefinition(
    id = SkillDraftBus.draft.value?.id ?: SkillStore.newId(),
    name = name.ifBlank { "My skill" },
    description = description,
    notes = notes,
    steps = steps,
    source = SkillDraftBus.draft.value?.source ?: "RECORDED",
    interview = SkillDraftBus.draft.value?.interview ?: emptyList(),
    trigger = trigger,
    createdAtMs = SkillDraftBus.draft.value?.createdAtMs ?: System.currentTimeMillis(),
    lastRunAtMs = SkillDraftBus.draft.value?.lastRunAtMs,
    runCount = SkillDraftBus.draft.value?.runCount ?: 0,
)

@Composable
private fun TriggerEditor(trigger: SkillTrigger?, onChange: (SkillTrigger?) -> Unit) {
    var tType by remember(trigger) { mutableStateOf(trigger?.type ?: "MANUAL") }
    var pkg by remember(trigger) { mutableStateOf(trigger?.pkg ?: "") }
    var text by remember(trigger) { mutableStateOf(trigger?.text ?: "") }
    var hour by remember(trigger) { mutableStateOf((trigger?.hour ?: 8).coerceIn(0, 23).toString()) }
    var minute by remember(trigger) { mutableStateOf((trigger?.minute ?: 0).coerceIn(0, 59).toString()) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AUTOMATION", style = MaterialTheme.typography.labelSmall, color = Accent)
            Text(
                "When should this skill run by itself?",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("MANUAL", "APP_OPEN", "APP_CLOSE", "TIME", "NOTIFICATION", "BATTERY_LOW").forEach { t ->
                    Text(
                        t.removePrefix("APP_").removeSuffix("_LOW").lowercase().replaceFirstChar { it.uppercase() }
                            .let { if (t == "APP_OPEN") "App opens" else if (t == "APP_CLOSE") "App closes" else if (t == "NOTIFICATION") "Notification" else if (t == "BATTERY_LOW") "Battery low" else it },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (t == tType) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .border(1.dp, if (t == tType) Accent else MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .clickable { tType = t },
                    )
                }
            }
            when (tType) {
                "APP_OPEN", "APP_CLOSE" -> OutlinedTextField(
                    value = pkg, onValueChange = { pkg = it },
                    label = { Text("Package (e.g. com.whatsapp)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent),
                )
                "TIME" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = hour, onValueChange = { hour = it }, label = { Text("Hour (0-23)") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = minute, onValueChange = { minute = it }, label = { Text("Minute (0-59)") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                "NOTIFICATION" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pkg, onValueChange = { pkg = it },
                        label = { Text("Package (blank = any app)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent),
                    )
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        label = { Text("Contains text (blank = anything)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent),
                    )
                    Text(
                        "Use {{title}} / {{text}} in a typed step to forward the notification content.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val current = trigger
            val changed = when {
                tType == "MANUAL" -> current != null
                tType != (current?.type ?: "MANUAL") -> true
                else -> current != SkillTrigger(
                    type = tType, pkg = pkg.ifBlank { null }, hour = hour.toIntOrNull() ?: -1,
                    minute = minute.toIntOrNull() ?: -1, text = text.ifBlank { null },
                    cooldownSec = current?.cooldownSec ?: 60,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(trigger?.describe() ?: "Runs only when you tap Run", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                if (tType != "MANUAL") {
                    TextButton(onClick = { onChange(SkillTrigger(type = tType, pkg = pkg.ifBlank { null }, hour = hour.toIntOrNull() ?: -1, minute = minute.toIntOrNull() ?: -1, text = text.ifBlank { null })) }) { Text("Apply") }
                }
                if (tType == "MANUAL" && current != null) {
                    TextButton(onClick = { onChange(null) }) { Text("Remove trigger") }
                }
                if (tType != "MANUAL" && !changed) {
                    Text("armed", style = MaterialTheme.typography.labelSmall, color = com.jarvis.mobile.ui.theme.Ok)
                }
            }
        }
    }
}

@Composable
private fun StepEditorDialog(initial: SkillStep?, onDismiss: () -> Unit, onSave: (SkillStep) -> Unit) {
    var type by remember { mutableStateOf(initial?.type ?: "TAP") }
    var text by remember { mutableStateOf(initial?.text ?: "") }
    var desc by remember { mutableStateOf(initial?.desc ?: "") }
    var viewId by remember { mutableStateOf(initial?.viewId ?: "") }
    var input by remember { mutableStateOf(initial?.input ?: "") }
    var x by remember { mutableStateOf(initial?.x?.toString() ?: "") }
    var y by remember { mutableStateOf(initial?.y?.toString() ?: "") }
    var waitMs by remember { mutableStateOf(initial?.waitMs?.toString() ?: "800") }
    var dir by remember { mutableStateOf(initial?.dir ?: "fwd") }
    var pkg by remember { mutableStateOf(initial?.pkg ?: "") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add step" else "Edit step") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    STEP_TYPES.forEach { t ->
                        Text(
                            t,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (t == type) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .border(
                                    1.dp,
                                    if (t == type) Accent else MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(50),
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .clickable { type = t },
                        )
                    }
                }
                if (type in setOf("TAP", "LONG_PRESS", "TEXT")) {
                    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Visible label (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Content description (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = viewId, onValueChange = { viewId = it }, label = { Text("Resource id (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = x, onValueChange = { x = it }, label = { Text("x") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = y, onValueChange = { y = it }, label = { Text("y") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
                if (type == "TEXT") {
                    OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("Text to type") }, modifier = Modifier.fillMaxWidth())
                }
                if (type == "SCROLL") {
                    OutlinedTextField(value = dir, onValueChange = { dir = it }, label = { Text("Direction: fwd / back") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (type == "WAIT") {
                    OutlinedTextField(value = waitMs, onValueChange = { waitMs = it }, label = { Text("Milliseconds to wait") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (type == "APP_OPEN") {
                    OutlinedTextField(value = pkg, onValueChange = { pkg = it }, label = { Text("Package (e.g. com.whatsapp)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (type in setOf("BACK", "HOME")) {
                    Text("No extra fields for this step.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    SkillStep(
                        type = type,
                        pkg = pkg.ifBlank { null },
                        viewId = viewId.ifBlank { null },
                        text = text.ifBlank { null },
                        desc = desc.ifBlank { null },
                        x = x.toIntOrNull(),
                        y = y.toIntOrNull(),
                        input = input.ifBlank { null },
                        dir = dir.ifBlank { null },
                        waitMs = waitMs.toLongOrNull(),
                    ),
                )
            }) { Text("Save step") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jarvis.mobile.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import com.jarvis.mobile.core.skills.ElementTarget
import com.jarvis.mobile.core.skills.SkillAction
import com.jarvis.mobile.core.skills.SkillDefinition
import com.jarvis.mobile.core.skills.SkillRunner
import com.jarvis.mobile.core.skills.SkillStore
import com.jarvis.mobile.core.triggers.SkillTrigger
import com.jarvis.mobile.core.triggers.TimeTriggerScheduler
import com.jarvis.mobile.ui.PickResultBus
import com.jarvis.mobile.ui.PickOnScreenActivity
import com.jarvis.mobile.ui.theme.Accent
import com.jarvis.mobile.ui.theme.Danger
import com.jarvis.mobile.ui.theme.Ok
import kotlinx.coroutines.launch

/** The action catalog - what a skill CAN be made of (all public-API backed). */
private data class CatalogEntry(
    val type: String,
    val title: String,
    val desc: String,
)

private val CATALOG = listOf(
    CatalogEntry("LAUNCH_APP", "Open app", "Start any installed app"),
    CatalogEntry("UI_CLICK", "Tap element", "Tap a button / item on a screen"),
    CatalogEntry("UI_LONG_PRESS", "Long-press", "Press and hold an element"),
    CatalogEntry("UI_TEXT", "Type text", "Put text into a field (search, chat, forms)"),
    CatalogEntry("SCROLL", "Scroll", "Scroll down / up / left / right"),
    CatalogEntry("BACK", "Press Back", "System Back"),
    CatalogEntry("HOME", "Go Home", "System Home"),
    CatalogEntry("RECENTS", "Recents", "Open the recents overview"),
    CatalogEntry("WAIT", "Wait", "Give a screen time to load"),
    CatalogEntry("NOTIFY", "Notification", "Show a JARVIS notification as a checkpoint"),
)

internal data class AppEntry(val label: String, val pkg: String)

internal fun queryLaunchableApps(): List<AppEntry> {
    val context = JarvisApp.instance
    return runCatching {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val entries = ArrayList<AppEntry>(resolved.size)
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == context.packageName) continue
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
            entries.add(AppEntry(label.ifBlank { pkg }, pkg))
        }
        entries.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}

/**
 * The skills-v3 builder (MacroDroid model): name a skill, pick a trigger, add
 * ACTIONS from a catalog, target elements with "Pick on screen" (screenshot +
 * tap - works in every app) or by label, and test each action in place.
 * Nothing about recording is load-bearing anymore (docs/SKILLS_V3.md).
 */
@Composable
fun SkillBuilderScreen(onClose: () -> Unit) {
    val context = JarvisApp.instance
    val original by remember { mutableStateOf(SkillDraftBus.draft.value) }

    LaunchedEffect(original) { if (original == null) onClose() }
    if (original == null) return

    var name by remember { mutableStateOf(original!!.name) }
    var description by remember { mutableStateOf(original!!.description) }
    var notes by remember { mutableStateOf(original!!.notes) }
    val actions = remember { mutableStateListOf<SkillAction>().apply { addAll(original!!.stepList()) } }
    var trigger by remember { mutableStateOf(original!!.trigger) }
    var editIndex by remember { mutableStateOf<Int?>(null) } // null = closed, -1 = add-new
    var editType by remember { mutableStateOf<String?>(null) }
    var showCatalog by remember { mutableStateOf(false) }
    var testFeedback by remember { mutableStateOf<Map<Int, Pair<Boolean, String>>>(emptyMap()) }
    var testing by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Build skill", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Choose actions from the catalog and aim each one - with 'Pick on screen' or a label. " +
                "Every action can be tested right here before you save.",
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
                label = { Text("Guardrail notes") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent),
            )
        }

        TriggerEditor(trigger) { trigger = it }

        Text("ACTIONS (${actions.size})", style = MaterialTheme.typography.labelSmall, color = Accent)
        actions.forEachIndexed { i, action ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${i + 1}. ${action.describe()}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    testFeedback[i]?.let { (ok, msg) ->
                        Text(
                            (if (ok) "✓ " else "✗ ") + msg,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ok) Ok else Danger,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.align(Alignment.End)) {
                        TextButton(onClick = {
                            testing = i
                            scope.launch {
                                val out = runCatching { SkillRunner.testAction(action) }
                                    .getOrElse { SkillRunner.ActionOutcome(false, "Test failed: ${it.message?.take(60)}", false) }
                                testFeedback = testFeedback + (i to (out.ok to out.message))
                                testing = null
                            }
                        }, enabled = testing == null) {
                            Text(if (testing == i) "…" else "Test", color = Accent)
                        }
                        TextButton(onClick = { editIndex = i; editType = action.type }) { Text("Edit") }
                        TextButton(onClick = { if (i > 0) { actions.add(i - 1, actions.removeAt(i)); testFeedback = emptyMap() } }) { Text("▲") }
                        TextButton(onClick = { if (i < actions.size - 1) { actions.add(i + 1, actions.removeAt(i)); testFeedback = emptyMap() } }) { Text("▼") }
                        TextButton(onClick = { actions.removeAt(i); testFeedback = emptyMap() }) { Text("✕", color = Danger) }
                    }
                }
            }
        }

        OutlinedButton(onClick = { showCatalog = true }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Add action")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val def = buildDefinition(original, name, description, notes, actions.toList(), trigger)
                    SkillStore.save(context, def)
                    TimeTriggerScheduler.arm(context, def)
                    SkillDraftBus.draft.value = null
                    onClose()
                },
                enabled = actions.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("Save skill") }
            OutlinedButton(
                onClick = {
                    val def = buildDefinition(original, name, description, notes, actions.toList(), trigger)
                    SkillStore.save(context, def)
                    TimeTriggerScheduler.arm(context, def)
                    SkillDraftBus.draft.value = null
                    AgentEngine.runSkill(def)
                    onClose()
                },
                enabled = actions.isNotEmpty() && !AgentEngine.isRunning(),
                modifier = Modifier.weight(1f),
            ) { Text("Save & run") }
        }
        Spacer(Modifier.height(10.dp))
    }

    if (showCatalog) {
        ModalBottomSheet(onDismissRequest = { showCatalog = false }) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Add action", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                CATALOG.forEach { entry ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                showCatalog = false
                                if (entry.type in setOf("BACK", "HOME", "RECENTS")) {
                                    actions.add(SkillAction(id = newId(), type = entry.type))
                                } else {
                                    editType = entry.type
                                    editIndex = -1
                                }
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            entry.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    editIndex?.let { idx ->
        val editing = actions.getOrNull(idx)
        ActionConfigDialog(
            type = editType ?: editing?.type ?: "UI_CLICK",
            initial = editing,
            onDismiss = { editIndex = null; editType = null },
            onSave = { newAction ->
                if (idx >= 0 && idx < actions.size) actions[idx] = newAction else actions.add(newAction)
                editIndex = null
                editType = null
            },
            onPickOnScreen = {
                PickOnScreenActivity.request(context)
                // Minimize JARVIS so the user can navigate to the target screen.
                val ctx = context
                runCatching {
                    Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                        .let { ctx.startActivity(it) }
                }
            },
        )
    }
}

private fun newId(): String = "a${System.currentTimeMillis()}${(0..999).random()}"

private fun buildDefinition(
    original: SkillDefinition?,
    name: String,
    description: String,
    notes: String,
    actions: List<SkillAction>,
    trigger: SkillTrigger?,
): SkillDefinition = SkillDefinition(
    id = original?.id ?: SkillStore.newId(),
    name = name.ifBlank { "My skill" },
    description = description,
    notes = notes,
    actions = actions,
    source = original?.source ?: "BUILT",
    interview = original?.interview ?: emptyList(),
    trigger = trigger,
    createdAtMs = original?.createdAtMs ?: System.currentTimeMillis(),
    lastRunAtMs = original?.lastRunAtMs,
    runCount = original?.runCount ?: 0,
    lastRunOk = original?.lastRunOk,
    lastRunLog = original?.lastRunLog ?: emptyList(),
)

/** Per-action config dialog with "Pick on screen" for UI targets. */
@Composable
private fun ActionConfigDialog(
    type: String,
    initial: SkillAction?,
    onDismiss: () -> Unit,
    onSave: (SkillAction) -> Unit,
    onPickOnScreen: () -> Unit,
) {
    var appPkg by remember { mutableStateOf(initial?.appPackage ?: "") }
    var label by remember { mutableStateOf(initial?.target?.text ?: "") }
    var desc by remember { mutableStateOf(initial?.target?.desc ?: "") }
    var viewId by remember { mutableStateOf(initial?.target?.viewId ?: "") }
    var input by remember { mutableStateOf(initial?.input ?: "") }
    var dir by remember { mutableStateOf(initial?.dir ?: "down") }
    var amount by remember { mutableStateOf((initial?.amount ?: 1).toString()) }
    var waitSec by remember { mutableStateOf(((initial?.waitMs ?: 1000) / 1000).toString()) }
    var message by remember { mutableStateOf(initial?.message ?: "") }
    var pickedTarget by remember { mutableStateOf(initial?.target) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }

    LaunchedEffect(Unit) { apps = queryLaunchableApps() }
    LaunchedEffect(initial) { if (initial?.target != null) pickedTarget = initial.target }

    // A finished "Pick on screen" lands here: adopt the target AND surface its
    // descriptors in the fields so the user sees (and can tweak) what was captured.
    LaunchedEffect(PickResultBus.result.value) {
        val res = PickResultBus.result.value ?: return@LaunchedEffect
        PickResultBus.result.value = null
        pickedTarget = res.target
        res.target.text?.let { label = it }
        res.target.desc?.let { desc = it }
        res.target.viewId?.let { viewId = it }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(CATALOG.firstOrNull { it.type == type }?.title ?: type) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (type) {
                    "LAUNCH_APP" -> {
                        AppDropdown(apps, appPkg) { appPkg = it }
                    }
                    "UI_CLICK", "UI_LONG_PRESS", "UI_TEXT" -> {
                        OutlinedTextField(
                            value = label, onValueChange = { label = it; pickedTarget = pickedTarget?.copy(text = it.ifBlank { null }) },
                            label = { Text("Button label (exact or contains)") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent),
                        )
                        OutlinedTextField(
                            value = desc, onValueChange = { desc = it; pickedTarget = pickedTarget?.copy(desc = it.ifBlank { null }) },
                            label = { Text("Or content description") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = viewId, onValueChange = { viewId = it; pickedTarget = pickedTarget?.copy(viewId = it.ifBlank { null }) },
                            label = { Text("Or resource id (e.g. send_button)") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        pickedTarget?.let { t ->
                            Text(
                                when {
                                    t.fx != null && t.fy != null && t.text.isNullOrBlank() && t.desc.isNullOrBlank() && t.viewId.isNullOrBlank() ->
                                        "Point target: ${(t.fx * 100).toInt()}%, ${(t.fy * 100).toInt()}%" + (t.pkg?.let { " in ${it.substringBefore('.')}" } ?: "")
                                    else -> "Current target: ${t.describe()}" + (t.pkg?.let { " in ${it.substringBefore('.')}" } ?: "")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Accent,
                            )
                        }
                        Button(onClick = onPickOnScreen, modifier = Modifier.fillMaxWidth()) {
                            Text("Pick on screen (works in every app)")
                        }
                        Text(
                            "Opens the screen; tap the notification, navigate, then tap the exact element on the screenshot.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (type == "UI_TEXT") {
                            OutlinedTextField(
                                value = input, onValueChange = { input = it },
                                label = { Text("Text to type") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    "SCROLL" -> {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("down", "up", "left", "right").forEach { d ->
                                Text(
                                    d,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (d == dir) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .border(1.dp, if (d == dir) Accent else MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                        .clickable { dir = d },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = amount, onValueChange = { amount = it },
                            label = { Text("How many pages (1-10)") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    "WAIT" -> OutlinedTextField(
                        value = waitSec, onValueChange = { waitSec = it },
                        label = { Text("Seconds to wait") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    "NOTIFY" -> OutlinedTextField(
                        value = message, onValueChange = { message = it },
                        label = { Text("Notification text") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    else -> Text("No extra fields for this action.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val target = pickedTarget?.copy(
                    text = label.ifBlank { null },
                    desc = desc.ifBlank { null },
                    viewId = viewId.ifBlank { null },
                )?.takeIf { it.hasNode || it.hasPoint }
                val action = when (type) {
                    "LAUNCH_APP" -> SkillAction(id = initial?.id ?: newId(), type = type, appPackage = appPkg.ifBlank { null })
                    "UI_CLICK", "UI_LONG_PRESS", "UI_TEXT" -> SkillAction(
                        id = initial?.id ?: newId(), type = type, target = target,
                        input = if (type == "UI_TEXT") input.ifBlank { null } else null,
                    )
                    "SCROLL" -> SkillAction(id = initial?.id ?: newId(), type = type, dir = dir, amount = (amount.toIntOrNull() ?: 1).coerceIn(1, 10))
                    "WAIT" -> SkillAction(id = initial?.id ?: newId(), type = type, waitMs = ((waitSec.toDoubleOrNull() ?: 1.0) * 1000).toLong().coerceIn(100, 60_000))
                    "NOTIFY" -> SkillAction(id = initial?.id ?: newId(), type = type, message = message.ifBlank { "Checkpoint" })
                    else -> SkillAction(id = initial?.id ?: newId(), type = type)
                }
                onSave(action)
            }) { Text("Save action") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AppDropdown(apps: List<AppEntry>, selectedPkg: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = apps.firstOrNull { it.pkg == selectedPkg }
    var filter by remember { mutableStateOf("") }
    Column {
        OutlinedTextField(
            value = selected?.label ?: selectedPkg,
            onValueChange = {},
            readOnly = true,
            label = { Text("App to open") },
            placeholder = { Text("Pick from your apps") },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = true },
        )
        if (open) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                apps.filter { filter.isBlank() || it.label.contains(filter, true) }.take(40).forEach { app ->
                    Text(
                        app.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(app.pkg); open = false }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }
            }
            TextButton(onClick = { open = false }) { Text("Close list") }
        }
    }
}

/** Trigger editor (carried over from the v2 draft screen - unchanged behavior). */
@Composable
internal fun TriggerEditor(trigger: SkillTrigger?, onChange: (SkillTrigger?) -> Unit) {
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
                "APP_OPEN", "APP_CLOSE" -> AppPkgField(pkg) { pkg = it }
                "TIME" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = hour, onValueChange = { hour = it }, label = { Text("Hour (0-23)") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = minute, onValueChange = { minute = it }, label = { Text("Minute (0-59)") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                "NOTIFICATION" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppPkgField(pkg, label = "Package (blank = any app)") { pkg = it }
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        label = { Text("Contains text (blank = anything)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Use {{title}} / {{text}} in a 'Type text' action to forward the notification content.",
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
                    Text("armed", style = MaterialTheme.typography.labelSmall, color = Ok)
                }
            }
        }
    }
}

@Composable
private fun AppPkgField(pkg: String, label: String = "Package (e.g. com.whatsapp)", onChange: (String) -> Unit) {
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { apps = queryLaunchableApps() }
    Column {
        OutlinedTextField(
            value = pkg, onValueChange = { onChange(it); open = false },
            label = { Text(label) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent),
        )
        if (open) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                apps.take(40).forEach { app ->
                    Text(
                        "${app.label} (${app.pkg})",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChange(app.pkg); open = false }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                    )
                }
            }
        }
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide app list" else "Or pick from your apps") }
    }
}

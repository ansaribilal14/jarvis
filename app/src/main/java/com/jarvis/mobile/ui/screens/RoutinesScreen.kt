package com.jarvis.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.ui.components.EmptyState
import com.jarvis.mobile.ui.components.SectionCard
import com.jarvis.mobile.ui.components.StatusChip
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local routines (spec: ROUTINES) - transparent, controllable automation. */
@Composable
fun RoutinesScreen() {
    val container = JarvisApp.instance.container
    val scope = rememberCoroutineScope()
    val routines by container.db.routineDao().all().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<com.jarvis.mobile.data.db.RoutineEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Routines", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Scheduled instructions JARVIS runs locally, e.g. every morning at 8. Routines can only use low-risk tools - anything sensitive is blocked and reported.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { creating = true }) { Text("+ New routine") }

        if (routines.isEmpty()) EmptyState("No routines yet.")
        routines.forEach { r ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, onClick = { editing = r }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        StatusChip("%02d:%02d".format(r.hour, r.minute), com.jarvis.mobile.ui.components.ChipState.ACCENT)
                    }
                    Text(r.instruction, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    r.lastRunAt?.let { Text("Last run: ${fmt.format(Date(it))}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Enabled", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(
                            checked = r.enabled,
                            onCheckedChange = { on ->
                                scope.launch {
                                    val updated = r.copy(enabled = on)
                                    container.db.routineDao().update(updated)
                                    if (on) container.routineManager.schedule(updated) else container.routineManager.cancel(r.id)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        RoutineEditor(
            initial = editing,
            onSave = { r ->
                scope.launch {
                    val id = container.db.routineDao().insert(r)
                    if (r.enabled) container.routineManager.schedule(r.copy(id = id))
                }
                creating = false; editing = null
            },
            onDelete = editing?.let { r ->
                {
                    scope.launch {
                        container.routineManager.cancel(r.id)
                        container.db.routineDao().delete(r.id)
                    }
                    editing = null
                }
            },
            onDismiss = { creating = false; editing = null },
        )
    }
}

private val dayLabels = listOf("M", "T", "W", "T", "F", "S", "S")

@Composable
private fun RoutineEditor(
    initial: com.jarvis.mobile.data.db.RoutineEntity?,
    onSave: (com.jarvis.mobile.data.db.RoutineEntity) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var instruction by remember { mutableStateOf(initial?.instruction ?: "") }
    var hour by remember { mutableIntStateOf(initial?.hour ?: 8) }
    var minute by remember { mutableIntStateOf(initial?.minute ?: 0) }
    var days by remember { mutableIntStateOf(initial?.daysMask ?: 0b1111111) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New routine" else "Edit routine") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(instruction, { instruction = it }, label = { Text("Instruction for Jarvis") }, minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        hour.toString(), { hour = it.toIntOrNull()?.coerceIn(0, 23) ?: hour },
                        label = { Text("Hour (0-23)") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        minute.toString(), { minute = it.toIntOrNull()?.coerceIn(0, 59) ?: minute },
                        label = { Text("Minute") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    dayLabels.forEachIndexed { i, d ->
                        FilterChip(
                            selected = days and (1 shl i) != 0,
                            onClick = { days = days xor (1 shl i) },
                            label = { Text(d) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && instruction.isNotBlank()) {
                    onSave(
                        com.jarvis.mobile.data.db.RoutineEntity(
                            id = initial?.id ?: 0,
                            name = name.trim(), instruction = instruction.trim(),
                            hour = hour, minute = minute, daysMask = days, enabled = enabled,
                        ),
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Delete") } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

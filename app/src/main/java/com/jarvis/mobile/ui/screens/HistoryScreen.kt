package com.jarvis.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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

/** Task history (spec: TASK HISTORY) with per-step inspection, retry and delete. */
@Composable
fun HistoryScreen() {
    val container = JarvisApp.instance.container
    val scope = rememberCoroutineScope()
    val tasks by container.memory.tasks().collectAsState(initial = emptyList())
    var detailFor by remember { mutableStateOf<Long?>(null) }
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Task history", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Everything JARVIS did, stored only on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tasks.isEmpty()) EmptyState("No tasks yet. Ask Jarvis something from the home tab.")
        tasks.forEach { t ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                onClick = { detailFor = t.id },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        StatusChip(
                            t.status,
                            when (t.status) {
                                "COMPLETED" -> com.jarvis.mobile.ui.components.ChipState.OK
                                "STOPPED" -> com.jarvis.mobile.ui.components.ChipState.WARN
                                else -> com.jarvis.mobile.ui.components.ChipState.BAD
                            },
                        )
                        Text(fmt.format(Date(t.startedAt)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(t.goal, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    t.resultSummary?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
            }
        }
        if (tasks.isNotEmpty()) {
            TextButton(onClick = { scope.launch { container.memory.purgeHistory() } }) { Text("Clear all history") }
        }
        Spacer(Modifier.height(24.dp))
    }

    val detailState = remember { mutableStateOf<Pair<com.jarvis.mobile.data.db.TaskEntity, List<com.jarvis.mobile.data.db.StepEntity>>?>(null) }

    androidx.compose.runtime.LaunchedEffect(detailFor) {
        detailFor?.let { id ->
            detailState.value = container.memory.taskWithSteps(id)
        } ?: run { detailState.value = null }
    }
    detailState.value?.let { (task, steps) ->
        AlertDialog(
            onDismissRequest = { detailState.value = null; detailFor = null },
            title = { Text(task.goal, maxLines = 3) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Status: ${task.status} · ${task.actionsUsed} actions", style = MaterialTheme.typography.labelMedium)
                    steps.forEach { s ->
                        Column {
                            Text("${s.idx}. ${s.tool} — ${s.status}", style = MaterialTheme.typography.titleMedium)
                            s.observation?.let { Text("→ $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            s.verification?.let { Text("✓ $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                    task.resultSummary?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    JarvisApp.instance.container.let {
                        com.jarvis.mobile.core.agent.AgentEngine.runGoal(task.goal, source = "QUICK")
                    }
                    detailState.value = null
                    detailFor = null
                }) { Text("Retry task") }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch { JarvisApp.instance.container.memory.deleteTask(task.id) }
                    detailState.value = null
                    detailFor = null
                }) { Text("Delete") }
            },
        )
    }
}

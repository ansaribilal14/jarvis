package com.jarvis.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jarvis.mobile.ui.theme.Accent
import com.jarvis.mobile.ui.theme.Danger
import com.jarvis.mobile.ui.theme.Ok
import com.jarvis.mobile.ui.theme.Warn

enum class ChipState { OK, WARN, BAD, NEUTRAL, ACCENT }

@Composable
fun StatusChip(text: String, state: ChipState) {
    val color = when (state) {
        ChipState.OK -> Ok
        ChipState.WARN -> Warn
        ChipState.BAD -> Danger
        ChipState.ACCENT -> Accent
        ChipState.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

@Composable
fun ConfirmDialog(
    req: com.jarvis.mobile.core.agent.ConfirmationRequest,
    onAnswer: (String, Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAnswer(req.id, false) },
        title = { Text(req.what) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Labeled("TARGET", req.target)
                Labeled("DETAILS", req.details)
                Labeled("WHY", req.why)
                StatusChip("RISK: ${req.risk}", if (req.risk == "HIGH") ChipState.BAD else ChipState.WARN)
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(req.id, true) }) { Text("Confirm", color = Accent) } },
        dismissButton = { TextButton(onClick = { onAnswer(req.id, false) }) { Text("Cancel", color = Danger) } },
    )
}

@Composable
fun Labeled(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun stepColor(status: String): Color = when (status) {
    "SUCCESS" -> Ok
    "RUNNING" -> Accent
    "FAILED", "BLOCKED" -> Danger
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

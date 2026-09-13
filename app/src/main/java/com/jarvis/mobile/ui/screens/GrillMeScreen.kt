package com.jarvis.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.mobile.core.skills.GrillMeEngine
import com.jarvis.mobile.ui.theme.Accent

/**
 * /grill-me: the AI interviews the user one targeted question at a time about
 * the recording (assumptions, per-run variation, failure behavior, guardrails),
 * then writes the full skill draft for review. Canned-ladder fallback keeps it
 * working with zero AI.
 */
@Composable
fun GrillMeScreen(onClose: () -> Unit) {
    val st by GrillMeEngine.state.collectAsState()
    var answer by remember { mutableStateOf("") }

    // Draft ready -> hand it to the editor and leave the interview.
    LaunchedEffect(st.done, st.draft) {
        if (st.done && st.draft != null) {
            SkillDraftBus.draft.value = st.draft
            GrillMeEngine.clear()
            onClose()
        }
    }
    // Nothing to interview about -> leave.
    LaunchedEffect(st.active, st.done) {
        if (!st.active && !st.done) onClose()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("/grill-me", style = MaterialTheme.typography.headlineSmall, color = Accent)
        Text(
            "I will interrogate you about this recording - a few targeted questions so the " +
                "skill actually does what you mean, then I write the full draft for you to edit.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Recorded steps under discussion
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("RECORDED (${st.steps.size} steps)", style = MaterialTheme.typography.labelSmall, color = Accent)
                st.steps.take(6).forEach { Text("• ${it.describe()}", style = MaterialTheme.typography.bodySmall) }
                if (st.steps.size > 6) Text("… +${st.steps.size - 6} more", style = MaterialTheme.typography.bodySmall)
            }
        }

        // Progress
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(
                progress = { st.qa.size.coerceAtMost(5) / 5f },
                modifier = Modifier.weight(1f).height(4.dp),
                color = Accent,
            )
            Text("${st.qa.size}/5", style = MaterialTheme.typography.labelSmall)
        }

        // Asked questions + your answers
        st.qa.forEach { pair ->
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(pair.q, style = MaterialTheme.typography.titleSmall)
                    Text(
                        pair.a,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Current question
        when {
            st.currentQuestion != null -> {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(st.currentQuestion ?: "", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = answer,
                            onValueChange = { answer = it },
                            placeholder = { Text("Your answer…") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent),
                            minLines = 1,
                            maxLines = 4,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { GrillMeEngine.answer(answer.ifBlank { "(no answer)" }); answer = "" },
                                modifier = Modifier.weight(1f),
                            ) { Text("Answer") }
                            OutlinedButton(
                                onClick = { GrillMeEngine.skip(); answer = "" },
                                modifier = Modifier.weight(1f),
                            ) { Text("Skip") }
                        }
                        OutlinedButton(
                            onClick = { GrillMeEngine.finish() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("That's enough - write the skill") }
                    }
                }
            }
            st.thinking -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Thinking of the next question…", style = MaterialTheme.typography.bodyMedium)
            }
        }

        st.error?.let { Text(it, color = androidx.compose.ui.graphics.Color.Red, style = MaterialTheme.typography.bodySmall) }

        OutlinedButton(onClick = { GrillMeEngine.cancel(); onClose() }, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel interview")
        }
        Spacer(Modifier.height(10.dp))
    }
}

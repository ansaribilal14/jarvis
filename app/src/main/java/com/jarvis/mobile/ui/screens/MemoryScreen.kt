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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.ui.components.EmptyState
import com.jarvis.mobile.ui.components.SectionCard
import kotlinx.coroutines.launch

/** Memory controls (spec: LOCAL MEMORY UI) - inspect / edit / delete / disable. */
@Composable
fun MemoryScreen() {
    val container = JarvisApp.instance.container
    val scope = rememberCoroutineScope()
    val facts by container.memory.facts().collectAsState(initial = emptyList())
    val memoryEnabled by container.settings.memoryEnabled.collectAsState(initial = true)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Memory", style = MaterialTheme.typography.headlineMedium)
        SectionCard("Preferences") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Memory enabled", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    checked = memoryEnabled,
                    onCheckedChange = { container.settings.setMemoryEnabled(it) },
                )
            }
            Text(
                "When off, JARVIS stores nothing about this conversation or tasks.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Facts JARVIS remembers") {
            var key by remember { mutableStateOf("") }
            var value by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(key, { key = it }, label = { Text("Key") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(value, { value = it }, label = { Text("Value") }, modifier = Modifier.weight(1.4f), singleLine = true)
            }
            TextButton(onClick = {
                if (key.isNotBlank() && value.isNotBlank()) {
                    scope.launch { container.memory.rememberFact(key.trim(), value.trim()) }
                    key = ""; value = ""
                }
            }) { Text("Add fact") }

            if (facts.isEmpty()) EmptyState("No facts stored.")
            facts.forEach { f ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(f.key, style = MaterialTheme.typography.titleMedium)
                            Text(f.value, style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = { scope.launch { container.memory.deleteFact(f.id) } }) { Text("Delete") }
                    }
                }
            }
            if (facts.isNotEmpty()) {
                TextButton(onClick = { scope.launch { container.memory.clearFacts() } }) { Text("Clear all facts") }
            }
        }

        SectionCard("Privacy") {
            Text(
                "Passwords, PINs, OTPs and card-like numbers are redacted before anything is stored. Screen contents are never saved as memories. Everything lives in a local database on this device only.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

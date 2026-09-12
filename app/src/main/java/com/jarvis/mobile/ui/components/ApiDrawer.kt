package com.jarvis.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.ui.theme.Accent
import com.jarvis.mobile.ui.theme.Danger
import com.jarvis.mobile.ui.theme.Ok
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Tiny app-wide bus so any screen can open the API-mode sidebar. */
object ApiDrawerBus {
    val open = MutableStateFlow(false)

    fun requestOpen() {
        open.value = true
    }
}

/** Free-tier NVIDIA NIM models offered as one-tap picks in the sidebar. */
private val NIM_MODELS = listOf(
    "meta/llama-3.1-8b-instruct",
    "meta/llama-3.3-70b-instruct",
    "mistralai/mistral-nemo-12b-instruct",
    "qwen/qwen2.5-7b-instruct",
    "deepseek-ai/deepseek-r1-distill-llama-8b",
)
private const val NIM_BASE_URL = "https://integrate.api.nvidia.com/v1"

/**
 * The API-mode sidebar: paste an NVIDIA NIM key, pick a free model, flip
 * API mode on - from then on every task runs against NIM automatically
 * (no local model activation, no further questions).
 */
@Composable
fun ApiDrawerSheet() {
    val container = JarvisApp.instance.container
    val scope = rememberCoroutineScope()
    val apiMode by container.settings.apiMode.collectAsState(initial = false)
    val nimModel by container.settings.nimModel.collectAsState(initial = NIM_MODELS.first())
    var keyField by remember { mutableStateOf("") }
    var modelField by remember { mutableStateOf("") }
    var testState by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(nimModel) { modelField = nimModel }

    ModalDrawerSheet(drawerContentColor = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text("API mode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "NVIDIA NIM · free models · key stays encrypted on this device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = keyField,
                onValueChange = { keyField = it },
                label = { Text("NVIDIA API key (nvapi-…)") },
                placeholder = { Text("paste your key here") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            Text("Model", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            NIM_MODELS.forEach { m ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = nimModel == m,
                        onClick = {
                            modelField = m
                            scope.launch { container.settings.setNimModel(m) }
                        },
                    )
                    Text(m, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(
                value = modelField,
                onValueChange = { modelField = it },
                label = { Text("Custom model id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = {
                val v = modelField.trim()
                if (v.isNotEmpty()) scope.launch { container.settings.setNimModel(v) }
            }) { Text("Save model id") }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (apiMode) "API mode is ON" else "API mode is OFF",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = apiMode,
                    onCheckedChange = { on ->
                        scope.launch {
                            if (on) {
                                // Turning ON persists everything the engine needs:
                                // key, NIM endpoint, model - then tasks just work.
                                if (keyField.isNotBlank()) container.vault.remoteApiKey = keyField.trim()
                                container.settings.setRemoteBaseUrl(NIM_BASE_URL)
                                container.settings.setRemoteModel(modelField.trim().ifBlank { nimModel })
                                container.settings.setNimModel(modelField.trim().ifBlank { nimModel })
                                container.settings.setLocalOnly(false)
                            }
                            container.settings.setApiMode(on)
                            testState = if (on) {
                                "API mode on - tasks now run on ${modelField.trim().ifBlank { nimModel }}" to true
                            } else null
                        }
                    },
                )
            }

            if (apiMode) {
                Text(
                    "Every task now uses ${nimModel.substringAfterLast('/')} - no local model needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ok,
                )
            } else {
                Text(
                    "Off - JARVIS uses the on-device local model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        if (keyField.isNotBlank()) container.vault.remoteApiKey = keyField.trim()
                        container.settings.setRemoteBaseUrl(NIM_BASE_URL)
                        val v = modelField.trim().ifBlank { nimModel }
                        container.settings.setRemoteModel(v)
                        container.settings.setNimModel(v)
                        val r = container.remoteProvider.verifyKey()
                        testState = r.fold(
                            onSuccess = { "Key works - model replied: ${it.take(40)}" to true },
                            onFailure = { "Key test failed: ${it.message?.take(90)}" to false },
                        )
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Testing…" else "Test & save key") }

            testState?.let { (msg, ok) ->
                Spacer(Modifier.height(8.dp))
                Text(msg, style = MaterialTheme.typography.bodySmall, color = if (ok) Ok else Danger)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                "Get a free key at build.nvidia.com (Sign in → any model → Get API key). " +
                    "Requests go directly from this phone to NVIDIA - nothing is proxied. " +
                    "Turn API mode off anytime to return fully on-device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

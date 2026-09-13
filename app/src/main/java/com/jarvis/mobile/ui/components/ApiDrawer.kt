package com.jarvis.mobile.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import kotlinx.coroutines.flow.first
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

/** OpenRouter free-tier models (no card needed - MobileAgent-style free cloud brains). */
private val OPENROUTER_MODELS = listOf(
    "openai/gpt-oss-120b:free",
    "meta-llama/llama-3.3-70b-instruct:free",
    "deepseek/deepseek-chat-v3.1:free",
    "qwen/qwen3-235b-a22b:free",
    "google/gemma-3-27b-it:free",
)

/**
 * One OpenAI-compatible provider preset. JARVIS speaks the same protocol to
 * all of them (chat/completions) - the preset just fills the endpoint, model
 * picks and key hint so the user never has to know what a base URL is.
 */
private data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val keyHint: String,
    val keyLabel: String,
    val models: List<String>,
    val note: String,
)

private val PROVIDERS = listOf(
    ProviderPreset(
        "nim", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "nvapi-…", "NVIDIA API key (nvapi-…)",
        NIM_MODELS, "Get a free key at build.nvidia.com (Sign in → any model → Get API key).",
    ),
    ProviderPreset(
        "openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "sk-or-…", "OpenRouter key (sk-or-…)",
        OPENROUTER_MODELS, "Free \":free\" models - create a key at openrouter.ai/keys (no card needed).",
    ),
    ProviderPreset(
        "deepseek", "DeepSeek", "https://api.deepseek.com/v1", "sk-…", "DeepSeek API key (sk-…)",
        listOf("deepseek-chat", "deepseek-reasoner"), "Very cheap, very strong - key at platform.deepseek.com.",
    ),
    ProviderPreset(
        "ollama", "Ollama (LAN)", "http://192.168.1.10:11434/v1", "ollama / blank", "Ollama key (usually blank)",
        listOf("qwen2.5:7b", "llama3.2:3b", "qwen2.5-coder:7b", "mistral:7b"),
        "Runs on your own PC - edit the endpoint below to http://<your-pc-ip>:11434/v1.",
    ),
    ProviderPreset(
        "custom", "Custom", "", "any OpenAI-compatible key", "API key",
        emptyList(), "Any OpenAI-compatible /v1/chat/completions endpoint.",
    ),
)

/**
 * The API-mode sidebar: pick a provider, paste a key, pick a model, flip API
 * mode on - from then on every task runs against that provider automatically
 * (no local model activation, no further questions). Free OpenRouter models
 * are the recommended way to give small phones a big brain.
 */
@Composable
fun ApiDrawerSheet() {
    val container = JarvisApp.instance.container
    val scope = rememberCoroutineScope()
    val apiMode by container.settings.apiMode.collectAsState(initial = false)
    val savedBaseUrl by container.settings.remoteBaseUrl.collectAsState(initial = "")
    var selectedId by remember {
        mutableStateOf(
            when {
                savedBaseUrl.isBlank() -> "nim"
                savedBaseUrl.contains("openrouter.ai") -> "openrouter"
                savedBaseUrl.contains("deepseek.com") -> "deepseek"
                savedBaseUrl.contains("11434") -> "ollama"
                savedBaseUrl == PROVIDERS[0].baseUrl -> "nim"
                else -> "custom"
            },
        )
    }
    val provider = PROVIDERS.firstOrNull { it.id == selectedId } ?: PROVIDERS[0]
    var keyField by remember { mutableStateOf("") }
    var modelField by remember { mutableStateOf("") }
    var urlField by remember { mutableStateOf(provider.baseUrl) }
    var testState by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }

    var initialized by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(selectedId) {
        if (!initialized) {
            // First open: show the SAVED model/url so the user sees current state.
            initialized = true
            val savedModel = runCatching { container.settings.remoteModel.first() }.getOrNull().orEmpty()
            val savedUrl = runCatching { container.settings.remoteBaseUrl.first() }.getOrNull().orEmpty()
            modelField = savedModel.ifBlank { provider.models.firstOrNull().orEmpty() }
            urlField = savedUrl.ifBlank { provider.baseUrl }
        } else {
            modelField = provider.models.firstOrNull().orEmpty()
            urlField = provider.baseUrl
        }
    }

    fun effectiveBaseUrl(): String =
        if (provider.id == "custom") urlField.trim().trimEnd('/')
        else provider.baseUrl

    fun effectiveModel(): String = modelField.trim().ifBlank { provider.models.firstOrNull() ?: "default" }

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
                "Any OpenAI-compatible provider · key stays encrypted on this device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            // ------------------------------------------------- provider chips
            Text("Provider", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PROVIDERS.take(3).forEach { p ->
                    Chip(
                        label = p.label,
                        selected = p.id == selectedId,
                        onClick = { selectedId = p.id; testState = null },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PROVIDERS.drop(3).forEach { p ->
                    Chip(
                        label = p.label,
                        selected = p.id == selectedId,
                        onClick = { selectedId = p.id; testState = null },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (provider.id == "custom") {
                OutlinedTextField(
                    value = urlField,
                    onValueChange = { urlField = it },
                    label = { Text("Base URL (…/v1)") },
                    placeholder = { Text("https://your-endpoint/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = keyField,
                onValueChange = { keyField = it },
                label = { Text(provider.keyLabel) },
                placeholder = { Text("paste your key here") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            if (provider.models.isNotEmpty()) {
                Text("Model", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                provider.models.forEach { m ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        RadioButton(
                            selected = modelField == m,
                            onClick = { modelField = m },
                        )
                        Text(m, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            OutlinedTextField(
                value = modelField,
                onValueChange = { modelField = it },
                label = { Text("Model id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(provider.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
                                // key, endpoint, model - then tasks just work.
                                if (keyField.isNotBlank()) container.vault.remoteApiKey = keyField.trim()
                                val base = effectiveBaseUrl()
                                if (base.isNotBlank()) container.settings.setRemoteBaseUrl(base)
                                val m = effectiveModel()
                                container.settings.setRemoteModel(m)
                                container.settings.setNimModel(m)
                                container.settings.setLocalOnly(false)
                            }
                            container.settings.setApiMode(on)
                            testState = if (on) {
                                "API mode on - tasks now run on ${effectiveModel()}" to true
                            } else null
                        }
                    },
                )
            }

            if (apiMode) {
                Text(
                    "Every task now runs in the cloud (${effectiveModel()}) - no local model needed.",
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
                        val base = effectiveBaseUrl()
                        if (base.isNotBlank()) container.settings.setRemoteBaseUrl(base)
                        val m = effectiveModel()
                        container.settings.setRemoteModel(m)
                        container.settings.setNimModel(m)
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
                "Requests go directly from this phone to the provider - nothing is proxied. " +
                    "Turn API mode off anytime to return fully on-device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .clipThenBorder(selected)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

private fun Modifier.clipThenBorder(selected: Boolean): Modifier =
    this.then(
        Modifier
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Accent else androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.4f),
                shape = RoundedCornerShape(10.dp),
            ),
    )

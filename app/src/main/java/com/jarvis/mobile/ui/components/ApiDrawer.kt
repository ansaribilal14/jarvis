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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.jarvis.mobile.core.model.CloudProviderPreset
import com.jarvis.mobile.core.model.CloudProviders
import com.jarvis.mobile.ui.theme.Accent
import com.jarvis.mobile.ui.theme.Danger
import com.jarvis.mobile.ui.theme.Ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Tiny app-wide bus so any screen can open the API-mode sidebar. */
object ApiDrawerBus {
    val open = MutableStateFlow(false)

    fun requestOpen() {
        open.value = true
    }
}

/**
 * One OpenAI-compatible provider preset. JARVIS speaks the same protocol to
 * all of them (chat/completions) - the preset just fills the endpoint, model
 * picks and key hint so the user never has to know what a base URL is.
 * Model slugs in the presets were verified live before shipping (v2.2.0);
 * see CloudProviders for the verification discipline.
 */
private fun presets(): List<CloudProviderPreset> = CloudProviders.PRESETS

/**
 * The API-mode sidebar: pick a provider, paste a key, pick a model, flip API
 * mode on - from then on every task runs against that provider automatically
 * (no local model activation, no further questions). Free OpenRouter models
 * are the recommended way to give small phones a big brain.
 *
 * v2.2: every provider gets its OWN key slot (encrypted), so switching
 * providers no longer overwrites the previous key. Saving a key for a
 * provider also makes that provider the ACTIVE one for the engine.
 */
@Composable
fun ApiDrawerSheet() {
    val container = JarvisApp.instance.container
    val scope = rememberCoroutineScope()
    val apiMode by container.settings.apiMode.collectAsState(initial = false)
    val savedBaseUrl by container.settings.remoteBaseUrl.collectAsState(initial = "")
    val PROVIDERS = remember { presets() }
    var selectedId by remember {
        mutableStateOf(CloudProviders.guessId(savedBaseUrl) ?: "nim")
    }
    val provider = PROVIDERS.firstOrNull { it.id == selectedId } ?: PROVIDERS[0]
    var keyField by remember { mutableStateOf("") }
    var modelField by remember { mutableStateOf("") }
    var urlField by remember { mutableStateOf(provider.baseUrl) }
    var testState by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var savedIds by remember { mutableStateOf(container.vault.providerKeyIds().toSet()) }
    var fetchedModels by remember { mutableStateOf<List<String>?>(null) }
    var fetchInfo by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var fetchBusy by remember { mutableStateOf(false) }

    /** Saved key for the SELECTED provider, legacy-aware (pre-2.2 single slot). */
    fun effectiveSavedKey(): String {
        val direct = container.vault.providerKey(selectedId)
        if (direct.isNotBlank()) return direct
        val legacy = container.vault.remoteApiKey
        if (legacy.isNotBlank() && CloudProviders.guessId(savedBaseUrl) == selectedId) return legacy
        return ""
    }

    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(selectedId) {
        fetchedModels = null
        fetchInfo = null
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
        if (provider.id == "custom" || provider.baseUrl.isBlank()) urlField.trim().trimEnd('/')
        else provider.baseUrl

    fun effectiveModel(): String = modelField.trim().ifBlank { provider.models.firstOrNull() ?: "default" }

    /** GET {base}/models with the provider key; returns model ids for the picker. */
    suspend fun fetchLiveModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        val base = effectiveBaseUrl()
        if (base.isBlank()) return@withContext Result.failure(IllegalStateException("Set the base URL first"))
        val key = keyField.trim().ifBlank { effectiveSavedKey() }
        try {
            val conn = URL("$base/models").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                return@withContext Result.failure(IllegalStateException("HTTP $code: ${text.take(120)}"))
            }
            val arr = JSONObject(text).optJSONArray("data")
                ?: return@withContext Result.failure(IllegalStateException("Unexpected /models response shape"))
            val ids = (0 until arr.length())
                .mapNotNull { arr.optJSONObject(it)?.optString("id") }
                .filter { it.isNotBlank() }
            val list = if (provider.freeOnly) ids.filter { it.endsWith(":free") } else ids
            Result.success(list.take(40))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

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
                "Any OpenAI-compatible provider · one encrypted key slot per provider",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            // ------------------------------------------------- provider chips
            Text("Provider", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            PROVIDERS.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    row.forEach { p ->
                        val marked = p.id in savedIds
                        Chip(
                            label = if (marked) "${p.label} ✓" else p.label,
                            selected = p.id == selectedId,
                            onClick = { selectedId = p.id; testState = null },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(4.dp))

            val savedKey = effectiveSavedKey()
            val activeId = CloudProviders.guessId(savedBaseUrl)
            if (activeId == selectedId && apiMode) {
                Text(
                    "ACTIVE - tasks currently run on this provider",
                    style = MaterialTheme.typography.labelMedium,
                    color = Accent,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                if (savedKey.isNotBlank()) {
                    "Saved key ••••${savedKey.takeLast(4)} - paste below to replace it."
                } else {
                    "No key saved for ${provider.label} yet."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            if (provider.baseUrl.isBlank() && provider.id == "custom") {
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
                placeholder = { Text(if (savedKey.isBlank()) "paste your key here" else "paste a new key to replace") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            // ------------------------------------------------- model picker
            val shownModels = fetchedModels ?: provider.models
            if (shownModels.isNotEmpty()) {
                Text(
                    if (fetchedModels != null) "Live model list (fetched just now)" else "Model",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                shownModels.forEach { m ->
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
            OutlinedButton(
                enabled = !fetchBusy,
                onClick = {
                    fetchBusy = true
                    scope.launch {
                        val r = fetchLiveModels()
                        r.fold(
                            onSuccess = { list ->
                                fetchedModels = list
                                if (list.isEmpty()) {
                                    fetchInfo = "Endpoint reachable but returned no models" to false
                                } else {
                                    fetchInfo = "${list.size} models fetched - tap one below" to true
                                    if (modelField.isBlank()) modelField = list.first()
                                }
                            },
                            onFailure = { fetchInfo = "Fetch failed: ${it.message?.take(90)}" to false },
                        )
                        fetchBusy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (fetchBusy) "Fetching…" else "Fetch live model list") }
            fetchInfo?.let { (msg, ok) ->
                Text(msg, style = MaterialTheme.typography.bodySmall, color = if (ok) Ok else Danger)
            }

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
                                // active key, endpoint, model - then tasks just work.
                                if (keyField.isNotBlank()) {
                                    container.vault.setProviderKey(selectedId, keyField.trim())
                                    container.vault.remoteApiKey = keyField.trim()
                                }
                                val base = effectiveBaseUrl()
                                if (base.isNotBlank()) container.settings.setRemoteBaseUrl(base)
                                container.settings.setRemoteModel(effectiveModel())
                                container.settings.setNimModel(effectiveModel())
                                container.settings.setLocalOnly(false)
                                savedIds = container.vault.providerKeyIds().toSet()
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
                        if (keyField.isNotBlank()) {
                            // Per-provider slot AND the active slot the engine reads.
                            container.vault.setProviderKey(selectedId, keyField.trim())
                            container.vault.remoteApiKey = keyField.trim()
                        }
                        val base = effectiveBaseUrl()
                        if (base.isNotBlank()) container.settings.setRemoteBaseUrl(base)
                        container.settings.setRemoteModel(effectiveModel())
                        container.settings.setNimModel(effectiveModel())
                        val r = container.remoteProvider.verifyKey()
                        testState = r.fold(
                            onSuccess = { "Key works - model replied: ${it.take(40)}" to true },
                            onFailure = { "Key test failed: ${it.message?.take(90)}" to false },
                        )
                        savedIds = container.vault.providerKeyIds().toSet()
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
                    "Each provider keeps its own key (encrypted); the last one you save is the one that runs. " +
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

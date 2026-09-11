package com.jarvis.mobile.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.model.DeviceProfiler
import com.jarvis.mobile.core.model.ModelCatalog
import com.jarvis.mobile.core.model.ModelManager
import com.jarvis.mobile.ui.components.SectionCard
import com.jarvis.mobile.ui.components.StatusChip
import com.jarvis.mobile.ui.theme.Accent
import kotlinx.coroutines.launch

/** Model manager UI (spec: MODEL MANAGER / MODEL UI). */
@Composable
fun ModelsScreen() {
    val context = LocalContext.current
    val container = JarvisApp.instance.container
    val scope = rememberCoroutineScope()
    val profile by remember { mutableStateOf(container.profiler.profile()) }
    val recommended = remember { ModelCatalog.recommendFor(profile) }
    val downloadStates by container.modelManager.states.collectAsState()
    val activeModelId by container.settings.activeModelId.collectAsState(initial = null)
    var benchmarkResult by remember { mutableStateOf<Double?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { container.modelManager.import(uri) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("This device — ${profile.deviceClass.name}") {
            Text("${profile.model} · Android ${profile.androidVersion} · ${profile.cores} cores", style = MaterialTheme.typography.bodyMedium)
            Text("${profile.totalRamGb} GB RAM (${profile.availRamGb} GB free) · ${profile.storageFreeGb} GB storage free", style = MaterialTheme.typography.bodyMedium)
            Text("Thermal: ${profile.thermalStatus} · Vulkan: ${if (profile.vulkan) "yes" else "no"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("Recommended") {
            ModelCard(
                model = recommended,
                state = downloadStates[recommended.id] ?: ModelManager.DownloadState.Idle,
                isActive = container.modelManager.isModelActive(recommended),
                isDownloaded = container.modelManager.isDownloaded(recommended),
                recommendedTag = "Recommended for your device",
                onDownload = { container.modelManager.download(recommended) },
                onPause = { container.modelManager.pause(recommended) },
                onResume = { container.modelManager.resume(recommended) },
                onCancel = { container.modelManager.cancel(recommended) },
                onActivate = { scope.launch { container.modelManager.selectAndLoad(recommended) } },
                onDelete = { container.modelManager.delete(recommended) },
            )
        }

        SectionCard("All models") {
            ModelCatalog.MODELS.filter { it.id != recommended.id }.forEach { m ->
                ModelCard(
                    model = m,
                    state = downloadStates[m.id] ?: ModelManager.DownloadState.Idle,
                    isActive = container.modelManager.isModelActive(m),
                    isDownloaded = container.modelManager.isDownloaded(m),
                    recommendedTag = null,
                    onDownload = { container.modelManager.download(m) },
                    onPause = { container.modelManager.pause(m) },
                    onResume = { container.modelManager.resume(m) },
                    onCancel = { container.modelManager.cancel(m) },
                    onActivate = { scope.launch { container.modelManager.selectAndLoad(m) } },
                    onDelete = { container.modelManager.delete(m) },
                )
                Spacer(Modifier.height(4.dp))
            }
            TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import a .gguf file…") }
        }

        SectionCard("Benchmark") {
            if (container.modelManager.llama.isReady()) {
                Text("Active: ${container.modelManager.llama.activeModel?.id}", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {
                    scope.launch {
                        benchmarkResult = container.modelManager.benchmarkActive().getOrNull()
                    }
                }) { Text("Measure generation speed") }
                benchmarkResult?.let {
                    Text("%.1f tokens/second (measured on this device)".format(it), style = MaterialTheme.typography.bodyMedium, color = Accent)
                }
            } else {
                Text("Load a model to benchmark it. Results are real measurements, not estimates.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun ModelCard(
    model: ModelCatalog.CatalogModel,
    state: ModelManager.DownloadState,
    isActive: Boolean,
    isDownloaded: Boolean,
    recommendedTag: String?,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(model.id, style = MaterialTheme.typography.titleMedium)
            if (isActive) StatusChip("ACTIVE", com.jarvis.mobile.ui.components.ChipState.ACCENT)
            if (model.minDeviceClass == DeviceProfiler.DeviceClass.BASIC) StatusChip("light", com.jarvis.mobile.ui.components.ChipState.NEUTRAL)
        }
        recommendedTag?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Accent) }
        Text("${model.params} · ${model.quant} · ${model.sizeMb} MB · RAM ≈ ${model.ramNeededGb} GB · ctx ${model.contextTrain / 1024}k", style = MaterialTheme.typography.bodyMedium)
        Text(model.strengths, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        when (state) {
            is ModelManager.DownloadState.Downloading -> {
                LinearProgressIndicator(progress = { (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Accent)
                Text("${state.downloaded / 1048576}/${state.total / 1048576} MB · ${state.speedKbs} KB/s", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPause) { Text("Pause") }
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            }
            is ModelManager.DownloadState.Verifying -> {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                Text("Verifying SHA-256 checksum…", style = MaterialTheme.typography.labelMedium)
            }
            is ModelManager.DownloadState.Failed -> {
                Text(state.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDownload) { Text("Retry") }
                    OutlinedButton(onClick = onDelete) { Text("Remove partial") }
                }
            }
            ModelManager.DownloadState.Done, ModelManager.DownloadState.Idle -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isDownloaded) {
                        StatusChip("on device", com.jarvis.mobile.ui.components.ChipState.OK)
                        if (!isActive) Button(onClick = onActivate) { Text("Activate") }
                        OutlinedButton(onClick = onDelete) { Text("Delete") }
                    } else {
                        Button(onClick = onDownload) { Text("Download") }
                    }
                }
            }
        }
    }
}

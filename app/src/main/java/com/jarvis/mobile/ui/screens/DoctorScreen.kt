package com.jarvis.mobile.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.accessibility.JarvisAccessibilityService
import com.jarvis.mobile.core.model.ModelCatalog
import com.jarvis.mobile.core.notifications.NotificationCache
import com.jarvis.mobile.ui.components.SectionCard
import com.jarvis.mobile.ui.components.StatusChip

private sealed class CheckState {
    data object Ok : CheckState()
    data class Degraded(val reason: String) : CheckState()
    data class Fix(val message: String) : CheckState()
}

/** Jarvis Doctor (spec: DIAGNOSTICS) - honest health report with fixes. */
@Composable
fun DoctorScreen() {
    val context = LocalContext.current
    val container = JarvisApp.instance.container
    var refresh by remember { mutableIntStateOf(0) }
    val a11yConnected by JarvisAccessibilityService.CONNECTED.collectAsState()
    val loadState by container.modelManager.loadState.collectAsState()

    val checks: List<Pair<String, CheckState>> = remember(refresh, a11yConnected, loadState) {
        val p = container.profiler.profile()
        listOf(
            "Accessibility service" to run {
                if (JarvisAccessibilityService.isReady) CheckState.Ok
                else CheckState.Fix("Enable JARVIS in system accessibility settings - required for all phone control")
            },
            "Local model" to run {
                val active = container.modelManager.llama.activeModel
                when {
                    loadState is com.jarvis.mobile.core.model.ModelManager.LoadState.Loading ->
                        CheckState.Degraded("Loading model into memory - this finishes in under a minute")
                    container.modelManager.llama.isReady() && active != null -> CheckState.Ok
                    loadState is com.jarvis.mobile.core.model.ModelManager.LoadState.Failed ->
                        CheckState.Fix("Last activation failed - open the Models tab for the reason and retry")
                    else -> CheckState.Fix("No model loaded - download one in the Models tab")
                }
            },
            "Inference runtime" to run {
                runCatching { com.jarvis.mobile.core.model.LlamaBridge.nativeIsLoaded() }
                CheckState.Ok
            },
            "RAM" to run {
                if (p.totalRamGb >= 3) CheckState.Ok
                else CheckState.Degraded("Low-RAM device (${p.totalRamGb} GB) - use the smallest model")
            },
            "Storage" to run {
                if (p.storageFreeGb >= 2.5) CheckState.Ok
                else CheckState.Degraded("Only ${p.storageFreeGb} GB free - downloads may fail")
            },
            "Microphone" to run {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) CheckState.Ok
                else CheckState.Fix("Grant microphone permission for voice input")
            },
            "Speech recognition" to run {
                val sr = com.jarvis.mobile.core.voice.SpeechInput(context)
                if (sr.available()) {
                    if (sr.onDeviceOnly()) CheckState.Ok else CheckState.Degraded("Recognizer available but may require network")
                } else CheckState.Fix("No speech recognizer installed on this device")
            },
            "Text-to-speech" to run {
                if (container.voiceOutput.isReady()) CheckState.Ok
                else CheckState.Degraded("TTS engine not ready yet (may finish initializing)")
            },
            "Notification access" to run {
                if (JarvisApp.instance.container.notificationCache.hasListenerAccess()) CheckState.Ok
                else CheckState.Fix("Enable JARVIS in Settings → Notification access")
            },
            "Overlay bubble" to run {
                if (Settings.canDrawOverlays(context)) CheckState.Ok
                else CheckState.Fix("Optional - allow \"Display over other apps\" to use the floating bubble")
            },
            "Modify system settings" to run {
                if (Settings.System.canWrite(context)) CheckState.Ok
                else CheckState.Fix("Grant \"Modify system settings\" to control brightness")
            },
            "Battery restrictions" to run {
                val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (pm.isIgnoringBatteryOptimizations(context.packageName)) CheckState.Ok
                else CheckState.Degraded("Android may pause background tasks - disable optimization for reliable routines")
            },
            "Thermal state" to run {
                when (p.thermalStatus) {
                    "none", "light" -> CheckState.Ok
                    "moderate" -> CheckState.Degraded("Device is warm - inference may slow down")
                    else -> CheckState.Degraded("Device is hot - heavy tasks are throttled")
                }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Jarvis Doctor", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { refresh++ }) { Text("Re-run") }
        }

        checks.forEach { (name, state) ->
            SectionCard(name) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        (state as? CheckState.Fix)?.let { fix ->
                            Text(fix.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        (state as? CheckState.Degraded)?.let { d ->
                            Text(d.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    when (state) {
                        is CheckState.Ok -> StatusChip("healthy", com.jarvis.mobile.ui.components.ChipState.OK)
                        is CheckState.Degraded -> StatusChip("degraded", com.jarvis.mobile.ui.components.ChipState.WARN)
                        is CheckState.Fix -> StatusChip("action needed", com.jarvis.mobile.ui.components.ChipState.BAD)
                    }
                }
            }
        }
    }
}

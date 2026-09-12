package com.jarvis.mobile.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.accessibility.JarvisAccessibilityService
import com.jarvis.mobile.core.model.ModelCatalog
import com.jarvis.mobile.core.model.ModelManager
import com.jarvis.mobile.ui.components.SectionCard
import com.jarvis.mobile.ui.components.StatusChip
import com.jarvis.mobile.ui.theme.Accent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** First-run experience (spec: ONBOARDING) - explain, profile, model, permissions, test. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val container = JarvisApp.instance.container
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }

    val profile by remember { mutableStateOf(container.profiler.profile()) }
    val recommended = remember { ModelCatalog.recommendFor(profile) }
    val downloadStates by container.modelManager.states.collectAsState()
    val loadState by container.modelManager.loadState.collectAsState()
    val a11y by JarvisAccessibilityService.CONNECTED.collectAsState()

    val loadingModelId = (loadState as? ModelManager.LoadState.Loading)?.modelId
    val activeModelId = (loadState as? ModelManager.LoadState.Loaded)?.modelId
    val loadError = loadState as? ModelManager.LoadState.Failed

    // Auto-advance once the recommended model is live - setup feels done, not stuck.
    LaunchedEffect(activeModelId) {
        if (activeModelId != null && step == 2) step = 3
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
    ) {
        Text("Step ${step + 1} of 5", style = MaterialTheme.typography.labelMedium, color = Accent)
        Spacer(Modifier.height(10.dp))

        when (step) {
            0 -> {
                Text("Meet JARVIS", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    "A local AI agent that lives on your phone and operates it for you: it reads the current screen, plans the actions needed, performs them, verifies the result and reports honestly.\n\nEverything runs on-device by default. No cloud AI. No accounts. No telemetry.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            1 -> {
                Text("Your device", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(12.dp))
                SectionCard(profile.model) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Android ${profile.androidVersion}", style = MaterialTheme.typography.bodyMedium)
                        StatusChip(profile.deviceClass.name, com.jarvis.mobile.ui.components.ChipState.ACCENT)
                    }
                    Text("${profile.cores} cores · ${profile.totalRamGb} GB RAM · ${profile.abi}", style = MaterialTheme.typography.bodyMedium)
                    Text("Free storage: ${profile.storageFreeGb} GB", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Recommended for this device: ${recommended.id} (${recommended.params}, ${recommended.sizeMb} MB)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            2 -> {
                Text("Download your local brain", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(12.dp))
                val st = downloadStates[recommended.id] ?: ModelManager.DownloadState.Idle
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${recommended.id} · ${recommended.params} · ${recommended.quant}", style = MaterialTheme.typography.titleMedium)
                        Text(recommended.strengths, style = MaterialTheme.typography.bodyMedium)
                        Text("${recommended.sizeMb} MB · needs ~${recommended.ramNeededGb} GB RAM", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        when (st) {
                            is ModelManager.DownloadState.Downloading -> {
                                LinearProgressIndicator(
                                    progress = { (st.downloaded.toFloat() / st.total).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Accent,
                                )
                                Text("${st.downloaded / 1048576}/${st.total / 1048576} MB · ${st.speedKbs} KB/s", style = MaterialTheme.typography.labelMedium)
                            }
                            is ModelManager.DownloadState.Verifying -> {
                                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                                Text("Verifying SHA-256…", style = MaterialTheme.typography.labelMedium)
                            }
                            is ModelManager.DownloadState.Done -> {
                                when {
                                    loadingModelId == recommended.id -> {
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp)
                                            Text("Activating… first load can take up to a minute.", style = MaterialTheme.typography.bodyMedium, color = Accent)
                                        }
                                    }
                                    activeModelId == recommended.id -> StatusChip("Active - you are set", com.jarvis.mobile.ui.components.ChipState.OK)
                                    else -> {
                                        StatusChip("Downloaded & verified", com.jarvis.mobile.ui.components.ChipState.OK)
                                        TextButton(onClick = {
                                            scope.launch { container.modelManager.selectAndLoad(recommended) }
                                        }) { Text("Activate") }
                                    }
                                }
                            }
                            is ModelManager.DownloadState.Failed -> Text(st.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            ModelManager.DownloadState.Idle -> {}
                        }
                        loadError?.takeIf { it.modelId == recommended.id }?.let { err ->
                            Text(err.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (st == ModelManager.DownloadState.Idle) {
                                Button(onClick = { container.modelManager.download(recommended) }, enabled = loadingModelId == null) { Text("Download model") }
                            }
                            OutlinedButton(onClick = { step = 3 }) { Text("Skip for now") }
                        }
                    }
                }
            }
            3 -> {
                Text("Permissions", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(12.dp))
                SectionCard("Accessibility (required)") {
                    Text("Lets JARVIS read the screen as structured text and perform taps/typing for you.", style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusChip(if (a11y) "Enabled" else "Disabled", if (a11y) com.jarvis.mobile.ui.components.ChipState.OK else com.jarvis.mobile.ui.components.ChipState.BAD)
                        if (!a11y) {
                            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open settings") }
                        }
                    }
                    if (!a11y) {
                        Text(
                            "On Android 13+ sideloaded apps can show the toggle as blocked: open App Info → ⋮ → Allow restricted settings, then enable JARVIS again.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                SectionCard("Microphone (voice)") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = { micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }) { Text("Allow microphone") }
                        Text("Optional - typing always works.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                SectionCard("Notifications (Android 13+)") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = { notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
                        Text("Shows agent progress + stop control.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            else -> {
                Text("Test screen reading", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    "JARVIS reads screens through Android Accessibility. Press the button - I will read THIS screen and tell you what I see.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(14.dp))
                var result by remember { mutableStateOf("") }
                Button(onClick = {
                    scope.launch {
                        val obs = withContext(Dispatchers.IO) { JarvisAccessibilityService.INSTANCE?.observe() }
                        result = if (obs == null) {
                            "Could not read the screen - make sure the accessibility service is enabled."
                        } else {
                            "I see \"${obs.packageName?.substringAfterLast('.') ?: "unknown app"}\" with ${obs.elements.size} visible elements. Screen understanding works."
                        }
                    }
                }) { Text("Read this screen") }
                if (result.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(result, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(14.dp))
                Button(onClick = {
                    scope.launch {
                        JarvisApp.instance.container.settings.setOnboarded(true)
                        onDone()
                    }
                }) { Text("Finish setup") }
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (step > 0) OutlinedButton(onClick = { step-- }) { Text("Back") }
            if (step < 4) Spacer(Modifier.weight(1f))
            if (step < 4) Button(onClick = { step++ }, enabled = step != 3 || a11y || true) { Text("Next") }
        }
        Spacer(Modifier.height(30.dp))
    }
}

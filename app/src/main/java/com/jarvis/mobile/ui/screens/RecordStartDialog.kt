package com.jarvis.mobile.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.skills.RecordLauncher
import com.jarvis.mobile.core.skills.RecordStartTarget

private data class AppEntry(val label: String, val pkg: String)

/** Cross-screen hand-off: HomeScreen's chip opens the Skills screen WITH the start dialog showing. */
object RecordStartBus {
    val openRecordStart = mutableStateOf(false)
}

/**
 * "Where should the recording start?" - asked every time the user taps
 * Record. Home screen (auto-navigates there) or any launchable app from the
 * dropdown (auto-launches it). On Start the recorder arms FIRST, then we
 * navigate, then the instruction card explains that everything is being
 * recorded while the red ink visualization follows every tap.
 */
@Composable
fun RecordStartDialog(
    onDismiss: () -> Unit,
    onStarted: () -> Unit,
) {
    val context = JarvisApp.instance
    var mode by remember { mutableStateOf(0) } // 0 = home, 1 = app
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loadingApps by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<AppEntry?>(null) }
    var dropdownOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loadingApps = true
        apps = queryLaunchableApps()
        loadingApps = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Where should the recording start?") },
        text = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { mode = 0 },
                ) {
                    RadioButton(selected = mode == 0, onClick = { mode = 0 })
                    Column(Modifier.padding(start = 4.dp)) {
                        Text("Home screen", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Goes to your home screen first - opening an app becomes part of the skill",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { mode = 1 },
                ) {
                    RadioButton(selected = mode == 1, onClick = { mode = 1 })
                    Column(Modifier.padding(start = 4.dp)) {
                        Text("An app", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Jump straight into the app and record from there",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (mode == 1) {
                    if (loadingApps) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                "  loading apps…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        ExposedDropdownMenuBox(
                            expanded = dropdownOpen,
                            onExpandedChange = { dropdownOpen = it },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            OutlinedTextField(
                                value = selectedApp?.label ?: "",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Choose an app") },
                                placeholder = { Text("Pick from your apps") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(dropdownOpen) },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth(),
                            )
                            ExposedDropdownMenu(
                                expanded = dropdownOpen,
                                onDismissRequest = { dropdownOpen = false },
                            ) {
                                apps.forEach { app ->
                                    DropdownMenuItem(
                                        text = { Text(app.label, maxLines = 1) },
                                        onClick = {
                                            selectedApp = app
                                            dropdownOpen = false
                                        },
                                    )
                                }
                                if (apps.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No launchable apps found") },
                                        onClick = {},
                                        enabled = false,
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    "After Start: recording is armed, the screen navigates, and a card reminds you that everything is being recorded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val target = when {
                        mode == 0 -> RecordStartTarget.Home
                        selectedApp != null -> RecordStartTarget.App(selectedApp!!.pkg, selectedApp!!.label)
                        else -> null
                    }
                    if (target != null) {
                        RecordLauncher.begin(context, target)
                        onStarted()
                    }
                },
                enabled = mode == 0 || selectedApp != null,
            ) { Text("Start") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun queryLaunchableApps(): List<AppEntry> {
    val context = JarvisApp.instance
    return runCatching {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        resolved.asSequence()
            .mapNotNull { it.loadLabel(pm)?.toString() to it.activityInfo?.packageName }
            .filter { (_, pkg) -> pkg != null && pkg != context.packageName }
            .map { (label, pkg) -> AppEntry(label, pkg!!) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
            .toList()
    }.getOrDefault(emptyList())
}

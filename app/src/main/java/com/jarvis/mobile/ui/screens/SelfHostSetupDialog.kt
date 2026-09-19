package com.jarvis.mobile.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.jarvis.mobile.core.adb.SelfHostShell
import kotlinx.coroutines.launch

/**
 * Built-in privileged setup - pairs JARVIS with the phone's OWN Wireless
 * debugging (Android 11+) and starts JARVIS's own shell server, so the user
 * never installs a second app. Three screens in one dialog:
 *
 *  1. enable Wireless debugging (deep link + instructions);
 *  2. type the 6-digit pairing code from the "Pair device with pairing code"
 *     dialog (ports are found automatically via mDNS, editable if needed);
 *  3. connect - JARVIS launches its own shell server; done.
 */
@Composable
fun SelfHostSetupDialog(onDismiss: () -> Unit) {
    val context = JarvisApp.instance
    val scope = rememberCoroutineScope()
    val state by SelfHostShell.state.collectAsState()
    var pairingCode by remember { mutableStateOf("") }
    var pairPort by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val ready = state.status == SelfHostShell.Status.READY

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (ready) "Precision capture ready" else "Built-in precision setup") },
        text = {
            Column {
                when {
                    ready -> {
                        Text(
                            "JARVIS is running its own privileged shell (uid 2000, started through this app - no other app involved). " +
                                "Recordings now capture every tap with exact coordinates, in every app.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Note: re-pairing is only needed after Wireless debugging is turned off or the phone reboots.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    busy -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 12.dp))
                            Text(
                                when (state.status) {
                                    SelfHostShell.Status.CONNECTING -> "Starting JARVIS's shell server…"
                                    else -> "Pairing with your device…"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        state.reason.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    else -> {
                        Text(
                            "One-time setup, about a minute: JARVIS pairs with your own phone's Wireless debugging and starts its own privileged shell - no second app needed.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "STEP 1 - Open Wireless debugging and turn it ON (it is inside Developer options).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        OutlinedButton(
                            onClick = { openWirelessDebugging(context) },
                            modifier = Modifier.padding(top = 6.dp),
                        ) { Text("Open Wireless debugging") }
                        Text(
                            "STEP 2 - Tap \"Pair device with pairing code\" in that screen, keep the dialog open, then type its 6-digit code here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        OutlinedTextField(
                            value = pairingCode,
                            onValueChange = { pairingCode = it.filter { c -> c.isDigit() }.take(6) },
                            label = { Text("Pairing code (6 digits)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        )
                        Text(
                            "Ports are found automatically. Only fill these if auto-detect fails:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Row {
                            OutlinedTextField(
                                value = pairPort,
                                onValueChange = { pairPort = it.filter { c -> c.isDigit() }.take(5) },
                                label = { Text("Pair port (from the pairing dialog)") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 6.dp),
                            )
                            OutlinedTextField(
                                value = connectPort,
                                onValueChange = { connectPort = it.filter { c -> c.isDigit() }.take(5) },
                                label = { Text("Wireless debugging port") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 6.dp),
                            )
                        }
                        error?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        state.reason.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!ready && !busy) {
                TextButton(
                    onClick = {
                        error = null
                        busy = true
                        scope.launch {
                            val pairedAlready = SelfHostShell.paired(context)
                            var pairingOk = pairedAlready
                            if (!pairedAlready) {
                                val port = pairPort.toIntOrNull()
                                val result = SelfHostShell.pair(context, port, pairingCode)
                                pairingOk = result.isSuccess
                                if (!pairingOk) error = result.exceptionOrNull()?.message ?: "pairing failed"
                            }
                            if (pairingOk) {
                                val connect = SelfHostShell.connect(context, connectPort.toIntOrNull())
                                if (connect.isFailure) {
                                    error = connect.exceptionOrNull()?.message ?: "connect failed"
                                }
                            }
                            busy = false
                        }
                    },
                    enabled = SelfHostShell.paired(context) || pairingCode.length == 6,
                ) { Text(if (SelfHostShell.paired(context)) "Connect" else "Pair & connect") }
            } else if (ready) {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            if (!ready) TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun openWirelessDebugging(context: android.content.Context) {
    // Hidden but widely-present deep link, then graceful fallbacks.
    val deepLink = Intent().setClassName(
        "com.android.settings",
        "com.android.settings.Settings\$WirelessDebuggingActivity",
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(deepLink) }.getOrElse {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.getOrElse {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

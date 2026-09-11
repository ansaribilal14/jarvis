package com.jarvis.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** About / Privacy / Licenses screen. */
@Composable
fun AboutScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("JARVIS", style = MaterialTheme.typography.displaySmall)
        Text("A local-first AI agent that operates your Android phone. Version 1.0.0", style = MaterialTheme.typography.bodyLarge)

        Text("Privacy", style = MaterialTheme.typography.titleLarge)
        Text(
            "JARVIS is designed local-first. Screen understanding, planning and speech run on this device. " +
                "Nothing is uploaded by default: no telemetry, no analytics, no silent screenshots, no microphone streaming. " +
                "The only network traffic JARVIS makes is: (1) downloading the open model file you choose, and (2) optional remote " +
                "inference if you explicitly turn LOCAL ONLY off and configure a provider.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text("Security", style = MaterialTheme.typography.titleLarge)
        Text(
            "JARVIS is a privileged app and treats that as a responsibility: risk-classified actions with confirmation for anything " +
                "medium or high risk, password and OTP field protection, screen-content injection defenses, an audit log of sensitive " +
                "actions, and an always-available STOP control. Secrets (if any) are stored in the Android Keystore-backed vault.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text("Open source", style = MaterialTheme.typography.titleLarge)
        Text(
            "Built on: llama.cpp (MIT), Jetpack Compose / AndroidX (Apache-2.0), Room (Apache-2.0), WorkManager (Apache-2.0), " +
                "ML Kit text recognition (Apache-2.0 with Google Play Services terms), kotlinx.coroutines & serialization (Apache-2.0). " +
                "Model files are downloaded separately and are governed by their respective licenses (Qwen, SmolLM2, Llama 3.2). " +
                "See THIRD_PARTY_NOTICES.md in the repository for the full text.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

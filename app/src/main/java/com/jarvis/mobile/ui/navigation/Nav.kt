package com.jarvis.mobile.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class Dest(val route: String, val label: String, val icon: ImageVector?) {
    Home("home", "Jarvis", Icons.Filled.ChatBubble),
    Models("models", "Models", Icons.Filled.ModelTraining),
    History("history", "History", Icons.Filled.History),
    Memory("memory", "Memory", Icons.Filled.Memory),
    Routines("routines", "Routines", Icons.Filled.Schedule),
    Doctor("doctor", "Doctor", Icons.Filled.Assessment),
    Settings("settings", "Settings", Icons.Filled.Settings),
    Onboarding("onboarding", "Setup", null),
    ;

    companion object {
        fun byRoute(r: String): Dest? = entries.firstOrNull { it.route == r }
    }
}

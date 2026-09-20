package com.jarvis.mobile

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jarvis.mobile.service.OverlayService
import com.jarvis.mobile.ui.navigation.Dest
import com.jarvis.mobile.ui.screens.AboutScreen
import com.jarvis.mobile.ui.screens.DoctorScreen
import com.jarvis.mobile.ui.screens.GrillMeScreen
import com.jarvis.mobile.ui.screens.HistoryScreen
import com.jarvis.mobile.ui.screens.HomeScreen
import com.jarvis.mobile.ui.screens.MemoryScreen
import com.jarvis.mobile.ui.screens.ModelsScreen
import com.jarvis.mobile.ui.screens.OnboardingScreen
import com.jarvis.mobile.ui.screens.RoutinesScreen
import com.jarvis.mobile.ui.screens.SettingsScreen
import com.jarvis.mobile.ui.screens.SkillBuilderScreen
import com.jarvis.mobile.ui.screens.SkillsScreen
import com.jarvis.mobile.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }

        setContent {
            val theme by JarvisApp.instance.container.settings.theme.collectAsState(initial = "SYSTEM")
            val onboarded by JarvisApp.instance.container.settings.onboarded.collectAsState(initial = true)
            JarvisTheme(themeMode = theme) { App(onboarded) }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_VOICE_MODE, false) == true) {
            // Voice session begins from the tile; the home screen mic is the entry point.
        }
    }

    companion object {
        const val EXTRA_VOICE_MODE = "voice_mode"
    }
}

@Composable
private fun App(onboarded: Boolean) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: Dest.Home.route
    val container = JarvisApp.instance.container
    val overlayEnabled by container.settings.overlayEnabled.collectAsState(initial = false)

    val tabs = listOf(Dest.Home, Dest.Models, Dest.History, Dest.Memory, Dest.Routines, Dest.Doctor, Dest.Settings)

    // API-mode sidebar (NVIDIA NIM): openable from Home / Models via ApiDrawerBus.
    val drawerState = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val drawerOpen by com.jarvis.mobile.ui.components.ApiDrawerBus.open.collectAsState()
    androidx.compose.runtime.LaunchedEffect(drawerOpen) {
        if (drawerOpen) {
            drawerState.open()
            com.jarvis.mobile.ui.components.ApiDrawerBus.open.value = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(overlayEnabled) {
        val ctx = JarvisApp.instance
        if (overlayEnabled) OverlayService.start(ctx) else OverlayService.stop(ctx)
    }

    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { com.jarvis.mobile.ui.components.ApiDrawerSheet() },
    ) {
        Scaffold(
            bottomBar = {
                if (current != Dest.Onboarding.route && current != "about") {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        tabs.forEach { d ->
                            NavigationBarItem(
                                selected = current == d.route,
                                onClick = { nav.navigate(d.route) { launchSingleTop = true; popUpTo(Dest.Home.route) { saveState = true } } },
                                icon = { d.icon?.let { Icon(it, contentDescription = d.label) } },
                                label = { Text(d.label, style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = if (onboarded) Dest.Home.route else Dest.Onboarding.route,
                modifier = Modifier
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
            ) {
                composable(Dest.Home.route) { HomeScreen(openTab = { r -> nav.navigate(r) { launchSingleTop = true } }) }
                composable("skills") { SkillsScreen(openTab = { r -> nav.navigate(r) { launchSingleTop = true } }) }
                composable("grill_me") { GrillMeScreen(onClose = { nav.popBackStack() }, onEditDraft = { nav.navigate("skill_builder") { launchSingleTop = true } }) }
                composable("skill_builder") { SkillBuilderScreen(onClose = { nav.popBackStack() }) }
                composable(Dest.Onboarding.route) { OnboardingScreen(onDone = { nav.navigate(Dest.Home.route) { popUpTo(0) } }) }
                composable(Dest.Models.route) { ModelsScreen() }
                composable(Dest.History.route) { HistoryScreen() }
                composable(Dest.Memory.route) { MemoryScreen() }
                composable(Dest.Routines.route) { RoutinesScreen() }
                composable(Dest.Doctor.route) { DoctorScreen() }
                composable(Dest.Settings.route) { SettingsScreen() }
                composable("about") { AboutScreen() }
            }
        }
    }
}

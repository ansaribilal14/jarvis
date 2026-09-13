package com.jarvis.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.jarvis.mobile.core.agent.AgentEngine
import com.jarvis.mobile.core.memory.MemoryStore
import com.jarvis.mobile.core.model.DeviceProfiler
import com.jarvis.mobile.core.model.ModelManager
import com.jarvis.mobile.core.model.RemoteOpenAiProvider
import com.jarvis.mobile.core.notifications.NotificationCache
import com.jarvis.mobile.core.remote.TelegramRemote
import com.jarvis.mobile.core.routines.RoutineManager
import com.jarvis.mobile.core.tools.ToolRegistry
import com.jarvis.mobile.core.tools.impl.buildToolSet
import com.jarvis.mobile.core.voice.VoiceOutput
import com.jarvis.mobile.data.db.AppDatabase
import com.jarvis.mobile.data.settings.SecureVault
import com.jarvis.mobile.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Application entry + tiny manual DI container (justified: single-process app, low count of singletons). */
class JarvisApp : Application() {

    lateinit var container: Container
        private set

    class Container(app: JarvisApp) {
        val context: Context = app
        val db: AppDatabase by lazy { AppDatabase.build(app) }
        val settings: SettingsRepository by lazy { SettingsRepository(app) }
        val vault: SecureVault by lazy { SecureVault(app) }
        val profiler: DeviceProfiler by lazy { DeviceProfiler(app) }
        val modelManager: ModelManager by lazy {
            ModelManager(app, settings, profiler)
        }
        val remoteProvider: RemoteOpenAiProvider by lazy { RemoteOpenAiProvider(settings, vault) }
        val memory: MemoryStore by lazy { MemoryStore(db, settings) }
        val voiceOutput: VoiceOutput by lazy { VoiceOutput(app, settings) }
        val notificationCache: NotificationCache by lazy { NotificationCache() }
        val routineManager: RoutineManager by lazy { RoutineManager(app, settings, memory) }
    }

    companion object {
        const val CH_AGENT = "agent"
        const val CH_ALERTS = "alerts"

        lateinit var instance: JarvisApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = Container(this)
        createChannels()
        ToolRegistry.registerAll(buildToolSet(container))
        AgentEngine.init(container)
        // Restore the last activated model after an app restart: without this the
        // agent greeted users with "no local model downloaded yet" even though the
        // model WAS downloaded + activated - it just was not back in RAM yet.
        container.modelManager.autoReloadActive()
        // Telegram remote control (adapted from the user's MobileAgent demo):
        // starts only when the user enabled it in Settings.
        CoroutineScope(Dispatchers.Default).launch {
            if (runCatching { container.settings.telegramRemoteEnabled.first() }.getOrDefault(false)) {
                TelegramRemote.start(this@JarvisApp)
            }
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_AGENT, getString(R.string.notification_channel_agent), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notification_channel_agent_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, getString(R.string.notification_channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.notification_channel_alerts_desc)
            },
        )
    }
}

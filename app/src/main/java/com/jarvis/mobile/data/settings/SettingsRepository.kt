package com.jarvis.mobile.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "jarvis_settings")

/**
 * All user-configurable behavior, persisted locally via DataStore.
 * The remote provider API key is NOT stored here - see SecureVault.
 */
class SettingsRepository(private val context: Context) {

    object Keys {
        val LOCAL_ONLY = booleanPreferencesKey("local_only")
        val VOICE_INPUT = booleanPreferencesKey("voice_input")
        val VOICE_OUTPUT = booleanPreferencesKey("voice_output")
        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val AUTO_APPROVE_MEDIUM = booleanPreferencesKey("auto_approve_medium")
        val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        val PAUSE_ON_USER_TOUCH = booleanPreferencesKey("pause_on_user_touch")
        val THEME = stringPreferencesKey("theme_mode") // SYSTEM, DARK, LIGHT
        val ACTIVE_MODEL_ID = stringPreferencesKey("active_model_id")
        val INFERENCE_THREADS = intPreferencesKey("inference_threads") // 0 = auto
        val CONTEXT_SIZE = intPreferencesKey("context_size")
        val MAX_ACTIONS = intPreferencesKey("max_actions")
        val UNLOAD_IDLE_MIN = intPreferencesKey("unload_idle_min")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val REMOTE_BASE_URL = stringPreferencesKey("remote_base_url")
        val REMOTE_MODEL = stringPreferencesKey("remote_model")
        val SCREEN_COMPACT = booleanPreferencesKey("screen_compact")
    }

    private val d get() = context.dataStore.data

    val localOnly: Flow<Boolean> = d.map { it[Keys.LOCAL_ONLY] ?: true }
    val voiceInput: Flow<Boolean> = d.map { it[Keys.VOICE_INPUT] ?: true }
    val voiceOutput: Flow<Boolean> = d.map { it[Keys.VOICE_OUTPUT] ?: true }
    val overlayEnabled: Flow<Boolean> = d.map { it[Keys.OVERLAY_ENABLED] ?: false }
    val autoApproveMedium: Flow<Boolean> = d.map { it[Keys.AUTO_APPROVE_MEDIUM] ?: false }
    val memoryEnabled: Flow<Boolean> = d.map { it[Keys.MEMORY_ENABLED] ?: true }
    val pauseOnUserTouch: Flow<Boolean> = d.map { it[Keys.PAUSE_ON_USER_TOUCH] ?: true }
    val theme: Flow<String> = d.map { it[Keys.THEME] ?: "SYSTEM" }
    val activeModelId: Flow<String?> = d.map { it[Keys.ACTIVE_MODEL_ID] }
    val inferenceThreads: Flow<Int> = d.map { it[Keys.INFERENCE_THREADS] ?: 0 }
    val contextSize: Flow<Int> = d.map { it[Keys.CONTEXT_SIZE] ?: 2048 }
    val maxActions: Flow<Int> = d.map { it[Keys.MAX_ACTIONS] ?: 12 }
    val unloadIdleMin: Flow<Int> = d.map { it[Keys.UNLOAD_IDLE_MIN] ?: 5 }
    val onboarded: Flow<Boolean> = d.map { it[Keys.ONBOARDED] ?: false }
    val remoteBaseUrl: Flow<String> = d.map { it[Keys.REMOTE_BASE_URL] ?: "" }
    val remoteModel: Flow<String> = d.map { it[Keys.REMOTE_MODEL] ?: "" }
    val screenCompact: Flow<Boolean> = d.map { it[Keys.SCREEN_COMPACT] ?: true }

    suspend fun snapshot(): Snap = Snap(
        localOnly = localOnly.first(),
        voiceInput = voiceInput.first(),
        voiceOutput = voiceOutput.first(),
        autoApproveMedium = autoApproveMedium.first(),
        memoryEnabled = memoryEnabled.first(),
        pauseOnUserTouch = pauseOnUserTouch.first(),
        activeModelId = activeModelId.first(),
        inferenceThreads = inferenceThreads.first(),
        contextSize = contextSize.first(),
        maxActions = maxActions.first(),
        unloadIdleMin = unloadIdleMin.first(),
        remoteBaseUrl = remoteBaseUrl.first(),
        remoteModel = remoteModel.first(),
        screenCompact = screenCompact.first(),
    )

    data class Snap(
        val localOnly: Boolean,
        val voiceInput: Boolean,
        val voiceOutput: Boolean,
        val autoApproveMedium: Boolean,
        val memoryEnabled: Boolean,
        val pauseOnUserTouch: Boolean,
        val activeModelId: String?,
        val inferenceThreads: Int,
        val contextSize: Int,
        val maxActions: Int,
        val unloadIdleMin: Int,
        val remoteBaseUrl: String,
        val remoteModel: String,
        val screenCompact: Boolean,
    )

    suspend fun setLocalOnly(v: Boolean) = set(Keys.LOCAL_ONLY, v)
    suspend fun setVoiceInput(v: Boolean) = set(Keys.VOICE_INPUT, v)
    suspend fun setVoiceOutput(v: Boolean) = set(Keys.VOICE_OUTPUT, v)
    suspend fun setOverlayEnabled(v: Boolean) = set(Keys.OVERLAY_ENABLED, v)
    suspend fun setAutoApproveMedium(v: Boolean) = set(Keys.AUTO_APPROVE_MEDIUM, v)
    suspend fun setMemoryEnabled(v: Boolean) = set(Keys.MEMORY_ENABLED, v)
    suspend fun setPauseOnUserTouch(v: Boolean) = set(Keys.PAUSE_ON_USER_TOUCH, v)
    suspend fun setTheme(v: String) = set(Keys.THEME, v)
    suspend fun setActiveModelId(v: String?) = context.dataStore.edit {
        if (v == null) it.remove(Keys.ACTIVE_MODEL_ID) else it[Keys.ACTIVE_MODEL_ID] = v
    }
    suspend fun setInferenceThreads(v: Int) = set(Keys.INFERENCE_THREADS, v)
    suspend fun setContextSize(v: Int) = set(Keys.CONTEXT_SIZE, v)
    suspend fun setMaxActions(v: Int) = set(Keys.MAX_ACTIONS, v)
    suspend fun setUnloadIdleMin(v: Int) = set(Keys.UNLOAD_IDLE_MIN, v)
    suspend fun setOnboarded(v: Boolean) = set(Keys.ONBOARDED, v)
    suspend fun setRemoteBaseUrl(v: String) = set(Keys.REMOTE_BASE_URL, v)
    suspend fun setRemoteModel(v: String) = set(Keys.REMOTE_MODEL, v)
    suspend fun setScreenCompact(v: Boolean) = set(Keys.SCREEN_COMPACT, v)

    private suspend fun <T> set(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}

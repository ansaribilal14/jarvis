package com.jarvis.mobile.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Hardware-backed (Android Keystore via EncryptedSharedPreferences) storage
 * for the secrets this app may hold: one API key per cloud provider (of which
 * exactly one is "active" for the engine - see remoteApiKey), plus the bot
 * tokens for the Telegram / Discord channels.
 * Never used for user passwords / OTPs (see PasswordPolicy).
 */
class SecureVault(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "jarvis_vault",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** The ACTIVE provider key the engine actually sends (kept for compatibility). */
    var remoteApiKey: String
        get() = prefs.getString(KEY_REMOTE_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REMOTE_API_KEY, value).apply()

    /** Per-provider key storage so switching providers never loses a saved key. */
    fun providerKey(providerId: String): String =
        prefs.getString(KEY_PROVIDER_PREFIX + providerId, "") ?: ""

    fun setProviderKey(providerId: String, value: String) {
        prefs.edit().putString(KEY_PROVIDER_PREFIX + providerId, value).apply()
    }

    /** Ids of providers that have a stored key (for UI "saved" markers). */
    fun providerKeyIds(): List<String> =
        prefs.all.keys.filter { it.startsWith(KEY_PROVIDER_PREFIX) }
            .map { it.removePrefix(KEY_PROVIDER_PREFIX) }
            .filter { (prefs.getString(KEY_PROVIDER_PREFIX + it, "") ?: "").isNotBlank() }
            .sorted()

    /** Discord bot token for the outbound notification channel (kept encrypted). */
    var discordBotToken: String
        get() = prefs.getString(KEY_DISCORD_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DISCORD_TOKEN, value).apply()

    /** Telegram bot token for the optional remote-control channel (kept encrypted). */
    var telegramBotToken: String
        get() = prefs.getString(KEY_TELEGRAM_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TELEGRAM_TOKEN, value).apply()

    companion object {
        private const val KEY_REMOTE_API_KEY = "remote_api_key"
        private const val KEY_TELEGRAM_TOKEN = "telegram_bot_token"
        private const val KEY_DISCORD_TOKEN = "discord_bot_token"
        private const val KEY_PROVIDER_PREFIX = "provider_key_"
    }
}

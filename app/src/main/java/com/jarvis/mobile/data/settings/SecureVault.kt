package com.jarvis.mobile.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Hardware-backed (Android Keystore via EncryptedSharedPreferences) storage for
 * the single secret this app may hold: an optional remote provider API key.
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

    var remoteApiKey: String
        get() = prefs.getString(KEY_REMOTE_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REMOTE_API_KEY, value).apply()

    /** Telegram bot token for the optional remote-control channel (kept encrypted). */
    var telegramBotToken: String
        get() = prefs.getString(KEY_TELEGRAM_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TELEGRAM_TOKEN, value).apply()

    companion object {
        private const val KEY_REMOTE_API_KEY = "remote_api_key"
        private const val KEY_TELEGRAM_TOKEN = "telegram_bot_token"
    }
}

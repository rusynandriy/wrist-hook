package com.rusyn.wristhook.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    data class Settings(
        val webhookUrl: String,
        val authHeader: String,
        val autoStopOnSilence: Boolean,
    )

    suspend fun load(): Settings {
        val prefs = context.dataStore.data.first()
        return Settings(
            // Personal build: fall back to the baked-in webhook when nothing is
            // stored, so the app goes straight to recording with no setup step.
            webhookUrl = prefs[KEY_WEBHOOK_URL]?.takeIf { it.isNotBlank() } ?: DEFAULT_WEBHOOK_URL,
            authHeader = prefs[KEY_AUTH_HEADER].orEmpty(),
            autoStopOnSilence = prefs[KEY_AUTO_STOP] ?: DEFAULT_AUTO_STOP,
        )
    }

    suspend fun setWebhookUrl(value: String) {
        context.dataStore.edit { it[KEY_WEBHOOK_URL] = value.trim() }
    }

    suspend fun setAuthHeader(value: String) {
        context.dataStore.edit { it[KEY_AUTH_HEADER] = value.trim() }
    }

    suspend fun setAutoStopOnSilence(value: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_STOP] = value }
    }

    companion object {
        // Blank by default: set your webhook URL on first launch (settings screen),
        // over adb (SetWebhookReceiver), or via the pairing flow.
        const val DEFAULT_WEBHOOK_URL = ""

        // Tap-free capture: stop + send after a short pause once speech is heard.
        const val DEFAULT_AUTO_STOP = true

        private val KEY_WEBHOOK_URL = stringPreferencesKey("webhook_url")
        private val KEY_AUTH_HEADER = stringPreferencesKey("auth_header")
        private val KEY_AUTO_STOP = booleanPreferencesKey("auto_stop_silence")
    }
}

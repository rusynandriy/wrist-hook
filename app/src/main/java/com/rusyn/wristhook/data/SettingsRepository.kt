package com.rusyn.wristhook.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    data class Settings(
        val webhookUrl: String,
        val authHeader: String,
        val autoStopOnSilence: Boolean,
        val maxRecordSec: Int,
        val accent: String,
    )

    suspend fun load(): Settings {
        val prefs = context.dataStore.data.first()
        return Settings(
            webhookUrl = prefs[KEY_WEBHOOK_URL]?.takeIf { it.isNotBlank() } ?: DEFAULT_WEBHOOK_URL,
            authHeader = prefs[KEY_AUTH_HEADER].orEmpty(),
            autoStopOnSilence = prefs[KEY_AUTO_STOP] ?: DEFAULT_AUTO_STOP,
            maxRecordSec = prefs[KEY_MAX_RECORD_SEC]
                ?.takeIf { it in MAX_RECORD_OPTIONS_SEC } ?: DEFAULT_MAX_RECORD_SEC,
            accent = prefs[KEY_ACCENT] ?: DEFAULT_ACCENT,
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

    suspend fun setMaxRecordSec(value: Int) {
        context.dataStore.edit { it[KEY_MAX_RECORD_SEC] = value }
    }

    suspend fun setAccent(value: String) {
        context.dataStore.edit { it[KEY_ACCENT] = value }
    }

    companion object {
        // Blank by default: set your webhook URL on first launch (settings screen),
        // over adb (SetWebhookReceiver), or via the pairing flow.
        const val DEFAULT_WEBHOOK_URL = ""

        // Tap-free capture: stop + send after a short pause once speech is heard.
        const val DEFAULT_AUTO_STOP = true

        // Auto stop & send when a take reaches this length.
        val MAX_RECORD_OPTIONS_SEC = listOf(30, 60, 120, 300, 600)
        const val DEFAULT_MAX_RECORD_SEC = 300

        // Accent presets; names are keys into ui.AccentPresets.
        val ACCENT_OPTIONS = listOf("red", "blue", "green", "amber", "violet")
        const val DEFAULT_ACCENT = "red"

        private val KEY_WEBHOOK_URL = stringPreferencesKey("webhook_url")
        private val KEY_AUTH_HEADER = stringPreferencesKey("auth_header")
        private val KEY_AUTO_STOP = booleanPreferencesKey("auto_stop_silence")
        private val KEY_MAX_RECORD_SEC = intPreferencesKey("max_record_sec")
        private val KEY_ACCENT = stringPreferencesKey("accent")
    }
}

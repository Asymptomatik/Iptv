package com.bobot.iptvapp.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed [OpenSubtitlesApiKeyStore], in the shared "iptv_prefs" file.
 *
 * Stored in clear like the Xtream credentials (ADR-003), in a file Android backup never copies
 * (`allowBackup="false"`). The key is an *application* setting, not part of the Xtream account:
 * the logout purge ([DataStoreLogoutFinalizer]) deliberately leaves it in place.
 */
@Singleton
class DataStoreOpenSubtitlesApiKeyStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : OpenSubtitlesApiKeyStore {

    companion object {
        internal val KEY_API_KEY = stringPreferencesKey("opensubtitles_api_key")
    }

    override fun observeIsConfigured(): Flow<Boolean> =
        dataStore.data.map { prefs -> !prefs[KEY_API_KEY].isNullOrBlank() }.distinctUntilChanged()

    override suspend fun getApiKey(): String? =
        dataStore.data.map { prefs -> prefs[KEY_API_KEY]?.takeIf { it.isNotBlank() } }.first()

    override suspend fun setApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        dataStore.edit { prefs ->
            if (trimmed.isEmpty()) prefs.remove(KEY_API_KEY) else prefs[KEY_API_KEY] = trimmed
        }
    }

    override suspend fun clearApiKey() {
        dataStore.edit { prefs -> prefs.remove(KEY_API_KEY) }
    }
}

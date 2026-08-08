package com.bobot.iptvapp.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore Preferences-backed implementation of [AppPreferencesStore].
 *
 * Uses the same "iptv_prefs" [DataStore] instance as [DataStoreCredentialsProvider]
 * (shared singleton provided by [com.bobot.iptvapp.di.PreferencesModule]). Key names
 * are prefixed with `"pref_"` to avoid collisions with credential keys (prefixed `"credentials_"`).
 *
 * All reads and writes go through the DataStore's serialized IO, which is automatically
 * dispatched off the main thread by the DataStore library.
 */
@Singleton
class DataStoreAppPreferencesStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : AppPreferencesStore {

    companion object {
        private val KEY_ACTIVE_PROFILE_ID = stringPreferencesKey("pref_active_profile_id")
        private val KEY_WIFI_ONLY_DOWNLOADS = booleanPreferencesKey("pref_wifi_only_downloads")
        private val KEY_DEFAULT_LANGUAGE_FILTER = stringPreferencesKey("pref_default_language_filter")
        private val KEY_DEFAULT_LIVE_LANGUAGE_FILTER =
            stringPreferencesKey("pref_default_live_language_filter")

        /** Product default for the Films/Series tabs, whose provider tags really are languages. */
        private const val DEFAULT_VOD_TAG = "FR"

        /** Product default for the Chaines tab, whose provider tags are regions. */
        private const val DEFAULT_LIVE_TAG = "EU"

        /**
         * Maps the raw stored value (or `null` if the key is absent) to the public semantics of
         * [observeDefaultLanguageFilter] / [getDefaultLanguageFilter]: absent -> [fallback],
         * empty -> null.
         *
         * [fallback] differs per tab — see [AppPreferencesStore.observeDefaultLiveLanguageFilter]
         * for why the Chaines tab defaults to a region tag rather than a language one.
         */
        private fun mapDefaultLanguageFilter(raw: String?, fallback: String): String? =
            when (raw) {
                null -> fallback
                "" -> null
                else -> raw
            }
    }

    override fun observeActiveProfileId(): Flow<String?> =
        dataStore.data.map { prefs -> prefs[KEY_ACTIVE_PROFILE_ID] }

    override suspend fun getActiveProfileId(): String? =
        dataStore.data.map { prefs -> prefs[KEY_ACTIVE_PROFILE_ID] }.first()

    override suspend fun setActiveProfileId(id: String?) {
        dataStore.edit { prefs ->
            if (id != null) {
                prefs[KEY_ACTIVE_PROFILE_ID] = id
            } else {
                prefs.remove(KEY_ACTIVE_PROFILE_ID)
            }
        }
    }

    override fun observeWifiOnlyDownloads(): Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_WIFI_ONLY_DOWNLOADS] ?: false }

    override suspend fun setWifiOnlyDownloads(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_WIFI_ONLY_DOWNLOADS] = enabled
        }
    }

    override fun observeDefaultLanguageFilter(): Flow<String?> =
        dataStore.data.map { prefs ->
            mapDefaultLanguageFilter(prefs[KEY_DEFAULT_LANGUAGE_FILTER], DEFAULT_VOD_TAG)
        }

    override suspend fun getDefaultLanguageFilter(): String? =
        observeDefaultLanguageFilter().first()

    override suspend fun setDefaultLanguageFilter(tag: String?) {
        dataStore.edit { prefs ->
            prefs[KEY_DEFAULT_LANGUAGE_FILTER] = tag?.trim()?.uppercase() ?: ""
        }
    }

    override fun observeDefaultLiveLanguageFilter(): Flow<String?> =
        dataStore.data.map { prefs ->
            mapDefaultLanguageFilter(prefs[KEY_DEFAULT_LIVE_LANGUAGE_FILTER], DEFAULT_LIVE_TAG)
        }

    override suspend fun getDefaultLiveLanguageFilter(): String? =
        observeDefaultLiveLanguageFilter().first()

    override suspend fun setDefaultLiveLanguageFilter(tag: String?) {
        dataStore.edit { prefs ->
            prefs[KEY_DEFAULT_LIVE_LANGUAGE_FILTER] = tag?.trim()?.uppercase() ?: ""
        }
    }
}

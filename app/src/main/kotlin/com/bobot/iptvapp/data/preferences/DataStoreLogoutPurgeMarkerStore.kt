package com.bobot.iptvapp.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [LogoutPurgeMarkerStore], sharing the single "iptv_prefs" DataStore described in
 * [com.bobot.iptvapp.di.PreferencesModule].
 *
 * The key is namespaced `logout_` so it sits alongside the existing `credentials_` and `pref_`
 * families without colliding.
 */
@Singleton
class DataStoreLogoutPurgeMarkerStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : LogoutPurgeMarkerStore {

    companion object {
        internal val KEY_PURGE_PENDING = booleanPreferencesKey("logout_purge_pending")
    }

    override suspend fun isPurgePending(): Boolean =
        dataStore.data.map { it[KEY_PURGE_PENDING] ?: false }.first()

    override suspend fun markPurgePending() {
        dataStore.edit { it[KEY_PURGE_PENDING] = true }
    }

    /**
     * Removes the key rather than writing `false`, so a completed purge leaves the preferences
     * exactly as a never-logged-out install would — the two states are equivalent to every reader
     * and there is no stale key to reason about later.
     */
    override suspend fun clearPurgePending() {
        dataStore.edit { it.remove(KEY_PURGE_PENDING) }
    }
}

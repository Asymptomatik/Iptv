package com.bobot.iptvapp.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.bobot.iptvapp.data.logout.LogoutFinalizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [LogoutFinalizer], writing both halves of the terminal state in one
 * [DataStore.edit] transaction on the shared "iptv_prefs" store.
 *
 * The keys are reached through the two owning classes' `internal` constants rather than being
 * re-declared here: a second spelling of `credentials_password` that drifted from the original
 * would leave the password on disk after a logout that reported success.
 */
@Singleton
class DataStoreLogoutFinalizer @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : LogoutFinalizer {

    override suspend fun finalizeLogout() {
        dataStore.edit { prefs ->
            prefs.remove(DataStoreCredentialsProvider.KEY_BASE_URL)
            prefs.remove(DataStoreCredentialsProvider.KEY_USERNAME)
            prefs.remove(DataStoreCredentialsProvider.KEY_PASSWORD)
            prefs.remove(DataStoreLogoutPurgeMarkerStore.KEY_PURGE_PENDING)
        }
    }
}

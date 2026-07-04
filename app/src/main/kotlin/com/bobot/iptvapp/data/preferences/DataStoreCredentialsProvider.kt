package com.bobot.iptvapp.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bobot.iptvapp.data.source.CredentialsProvider
import com.bobot.iptvapp.domain.model.XtreamCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [CredentialsProvider] backed by DataStore Preferences.
 *
 * Credentials (baseUrl, username, password) are written to the "iptv_prefs" DataStore file
 * and survive process restarts. [getCredentials] returns `null` if any required field is
 * missing or blank, which signals that onboarding has not yet been completed.
 *
 * ## How cache invalidation works
 * This provider exposes [observeCredentials] as a cold Flow derived from DataStore's own
 * reactive stream. [com.bobot.iptvapp.data.repository.CatalogRepositoryImpl] and
 * [com.bobot.iptvapp.data.source.RemoteXtreamSource] both observe this flow via an
 * application-scoped coroutine started in their `init` block. When credentials change
 * (new server or logout), each observer automatically invalidates its local caches before
 * the next data request is made. This avoids circular dependencies between the provider
 * and the repository.
 *
 * ## Security — V1 plaintext storage
 * The password is stored as plaintext in DataStore Preferences. This is an accepted
 * trade-off for V1:
 *  - The app is fully offline-first with no backend exposure.
 *  - DataStore writes to the app's private data directory, inaccessible to other apps
 *    on non-rooted devices, and excluded from Android cloud backup by default.
 *  - Xtream Codes credentials are server-access credentials, not financial secrets.
 *
 * TODO (V2 hardening): Migrate to EncryptedSharedPreferences or a Tink-backed encrypted
 * DataStore (Jetpack Security / DataStore with EncryptedFile) once the onboarding and
 * settings flows are stable. See ADR-003 for the full trade-off analysis.
 *
 * @param dataStore DataStore<Preferences> singleton provided by [com.bobot.iptvapp.di.PreferencesModule].
 */
@Singleton
class DataStoreCredentialsProvider @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : CredentialsProvider {

    companion object {
        internal val KEY_BASE_URL = stringPreferencesKey("credentials_base_url")
        internal val KEY_USERNAME = stringPreferencesKey("credentials_username")
        internal val KEY_PASSWORD = stringPreferencesKey("credentials_password")
    }

    // ── CredentialsProvider ───────────────────────────────────────────────────

    override fun observeCredentials(): Flow<XtreamCredentials?> =
        dataStore.data.map { it.toCredentials() }

    override suspend fun getCredentials(): XtreamCredentials? =
        dataStore.data.map { it.toCredentials() }.first()

    override suspend fun setCredentials(credentials: XtreamCredentials) {
        dataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = credentials.baseUrl
            prefs[KEY_USERNAME] = credentials.username
            prefs[KEY_PASSWORD] = credentials.password
        }
    }

    override suspend fun clearCredentials() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_BASE_URL)
            prefs.remove(KEY_USERNAME)
            prefs.remove(KEY_PASSWORD)
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Maps raw [Preferences] to [XtreamCredentials], returning `null` when any required
     * field is absent or blank. "Blank" covers empty strings and whitespace-only strings,
     * which can result from a partially completed onboarding screen.
     */
    private fun Preferences.toCredentials(): XtreamCredentials? {
        val baseUrl  = this[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: return null
        val username = this[KEY_USERNAME]?.takeIf { it.isNotBlank() } ?: return null
        val password = this[KEY_PASSWORD]?.takeIf { it.isNotBlank() } ?: return null
        return XtreamCredentials(baseUrl, username, password)
    }
}

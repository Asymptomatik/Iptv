package com.bobot.iptvapp.data.source

import com.bobot.iptvapp.domain.model.XtreamCredentials
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction for reading, writing, and observing the current [XtreamCredentials] at runtime.
 *
 * [RemoteXtreamSource] calls [getCredentials] before establishing each API session.
 * When the result is `null`, the source throws [CatalogException.AuthenticationFailed]
 * and the repository propagates a [com.bobot.iptvapp.domain.util.Resource.Error] to the UI.
 *
 * ## Cache invalidation
 * Collectors of [observeCredentials] (e.g. [com.bobot.iptvapp.data.repository.CatalogRepositoryImpl]
 * and [RemoteXtreamSource]) react to credential changes by invalidating their in-memory
 * session caches, ensuring fresh content is fetched from the new server configuration.
 *
 * ## Implementations
 *  - [InMemoryCredentialsProvider] — lightweight in-memory holder, kept as a test double.
 *  - [com.bobot.iptvapp.data.preferences.DataStoreCredentialsProvider] — DataStore Preferences
 *    implementation used in production (bound in [com.bobot.iptvapp.di.RepositoryModule]).
 */
interface CredentialsProvider {

    /**
     * Returns the currently configured [XtreamCredentials], or `null` when no credentials
     * have been provided yet (e.g. before onboarding is complete).
     */
    suspend fun getCredentials(): XtreamCredentials?

    /**
     * Emits the current [XtreamCredentials] (or `null`) immediately on collection,
     * then again on every subsequent change.
     *
     * Collectors (e.g. [com.bobot.iptvapp.data.repository.CatalogRepositoryImpl]) use
     * this flow to react to credential changes and invalidate session caches automatically.
     */
    fun observeCredentials(): Flow<XtreamCredentials?>

    /**
     * Persists [credentials], overwriting any previously stored value.
     * Collectors of [observeCredentials] receive the new value after this call completes.
     */
    suspend fun setCredentials(credentials: XtreamCredentials)

    /**
     * Removes stored credentials (logout / account removal).
     * After clearing, [getCredentials] returns `null` and [observeCredentials] emits `null`.
     */
    suspend fun clearCredentials()
}

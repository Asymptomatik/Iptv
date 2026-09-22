package com.bobot.iptvapp.data.logout

/**
 * Deletes the Room-backed local state that must not survive a logout: the downloads index, the
 * catalogue cache and the EPG cache.
 *
 * Split into one method per logical store rather than a single `purgeEverything()` so
 * [LogoutPurger][com.bobot.iptvapp.domain.logout.LogoutPurger] can place each store at its own
 * position in the mandated purge order (downloads only *after* Media3 has released its index and
 * cache; credentials strictly last).
 *
 * ## Fail-fast, unlike the reactive purge
 * [com.bobot.iptvapp.data.repository.CatalogRepositoryImpl] already purges the same catalogue/EPG
 * tables reactively when [com.bobot.iptvapp.data.source.CredentialsProvider.observeCredentials]
 * emits `null`, but it deliberately swallows per-table failures ("quietly") because it runs inside
 * an application-scoped observer that must never crash. That makes it a safety net, not a
 * guarantee: a failure there is invisible. The logout orchestrator needs the opposite contract —
 * a failed delete must propagate so the pending marker stays set and the user is offered a retry —
 * so this purger lets exceptions through.
 */
interface LocalCachePurger {

    /** Deletes every row of the `downloads` index. Idempotent: purging an empty table is a no-op. */
    suspend fun purgeDownloadIndex()

    /** Deletes every catalogue and EPG cache row, across all accounts. Idempotent. */
    suspend fun purgeCatalogAndEpgCaches()

    /**
     * Number of rows left in the `downloads` index.
     *
     * Backs the "no credentials but leftover downloads" recovery case: an install that was logged
     * out before this feature existed has no pending marker to resume from, so the residue itself
     * is the signal that a local purge is still owed.
     */
    suspend fun countDownloadResidue(): Int

    /**
     * Number of rows left across the catalogue and EPG tables.
     *
     * The download index is not the only store a purge can lose a race against. A catalogue fetch
     * issued before the logout completes *after* it and writes the previous account's channels,
     * movies or series back — the same late-write shape [countDownloadResidue] exists for, one
     * layer up. [com.bobot.iptvapp.data.repository.CatalogRepositoryImpl] refuses those writes at
     * the source; this is the orchestrator's independent confirmation, so a producer that slips
     * through keeps the pending marker set instead of being announced as a completed logout.
     */
    suspend fun countCatalogResidue(): Int
}

package com.bobot.iptvapp.data.logout

/**
 * Drops every in-memory catalogue memo, synchronously, on the purge's own thread of control.
 *
 * [com.bobot.iptvapp.data.repository.CatalogRepositoryImpl] already clears these memos reactively,
 * from an application-scoped observer of
 * [com.bobot.iptvapp.data.source.CredentialsProvider.observeCredentials]. That observer is the
 * problem, not the solution, as far as a *logout* is concerned: it only runs once DataStore has
 * emitted `null`, which happens after the purge has finished writing, on a coroutine the purge
 * neither starts nor waits for. Between the two, the memos still hold the previous account's
 * channels, movies and series, and anything reading the repository is served them from RAM — no
 * Room row and no credential involved, so every check the purge performs comes back clean.
 *
 * Calling this first makes the invalidation part of the purge rather than a consequence of it, and
 * — because the implementation bumps the fetch generations before clearing — also closes the door
 * behind it: a request already in flight can no longer publish its result into the memos or into
 * Room once the purge has started. See
 * [com.bobot.iptvapp.data.repository.CatalogRepositoryImpl.invalidateCache].
 *
 * Narrow on purpose: the orchestrator has no business holding a whole
 * [com.bobot.iptvapp.domain.repository.CatalogRepository], and a one-method seam is what lets
 * [DefaultLogoutPurger]'s tests assert *when* the invalidation happens relative to the other steps.
 */
interface SessionCacheInvalidator {

    /**
     * Invalidates every in-memory catalogue cache. Idempotent, and never throws by contract.
     *
     * `suspend` because closing the publication window is not enough on its own: a producer that
     * already passed its generation check a moment before this runs may still be mid-write to Room.
     * Implementations must not return until that write has landed (or a bounded wait gives up),
     * so the caller's next step — clearing Room — is guaranteed to run after it, not concurrently
     * with it.
     */
    suspend fun invalidateSessionCaches()
}

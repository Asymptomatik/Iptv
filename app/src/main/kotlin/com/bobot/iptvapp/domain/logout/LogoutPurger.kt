package com.bobot.iptvapp.domain.logout

/**
 * The single place that knows how to take this install from "signed in" to "nothing of the previous
 * account is left".
 *
 * ## Why one orchestrator
 * The steps have a mandatory order and a shared failure contract, and both were previously spread
 * across a ViewModel, a repository's reactive credentials observer, and Media3's own callbacks —
 * which is how it became possible to clear the credentials while the downloads, their Media3 index
 * entries and their cached bytes all stayed on disk. Concentrating the order here means
 * [com.bobot.iptvapp.ui.screen.settings.SettingsViewModel] only has to know "it worked" or "it did
 * not", and any future caller gets the same guarantees for free.
 *
 * ## The order, and why each position is forced
 *  1. **Mark pending.** Before anything destructive, so a crash at any later point is recoverable.
 *  2. **Invalidate the in-memory catalogue caches**, synchronously — see
 *     [com.bobot.iptvapp.data.logout.SessionCacheInvalidator]. It comes before the deletes because
 *     it is what stops a fetch already in flight from writing the account back in behind them, and
 *     because a memo still holding the previous account is served from RAM without touching any
 *     store the later checks look at.
 *  3. **Media3 index**, waited out until observably empty — a live transfer holds both an index
 *     entry and a file handle, so nothing downstream is meaningful until it has really let go.
 *  4. **Media3 cache**, through the live instance, likewise waited out until a fresh read of it
 *     comes back empty.
 *  5. **Room `downloads`**, after Media3 — [com.bobot.iptvapp.download.DownloadTracker] writes this
 *     table from Media3 callbacks, so clearing it first would just let a late callback rewrite a
 *     row naming the old account's stream URL.
 *  6. **Catalogue and EPG caches.**
 *  7. **Check what is left**, across all of the above, and replay the walk once if anything is.
 *  8. **Credentials and marker together, last**, in one write — see
 *     [com.bobot.iptvapp.data.logout.LogoutFinalizer]. The credentials are the only thing that can
 *     prove *which* account residue belongs to, and the only thing that makes a retry possible, so
 *     they go once there is provably nothing left; committing them in the same transaction as the
 *     marker is what keeps "signed out" and "nothing owed" from ever disagreeing.
 *
 * Every step is idempotent, so a retry after a failure — or a resume after a crash — can simply
 * replay the whole sequence.
 */
interface LogoutPurger {

    /**
     * Runs the full logout.
     *
     * @throws LogoutPurgeException if any step fails. The marker stays set and the credentials stay
     *   in place, so the caller can surface a retryable error and call this again.
     */
    suspend fun logOut()

    /**
     * Completes a purge this install still owes, if any: either one that was interrupted (marker
     * still set), or the legacy state of an install logged out before this feature existed, which
     * has no credentials yet still holds download residue.
     *
     * @return `true` when a purge actually ran.
     * @throws LogoutPurgeException if the purge fails; the marker is left set for the next attempt.
     */
    suspend fun recoverIfNeeded(): Boolean
}

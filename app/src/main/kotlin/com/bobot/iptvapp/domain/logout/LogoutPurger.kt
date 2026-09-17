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
 *  2. **Media3 index**, waited out until observably empty — a live transfer holds both an index
 *     entry and a file handle, so nothing downstream is meaningful until it has really let go.
 *  3. **Media3 cache**, through the live instance.
 *  4. **Room `downloads`**, after Media3 — [com.bobot.iptvapp.download.DownloadTracker] writes this
 *     table from Media3 callbacks, so clearing it first would just let a late callback rewrite a
 *     row naming the old account's stream URL.
 *  5. **Catalogue and EPG caches.**
 *  6. **Credentials, last.** They are the only thing that can prove *which* account the residue
 *     belongs to; dropping them first would turn a recoverable half-purge into orphaned data.
 *  7. **Clear the marker**, only now.
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

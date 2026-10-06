package com.bobot.iptvapp.data.logout

import com.bobot.iptvapp.data.preferences.LogoutPurgeMarkerStore
import com.bobot.iptvapp.data.source.CredentialsProvider
import com.bobot.iptvapp.domain.logout.LogoutPurgeException
import com.bobot.iptvapp.domain.logout.LogoutPurger
import com.bobot.iptvapp.download.purge.DownloadStoragePurger
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** Production [LogoutPurger]. See the interface KDoc for the order and why it is fixed. */
@Singleton
class DefaultLogoutPurger @Inject constructor(
    private val markerStore: LogoutPurgeMarkerStore,
    private val downloadStoragePurger: DownloadStoragePurger,
    private val localCachePurger: LocalCachePurger,
    private val sessionCacheInvalidator: SessionCacheInvalidator,
    private val activePlaybackStopper: ActivePlaybackStopper,
    private val logoutFinalizer: LogoutFinalizer,
    private val credentialsProvider: CredentialsProvider,
    private val downloadedSubtitlePurger: DownloadedSubtitlePurger,
) : LogoutPurger {

    override suspend fun logOut() {
        markerStore.markPurgePending()
        runPurge()
    }

    override suspend fun recoverIfNeeded(): Boolean {
        if (!isPurgeOwed()) return false
        // Re-mark before resuming: the legacy "no credentials but leftover downloads" case has no
        // marker of its own, and a crash during *this* attempt has to stay recoverable.
        markerStore.markPurgePending()
        runPurge()
        return true
    }

    /**
     * An interrupted purge announces itself through the marker. A legacy one cannot — it predates
     * the marker — so its signature is used instead: no credentials, yet download state still on
     * disk. The credentials check comes first because leftover downloads are perfectly normal while
     * a user is signed in.
     */
    private suspend fun isPurgeOwed(): Boolean {
        if (markerStore.isPurgePending()) return true
        if (credentialsProvider.getCredentials() != null) return false
        return localCachePurger.countDownloadResidue() > 0 || downloadStoragePurger.hasStorageResidue()
    }

    /**
     * Walks every store, then *checks*, and only clears the marker once the check comes back empty.
     *
     * ## Why a successful walk is not enough
     * Every step can return normally and still leave the account on disk. The purge visits each
     * store once; [com.bobot.iptvapp.download.DownloadTracker] observes Media3 asynchronously, so a
     * callback that read a download before `clearAll()` lands its `upsert` *after* it, putting a
     * row whose `streamUrl` contains the username and password back into a table the purge has
     * already walked past and will never look at again. No step sees it, because at the moment each
     * step runs the row is not there yet. The only point at which the whole picture exists is after
     * the last step — which is exactly where the marker used to be cleared unconditionally, turning
     * that residue into a logout the app believed was complete.
     *
     * ## Why it replays instead of failing straight away
     * The realistic case is a single late callback, and every step is idempotent by contract, so
     * one more walk costs almost nothing and clears it. Residue that survives the replay is not a
     * timing artefact — something is actively writing, or a delete is failing silently — and the
     * honest outcome then is a failure with the marker left set: the next launch resumes, and
     * downloads stay refused until it does.
     */
    private suspend fun runPurge() {
        var replaysLeft = RESIDUE_REPLAY_BUDGET
        while (true) {
            purgeEveryStore()

            val residue = step("la vérification des données restantes") { describeResidue() }
                ?: break

            if (replaysLeft == 0) {
                throw LogoutPurgeException(
                    "Des données du compte sont réapparues pendant la déconnexion " +
                        "($residue) et n'ont pas pu être supprimées.",
                )
            }
            replaysLeft--
        }
        // Reached only when every store is provably empty — see the interface KDoc. Credentials go
        // in the same write as the marker, so there is no instant at which one has been committed
        // without the other; see [LogoutFinalizer].
        step("la finalisation de la déconnexion") { logoutFinalizer.finalizeLogout() }
    }

    private suspend fun purgeEveryStore() {
        // First, and repeated on every replay: stops the one producer the storage purge below does
        // not own — a player screen left open through logout, sharing the same live Media3 cache
        // instance evictCachedResources() is trying to empty. See [ActivePlaybackStopper].
        step("l'arrêt de la lecture en cours") {
            activePlaybackStopper.stopActivePlayback()
        }
        // Closes the catalogue producers' publication window before anything is deleted, so a fetch
        // already in flight cannot write the account back into Room behind the clears below. See
        // [SessionCacheInvalidator].
        step("l'invalidation des caches en mémoire") {
            sessionCacheInvalidator.invalidateSessionCaches()
        }
        step("la suppression des téléchargements Media3") {
            downloadStoragePurger.removeAllDownloadsAndAwaitEmptyIndex()
        }
        step("le vidage du cache de téléchargement") {
            downloadStoragePurger.evictCachedResources()
        }
        step("le vidage de l'index local des téléchargements") {
            localCachePurger.purgeDownloadIndex()
        }
        step("le vidage des caches catalogue et EPG") {
            localCachePurger.purgeCatalogAndEpgCaches()
        }
        step("la suppression des sous-titres téléchargés") {
            downloadedSubtitlePurger.purgeDownloadedSubtitles()
        }
    }

    /**
     * What is left across every local store, phrased for the user, or `null` when there is nothing
     * left.
     *
     * Every store is read on every pass rather than short-circuiting on the first non-empty one:
     * the message names everything that is still there, which is what makes a repeated failure
     * diagnosable. The catalogue is included because a fetch in flight at logout time lands in
     * those tables, not in the downloads index — see [LocalCachePurger.countCatalogResidue].
     */
    private suspend fun describeResidue(): String? {
        val roomRows = localCachePurger.countDownloadResidue()
        val storageResidue = downloadStoragePurger.hasStorageResidue()
        val catalogRows = localCachePurger.countCatalogResidue()
        val subtitleResidue = downloadedSubtitlePurger.hasResidue()
        return listOfNotNull(
            "$roomRows téléchargement(s) en base".takeIf { roomRows > 0 },
            "des fichiers de téléchargement".takeIf { storageResidue },
            "$catalogRows ligne(s) de catalogue".takeIf { catalogRows > 0 },
            "des sous-titres téléchargés".takeIf { subtitleResidue },
        ).takeIf { it.isNotEmpty() }?.joinToString(" et ")
    }

    /**
     * Runs one step, converting any failure into a [LogoutPurgeException] naming it, and leaving
     * the marker exactly as it was — set.
     *
     * [CancellationException] is rethrown untouched: it means the caller's scope went away, not
     * that the step failed, and swallowing it would both break structured concurrency and report a
     * purge error the user cannot act on.
     */
    private suspend fun <T> step(label: String, block: suspend () -> T): T {
        try {
            return block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: LogoutPurgeException) {
            throw failure
        } catch (failure: Exception) {
            throw LogoutPurgeException("Échec de $label pendant la déconnexion.", failure)
        }
    }

    private companion object {
        /**
         * How many extra walks a purge is allowed after finding residue. One covers the single
         * late callback this exists for; more would just prolong a purge that is losing a race
         * against something still writing, which the pending marker already handles correctly.
         */
        const val RESIDUE_REPLAY_BUDGET = 1
    }
}

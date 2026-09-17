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
    private val credentialsProvider: CredentialsProvider,
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

    private suspend fun runPurge() {
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
        step("l'effacement des identifiants") {
            credentialsProvider.clearCredentials()
        }
        // Reached only when every step above returned normally — see the interface KDoc.
        markerStore.clearPurgePending()
    }

    /**
     * Runs one step, converting any failure into a [LogoutPurgeException] naming it, and leaving
     * the marker exactly as it was — set.
     *
     * [CancellationException] is rethrown untouched: it means the caller's scope went away, not
     * that the step failed, and swallowing it would both break structured concurrency and report a
     * purge error the user cannot act on.
     */
    private suspend fun step(label: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: LogoutPurgeException) {
            throw failure
        } catch (failure: Exception) {
            throw LogoutPurgeException("Échec de $label pendant la déconnexion.", failure)
        }
    }
}

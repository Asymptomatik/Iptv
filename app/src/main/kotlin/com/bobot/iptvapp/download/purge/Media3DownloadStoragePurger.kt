package com.bobot.iptvapp.download.purge

import com.bobot.iptvapp.domain.logout.LogoutPurgeException
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/** [DownloadStoragePurger] driving the live Media3 index and cache through their gateways. */
@Singleton
class Media3DownloadStoragePurger @Inject constructor(
    private val index: Media3DownloadIndexGateway,
    private val cache: Media3CacheGateway,
) : DownloadStoragePurger {

    /**
     * Issues one global removal, then re-reads the index until it is empty.
     *
     * The re-read is what makes this honest. `removeAllDownloads` only hands Media3 a request; the
     * transfer thread cancels, writes and releases on its own schedule, and returning on the
     * command would let the credentials be cleared while the index still named content from the
     * account being signed out of. The wait is bounded so a stuck index surfaces as a retryable
     * failure rather than an indefinite spinner.
     */
    override suspend fun removeAllDownloadsAndAwaitEmptyIndex() {
        index.removeAllDownloads()

        var waitedMillis = 0L
        while (true) {
            if (index.countIndexedDownloads() == 0) return
            if (waitedMillis >= AWAIT_EMPTY_TIMEOUT_MILLIS) break
            delay(AWAIT_EMPTY_POLL_MILLIS)
            waitedMillis += AWAIT_EMPTY_POLL_MILLIS
        }

        throw LogoutPurgeException(
            "L'index de téléchargement Media3 contient encore " +
                "${index.countIndexedDownloads()} entrée(s) après ${AWAIT_EMPTY_TIMEOUT_MILLIS} ms.",
        )
    }

    /**
     * Evicts key by key through the live instance. The keys are snapshotted first because
     * [Media3CacheGateway.removeResource] mutates the very set [Media3CacheGateway.cachedKeys]
     * reports.
     */
    override suspend fun evictCachedResources() {
        cache.cachedKeys().toList().forEach { cache.removeResource(it) }
    }

    override suspend fun hasStorageResidue(): Boolean =
        index.countIndexedDownloads() > 0 || cache.cachedKeys().isNotEmpty()

    companion object {
        /** How long to keep checking the index before declaring the purge failed. */
        internal const val AWAIT_EMPTY_TIMEOUT_MILLIS = 30_000L

        /** Gap between two index checks while waiting. */
        internal const val AWAIT_EMPTY_POLL_MILLIS = 50L
    }
}

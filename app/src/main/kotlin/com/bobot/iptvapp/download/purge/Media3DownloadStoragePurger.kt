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
        var remaining: Int
        while (true) {
            remaining = index.countIndexedDownloads()
            if (remaining == 0) return
            if (waitedMillis >= AWAIT_EMPTY_TIMEOUT_MILLIS) break
            delay(AWAIT_EMPTY_POLL_MILLIS)
            waitedMillis += AWAIT_EMPTY_POLL_MILLIS
        }

        // The count the wait actually gave up on, not a fresh read: re-reading here would be one
        // more I/O call on a path that is already failing, and an index that drains on that very
        // read would produce a "still contains 0 entries" message contradicting itself.
        throw LogoutPurgeException(
            "L'index de téléchargement Media3 contient encore " +
                "$remaining entrée(s) après ${AWAIT_EMPTY_TIMEOUT_MILLIS} ms.",
        )
    }

    /**
     * Evicts key by key through the live instance, then re-reads the cache until it observes it
     * empty on [REQUIRED_CONSECUTIVE_EMPTY_READS] consecutive reads — the same contract as
     * [removeAllDownloadsAndAwaitEmptyIndex], strengthened for the same [Media3CacheGateway] the
     * player's own `CacheDataSource` writes through.
     *
     * A single empty read is not evidence the cache stays empty: it shares this [cache] instance
     * with active playback, which can create a span in the instant right after that read returns —
     * a purge that trusted one read could announce success with the previous account's bytes about
     * to land on disk. Requiring [REQUIRED_CONSECUTIVE_EMPTY_READS] reads in a row, one poll
     * interval apart, gives that writer a window to show up before the purge commits to "empty".
     *
     * On timeout, the *last* pass may have just emptied the cache with its final removal — reporting
     * the count from before that removal would fail a purge that actually just succeeded, so the
     * failure path re-reads once more before deciding.
     */
    override suspend fun evictCachedResources() {
        var waitedMillis = 0L
        var consecutiveEmptyReads = 0
        while (true) {
            val keys = cache.cachedKeys()
            if (keys.isEmpty()) {
                consecutiveEmptyReads++
                if (consecutiveEmptyReads >= REQUIRED_CONSECUTIVE_EMPTY_READS) return
            } else {
                consecutiveEmptyReads = 0
                keys.toList().forEach { cache.removeResource(it) }
            }
            if (waitedMillis >= AWAIT_EMPTY_TIMEOUT_MILLIS) break
            delay(AWAIT_EMPTY_POLL_MILLIS)
            waitedMillis += AWAIT_EMPTY_POLL_MILLIS
        }

        // A fresh read, not the last pass's stale count: that pass may have just emptied the cache
        // with a removal the loop above never got to re-observe before giving up on time.
        val residual = cache.cachedKeys()
        if (residual.isEmpty()) return
        throw LogoutPurgeException(
            "Le cache de téléchargement Media3 contient encore " +
                "${residual.size} ressource(s) après ${AWAIT_EMPTY_TIMEOUT_MILLIS} ms.",
        )
    }

    override suspend fun hasStorageResidue(): Boolean =
        index.countIndexedDownloads() > 0 || cache.cachedKeys().isNotEmpty()

    companion object {
        /** How long to keep checking the index before declaring the purge failed. */
        internal const val AWAIT_EMPTY_TIMEOUT_MILLIS = 30_000L

        /** Gap between two index checks while waiting. */
        internal const val AWAIT_EMPTY_POLL_MILLIS = 50L

        /** See [evictCachedResources]'s KDoc for why a single empty read is not enough. */
        internal const val REQUIRED_CONSECUTIVE_EMPTY_READS = 2
    }
}

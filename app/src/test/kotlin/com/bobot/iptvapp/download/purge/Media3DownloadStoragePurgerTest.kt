package com.bobot.iptvapp.download.purge

import com.bobot.iptvapp.domain.logout.LogoutPurgeException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Media3DownloadStoragePurger] — slice 3 of the logout purge.
 *
 * `runTest` runs the purger's wait loop on virtual time, so an index modelled as taking hundreds of
 * checks to drain costs nothing to assert on.
 */
class Media3DownloadStoragePurgerTest {

    @Test
    fun `removing downloads only returns once the index is observably empty`() = runTest {
        val index = FakeMedia3DownloadIndexGateway(
            initialDownloadIds = setOf("movie-1", "movie-2"),
            // The removal command lands three checks after it is issued: returning on the command
            // alone would leave two entries behind.
            removalLatency = 3,
        )
        val purger = Media3DownloadStoragePurger(index, FakeMedia3CacheGateway())

        purger.removeAllDownloadsAndAwaitEmptyIndex()

        assertEquals(emptySet<String>(), index.currentDownloadIds)
    }

    @Test
    fun `a download that is actively transferring is removed like any other`() = runTest {
        // Media3 exposes no distinction at the index level — an in-flight transfer holds an index
        // entry exactly like a finished one, and removeAllDownloads cancels it. The purge must not
        // treat it as a special case it can skip.
        val index = FakeMedia3DownloadIndexGateway(
            initialDownloadIds = setOf("movie-in-flight"),
            removalLatency = 2,
        )
        val purger = Media3DownloadStoragePurger(index, FakeMedia3CacheGateway())

        purger.removeAllDownloadsAndAwaitEmptyIndex()

        assertEquals(emptySet<String>(), index.currentDownloadIds)
        assertEquals(1, index.removeAllCount)
    }

    @Test
    fun `an index that never drains fails instead of reporting success`() = runTest {
        val index = FakeMedia3DownloadIndexGateway(
            initialDownloadIds = setOf("movie-1"),
            removalLatency = Int.MAX_VALUE,
        )
        val purger = Media3DownloadStoragePurger(index, FakeMedia3CacheGateway())

        val thrown = runCatching { purger.removeAllDownloadsAndAwaitEmptyIndex() }.exceptionOrNull()

        assertTrue(
            "attendu: LogoutPurgeException, obtenu: $thrown",
            thrown is LogoutPurgeException,
        )
    }

    @Test
    fun `the timeout reports the count it actually waited on, not a fresh read`() = runTest {
        // The index drains on the very next read after the wait budget runs out. Re-reading it to
        // build the message therefore reports an empty index in a message that exists to say the
        // index was not empty — an error that contradicts itself, and one more I/O call on a path
        // that is already failing.
        val readsBeforeTimeout =
            (Media3DownloadStoragePurger.AWAIT_EMPTY_TIMEOUT_MILLIS /
                Media3DownloadStoragePurger.AWAIT_EMPTY_POLL_MILLIS).toInt() + 1
        val index = FakeMedia3DownloadIndexGateway(
            initialDownloadIds = setOf("movie-1"),
            removalLatency = readsBeforeTimeout,
        )
        val purger = Media3DownloadStoragePurger(index, FakeMedia3CacheGateway())

        val thrown = runCatching { purger.removeAllDownloadsAndAwaitEmptyIndex() }.exceptionOrNull()

        assertTrue(
            "le message doit nommer l'entrée réellement observée, obtenu: ${thrown?.message}",
            thrown?.message?.contains("1 entrée(s)") == true,
        )
    }

    @Test
    fun `awaiting an already empty index succeeds without waiting`() = runTest {
        val index = FakeMedia3DownloadIndexGateway()
        val purger = Media3DownloadStoragePurger(index, FakeMedia3CacheGateway())

        purger.removeAllDownloadsAndAwaitEmptyIndex()
        purger.removeAllDownloadsAndAwaitEmptyIndex()

        assertEquals(emptySet<String>(), index.currentDownloadIds)
    }

    @Test
    fun `evicting releases every cached resource through the live cache instance`() = runTest {
        val cache = FakeMedia3CacheGateway(setOf("movie-1", "movie-2"))
        val purger = Media3DownloadStoragePurger(FakeMedia3DownloadIndexGateway(), cache)

        purger.evictCachedResources()

        assertEquals(setOf("movie-1", "movie-2"), cache.removedKeys.toSet())
        assertEquals(emptySet<String>(), cache.cachedKeys())
    }

    @Test
    fun `a resource appearing during the first eviction pass is not mistaken for an empty cache`() =
        runTest {
            val cache = FakeMedia3CacheGateway(setOf("movie-1"))
            // The player's writer creates a span for movie-2 while movie-1 is being released — i.e.
            // strictly after cachedKeys() was snapshotted and strictly before the call returns.
            cache.appearDuringEvictionOf(duringRemovalOf = "movie-1", appearing = "movie-2")
            val purger = Media3DownloadStoragePurger(FakeMedia3DownloadIndexGateway(), cache)

            purger.evictCachedResources()

            assertEquals(
                "a single snapshot cannot see a key created after it was taken; returning here " +
                    "would let the logout be announced with the previous account's bytes on disk",
                emptySet<String>(),
                cache.cachedKeys(),
            )
        }

    @Test
    fun `a resource appearing right after the first empty read is not mistaken for a successful eviction`() =
        runTest {
            // Unlike the pass above, this key is not visible during removeResource at all — it
            // appears strictly *after* a first cachedKeys() read has already come back empty,
            // modelling the player's CacheDataSource (sharing the same live instance) writing in
            // the gap between that read and a confirmation re-read. One empty read is not evidence.
            val cache = FakeMedia3CacheGateway(setOf("movie-1"))
            cache.appearAfterFirstEmptyRead("movie-2")
            val purger = Media3DownloadStoragePurger(FakeMedia3DownloadIndexGateway(), cache)

            purger.evictCachedResources()

            assertEquals(
                "a write landing in the instant after the first empty read must still be caught " +
                    "by a confirmation re-read before the purge is announced complete",
                emptySet<String>(),
                cache.cachedKeys(),
            )
        }

    @Test
    fun `the last pass emptying the cache right as the timeout is reached is not reported as residue`() =
        runTest {
            // The producer stops refilling on exactly the removal that lands as the wait budget
            // runs out: the loop's final read sees the cache non-empty, removes it, and only then
            // times out. Reporting the count from before that last removal would fail a purge that
            // actually just succeeded.
            val pollCount = (
                Media3DownloadStoragePurger.AWAIT_EMPTY_TIMEOUT_MILLIS /
                    Media3DownloadStoragePurger.AWAIT_EMPTY_POLL_MILLIS
                ).toInt()
            val cache = FakeMedia3CacheGateway(setOf("movie-1"))
            cache.reappearsAfterEveryRemoval = "movie-1"
            cache.reappearCountRemaining = pollCount
            val purger = Media3DownloadStoragePurger(FakeMedia3DownloadIndexGateway(), cache)

            purger.evictCachedResources()

            assertEquals(
                "the removal that just landed must be trusted over a stale pre-removal count",
                emptySet<String>(),
                cache.cachedKeys(),
            )
        }

    @Test
    fun `a cache that keeps refilling fails instead of reporting an eviction that never converged`() =
        runTest {
            val cache = FakeMedia3CacheGateway(setOf("movie-1"))
            cache.reappearsAfterEveryRemoval = "movie-1"
            val purger = Media3DownloadStoragePurger(FakeMedia3DownloadIndexGateway(), cache)

            val thrown = runCatching { purger.evictCachedResources() }.exceptionOrNull()

            assertTrue(
                "attendu: LogoutPurgeException bornee dans le temps, obtenu: $thrown",
                thrown is LogoutPurgeException,
            )
        }

    @Test
    fun `evicting an already empty cache is a no-op`() = runTest {
        val cache = FakeMedia3CacheGateway(setOf("movie-1"))
        val purger = Media3DownloadStoragePurger(FakeMedia3DownloadIndexGateway(), cache)

        purger.evictCachedResources()
        purger.evictCachedResources()

        assertEquals(listOf("movie-1"), cache.removedKeys)
    }

    @Test
    fun `residue is reported while either the index or the cache still holds anything`() = runTest {
        assertTrue(
            Media3DownloadStoragePurger(
                FakeMedia3DownloadIndexGateway(setOf("movie-1")),
                FakeMedia3CacheGateway(),
            ).hasStorageResidue(),
        )
        assertTrue(
            Media3DownloadStoragePurger(
                FakeMedia3DownloadIndexGateway(),
                FakeMedia3CacheGateway(setOf("movie-1")),
            ).hasStorageResidue(),
        )
        assertFalse(
            Media3DownloadStoragePurger(
                FakeMedia3DownloadIndexGateway(),
                FakeMedia3CacheGateway(),
            ).hasStorageResidue(),
        )
    }
}

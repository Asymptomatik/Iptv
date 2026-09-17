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

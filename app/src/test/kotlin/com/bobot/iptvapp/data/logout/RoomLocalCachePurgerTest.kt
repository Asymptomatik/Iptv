package com.bobot.iptvapp.data.logout

import com.bobot.iptvapp.data.local.dao.FakeCatalogCacheDao
import com.bobot.iptvapp.data.local.dao.FakeDownloadDao
import com.bobot.iptvapp.data.local.dao.FakeEpgDao
import com.bobot.iptvapp.data.local.entity.CategoryEntity
import com.bobot.iptvapp.data.local.entity.CatalogSyncEntity
import com.bobot.iptvapp.data.local.entity.DownloadEntity
import com.bobot.iptvapp.data.local.entity.EpgProgramEntity
import com.bobot.iptvapp.domain.model.ContentType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RoomLocalCachePurger] — slice 1 of the logout purge (Room state).
 *
 * Uses the in-memory DAO fakes rather than mocks so the assertions are about rows actually being
 * gone, not about which DAO method was called.
 *
 * [testDispatcher] is handed to the purger *and* to `runTest`, so the `withContext(ioDispatcher)`
 * hop each method makes resolves against the same test scheduler instead of a second, never-advanced
 * one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomLocalCachePurgerTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val downloadDao = FakeDownloadDao()
    private val catalogCacheDao = FakeCatalogCacheDao()
    private val epgDao = FakeEpgDao()

    private val purger = RoomLocalCachePurger(
        downloadDao = downloadDao,
        catalogCacheDao = catalogCacheDao,
        epgDao = epgDao,
        ioDispatcher = testDispatcher,
    )

    private fun download(id: String) = DownloadEntity(
        downloadId = id,
        contentType = "MOVIE",
        contentId = id,
        title = "Titre $id",
        artworkUrl = null,
        streamUrl = "http://server:8080/movie/user/password/$id.mkv",
        state = "QUEUED",
        bytesDownloaded = 0L,
        contentLength = DownloadEntity.UNKNOWN_CONTENT_LENGTH,
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )

    @Test
    fun `purging the download index removes every row`() = runTest(testDispatcher) {
        downloadDao.upsert(download("movie-1"))
        downloadDao.upsert(download("movie-2"))

        purger.purgeDownloadIndex()

        assertEquals(emptyList<DownloadEntity>(), downloadDao.currentRows)
    }

    @Test
    fun `purging the download index twice is idempotent`() = runTest(testDispatcher) {
        downloadDao.upsert(download("movie-1"))

        purger.purgeDownloadIndex()
        purger.purgeDownloadIndex()

        assertEquals(emptyList<DownloadEntity>(), downloadDao.currentRows)
    }

    @Test
    fun `download residue count reflects the rows still indexed`() = runTest(testDispatcher) {
        downloadDao.upsert(download("movie-1"))
        downloadDao.upsert(download("movie-2"))

        assertEquals(2, purger.countDownloadResidue())

        purger.purgeDownloadIndex()

        assertEquals(0, purger.countDownloadResidue())
    }

    // ── Catalogue and EPG caches ──────────────────────────────────────────────

    @Test
    fun `purging the catalogue and EPG caches empties both, across every account`() = runTest(testDispatcher) {
        catalogCacheDao.upsertCategories(
            listOf(category(accountKey = "accountA"), category(accountKey = "accountB")),
        )
        catalogCacheDao.upsertSyncMarker(syncMarker(accountKey = "accountA"))
        epgDao.upsert(listOf(program(accountKey = "accountA"), program(accountKey = "accountB")))

        purger.purgeCatalogAndEpgCaches()

        assertEquals(
            emptyList<CategoryEntity>(),
            catalogCacheDao.getCategoriesByType("accountA", ContentType.MOVIE.name),
        )
        assertEquals(
            emptyList<CategoryEntity>(),
            catalogCacheDao.getCategoriesByType("accountB", ContentType.MOVIE.name),
        )
        assertNull(catalogCacheDao.syncedAtMillisOrNull("accountA", ContentType.MOVIE.name, "all"))
        assertEquals(emptyList<EpgProgramEntity>(), epgDao.currentPrograms)
    }

    @Test
    fun `purging the catalogue and EPG caches twice is idempotent`() = runTest(testDispatcher) {
        catalogCacheDao.upsertCategories(listOf(category(accountKey = "accountA")))
        epgDao.upsert(listOf(program(accountKey = "accountA")))

        purger.purgeCatalogAndEpgCaches()
        purger.purgeCatalogAndEpgCaches()

        assertEquals(
            emptyList<CategoryEntity>(),
            catalogCacheDao.getCategoriesByType("accountA", ContentType.MOVIE.name),
        )
        assertEquals(emptyList<EpgProgramEntity>(), epgDao.currentPrograms)
    }

    @Test
    fun `a failing EPG purge propagates so the caller can keep the logout pending`() = runTest(testDispatcher) {
        epgDao.failOnClear = IllegalStateException("disque plein")

        val thrown = runCatching { purger.purgeCatalogAndEpgCaches() }.exceptionOrNull()

        assertTrue("attendu: l'echec remonte au lieu d'etre avale", thrown is IllegalStateException)
    }

    private fun category(accountKey: String) = CategoryEntity(
        accountKey = accountKey,
        id = "cat-1",
        name = "Action",
        contentType = ContentType.MOVIE,
    )

    private fun syncMarker(accountKey: String) = CatalogSyncEntity(
        accountKey = accountKey,
        contentType = ContentType.MOVIE.name,
        scope = "all",
        syncedAtMillis = 1L,
    )

    private fun program(accountKey: String) = EpgProgramEntity(
        accountKey = accountKey,
        channelId = "chan-1",
        startMillis = 0L,
        title = "Programme",
        description = null,
        endMillis = 10L,
    )
}

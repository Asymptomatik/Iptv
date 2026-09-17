package com.bobot.iptvapp.data.logout

import com.bobot.iptvapp.data.local.dao.FakeCatalogCacheDao
import com.bobot.iptvapp.data.local.dao.FakeDownloadDao
import com.bobot.iptvapp.data.local.dao.FakeEpgDao
import com.bobot.iptvapp.data.local.entity.CategoryEntity
import com.bobot.iptvapp.data.local.entity.DownloadEntity
import com.bobot.iptvapp.data.preferences.FakeLogoutPurgeMarkerStore
import com.bobot.iptvapp.data.preferences.LogoutPurgeMarkerStore
import com.bobot.iptvapp.data.source.CredentialsProvider
import com.bobot.iptvapp.data.source.InMemoryCredentialsProvider
import com.bobot.iptvapp.domain.logout.LogoutPurgeException
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.download.purge.DownloadStoragePurger
import com.bobot.iptvapp.download.purge.FakeMedia3CacheGateway
import com.bobot.iptvapp.download.purge.FakeMedia3DownloadIndexGateway
import com.bobot.iptvapp.download.purge.Media3DownloadStoragePurger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DefaultLogoutPurger] — slice 5 of the logout purge.
 *
 * ## Real collaborators, not mocks
 * The purgers under the orchestrator are the *real* [Media3DownloadStoragePurger] and
 * [RoomLocalCachePurger], driven by the in-memory gateway/DAO fakes, and credentials go through the
 * real [InMemoryCredentialsProvider]. So "the downloads are gone" is asserted by looking at the
 * rows, not by verifying a call happened.
 *
 * ## How order is observed
 * Order is the one thing state alone cannot show — after a successful purge everything is empty
 * whatever sequence produced it. Each collaborator is therefore wrapped in a thin recorder that
 * appends its step name to a shared [journal]; the recorders delegate, they do not stub.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultLogoutPurgerTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val journal = mutableListOf<String>()

    private val downloadDao = FakeDownloadDao()
    private val catalogCacheDao = FakeCatalogCacheDao()
    private val epgDao = FakeEpgDao()
    private val indexGateway = FakeMedia3DownloadIndexGateway()
    private val cacheGateway = FakeMedia3CacheGateway()
    private val credentials = InMemoryCredentialsProvider()
    private val markerStore = FakeLogoutPurgeMarkerStore()

    private val accountA = XtreamCredentials("http://a.example:8080", "userA", "passA")
    private val accountB = XtreamCredentials("http://b.example:8080", "userB", "passB")

    // ── Recorders ─────────────────────────────────────────────────────────────

    private inner class RecordingMarkerStore(
        private val delegate: LogoutPurgeMarkerStore,
    ) : LogoutPurgeMarkerStore {
        override suspend fun isPurgePending() = delegate.isPurgePending()
        override suspend fun markPurgePending() {
            journal += "mark-pending"
            delegate.markPurgePending()
        }
        override suspend fun clearPurgePending() {
            journal += "clear-pending"
            delegate.clearPurgePending()
        }
    }

    private inner class RecordingStoragePurger(
        private val delegate: DownloadStoragePurger,
    ) : DownloadStoragePurger {
        override suspend fun removeAllDownloadsAndAwaitEmptyIndex() {
            journal += "media3-index"
            delegate.removeAllDownloadsAndAwaitEmptyIndex()
        }
        override suspend fun evictCachedResources() {
            journal += "media3-cache"
            delegate.evictCachedResources()
        }
        override suspend fun hasStorageResidue() = delegate.hasStorageResidue()
    }

    private inner class RecordingLocalCachePurger(
        private val delegate: LocalCachePurger,
    ) : LocalCachePurger {
        override suspend fun purgeDownloadIndex() {
            journal += "room-downloads"
            delegate.purgeDownloadIndex()
        }
        override suspend fun purgeCatalogAndEpgCaches() {
            journal += "room-catalog-epg"
            delegate.purgeCatalogAndEpgCaches()
        }
        override suspend fun countDownloadResidue() = delegate.countDownloadResidue()
    }

    private inner class RecordingCredentialsProvider(
        private val delegate: CredentialsProvider,
    ) : CredentialsProvider {
        override suspend fun getCredentials(): XtreamCredentials? = delegate.getCredentials()
        override fun observeCredentials(): Flow<XtreamCredentials?> = delegate.observeCredentials()
        override suspend fun setCredentials(credentials: XtreamCredentials) =
            delegate.setCredentials(credentials)
        override suspend fun clearCredentials() {
            journal += "credentials"
            delegate.clearCredentials()
        }
    }

    // ── Subject ───────────────────────────────────────────────────────────────

    private val storagePurger = Media3DownloadStoragePurger(indexGateway, cacheGateway)
    private val localCachePurger = RoomLocalCachePurger(
        downloadDao = downloadDao,
        catalogCacheDao = catalogCacheDao,
        epgDao = epgDao,
        ioDispatcher = testDispatcher,
    )

    private val purger = DefaultLogoutPurger(
        markerStore = RecordingMarkerStore(markerStore),
        downloadStoragePurger = RecordingStoragePurger(storagePurger),
        localCachePurger = RecordingLocalCachePurger(localCachePurger),
        credentialsProvider = RecordingCredentialsProvider(credentials),
    )

    private fun download(id: String) = DownloadEntity(
        downloadId = id,
        contentType = "MOVIE",
        contentId = id,
        title = "Titre $id",
        artworkUrl = null,
        // The URL carries the account's username and password — the reason this table cannot be
        // allowed to outlive the session that wrote it.
        streamUrl = "http://a.example:8080/movie/userA/passA/$id.mkv",
        state = "COMPLETED",
        bytesDownloaded = 10L,
        contentLength = 10L,
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )

    private suspend fun seedAccountAWithDownloads() {
        credentials.setCredentials(accountA)
        downloadDao.upsert(download("movie-1"))
        catalogCacheDao.upsertCategories(
            listOf(CategoryEntity("accountA", "cat-1", "Action", ContentType.MOVIE)),
        )
    }

    // ── Order ─────────────────────────────────────────────────────────────────

    @Test
    fun `logging out runs every step in the mandated order, credentials last`() =
        runTest(testDispatcher) {
            seedAccountAWithDownloads()

            purger.logOut()

            assertEquals(
                listOf(
                    "mark-pending",
                    "media3-index",
                    "media3-cache",
                    "room-downloads",
                    "room-catalog-epg",
                    "credentials",
                    "clear-pending",
                ),
                journal,
            )
        }

    @Test
    fun `logging out leaves no local trace of the account`() = runTest(testDispatcher) {
        seedAccountAWithDownloads()

        purger.logOut()

        assertEquals(emptyList<DownloadEntity>(), downloadDao.currentRows)
        assertEquals(
            emptyList<CategoryEntity>(),
            catalogCacheDao.getCategoriesByType("accountA", ContentType.MOVIE.name),
        )
        assertNull(credentials.getCredentials())
        assertFalse(markerStore.pending)
    }

    @Test
    fun `logging out twice is idempotent`() = runTest(testDispatcher) {
        seedAccountAWithDownloads()

        purger.logOut()
        purger.logOut()

        assertEquals(emptyList<DownloadEntity>(), downloadDao.currentRows)
        assertFalse(markerStore.pending)
    }

    // ── Failure and retry ─────────────────────────────────────────────────────

    @Test
    fun `a Media3 index that never drains keeps the purge pending and the credentials intact`() =
        runTest(testDispatcher) {
            seedAccountAWithDownloads()
            indexGateway.failOnRemoveAll = IllegalStateException("Media3 indisponible")

            val thrown = runCatching { purger.logOut() }.exceptionOrNull()

            assertTrue("attendu: LogoutPurgeException, obtenu: $thrown", thrown is LogoutPurgeException)
            assertTrue("le marqueur doit rester actif", markerStore.pending)
            assertNotNull("les credentials ne doivent pas être effacés", credentials.getCredentials())
        }

    @Test
    fun `a failing Room purge keeps the purge pending and the credentials intact`() =
        runTest(testDispatcher) {
            seedAccountAWithDownloads()
            downloadDao.failOnClear = IllegalStateException("disque plein")

            val thrown = runCatching { purger.logOut() }.exceptionOrNull()

            assertTrue("attendu: LogoutPurgeException, obtenu: $thrown", thrown is LogoutPurgeException)
            assertTrue(markerStore.pending)
            assertNotNull(credentials.getCredentials())
            assertEquals(1, downloadDao.currentRows.size)
        }

    @Test
    fun `retrying after a failure completes the purge and clears the marker`() =
        runTest(testDispatcher) {
            seedAccountAWithDownloads()
            downloadDao.failOnClear = IllegalStateException("disque plein")
            runCatching { purger.logOut() }

            downloadDao.failOnClear = null
            purger.logOut()

            assertEquals(emptyList<DownloadEntity>(), downloadDao.currentRows)
            assertNull(credentials.getCredentials())
            assertFalse(markerStore.pending)
        }

    // ── Recovery ──────────────────────────────────────────────────────────────

    @Test
    fun `recovery resumes a purge the marker says is still owed`() = runTest(testDispatcher) {
        seedAccountAWithDownloads()
        markerStore.markPurgePending()
        journal.clear()

        val ran = purger.recoverIfNeeded()

        assertTrue(ran)
        assertEquals(emptyList<DownloadEntity>(), downloadDao.currentRows)
        assertNull(credentials.getCredentials())
        assertFalse(markerStore.pending)
    }

    @Test
    fun `recovery does nothing on a healthy signed-in install`() = runTest(testDispatcher) {
        seedAccountAWithDownloads()
        journal.clear()

        val ran = purger.recoverIfNeeded()

        assertFalse(ran)
        assertEquals(emptyList<String>(), journal)
        // A signed-in user's downloads are none of recovery's business.
        assertEquals(1, downloadDao.currentRows.size)
        assertNotNull(credentials.getCredentials())
    }

    @Test
    fun `recovery cleans up the legacy state of no credentials but leftover downloads`() =
        runTest(testDispatcher) {
            // An install logged out before this feature existed: the credentials went, the
            // downloads did not, and there is no marker to resume from.
            downloadDao.upsert(download("movie-1"))

            val ran = purger.recoverIfNeeded()

            assertTrue(ran)
            assertEquals(emptyList<DownloadEntity>(), downloadDao.currentRows)
            assertFalse(markerStore.pending)
        }

    @Test
    fun `recovery treats leftover Media3 storage as residue too`() = runTest(testDispatcher) {
        val purgerOverResidue = DefaultLogoutPurger(
            markerStore = RecordingMarkerStore(markerStore),
            downloadStoragePurger = RecordingStoragePurger(
                Media3DownloadStoragePurger(
                    FakeMedia3DownloadIndexGateway(setOf("movie-1")),
                    FakeMedia3CacheGateway(setOf("movie-1")),
                ),
            ),
            localCachePurger = RecordingLocalCachePurger(localCachePurger),
            credentialsProvider = RecordingCredentialsProvider(credentials),
        )

        assertTrue(purgerOverResidue.recoverIfNeeded())
    }

    @Test
    fun `recovery does nothing on a clean logged-out install`() = runTest(testDispatcher) {
        val ran = purger.recoverIfNeeded()

        assertFalse(ran)
        assertEquals(emptyList<String>(), journal)
    }

    // ── Cross-account ─────────────────────────────────────────────────────────

    @Test
    fun `nothing from the previous account survives into the next session`() =
        runTest(testDispatcher) {
            seedAccountAWithDownloads()
            purger.logOut()

            credentials.setCredentials(accountB)

            assertEquals(emptyList<DownloadEntity>(), downloadDao.currentRows)
            assertEquals(0, localCachePurger.countDownloadResidue())
            assertEquals(
                emptyList<CategoryEntity>(),
                catalogCacheDao.getCategoriesByType("accountA", ContentType.MOVIE.name),
            )
        }
}

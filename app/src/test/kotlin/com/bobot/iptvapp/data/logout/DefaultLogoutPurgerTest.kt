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

    /**
     * @property restoreKeyOnEvictCount how many of the *first* evictions end with a resource put
     *   straight back into the cache. `1` is the realistic single late release, [Int.MAX_VALUE] a
     *   residue that never goes away.
     */
    private inner class RecordingStoragePurger(
        private val delegate: DownloadStoragePurger,
    ) : DownloadStoragePurger {
        var restoreKeyOnEvictCount: Int = 0
        private var evictCount = 0

        override suspend fun removeAllDownloadsAndAwaitEmptyIndex() {
            journal += "media3-index"
            delegate.removeAllDownloadsAndAwaitEmptyIndex()
        }
        override suspend fun evictCachedResources() {
            journal += "media3-cache"
            delegate.evictCachedResources()
            evictCount++
            if (evictCount <= restoreKeyOnEvictCount) cacheGateway.restoreResource("movie-late")
        }
        override suspend fun hasStorageResidue() = delegate.hasStorageResidue()
    }

    /**
     * @property resurrectRowOnPurgeCount how many of the *first* `purgeDownloadIndex()` calls end
     *   with a credential-bearing row written straight back — a
     *   [com.bobot.iptvapp.download.DownloadTracker] callback that read the row before `clearAll()`
     *   and wrote it after. Scaled like [RecordingStoragePurger.restoreKeyOnEvictCount].
     */
    private inner class RecordingLocalCachePurger(
        private val delegate: LocalCachePurger,
    ) : LocalCachePurger {
        var resurrectRowOnPurgeCount: Int = 0
        var resurrectCatalogOnPurgeCount: Int = 0
        private var catalogPurgeCount = 0
        private var purgeCount = 0

        override suspend fun purgeDownloadIndex() {
            journal += "room-downloads"
            delegate.purgeDownloadIndex()
            purgeCount++
            if (purgeCount <= resurrectRowOnPurgeCount) downloadDao.upsert(download("movie-late"))
        }
        /**
         * @see resurrectCatalogOnPurgeCount — the same late-write shape, one store up: a catalogue
         *   fetch that was in flight when the logout started, landing after the tables were cleared.
         */
        override suspend fun purgeCatalogAndEpgCaches() {
            journal += "room-catalog-epg"
            delegate.purgeCatalogAndEpgCaches()
            catalogPurgeCount++
            if (catalogPurgeCount <= resurrectCatalogOnPurgeCount) {
                catalogCacheDao.upsertCategories(
                    listOf(CategoryEntity("accountA", "cat-late", "Action", ContentType.MOVIE)),
                )
            }
        }
        override suspend fun countDownloadResidue() = delegate.countDownloadResidue()

        override suspend fun countCatalogResidue() = delegate.countCatalogResidue()
    }

    /** Journals the synchronous memo invalidation, so its position in the sequence is assertable. */
    private inner class RecordingSessionCacheInvalidator : SessionCacheInvalidator {
        var invalidations = 0
        override suspend fun invalidateSessionCaches() {
            invalidations++
            journal += "invalidate-memos"
        }
    }

    /**
     * Journals the player stop, so its position ahead of the Media3 storage steps is assertable —
     * see [ActivePlaybackStopper]'s KDoc for why it has to run before them.
     */
    private inner class RecordingActivePlaybackStopper : ActivePlaybackStopper {
        var stops = 0
        override suspend fun stopActivePlayback() {
            stops++
            journal += "stop-playback"
        }
    }

    /**
     * Commits credentials and marker together, or throws and commits neither — mirroring
     * [LogoutFinalizer]'s atomicity contract, which is the property these tests are about.
     *
     * @property failWith when set, the write throws instead of committing.
     */
    private inner class RecordingLogoutFinalizer : LogoutFinalizer {
        var failWith: Throwable? = null

        override suspend fun finalizeLogout() {
            failWith?.let { throw it }
            journal += "finalize"
            credentials.clearCredentials()
            markerStore.clearPurgePending()
        }
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

    private val recordingStoragePurger = RecordingStoragePurger(storagePurger)
    private val recordingLocalCachePurger = RecordingLocalCachePurger(localCachePurger)
    private val recordingInvalidator = RecordingSessionCacheInvalidator()
    private val recordingPlaybackStopper = RecordingActivePlaybackStopper()
    private val recordingFinalizer = RecordingLogoutFinalizer()

    private val purger = DefaultLogoutPurger(
        markerStore = RecordingMarkerStore(markerStore),
        downloadStoragePurger = recordingStoragePurger,
        localCachePurger = recordingLocalCachePurger,
        sessionCacheInvalidator = recordingInvalidator,
        activePlaybackStopper = recordingPlaybackStopper,
        logoutFinalizer = recordingFinalizer,
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
                    "stop-playback",
                    "invalidate-memos",
                    "media3-index",
                    "media3-cache",
                    "room-downloads",
                    "room-catalog-epg",
                    "finalize",
                ),
                journal,
            )
        }

    @Test
    fun `active playback is stopped before the Media3 storage steps run, on every replay`() =
        runTest(testDispatcher) {
            // A player left open through logout shares the live cache instance the storage purge is
            // trying to empty — see [ActivePlaybackStopper]. Stopping it after eviction already ran
            // would let it write behind the check that just declared the cache empty; stopping it
            // before removes the producer instead of racing it.
            seedAccountAWithDownloads()
            recordingStoragePurger.restoreKeyOnEvictCount = 1

            purger.logOut()

            assertEquals(2, recordingPlaybackStopper.stops)
            val stopIndexes = journal.withIndex().filter { it.value == "stop-playback" }.map { it.index }
            val evictIndexes = journal.withIndex().filter { it.value == "media3-cache" }.map { it.index }
            assertEquals(2, stopIndexes.size)
            assertEquals(2, evictIndexes.size)
            stopIndexes.zip(evictIndexes).forEach { (stopIndex, evictIndex) ->
                assertTrue(
                    "each replay must stop playback before it evicts the cache, not after",
                    stopIndex < evictIndex,
                )
            }
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
            sessionCacheInvalidator = RecordingSessionCacheInvalidator(),
            activePlaybackStopper = RecordingActivePlaybackStopper(),
            logoutFinalizer = RecordingLogoutFinalizer(),
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

    // ── Residue verified before the marker is cleared (review finding H3) ─────
    //
    // Every step of the purge can succeed and still leave the account on disk: the purge walks
    // each store once, and a Media3 callback that was already in flight lands *behind* it. The
    // steps have no way to notice — the only moment the whole picture exists is after the last
    // one, which is where the marker used to be cleared unconditionally.

    @Test
    fun `a row written back after the table was cleared is caught before the marker goes`() =
        runTest(testDispatcher) {
            seedAccountAWithDownloads()
            // One late DownloadTracker callback: it read the row before clearAll() and wrote it
            // back after. Every step still reports success.
            recordingLocalCachePurger.resurrectRowOnPurgeCount = 1

            purger.logOut()

            assertEquals(
                "the resurrected row carries the account's password in its URL",
                emptyList<DownloadEntity>(),
                downloadDao.currentRows,
            )
            assertFalse(markerStore.pending)
            assertEquals(
                "the purge must re-walk the stores rather than trust the pass that missed it",
                2,
                journal.count { it == "room-downloads" },
            )
            assertEquals("finalize", journal.last())
        }

    @Test
    fun `a resource re-cached after the eviction is caught before the marker goes`() =
        runTest(testDispatcher) {
            seedAccountAWithDownloads()
            recordingStoragePurger.restoreKeyOnEvictCount = 1

            purger.logOut()

            assertFalse(markerStore.pending)
            assertFalse("the cache must end up empty", storagePurger.hasStorageResidue())
            assertEquals(2, journal.count { it == "media3-cache" })
        }

    @Test
    fun `Room residue that keeps coming back fails the purge instead of clearing the marker`() =
        runTest(testDispatcher) {
            seedAccountAWithDownloads()
            recordingLocalCachePurger.resurrectRowOnPurgeCount = Int.MAX_VALUE

            val thrown = runCatching { purger.logOut() }.exceptionOrNull()

            assertTrue("attendu: LogoutPurgeException, obtenu: $thrown", thrown is LogoutPurgeException)
            assertTrue("le marqueur doit rester actif", markerStore.pending)
            assertEquals(0, journal.count { it == "finalize" })
        }

    @Test
    fun `Media3 residue that keeps coming back fails the purge instead of clearing the marker`() =
        runTest(testDispatcher) {
            seedAccountAWithDownloads()
            recordingStoragePurger.restoreKeyOnEvictCount = Int.MAX_VALUE

            val thrown = runCatching { purger.logOut() }.exceptionOrNull()

            assertTrue("attendu: LogoutPurgeException, obtenu: $thrown", thrown is LogoutPurgeException)
            assertTrue(markerStore.pending)
            assertEquals(0, journal.count { it == "finalize" })
        }

    @Test
    fun `a catalogue row written back after the caches were cleared is caught before the marker goes`() =
        runTest(testDispatcher) {
            seedAccountAWithDownloads()
            // A getMovies/getLiveChannels call that was already in flight when the logout started,
            // landing its write after purgeCatalogAndEpgCaches() walked past. Every step still
            // reports success, and the downloads index — the only store the check used to read —
            // is genuinely empty, so nothing else can catch this.
            recordingLocalCachePurger.resurrectCatalogOnPurgeCount = 1

            purger.logOut()

            assertEquals(
                "the catalogue rows name the previous account and must not outlive it",
                0,
                localCachePurger.countCatalogResidue(),
            )
            assertFalse(markerStore.pending)
            assertEquals(2, journal.count { it == "room-catalog-epg" })
        }

    @Test
    fun `catalogue residue that keeps coming back fails the purge instead of announcing success`() =
        runTest(testDispatcher) {
            seedAccountAWithDownloads()
            recordingLocalCachePurger.resurrectCatalogOnPurgeCount = Int.MAX_VALUE

            val thrown = runCatching { purger.logOut() }.exceptionOrNull()

            assertTrue("attendu: LogoutPurgeException, obtenu: $thrown", thrown is LogoutPurgeException)
            assertTrue("le marqueur doit rester actif", markerStore.pending)
            assertEquals(0, journal.count { it == "finalize" })
        }

    @Test
    fun `the in-memory catalogue caches are invalidated by the purge itself, before anything is cleared`() =
        runTest(testDispatcher) {
            seedAccountAWithDownloads()
            // Two passes, so the invalidation is shown to be re-applied rather than done once at
            // the top: the producers a replay is racing are still running during the replay.
            recordingLocalCachePurger.resurrectRowOnPurgeCount = 1

            purger.logOut()

            assertTrue(
                "the memos must be dropped before the stores, not by the credentials observer " +
                    "afterwards — until then the previous account is still served from RAM",
                journal.indexOf("invalidate-memos") < journal.indexOf("room-catalog-epg"),
            )
            assertEquals(2, recordingInvalidator.invalidations)
        }

    // ── Terminal state ────────────────────────────────────────────────────────

    @Test
    fun `a finalization that fails leaves the account signed in rather than half-erased`() =
        runTest(testDispatcher) {
            seedAccountAWithDownloads()
            recordingFinalizer.failWith = IllegalStateException("disque plein")

            val thrown = runCatching { purger.logOut() }.exceptionOrNull()

            assertTrue("attendu: LogoutPurgeException, obtenu: $thrown", thrown is LogoutPurgeException)
            assertTrue("le marqueur doit rester actif", markerStore.pending)
            assertEquals(
                "credentials and marker are one write: a failure must roll back to signed-in, " +
                    "not leave Réglages claiming a session whose credentials are already gone",
                accountA,
                credentials.getCredentials(),
            )
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

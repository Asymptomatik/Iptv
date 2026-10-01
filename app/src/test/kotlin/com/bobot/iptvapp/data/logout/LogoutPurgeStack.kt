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
import com.bobot.iptvapp.domain.logout.LogoutPurger
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.download.purge.DownloadStoragePurger
import com.bobot.iptvapp.download.purge.FakeMedia3CacheGateway
import com.bobot.iptvapp.download.purge.FakeMedia3DownloadIndexGateway
import com.bobot.iptvapp.download.purge.Media3DownloadStoragePurger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow

/**
 * The whole logout purge assembled out of in-memory fakes, with every store crossing recorded in a
 * shared [journal] and one step that a test can hold open.
 *
 * Shared by the tests that have to observe *ordering between two callers* rather than the purge's
 * own behaviour — [LogoutCoordinatorTest] and
 * [com.bobot.iptvapp.data.repository.DownloadPurgeRaceTest]. The purger underneath is the real
 * [DefaultLogoutPurger] over the real [Media3DownloadStoragePurger] and [RoomLocalCachePurger], so
 * these tests fail when the *actual* sequencing breaks, not when a call count changes.
 */
class LogoutPurgeStack(ioDispatcher: CoroutineDispatcher) {

    /** Every step crossing, in the order it really happened, across all callers. */
    val journal = mutableListOf<String>()

    val downloadDao = FakeDownloadDao()
    val catalogCacheDao = FakeCatalogCacheDao()
    val epgDao = FakeEpgDao()
    val indexGateway = FakeMedia3DownloadIndexGateway()
    val cacheGateway = FakeMedia3CacheGateway()
    val credentials = InMemoryCredentialsProvider()
    val markerStore = FakeLogoutPurgeMarkerStore()

    /**
     * When set, the *first* Media3 index removal suspends on it. That is the purge's longest step
     * in production (it waits the index out), so it is the realistic place for a second caller to
     * arrive while a purge is mid-flight.
     */
    var gateFirstMedia3Removal: CompletableDeferred<Unit>? = null

    /**
     * When set, the atomic finalization throws it instead of committing. Models the one failure the
     * old two-write sequence could not survive coherently — see [LogoutFinalizer].
     */
    var failFinalizationWith: Throwable? = null

    private val storagePurger = GatingStoragePurger(
        Media3DownloadStoragePurger(indexGateway, cacheGateway),
    )

    /** Exposed so callers can seed residue or assert on the Room side directly. */
    val localCachePurger = ResidueInjectingCachePurger(
        RoomLocalCachePurger(
            downloadDao = downloadDao,
            catalogCacheDao = catalogCacheDao,
            epgDao = epgDao,
            ioDispatcher = ioDispatcher,
        ),
    )

    /** Downloaded online subtitles; not journaled, so the callers' journals keep their shape. */
    val downloadedSubtitles = FakeDownloadedSubtitlePurger()

    val purger: LogoutPurger = DefaultLogoutPurger(
        markerStore = RecordingMarkerStore(markerStore),
        downloadStoragePurger = storagePurger,
        localCachePurger = localCachePurger,
        sessionCacheInvalidator = RecordingSessionCacheInvalidator(),
        activePlaybackStopper = RecordingActivePlaybackStopper(),
        logoutFinalizer = RecordingLogoutFinalizer(),
        credentialsProvider = RecordingCredentialsProvider(credentials),
        downloadedSubtitlePurger = downloadedSubtitles,
    )

    val accountA = XtreamCredentials("http://a.example:8080", "userA", "passA")

    fun downloadRow(id: String) = DownloadEntity(
        downloadId = id,
        contentType = "MOVIE",
        contentId = id,
        title = "Titre $id",
        // Credentials in the path — the reason no row may outlive the session that wrote it.
        streamUrl = "http://a.example:8080/movie/userA/passA/$id.mkv",
        artworkUrl = null,
        state = "COMPLETED",
        bytesDownloaded = 10L,
        contentLength = 10L,
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )

    suspend fun seedSignedInAccountWithDownloads() {
        credentials.setCredentials(accountA)
        downloadDao.upsert(downloadRow("movie-1"))
        catalogCacheDao.upsertCategories(
            listOf(CategoryEntity("accountA", "cat-1", "Action", ContentType.MOVIE)),
        )
    }

    // ── Recorders ─────────────────────────────────────────────────────────────

    private inner class GatingStoragePurger(
        private val delegate: DownloadStoragePurger,
    ) : DownloadStoragePurger {
        private var gateUsed = false

        override suspend fun removeAllDownloadsAndAwaitEmptyIndex() {
            journal += "media3-index:enter"
            if (!gateUsed) {
                gateUsed = true
                gateFirstMedia3Removal?.await()
            }
            delegate.removeAllDownloadsAndAwaitEmptyIndex()
            journal += "media3-index:exit"
        }

        override suspend fun evictCachedResources() {
            journal += "media3-cache"
            delegate.evictCachedResources()
        }

        override suspend fun hasStorageResidue() = delegate.hasStorageResidue()
    }

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

    /**
     * Models the one thing no fake covered before: a [com.bobot.iptvapp.download.DownloadTracker]
     * callback that read a row before `clearAll()` and wrote it back after.
     *
     * [resurrectOnPurgeCount] is how many of the *first* `purgeDownloadIndex()` calls end with the
     * row put straight back — `1` is the realistic single late callback, a large value models a
     * residue that never goes away.
     */
    inner class ResidueInjectingCachePurger(
        private val delegate: LocalCachePurger,
    ) : LocalCachePurger {

        var resurrectOnPurgeCount: Int = 0
        var resurrectedRowId: String = "movie-late"

        /**
         * Same shape, one store up: how many of the first `purgeCatalogAndEpgCaches()` calls end
         * with a catalogue row written straight back — a catalogue fetch that was already in flight
         * when the logout started, completing after the tables were cleared.
         */
        var resurrectCatalogOnPurgeCount: Int = 0

        private var purgeCount = 0
        private var catalogPurgeCount = 0

        override suspend fun purgeDownloadIndex() {
            journal += "room-downloads"
            delegate.purgeDownloadIndex()
            purgeCount++
            if (purgeCount <= resurrectOnPurgeCount) {
                downloadDao.upsert(downloadRow(resurrectedRowId))
            }
        }

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

    /** Journals the synchronous memo invalidation so its *position* in the sequence is assertable. */
    private inner class RecordingSessionCacheInvalidator : SessionCacheInvalidator {
        override suspend fun invalidateSessionCaches() {
            journal += "invalidate-memos"
        }
    }

    /** Journals the player stop so its *position* ahead of the Media3 storage steps is assertable. */
    private inner class RecordingActivePlaybackStopper : ActivePlaybackStopper {
        override suspend fun stopActivePlayback() {
            journal += "stop-playback"
        }
    }

    /**
     * Commits credentials and marker together, or throws and commits neither — the fake mirrors
     * [LogoutFinalizer]'s atomicity contract, which is the property under test.
     */
    private inner class RecordingLogoutFinalizer : LogoutFinalizer {
        override suspend fun finalizeLogout() {
            failFinalizationWith?.let { throw it }
            journal += "finalize"
            credentials.clearCredentials()
            markerStore.clearPurgePending()
        }
    }
}

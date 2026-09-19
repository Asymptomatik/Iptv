package com.bobot.iptvapp.data.repository

import com.bobot.iptvapp.data.local.entity.DownloadEntity
import com.bobot.iptvapp.data.logout.LogoutCoordinator
import com.bobot.iptvapp.data.logout.LogoutPurgeStack
import com.bobot.iptvapp.domain.model.DownloadContentType
import com.bobot.iptvapp.domain.model.DownloadRequestData
import com.bobot.iptvapp.domain.model.DownloadRequestId
import com.bobot.iptvapp.download.FakeDownloadCommander
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Adversarial regression test for the second review's Blocker #1: the enqueue TOCTOU race
 * extended to Media3's own acceptance latency.
 *
 * [com.bobot.iptvapp.download.IptvDownloadService.Commander.enqueue] hands Media3 a command
 * through `DownloadService`'s intent-dispatch machinery, which is asynchronous relative to the call
 * that sends it — unlike the purge's [com.bobot.iptvapp.download.purge.Media3DownloadIndexGateway.removeAllDownloads],
 * which goes straight to the singleton `DownloadManager`. A [LogoutCoordinator] lock around the
 * check-write-command sequence alone is not enough: it closes the window between the marker check
 * and the Room write, but not the window between "the add command was sent" and "Media3 actually
 * filed it" — and that second window is exactly where a concurrent purge's `removeAllDownloads`
 * can observe an empty index, declare success, and let the late add land afterwards as an
 * orphaned, credential-bearing transfer for an account that has already been signed out of.
 *
 * [LogoutPurgeStack.indexGateway] is shared between the enqueue and the purge, exactly as the real
 * [com.bobot.iptvapp.di.LogoutModule] shares one [com.bobot.iptvapp.download.purge.Media3DownloadIndexGateway]
 * singleton between [DownloadRepositoryImpl] and [com.bobot.iptvapp.download.purge.Media3DownloadStoragePurger] —
 * the race only exists because both sides look at the same live index.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadPurgeRaceTest {

    private val testDispatcher = StandardTestDispatcher()
    private val applicationScope = CoroutineScope(SupervisorJob() + testDispatcher)
    private val stack = LogoutPurgeStack(testDispatcher)

    private val coordinator = LogoutCoordinator(
        logoutPurger = stack.purger,
        markerStore = stack.markerStore,
        applicationScope = applicationScope,
    )

    /** When set, every [FakeDownloadCommander.enqueue] lands behind this gate rather than immediately. */
    private var landingGate: CompletableDeferred<Unit>? = null

    private val commander = FakeDownloadCommander(
        onEnqueue = { id -> stack.indexGateway.enqueueDownload(id, gate = landingGate) },
    )

    private val repository = DownloadRepositoryImpl(
        downloadDao = stack.downloadDao,
        downloadService = commander,
        logoutCoordinator = coordinator,
        media3IndexGateway = stack.indexGateway,
        ioDispatcher = testDispatcher,
    )

    @After
    fun tearDown() {
        applicationScope.cancel()
    }

    @Test
    fun `an enqueue whose Media3 acceptance is still landing blocks a concurrent logout instead of letting it declare an empty index`() =
        runTest(testDispatcher) {
            stack.seedSignedInAccountWithDownloads()
            landingGate = CompletableDeferred()

            val request = DownloadRequestData(
                contentType = DownloadContentType.MOVIE,
                contentId = "movie-late",
                title = "Titre",
                artworkUrl = null,
                streamUrl = "http://a.example:8080/movie/userA/passA/movie-late.mkv",
            )

            var downloadId: String? = null
            val enqueueJob = launch { downloadId = repository.enqueue(request) }
            advanceUntilIdle()

            assertNull("enqueue must still be suspended awaiting Media3's acceptance", downloadId)

            val logoutJob = launch { coordinator.logOut() }
            advanceUntilIdle()

            assertEquals(
                "the purge must not run a single step while the enqueue still holds the barrier",
                emptyList<String>(),
                stack.journal,
            )

            landingGate!!.complete(Unit)
            advanceUntilIdle()
            enqueueJob.join()
            logoutJob.join()

            assertNotNull("the enqueue must complete once Media3 has observably accepted it", downloadId)
            assertEquals(
                "the download must have been purged, not left behind Media3's late acceptance",
                emptyList<DownloadEntity>(),
                stack.downloadDao.currentRows,
            )
            assertEquals(0, stack.indexGateway.countIndexedDownloads())
            assertNull(stack.credentials.getCredentials())
        }

    @Test
    fun `an add that never observably lands within the acceptance timeout is cancelled before the barrier releases, even if it lands after a concurrent purge`() =
        runTest(testDispatcher) {
            stack.seedSignedInAccountWithDownloads()

            val cancellingCommander = FakeDownloadCommander(
                onEnqueue = { id -> stack.indexGateway.enqueueDownloadPendingIndefinitely(id) },
                onRemove = { id -> stack.indexGateway.cancelPendingAdd(id) },
            )
            val repositoryWithCancellingCommander = DownloadRepositoryImpl(
                downloadDao = stack.downloadDao,
                downloadService = cancellingCommander,
                logoutCoordinator = coordinator,
                media3IndexGateway = stack.indexGateway,
                ioDispatcher = testDispatcher,
            )

            val request = DownloadRequestData(
                contentType = DownloadContentType.MOVIE,
                contentId = "movie-late",
                title = "Titre",
                artworkUrl = null,
                streamUrl = "http://a.example:8080/movie/userA/passA/movie-late.mkv",
            )

            var downloadId: String? = null
            val enqueueJob = launch { downloadId = repositoryWithCancellingCommander.enqueue(request) }
            advanceUntilIdle()
            enqueueJob.join()

            assertNull("the add never observably landed, so the enqueue must report failure", downloadId)

            // The barrier is free once the compensation has been sent: a concurrent purge now runs
            // to completion, believing the index empty.
            val logoutJob = launch { coordinator.logOut() }
            advanceUntilIdle()
            logoutJob.join()

            // The delayed Intent finally lands — after both the timeout and the purge.
            stack.indexGateway.landLateAdd(DownloadRequestId.create(DownloadContentType.MOVIE, "movie-late"))

            assertEquals(
                "the ordered compensation must have cancelled the late add — nothing may survive",
                0,
                stack.indexGateway.countIndexedDownloads(),
            )
            assertEquals(emptyList<DownloadEntity>(), stack.downloadDao.currentRows)
            assertNull(stack.credentials.getCredentials())
        }

    @Test
    fun `a resume arriving while the purge holds the barrier is refused instead of re-caching the account`() =
        runTest(testDispatcher) {
            stack.seedSignedInAccountWithDownloads()
            stack.gateFirstMedia3Removal = CompletableDeferred()

            val logoutJob = launch { coordinator.logOut() }
            advanceUntilIdle()
            assertEquals(
                "the purge must be parked inside the index step, holding the barrier",
                listOf("mark-pending", "stop-playback", "invalidate-memos", "media3-index:enter"),
                stack.journal,
            )

            // The worst of the three to let through: a resume restarts a transfer, re-creating both
            // the index entry the purge is waiting out and the cache spans it is about to evict.
            var resumed: Boolean? = null
            var paused: Boolean? = null
            launch { resumed = repository.resume("movie-1") }
            launch { paused = repository.pause("movie-1") }
            advanceUntilIdle()

            assertEquals(false, resumed)
            assertEquals(false, paused)
            assertEquals(
                "no Media3 command may be sent behind a purge already walking its steps",
                emptyList<String>(),
                commander.commands,
            )

            stack.gateFirstMedia3Removal!!.complete(Unit)
            advanceUntilIdle()
            logoutJob.join()

            assertNull(stack.credentials.getCredentials())
        }

    @Test
    fun `pause and resume are refused while a purge is merely owed, and accepted once it is not`() =
        runTest(testDispatcher) {
            // No purge running — just the marker an interrupted one left behind. The barrier is
            // free, so only the marker check can catch this.
            stack.markerStore.markPurgePending()

            assertEquals(false, repository.pause("movie-1"))
            assertEquals(false, repository.resume("movie-1"))
            assertEquals(emptyList<String>(), commander.commands)

            stack.markerStore.clearPurgePending()

            assertEquals(true, repository.pause("movie-1"))
            assertEquals(true, repository.resume("movie-1"))
            assertEquals(listOf("pause:movie-1", "resume:movie-1"), commander.commands)
        }

    @Test
    fun `a removal is never refused, because deleting can only ever help the purge`() =
        runTest(testDispatcher) {
            stack.markerStore.markPurgePending()

            repository.remove("movie-1")

            assertEquals(
                "refusing a removal would block the one action that helps a pending purge",
                listOf("remove:movie-1"),
                commander.commands,
            )
        }
}

package com.bobot.iptvapp.download

import android.net.Uri
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import com.bobot.iptvapp.data.logout.LogoutCoordinator
import com.bobot.iptvapp.data.logout.LogoutPurgeStack
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
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
import org.junit.Before
import org.junit.Test

/**
 * Adversarial regression test for the second review's Blocker #2: [DownloadTracker]'s Media3
 * callbacks read-then-write Room with no coordination against a concurrent logout purge.
 *
 * A callback already past [com.bobot.iptvapp.data.local.dao.DownloadDao.get] when a purge clears
 * the table can still [com.bobot.iptvapp.data.local.dao.DownloadDao.upsert] the stale row straight
 * back — resurrecting a credential-bearing entry the logout believed it had removed. The real
 * [DownloadTracker] is exercised here (not a hand-rolled simulation) via a mocked
 * [DownloadManager] whose only job is to hand back the [DownloadManager.Listener] it registered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadTrackerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val applicationScope = CoroutineScope(SupervisorJob() + testDispatcher)
    private val stack = LogoutPurgeStack(testDispatcher)

    private val coordinator = LogoutCoordinator(
        logoutPurger = stack.purger,
        markerStore = stack.markerStore,
        applicationScope = applicationScope,
    )

    private val listenerSlot: CapturingSlot<DownloadManager.Listener> = slot()

    private val downloadManager: DownloadManager = mockk {
        every { addListener(capture(listenerSlot)) } returns Unit
    }

    private lateinit var tracker: DownloadTracker

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)

        tracker = DownloadTracker(
            downloadDao = stack.downloadDao,
            logoutCoordinator = coordinator,
            downloadManager = downloadManager,
            ioDispatcher = testDispatcher,
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
        applicationScope.cancel()
    }

    private fun download(id: String, state: Int) = Download(
        DownloadRequest.Builder(id, Uri.parse("http://a.example:8080/movie/userA/passA/$id.mkv")).build(),
        state,
        0L,
        0L,
        100L,
        Download.STOP_REASON_NONE,
        Download.FAILURE_REASON_NONE,
    )

    @Test
    fun `a callback suspended between get and upsert does not resurrect a row a concurrent logout has purged`() =
        runTest(testDispatcher) {
            stack.seedSignedInAccountWithDownloads()
            stack.downloadDao.gateGet = CompletableDeferred()

            val callbackJob = launch {
                listenerSlot.captured.onDownloadChanged(
                    downloadManager,
                    download("movie-1", Download.STATE_DOWNLOADING),
                    null,
                )
            }
            advanceUntilIdle()

            val logoutJob = launch { coordinator.logOut() }
            advanceUntilIdle()

            assertEquals(
                "the purge must not run a single step while the callback still holds the barrier",
                emptyList<String>(),
                stack.journal,
            )

            stack.downloadDao.gateGet!!.complete(Unit)
            advanceUntilIdle()
            callbackJob.join()
            logoutJob.join()

            assertEquals(
                "the download must stay purged, not resurrected by the stalled callback's upsert",
                emptyList<com.bobot.iptvapp.data.local.entity.DownloadEntity>(),
                stack.downloadDao.currentRows,
            )
        }

    @Test
    fun `onDownloadRemoved is refused while a logout purge is pending`() = runTest(testDispatcher) {
        stack.seedSignedInAccountWithDownloads()
        stack.markerStore.markPurgePending()

        listenerSlot.captured.onDownloadRemoved(downloadManager, download("movie-1", Download.STATE_REMOVING))
        advanceUntilIdle()

        assertEquals(
            "a purge-owed removal callback must not touch Room before the purge itself runs",
            1,
            stack.downloadDao.currentRows.size,
        )
    }
}

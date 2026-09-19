package com.bobot.iptvapp.data.repository

import com.bobot.iptvapp.data.logout.LogoutCoordinator
import com.bobot.iptvapp.data.logout.LogoutPurgeStack
import com.bobot.iptvapp.domain.model.DownloadContentType
import com.bobot.iptvapp.domain.model.DownloadRequestData
import com.bobot.iptvapp.download.FakeDownloadCommander
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [DownloadRepositoryImpl]'s enqueue guard.
 *
 * The guard is what stops the purge from racing the UI: without it, a download queued while the
 * purge is walking its steps lands in a table that has already been cleared, and survives the
 * logout that was supposed to remove everything. [LogoutCoordinator] and [LogoutPurgeStack]'s real
 * [com.bobot.iptvapp.domain.logout.LogoutPurger] are used rather than a marker fake, so these tests
 * exercise the actual mutual-exclusion boundary — see [DownloadPurgeRaceTest] for the adversarial
 * interleaving that this boundary alone (without awaiting Media3's acceptance) does not close.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRepositoryImplTest {

    private val testDispatcher = StandardTestDispatcher()
    private val applicationScope = CoroutineScope(SupervisorJob() + testDispatcher)
    private val stack = LogoutPurgeStack(testDispatcher)

    private val coordinator = LogoutCoordinator(
        logoutPurger = stack.purger,
        markerStore = stack.markerStore,
        applicationScope = applicationScope,
    )

    private val commander = FakeDownloadCommander(
        onEnqueue = { id -> stack.indexGateway.enqueueDownload(id) },
    )

    private val repository = DownloadRepositoryImpl(
        downloadDao = stack.downloadDao,
        downloadService = commander,
        logoutCoordinator = coordinator,
        media3IndexGateway = stack.indexGateway,
        ioDispatcher = testDispatcher,
    )

    private val request = DownloadRequestData(
        contentType = DownloadContentType.MOVIE,
        contentId = "movie-1",
        title = "Un film",
        artworkUrl = null,
        streamUrl = "http://a.example:8080/movie/userA/passA/movie-1.mkv",
    )

    @After
    fun tearDown() {
        applicationScope.cancel()
    }

    @Test
    fun `enqueue indexes the request and commands Media3 when no purge is pending`() =
        runTest(testDispatcher) {
            val downloadId = repository.enqueue(request)
            advanceUntilIdle()

            assertNotNull(downloadId)
            assertEquals(1, stack.downloadDao.currentRows.size)
            assertEquals(listOf("enqueue:$downloadId"), commander.commands)
        }

    @Test
    fun `enqueue is refused while a logout purge is pending`() = runTest(testDispatcher) {
        stack.markerStore.markPurgePending()

        val downloadId = repository.enqueue(request)

        assertNull(downloadId)
        assertEquals(emptyList<Any>(), stack.downloadDao.currentRows)
        assertEquals(emptyList<String>(), commander.commands)
    }

    @Test
    fun `enqueue is accepted again once the purge has completed`() = runTest(testDispatcher) {
        stack.markerStore.markPurgePending()
        repository.enqueue(request)

        stack.markerStore.clearPurgePending()
        val downloadId = repository.enqueue(request)
        advanceUntilIdle()

        assertNotNull(downloadId)
        assertEquals(1, stack.downloadDao.currentRows.size)
        assertEquals(listOf("enqueue:$downloadId"), commander.commands)
    }
}

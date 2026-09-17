package com.bobot.iptvapp.data.repository

import com.bobot.iptvapp.data.local.dao.FakeDownloadDao
import com.bobot.iptvapp.data.preferences.FakeLogoutPurgeMarkerStore
import com.bobot.iptvapp.domain.model.DownloadContentType
import com.bobot.iptvapp.domain.model.DownloadRequestData
import com.bobot.iptvapp.download.FakeDownloadCommander
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [DownloadRepositoryImpl]'s enqueue guard — slice 6 of the logout purge.
 *
 * The guard is what stops the purge from racing the UI: without it, a download queued while the
 * purge is walking its steps lands in a table that has already been cleared, and survives the
 * logout that was supposed to remove everything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRepositoryImplTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val downloadDao = FakeDownloadDao()
    private val commander = FakeDownloadCommander()
    private val markerStore = FakeLogoutPurgeMarkerStore()

    private val repository = DownloadRepositoryImpl(
        downloadDao = downloadDao,
        downloadService = commander,
        logoutPurgeMarkerStore = markerStore,
        ioDispatcher = testDispatcher,
    )

    private val request = DownloadRequestData(
        contentType = DownloadContentType.MOVIE,
        contentId = "movie-1",
        title = "Un film",
        artworkUrl = null,
        streamUrl = "http://a.example:8080/movie/userA/passA/movie-1.mkv",
    )

    @Test
    fun `enqueue indexes the request and commands Media3 when no purge is pending`() =
        runTest(testDispatcher) {
            val downloadId = repository.enqueue(request)

            assertNotNull(downloadId)
            assertEquals(1, downloadDao.currentRows.size)
            assertEquals(listOf("enqueue:$downloadId"), commander.commands)
        }

    @Test
    fun `enqueue is refused while a logout purge is pending`() = runTest(testDispatcher) {
        markerStore.markPurgePending()

        val downloadId = repository.enqueue(request)

        assertNull(downloadId)
        assertEquals(emptyList<Any>(), downloadDao.currentRows)
        assertEquals(emptyList<String>(), commander.commands)
    }

    @Test
    fun `enqueue is accepted again once the purge has completed`() = runTest(testDispatcher) {
        markerStore.markPurgePending()
        repository.enqueue(request)

        markerStore.clearPurgePending()
        val downloadId = repository.enqueue(request)

        assertNotNull(downloadId)
        assertEquals(1, downloadDao.currentRows.size)
        assertEquals(listOf("enqueue:$downloadId"), commander.commands)
    }
}

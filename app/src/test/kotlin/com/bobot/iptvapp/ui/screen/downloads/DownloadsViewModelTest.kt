package com.bobot.iptvapp.ui.screen.downloads

import com.bobot.iptvapp.domain.model.DownloadContentType
import com.bobot.iptvapp.domain.model.DownloadState
import com.bobot.iptvapp.domain.model.OfflineDownload
import com.bobot.iptvapp.domain.repository.DownloadRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val downloads = MutableStateFlow<List<OfflineDownload>>(emptyList())
    private lateinit var repository: DownloadRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        every { repository.observeDownloads() } returns downloads
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `observed downloads are exposed after loading completes`() {
        val viewModel = DownloadsViewModel(repository)
        val item = download(downloadId = "MOVIE:42", state = DownloadState.DOWNLOADING)

        downloads.value = listOf(item)
        testDispatcher.scheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(listOf(item), viewModel.uiState.value.downloads)
    }

    @Test
    fun `pause delegates to repository for the selected download`() {
        every { repository.observeDownloads() } returns downloads
        coEvery { repository.pause("MOVIE:42") } just Runs
        val viewModel = DownloadsViewModel(repository)

        viewModel.pause("MOVIE:42")
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { repository.pause("MOVIE:42") }
    }

    @Test
    fun `resume delegates to repository for the selected download`() {
        coEvery { repository.resume("MOVIE:42") } just Runs
        val viewModel = DownloadsViewModel(repository)

        viewModel.resume("MOVIE:42")
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { repository.resume("MOVIE:42") }
    }

    @Test
    fun `remove delegates to repository for the selected download`() {
        coEvery { repository.remove("MOVIE:42") } just Runs
        val viewModel = DownloadsViewModel(repository)

        viewModel.remove("MOVIE:42")
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { repository.remove("MOVIE:42") }
    }

    private fun download(downloadId: String, state: DownloadState) = OfflineDownload(
        downloadId = downloadId,
        contentType = DownloadContentType.MOVIE,
        contentId = "42",
        title = "Le film",
        artworkUrl = null,
        streamUrl = "https://example.test/movie.mp4",
        state = state,
        bytesDownloaded = 50L,
        contentLength = 100L,
        updatedAtMillis = 1L,
    )
}

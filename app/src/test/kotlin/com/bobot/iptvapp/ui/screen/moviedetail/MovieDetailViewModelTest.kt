package com.bobot.iptvapp.ui.screen.moviedetail

import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.data.source.CredentialsProvider
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.DownloadContentType
import com.bobot.iptvapp.domain.model.DownloadRequestData
import com.bobot.iptvapp.domain.model.DownloadRequestId
import com.bobot.iptvapp.domain.model.DownloadState
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.OfflineDownload
import com.bobot.iptvapp.domain.model.PlaybackProgress
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.repository.DownloadRepository
import com.bobot.iptvapp.domain.repository.FavoritesRepository
import com.bobot.iptvapp.domain.repository.PlaybackProgressRepository
import com.bobot.iptvapp.domain.util.Resource
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MovieDetailViewModel].
 *
 * Follows the exact `viewModelScope` testing convention established by
 * [com.bobot.iptvapp.ui.screen.home.HomeViewModelTest] and
 * [com.bobot.iptvapp.ui.screen.profiles.ProfilesViewModelTest]: [Dispatchers.setMain] swaps in a
 * [StandardTestDispatcher], and `testDispatcher.scheduler.runCurrent()` deterministically drains
 * the `viewModelScope.launch` coroutine started by [MovieDetailViewModel.initialize] /
 * [MovieDetailViewModel.onRetry] / [MovieDetailViewModel.onToggleFavorite] — including the
 * never-completing `isFavorite` Flow collector, which just suspends again waiting for the next
 * [MutableStateFlow] emission.
 *
 * All five collaborators are `mockk()` doubles. [FavoritesRepository.isFavorite] is stubbed to
 * return this test's own [isFavoriteFlow] [MutableStateFlow] (mirroring
 * [com.bobot.iptvapp.ui.screen.home.HomeViewModelTest]'s
 * `every { catalogRepository.observeXxx() } returns xxxFlow` pattern), so tests simulate a
 * favorites-table change by pushing a new value onto it directly.
 */
class MovieDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var catalogRepository: CatalogRepository
    private lateinit var favoritesRepository: FavoritesRepository
    private lateinit var playbackProgressRepository: PlaybackProgressRepository
    private lateinit var downloadRepository: DownloadRepository
    private lateinit var appPreferencesStore: AppPreferencesStore
    private lateinit var credentialsProvider: CredentialsProvider
    private lateinit var viewModel: MovieDetailViewModel

    private lateinit var isFavoriteFlow: MutableStateFlow<Boolean>
    private lateinit var downloadFlow: MutableStateFlow<OfflineDownload?>

    private val profileId = "p1"
    private val movieId = "m1"

    private val credentials = XtreamCredentials(
        baseUrl = "http://example.com:8080",
        username = "alice",
        password = "secret",
    )

    private val movie = Movie(
        id = movieId,
        title = "Explosion Totale",
        posterUrl = "http://example.com/poster.jpg",
        plot = "Un synopsis.",
        categoryId = "10",
        rating = "7.8",
        year = 2023,
        addedMillis = null,
        durationMillis = 100_000L,
        containerExtension = "mkv",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        catalogRepository = mockk()
        favoritesRepository = mockk()
        playbackProgressRepository = mockk()
        downloadRepository = mockk()
        appPreferencesStore = mockk()
        credentialsProvider = mockk()

        isFavoriteFlow = MutableStateFlow(false)
        downloadFlow = MutableStateFlow(null)

        coEvery { appPreferencesStore.getActiveProfileId() } returns profileId
        coEvery { credentialsProvider.getCredentials() } returns credentials
        coEvery { playbackProgressRepository.getProgress(any(), any(), any()) } returns null
        every { favoritesRepository.isFavorite(any(), any(), any()) } returns isFavoriteFlow
        every { downloadRepository.observeDownload(any()) } returns downloadFlow
        coEvery { favoritesRepository.toggleFavorite(any(), any(), any()) } just Runs
        coEvery { downloadRepository.enqueue(any()) } returns "MOVIE:$movieId"
        coEvery { downloadRepository.pause(any()) } just Runs
        coEvery { downloadRepository.resume(any()) } just Runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() {
        viewModel = MovieDetailViewModel(
            catalogRepository = catalogRepository,
            favoritesRepository = favoritesRepository,
            playbackProgressRepository = playbackProgressRepository,
            downloadRepository = downloadRepository,
            appPreferencesStore = appPreferencesStore,
            credentialsProvider = credentialsProvider,
        )
    }

    private fun initialize(id: String = movieId) {
        createViewModel()
        viewModel.initialize(id)
        testDispatcher.scheduler.runCurrent()
    }

    // ── Successful load ───────────────────────────────────────────────────────

    @Test
    fun `initialize loads movie metadata and builds the stream URL from credentials`() {
        coEvery { catalogRepository.getMovieDetail(movieId) } returns Resource.Success(movie)

        initialize()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(movie, state.movie)
        assertEquals("http://example.com:8080/movie/alice/secret/m1.mkv", state.streamUrl)
    }

    @Test
    fun `initialize is idempotent and only fetches the movie once`() {
        coEvery { catalogRepository.getMovieDetail(movieId) } returns Resource.Success(movie)

        initialize()
        viewModel.initialize(movieId)
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { catalogRepository.getMovieDetail(movieId) }
    }

    @Test
    fun `initialize falls back to the mp4 container extension when the movie has none`() {
        coEvery { catalogRepository.getMovieDetail(movieId) } returns
            Resource.Success(movie.copy(containerExtension = null))

        initialize()

        assertEquals("http://example.com:8080/movie/alice/secret/m1.mp4", viewModel.uiState.value.streamUrl)
    }

    @Test
    fun `initialize leaves the stream URL null when no credentials are configured`() {
        coEvery { catalogRepository.getMovieDetail(movieId) } returns Resource.Success(movie)
        coEvery { credentialsProvider.getCredentials() } returns null

        initialize()

        assertNull(viewModel.uiState.value.streamUrl)
    }

    // ── Error ──────────────────────────────────────────────────────────────────

    @Test
    fun `initialize surfaces the error message and leaves movie null when the fetch fails`() {
        coEvery { catalogRepository.getMovieDetail(movieId) } returns
            Resource.Error(message = "Film introuvable")

        initialize()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Film introuvable", state.errorMessage)
        assertNull(state.movie)
    }

    // ── Resume eligibility ────────────────────────────────────────────────────

    @Test
    fun `a progress record below the minimum position threshold does not enable resume`() {
        coEvery { catalogRepository.getMovieDetail(movieId) } returns Resource.Success(movie)
        coEvery { playbackProgressRepository.getProgress(profileId, movieId, ContentType.MOVIE) } returns
            progressOf(positionMillis = 2_000L, durationMillis = 100_000L)

        initialize()

        assertFalse(viewModel.uiState.value.canResume)
    }

    @Test
    fun `a progress record at or beyond the completion threshold does not enable resume`() {
        coEvery { catalogRepository.getMovieDetail(movieId) } returns Resource.Success(movie)
        coEvery { playbackProgressRepository.getProgress(profileId, movieId, ContentType.MOVIE) } returns
            progressOf(positionMillis = 96_000L, durationMillis = 100_000L)

        initialize()

        assertFalse(viewModel.uiState.value.canResume)
    }

    @Test
    fun `a mid-playback progress record enables resume`() {
        coEvery { catalogRepository.getMovieDetail(movieId) } returns Resource.Success(movie)
        coEvery { playbackProgressRepository.getProgress(profileId, movieId, ContentType.MOVIE) } returns
            progressOf(positionMillis = 50_000L, durationMillis = 100_000L)

        initialize()

        assertTrue(viewModel.uiState.value.canResume)
    }

    @Test
    fun `no active profile disables resume without querying playback progress`() {
        coEvery { catalogRepository.getMovieDetail(movieId) } returns Resource.Success(movie)
        coEvery { appPreferencesStore.getActiveProfileId() } returns null

        initialize()

        assertFalse(viewModel.uiState.value.canResume)
        coVerify(exactly = 0) { playbackProgressRepository.getProgress(any(), any(), any()) }
    }

    // ── Favorites ──────────────────────────────────────────────────────────────

    @Test
    fun `initialize reflects the current favorite state and reacts to later changes`() {
        coEvery { catalogRepository.getMovieDetail(movieId) } returns Resource.Success(movie)
        isFavoriteFlow.value = true

        initialize()

        assertTrue(viewModel.uiState.value.isFavorite)

        isFavoriteFlow.value = false
        testDispatcher.scheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isFavorite)
    }

    @Test
    fun `onToggleFavorite delegates to the repository for the active profile and movie`() {
        coEvery { catalogRepository.getMovieDetail(movieId) } returns Resource.Success(movie)
        initialize()

        viewModel.onToggleFavorite()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { favoritesRepository.toggleFavorite(profileId, movieId, ContentType.MOVIE) }
    }

    @Test
    fun `onToggleFavorite is a no-op when no profile is active`() {
        coEvery { catalogRepository.getMovieDetail(movieId) } returns Resource.Success(movie)
        coEvery { appPreferencesStore.getActiveProfileId() } returns null
        initialize()

        viewModel.onToggleFavorite()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) { favoritesRepository.toggleFavorite(any(), any(), any()) }
    }

    // ── Retry ──────────────────────────────────────────────────────────────────

    @Test
    fun `onRetry re-fetches the same movie`() {
        coEvery { catalogRepository.getMovieDetail(movieId) } returns
            Resource.Error(message = "Hors ligne") andThen Resource.Success(movie)
        initialize()
        assertEquals("Hors ligne", viewModel.uiState.value.errorMessage)

        viewModel.onRetry()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 2) { catalogRepository.getMovieDetail(movieId) }
        val state = viewModel.uiState.value
        assertNull(state.errorMessage)
        assertEquals(movie, state.movie)
    }

    // ── Offline download ───────────────────────────────────────────────────────

    @Test
    fun `initialize observes the movie offline download using its stable request ID`() {
        coEvery { catalogRepository.getMovieDetail(movieId) } returns Resource.Success(movie)
        val download = downloadOf(state = DownloadState.DOWNLOADING)
        downloadFlow.value = download

        initialize()

        assertEquals(download, viewModel.uiState.value.download)
        verify(exactly = 1) {
            downloadRepository.observeDownload(DownloadRequestId.create(DownloadContentType.MOVIE, movieId))
        }
    }

    @Test
    fun `onDownloadClick enqueues the loaded movie with its display metadata and stream URL`() {
        coEvery { catalogRepository.getMovieDetail(movieId) } returns Resource.Success(movie)
        initialize()

        viewModel.onDownloadClick()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) {
            downloadRepository.enqueue(
                DownloadRequestData(
                    contentType = DownloadContentType.MOVIE,
                    contentId = movieId,
                    title = movie.title,
                    artworkUrl = movie.posterUrl,
                    streamUrl = "http://example.com:8080/movie/alice/secret/m1.mkv",
                ),
            )
        }
    }

    @Test
    fun `onDownloadClick is a no-op until a movie stream URL is available`() {
        coEvery { catalogRepository.getMovieDetail(movieId) } returns Resource.Success(movie)
        coEvery { credentialsProvider.getCredentials() } returns null
        initialize()

        viewModel.onDownloadClick()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) { downloadRepository.enqueue(any()) }
    }

    @Test
    fun `onPauseDownload delegates the current download ID to the repository`() {
        coEvery { catalogRepository.getMovieDetail(movieId) } returns Resource.Success(movie)
        downloadFlow.value = downloadOf(state = DownloadState.DOWNLOADING)
        initialize()

        viewModel.onPauseDownload()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { downloadRepository.pause("MOVIE:$movieId") }
    }

    @Test
    fun `onResumeDownload delegates the current download ID to the repository`() {
        coEvery { catalogRepository.getMovieDetail(movieId) } returns Resource.Success(movie)
        downloadFlow.value = downloadOf(state = DownloadState.PAUSED)
        initialize()

        viewModel.onResumeDownload()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { downloadRepository.resume("MOVIE:$movieId") }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun progressOf(positionMillis: Long, durationMillis: Long) = PlaybackProgress(
        contentId = movieId,
        contentType = ContentType.MOVIE,
        positionMillis = positionMillis,
        durationMillis = durationMillis,
        lastUpdatedMillis = 1_000L,
        profileId = profileId,
    )

    private fun downloadOf(state: DownloadState) = OfflineDownload(
        downloadId = "MOVIE:$movieId",
        contentType = DownloadContentType.MOVIE,
        contentId = movieId,
        title = movie.title,
        artworkUrl = movie.posterUrl,
        streamUrl = "http://example.com:8080/movie/alice/secret/m1.mkv",
        state = state,
        bytesDownloaded = 50L,
        contentLength = 100L,
        updatedAtMillis = 1_000L,
    )
}

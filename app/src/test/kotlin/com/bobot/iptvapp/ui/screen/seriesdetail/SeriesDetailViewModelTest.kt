package com.bobot.iptvapp.ui.screen.seriesdetail

import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.data.source.CredentialsProvider
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.Episode
import com.bobot.iptvapp.domain.model.Season
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.repository.FavoritesRepository
import com.bobot.iptvapp.domain.util.Resource
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
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
 * Unit tests for [SeriesDetailViewModel].
 *
 * Follows the exact `viewModelScope` testing convention established by
 * [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModelTest]: [Dispatchers.setMain] swaps
 * in a [StandardTestDispatcher], and `testDispatcher.scheduler.runCurrent()` deterministically
 * drains the `viewModelScope.launch` coroutine started by [SeriesDetailViewModel.initialize] /
 * [SeriesDetailViewModel.onRetry] / [SeriesDetailViewModel.onToggleFavorite] — including the
 * never-completing `isFavorite` Flow collector, which just suspends again waiting for the next
 * [MutableStateFlow] emission.
 *
 * All four collaborators are `mockk()` doubles. [FavoritesRepository.isFavorite] is stubbed to
 * return this test's own [isFavoriteFlow] [MutableStateFlow] (mirroring
 * [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModelTest]'s
 * `every { favoritesRepository.isFavorite(...) } returns isFavoriteFlow` pattern), so tests
 * simulate a favorites-table change by pushing a new value onto it directly.
 */
class SeriesDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var catalogRepository: CatalogRepository
    private lateinit var favoritesRepository: FavoritesRepository
    private lateinit var appPreferencesStore: AppPreferencesStore
    private lateinit var credentialsProvider: CredentialsProvider
    private lateinit var viewModel: SeriesDetailViewModel

    private lateinit var isFavoriteFlow: MutableStateFlow<Boolean>

    private val profileId = "p1"
    private val seriesId = "s1"

    private val credentials = XtreamCredentials(
        baseUrl = "http://example.com:8080",
        username = "alice",
        password = "secret",
    )

    private val season1Episodes = listOf(
        Episode(
            id = "e1",
            title = "Pilote",
            episodeNumber = 1,
            seasonNumber = 1,
            plot = "Un synopsis.",
            durationMillis = 100_000L,
            containerExtension = "mkv",
            coverUrl = "http://example.com/e1.jpg",
        ),
        Episode(
            id = "e2",
            title = "Le Chat est dans le sac",
            episodeNumber = 2,
            seasonNumber = 1,
            plot = null,
            durationMillis = 90_000L,
            containerExtension = null,
            coverUrl = null,
        ),
    )

    private val series = Series(
        id = seriesId,
        title = "Breaking Bad",
        coverUrl = "http://example.com/poster.jpg",
        plot = "Un synopsis de série.",
        categoryId = "3",
        rating = "9.5",
        year = 2008,
        seasons = listOf(
            Season(seasonNumber = 1, name = "Saison 1", coverUrl = null, episodes = season1Episodes),
            Season(seasonNumber = 2, name = "Saison 2", coverUrl = null, episodes = emptyList()),
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        catalogRepository = mockk()
        favoritesRepository = mockk()
        appPreferencesStore = mockk()
        credentialsProvider = mockk()

        isFavoriteFlow = MutableStateFlow(false)

        coEvery { appPreferencesStore.getActiveProfileId() } returns profileId
        coEvery { credentialsProvider.getCredentials() } returns credentials
        every { favoritesRepository.isFavorite(any(), any(), any()) } returns isFavoriteFlow
        coEvery { favoritesRepository.toggleFavorite(any(), any(), any()) } just Runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() {
        viewModel = SeriesDetailViewModel(
            catalogRepository = catalogRepository,
            favoritesRepository = favoritesRepository,
            appPreferencesStore = appPreferencesStore,
            credentialsProvider = credentialsProvider,
        )
    }

    private fun initialize(id: String = seriesId) {
        createViewModel()
        viewModel.initialize(id)
        testDispatcher.scheduler.runCurrent()
    }

    // ── Successful load ───────────────────────────────────────────────────────

    @Test
    fun `initialize loads series metadata and defaults to the first season`() {
        coEvery { catalogRepository.getSeriesDetail(seriesId) } returns Resource.Success(series)

        initialize()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(series, state.series)
        assertEquals(1, state.selectedSeasonNumber)
        assertEquals(season1Episodes, state.selectedSeasonEpisodes)
    }

    @Test
    fun `initialize is idempotent and only fetches the series once`() {
        coEvery { catalogRepository.getSeriesDetail(seriesId) } returns Resource.Success(series)

        initialize()
        viewModel.initialize(seriesId)
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { catalogRepository.getSeriesDetail(seriesId) }
    }

    @Test
    fun `initialize leaves selectedSeasonNumber null when the series has no seasons`() {
        coEvery { catalogRepository.getSeriesDetail(seriesId) } returns
            Resource.Success(series.copy(seasons = emptyList()))

        initialize()

        val state = viewModel.uiState.value
        assertNull(state.selectedSeasonNumber)
        assertTrue(state.selectedSeasonEpisodes.isEmpty())
    }

    @Test
    fun `initialize marks credentials unavailable when none are configured`() {
        coEvery { catalogRepository.getSeriesDetail(seriesId) } returns Resource.Success(series)
        coEvery { credentialsProvider.getCredentials() } returns null

        initialize()

        assertFalse(viewModel.uiState.value.hasCredentials)
    }

    // ── Error ──────────────────────────────────────────────────────────────────

    @Test
    fun `initialize surfaces the error message and leaves series null when the fetch fails`() {
        coEvery { catalogRepository.getSeriesDetail(seriesId) } returns
            Resource.Error(message = "Série introuvable")

        initialize()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Série introuvable", state.errorMessage)
        assertNull(state.series)
    }

    // ── Season selection ──────────────────────────────────────────────────────

    @Test
    fun `onSelectSeason switches the exposed episode list to the newly selected season`() {
        coEvery { catalogRepository.getSeriesDetail(seriesId) } returns Resource.Success(series)
        initialize()

        viewModel.onSelectSeason(2)

        val state = viewModel.uiState.value
        assertEquals(2, state.selectedSeasonNumber)
        assertTrue(state.selectedSeasonEpisodes.isEmpty())
    }

    @Test
    fun `onSelectSeason with an unmatched season number yields an empty episode list`() {
        coEvery { catalogRepository.getSeriesDetail(seriesId) } returns Resource.Success(series)
        initialize()

        viewModel.onSelectSeason(99)

        assertTrue(viewModel.uiState.value.selectedSeasonEpisodes.isEmpty())
    }

    // ── Episode stream URL ────────────────────────────────────────────────────

    @Test
    fun `buildEpisodeStreamUrl builds the URL from the cached credentials and the episode's container extension`() {
        coEvery { catalogRepository.getSeriesDetail(seriesId) } returns Resource.Success(series)
        initialize()

        val url = viewModel.buildEpisodeStreamUrl(season1Episodes[0])

        assertEquals("http://example.com:8080/series/alice/secret/e1.mkv", url)
    }

    @Test
    fun `buildEpisodeStreamUrl falls back to the mp4 container extension when the episode has none`() {
        coEvery { catalogRepository.getSeriesDetail(seriesId) } returns Resource.Success(series)
        initialize()

        val url = viewModel.buildEpisodeStreamUrl(season1Episodes[1])

        assertEquals("http://example.com:8080/series/alice/secret/e2.mp4", url)
    }

    @Test
    fun `buildEpisodeStreamUrl returns null when no credentials are configured`() {
        coEvery { catalogRepository.getSeriesDetail(seriesId) } returns Resource.Success(series)
        coEvery { credentialsProvider.getCredentials() } returns null
        initialize()

        assertNull(viewModel.buildEpisodeStreamUrl(season1Episodes[0]))
    }

    // ── Favorites ──────────────────────────────────────────────────────────────

    @Test
    fun `initialize reflects the current favorite state and reacts to later changes`() {
        coEvery { catalogRepository.getSeriesDetail(seriesId) } returns Resource.Success(series)
        isFavoriteFlow.value = true

        initialize()

        assertTrue(viewModel.uiState.value.isFavorite)

        isFavoriteFlow.value = false
        testDispatcher.scheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isFavorite)
    }

    @Test
    fun `onToggleFavorite delegates to the repository for the active profile and series at series scope`() {
        coEvery { catalogRepository.getSeriesDetail(seriesId) } returns Resource.Success(series)
        initialize()

        viewModel.onToggleFavorite()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { favoritesRepository.toggleFavorite(profileId, seriesId, ContentType.SERIES) }
    }

    @Test
    fun `onToggleFavorite is a no-op when no profile is active`() {
        coEvery { catalogRepository.getSeriesDetail(seriesId) } returns Resource.Success(series)
        coEvery { appPreferencesStore.getActiveProfileId() } returns null
        initialize()

        viewModel.onToggleFavorite()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) { favoritesRepository.toggleFavorite(any(), any(), any()) }
    }

    // ── Retry ──────────────────────────────────────────────────────────────────

    @Test
    fun `onRetry re-fetches the same series`() {
        coEvery { catalogRepository.getSeriesDetail(seriesId) } returns
            Resource.Error(message = "Hors ligne") andThen Resource.Success(series)
        initialize()
        assertEquals("Hors ligne", viewModel.uiState.value.errorMessage)

        viewModel.onRetry()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 2) { catalogRepository.getSeriesDetail(seriesId) }
        val state = viewModel.uiState.value
        assertNull(state.errorMessage)
        assertEquals(series, state.series)
    }
}

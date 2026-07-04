package com.bobot.iptvapp.ui.screen.search

import com.bobot.iptvapp.domain.model.Channel
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.util.Resource
import io.mockk.Runs
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
 * Unit tests for [SearchViewModel].
 *
 * Follows the exact `viewModelScope` testing convention established by
 * [com.bobot.iptvapp.ui.screen.home.HomeViewModelTest]: [Dispatchers.setMain] swaps in a
 * [StandardTestDispatcher], and `testDispatcher.scheduler.runCurrent()` deterministically drains
 * the `init` block's reactive collector (which never itself completes, since [SearchViewModel]
 * combines the hot query [kotlinx.coroutines.flow.MutableStateFlow] with the three content Flows)
 * after each simulated repository emission.
 *
 * [CatalogRepository] is a `mockk()` double. Its three content Flow methods are each stubbed to
 * return one of this test's own [MutableStateFlow]s directly (mirroring
 * [com.bobot.iptvapp.ui.screen.home.HomeViewModelTest]'s
 * `every { catalogRepository.getLiveChannels(null) } returns channelsFlow` pattern) so tests push
 * new [Resource] values to simulate the repository's reactive updates.
 */
class SearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var catalogRepository: CatalogRepository
    private lateinit var viewModel: SearchViewModel

    private lateinit var channelsFlow: MutableStateFlow<Resource<List<Channel>>>
    private lateinit var moviesFlow: MutableStateFlow<Resource<List<Movie>>>
    private lateinit var seriesListFlow: MutableStateFlow<Resource<List<Series>>>

    private val chan1 = Channel(id = "c1", name = "Chaîne Sport", logoUrl = null, categoryId = "1", epgChannelId = null)
    private val chan2 = Channel(id = "c2", name = "Actu 24", logoUrl = null, categoryId = "1", epgChannelId = null)

    private val movie1 = Movie(
        id = "m1",
        title = "Explosion Totale",
        posterUrl = null,
        plot = null,
        categoryId = "10",
        rating = null,
        year = null,
        addedMillis = null,
        durationMillis = null,
        containerExtension = null,
    )

    private val series1 = Series(
        id = "s1",
        title = "La Casa de Papel",
        coverUrl = null,
        plot = null,
        categoryId = "20",
        rating = null,
        year = null,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        catalogRepository = mockk()

        channelsFlow = MutableStateFlow(Resource.Loading)
        moviesFlow = MutableStateFlow(Resource.Loading)
        seriesListFlow = MutableStateFlow(Resource.Loading)

        every { catalogRepository.getLiveChannels(null) } returns channelsFlow
        every { catalogRepository.getMovies(null) } returns moviesFlow
        every { catalogRepository.getSeriesList(null) } returns seriesListFlow
        every { catalogRepository.invalidateCaches() } just Runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Creates [viewModel] and drains its `init` block's reactive collector. */
    private fun createViewModel() {
        viewModel = SearchViewModel(catalogRepository)
        testDispatcher.scheduler.runCurrent()
    }

    // ── Empty query ────────────────────────────────────────────────────────────

    @Test
    fun `empty query yields no results even once the catalog has loaded`() {
        createViewModel()

        channelsFlow.value = Resource.Success(listOf(chan1, chan2))
        moviesFlow.value = Resource.Success(listOf(movie1))
        seriesListFlow.value = Resource.Success(listOf(series1))
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals("", state.query)
        assertFalse(state.hasAnyResults)
        assertTrue(state.liveResults.isEmpty())
        assertTrue(state.movieResults.isEmpty())
        assertTrue(state.seriesResults.isEmpty())
    }

    // ── Filtering across content types ────────────────────────────────────────

    @Test
    fun `filtering matches across all three content types by substring`() {
        createViewModel()
        channelsFlow.value = Resource.Success(listOf(chan1, chan2))
        moviesFlow.value = Resource.Success(listOf(movie1))
        seriesListFlow.value = Resource.Success(listOf(series1))
        testDispatcher.scheduler.runCurrent()

        viewModel.onQueryChange("a")
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals("a", state.query)
        // "Chaîne Sport" and "Actu 24" both contain "a"; "Explosion Totale" and
        // "La Casa de Papel" both contain "a" too.
        assertEquals(
            listOf(
                SearchResultItem(id = "c1", title = "Chaîne Sport", imageUrl = null, contentType = ContentType.LIVE),
                SearchResultItem(id = "c2", title = "Actu 24", imageUrl = null, contentType = ContentType.LIVE),
            ),
            state.liveResults,
        )
        assertEquals(
            listOf(SearchResultItem(id = "m1", title = "Explosion Totale", imageUrl = null, contentType = ContentType.MOVIE)),
            state.movieResults,
        )
        assertEquals(
            listOf(SearchResultItem(id = "s1", title = "La Casa de Papel", imageUrl = null, contentType = ContentType.SERIES)),
            state.seriesResults,
        )
    }

    @Test
    fun `filtering is case-insensitive`() {
        createViewModel()
        channelsFlow.value = Resource.Success(listOf(chan1))
        moviesFlow.value = Resource.Success(emptyList())
        seriesListFlow.value = Resource.Success(emptyList())
        testDispatcher.scheduler.runCurrent()

        viewModel.onQueryChange("SPORT")
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(
            listOf(SearchResultItem(id = "c1", title = "Chaîne Sport", imageUrl = null, contentType = ContentType.LIVE)),
            state.liveResults,
        )
    }

    @Test
    fun `no matching item yields the no-results state (empty result lists, no error, not loading)`() {
        createViewModel()
        channelsFlow.value = Resource.Success(listOf(chan1))
        moviesFlow.value = Resource.Success(listOf(movie1))
        seriesListFlow.value = Resource.Success(listOf(series1))
        testDispatcher.scheduler.runCurrent()

        viewModel.onQueryChange("xyz-inexistant")
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.hasAnyResults)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun `query change reacts live to the latest typed value`() {
        createViewModel()
        channelsFlow.value = Resource.Success(listOf(chan1, chan2))
        moviesFlow.value = Resource.Success(emptyList())
        seriesListFlow.value = Resource.Success(emptyList())
        testDispatcher.scheduler.runCurrent()

        viewModel.onQueryChange("sport")
        testDispatcher.scheduler.runCurrent()
        assertEquals(1, viewModel.uiState.value.liveResults.size)

        viewModel.onQueryChange("actu")
        testDispatcher.scheduler.runCurrent()
        val state = viewModel.uiState.value
        assertEquals(1, state.liveResults.size)
        assertEquals("c2", state.liveResults.first().id)
    }

    // ── Underlying catalog loading / error handling ───────────────────────────

    @Test
    fun `init with every section still loading shows isLoading true and no results`() {
        createViewModel()

        val state = viewModel.uiState.value
        assertTrue(state.isLoading)
        assertFalse(state.hasAnyResults)
        assertNull(state.errorMessage)
    }

    @Test
    fun `an error in one section is surfaced without wiping matches already found in another section`() {
        createViewModel()
        channelsFlow.value = Resource.Success(listOf(chan1))
        moviesFlow.value = Resource.Error(message = "Panne films")
        seriesListFlow.value = Resource.Success(emptyList())
        testDispatcher.scheduler.runCurrent()

        viewModel.onQueryChange("sport")
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Panne films", state.errorMessage)
        assertTrue(state.liveResults.isNotEmpty())
        assertTrue(state.movieResults.isEmpty())
    }

    @Test
    fun `partial loading of one section still surfaces results already available from another`() {
        createViewModel()
        channelsFlow.value = Resource.Success(listOf(chan1))
        // moviesFlow / seriesListFlow remain Resource.Loading.
        testDispatcher.scheduler.runCurrent()

        viewModel.onQueryChange("sport")
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.isLoading)
        assertTrue(state.liveResults.isNotEmpty())
        assertNull(state.errorMessage)
    }

    // ── Retry ──────────────────────────────────────────────────────────────────

    @Test
    fun `onRetry clears the error immediately and invalidates the repository cache`() {
        createViewModel()
        channelsFlow.value = Resource.Error(message = "Hors ligne")
        testDispatcher.scheduler.runCurrent()

        viewModel.onQueryChange("a")
        testDispatcher.scheduler.runCurrent()
        assertEquals("Hors ligne", viewModel.uiState.value.errorMessage)

        viewModel.onRetry()

        assertNull(viewModel.uiState.value.errorMessage)
        verify(exactly = 1) { catalogRepository.invalidateCaches() }
    }

    @Test
    fun `onRetry re-subscribes to every content repository Flow`() {
        createViewModel()
        verify(exactly = 1) { catalogRepository.getLiveChannels(null) }
        verify(exactly = 1) { catalogRepository.getMovies(null) }
        verify(exactly = 1) { catalogRepository.getSeriesList(null) }

        viewModel.onRetry()
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 2) { catalogRepository.getLiveChannels(null) }
        verify(exactly = 2) { catalogRepository.getMovies(null) }
        verify(exactly = 2) { catalogRepository.getSeriesList(null) }
    }
}

package com.bobot.iptvapp.ui.screen.search

import com.bobot.iptvapp.domain.model.Category
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
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
 * Unit tests for [SearchViewModel] (OOM fix — category-scoped, sequential loading).
 *
 * Follows the exact `viewModelScope` testing convention established by
 * [com.bobot.iptvapp.ui.screen.home.HomeViewModelTest]: [Dispatchers.setMain] swaps in a
 * [StandardTestDispatcher], and `testDispatcher.scheduler.runCurrent()` deterministically drains
 * the `init` block's reactive collector (which never itself completes) after each simulated
 * repository emission.
 *
 * [CatalogRepository] is a `mockk()` double. Its three category Flow methods
 * (`observeLiveCategories`, `observeVodCategories`, `observeSeriesCategories`) are each stubbed to
 * return one of this test's own [MutableStateFlow]s directly (mirroring
 * [com.bobot.iptvapp.ui.screen.home.HomeViewModelTest]'s
 * `every { catalogRepository.observeLiveCategories() } returns liveCategoriesFlow` pattern) so
 * tests push new [Resource] values to simulate the repository's reactive updates.
 *
 * ## Category-scoped loading (OOM fix)
 * [SearchViewModel] no longer calls `getLiveChannels(null)` / `getMovies(null)` /
 * `getSeriesList(null)` (the unfiltered, whole-catalog calls that caused the OOM — see
 * [SearchViewModel] class KDoc). Instead it fetches items **one category at a time**, sequentially,
 * content type by content type. This test's [stubLiveChannels] / [stubMovies] / [stubSeries]
 * helpers stub the per-category overload (`getLiveChannels(categoryId)` etc.) for a specific
 * category id, mirroring how the production code now calls it. A per-category Flow is stubbed as
 * an already-completed `flowOf(Resource.Success(...))` — [SearchViewModel] only ever awaits its
 * first non-[Resource.Loading] value, so a bare terminal value is sufficient and keeps tests
 * deterministic under [StandardTestDispatcher] without extra `runCurrent()` steps.
 */
class SearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var catalogRepository: CatalogRepository
    private lateinit var viewModel: SearchViewModel

    private lateinit var liveCategoriesFlow: MutableStateFlow<Resource<List<Category>>>
    private lateinit var vodCategoriesFlow: MutableStateFlow<Resource<List<Category>>>
    private lateinit var seriesCategoriesFlow: MutableStateFlow<Resource<List<Category>>>

    private val sportCategory = Category(id = "1", name = "Sport", type = ContentType.LIVE)
    private val newsCategory = Category(id = "2", name = "News", type = ContentType.LIVE)
    private val actionCategory = Category(id = "10", name = "Action", type = ContentType.MOVIE)
    private val dramaCategory = Category(id = "20", name = "Drames", type = ContentType.SERIES)

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

        liveCategoriesFlow = MutableStateFlow(Resource.Loading)
        vodCategoriesFlow = MutableStateFlow(Resource.Loading)
        seriesCategoriesFlow = MutableStateFlow(Resource.Loading)

        every { catalogRepository.observeLiveCategories() } returns liveCategoriesFlow
        every { catalogRepository.observeVodCategories() } returns vodCategoriesFlow
        every { catalogRepository.observeSeriesCategories() } returns seriesCategoriesFlow
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

    // ── Category-scoped fetch stubs (OOM fix) ─────────────────────────────────

    /** Stubs the per-category `getLiveChannels(categoryId)` overload — see class KDoc. */
    private fun stubLiveChannels(categoryId: String, channels: List<Channel>) {
        every { catalogRepository.getLiveChannels(categoryId) } returns flowOf(Resource.Success(channels))
    }

    /** Stubs the per-category `getMovies(categoryId)` overload — see class KDoc. */
    private fun stubMovies(categoryId: String, movies: List<Movie>) {
        every { catalogRepository.getMovies(categoryId) } returns flowOf(Resource.Success(movies))
    }

    /** Stubs the per-category `getSeriesList(categoryId)` overload — see class KDoc. */
    private fun stubSeries(categoryId: String, series: List<Series>) {
        every { catalogRepository.getSeriesList(categoryId) } returns flowOf(Resource.Success(series))
    }

    // ── Empty query ────────────────────────────────────────────────────────────

    @Test
    fun `empty query yields no results even once the catalog has loaded`() {
        createViewModel()

        stubLiveChannels("1", listOf(chan1, chan2))
        stubMovies("10", listOf(movie1))
        stubSeries("20", listOf(series1))
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(dramaCategory))
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
        stubLiveChannels("1", listOf(chan1, chan2))
        stubMovies("10", listOf(movie1))
        stubSeries("20", listOf(series1))
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(dramaCategory))
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
        stubLiveChannels("1", listOf(chan1))
        stubMovies("10", emptyList())
        stubSeries("20", emptyList())
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(dramaCategory))
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
        stubLiveChannels("1", listOf(chan1))
        stubMovies("10", listOf(movie1))
        stubSeries("20", listOf(series1))
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(dramaCategory))
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
        stubLiveChannels("1", listOf(chan1, chan2))
        stubMovies("10", emptyList())
        stubSeries("20", emptyList())
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(dramaCategory))
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
        stubLiveChannels("1", listOf(chan1))
        stubSeries("20", emptyList())
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        vodCategoriesFlow.value = Resource.Error(message = "Panne films")
        seriesCategoriesFlow.value = Resource.Success(listOf(dramaCategory))
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
        stubLiveChannels("1", listOf(chan1))
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        // vodCategoriesFlow / seriesCategoriesFlow remain Resource.Loading.
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
        liveCategoriesFlow.value = Resource.Error(message = "Hors ligne")
        testDispatcher.scheduler.runCurrent()

        viewModel.onQueryChange("a")
        testDispatcher.scheduler.runCurrent()
        assertEquals("Hors ligne", viewModel.uiState.value.errorMessage)

        viewModel.onRetry()

        assertNull(viewModel.uiState.value.errorMessage)
        verify(exactly = 1) { catalogRepository.invalidateCaches() }
    }

    @Test
    fun `onRetry re-subscribes to every repository Flow and re-fetches every category`() {
        stubLiveChannels("1", listOf(chan1))
        stubMovies("10", listOf(movie1))
        stubSeries("20", listOf(series1))

        createViewModel()
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(dramaCategory))
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) { catalogRepository.observeLiveCategories() }
        verify(exactly = 1) { catalogRepository.observeVodCategories() }
        verify(exactly = 1) { catalogRepository.observeSeriesCategories() }
        verify(exactly = 1) { catalogRepository.getLiveChannels("1") }
        verify(exactly = 1) { catalogRepository.getMovies("10") }
        verify(exactly = 1) { catalogRepository.getSeriesList("20") }

        viewModel.onRetry()
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 2) { catalogRepository.observeLiveCategories() }
        verify(exactly = 2) { catalogRepository.observeVodCategories() }
        verify(exactly = 2) { catalogRepository.observeSeriesCategories() }
        verify(exactly = 2) { catalogRepository.getLiveChannels("1") }
        verify(exactly = 2) { catalogRepository.getMovies("10") }
        verify(exactly = 2) { catalogRepository.getSeriesList("20") }
    }

    // ── OOM fix: category-scoped, sequential loading ──────────────────────────

    @Test
    fun `every category is fetched individually via getXxx(categoryId), never the unfiltered categoryId = null call`() {
        stubLiveChannels("1", listOf(chan1))
        stubLiveChannels("2", emptyList())
        stubMovies("10", listOf(movie1))
        stubSeries("20", listOf(series1))

        createViewModel()
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory, newsCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(dramaCategory))
        testDispatcher.scheduler.runCurrent()

        // Every category fetched individually...
        verify(exactly = 1) { catalogRepository.getLiveChannels("1") }
        verify(exactly = 1) { catalogRepository.getLiveChannels("2") }
        verify(exactly = 1) { catalogRepository.getMovies("10") }
        verify(exactly = 1) { catalogRepository.getSeriesList("20") }
        // ...and the unfiltered, whole-catalog overload is never invoked from this loading path.
        verify(exactly = 0) { catalogRepository.getLiveChannels(null) }
        verify(exactly = 0) { catalogRepository.getMovies(null) }
        verify(exactly = 0) { catalogRepository.getSeriesList(null) }
    }

    @Test
    fun `content types are loaded strictly sequentially, one after another, never fanned out concurrently`() {
        stubLiveChannels("1", listOf(chan1))
        stubMovies("10", listOf(movie1))
        stubSeries("20", listOf(series1))

        createViewModel()
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(dramaCategory))
        testDispatcher.scheduler.runCurrent()

        // The sequential loader awaits Live's entire per-category loop before starting Movies,
        // and Movies' before starting Series — this ordering is the actual mechanism that bounds
        // memory to ~1 category at a time across all three content types (see class KDoc).
        verifyOrder {
            catalogRepository.getLiveChannels("1")
            catalogRepository.getMovies("10")
            catalogRepository.getSeriesList("20")
        }
    }

    @Test
    fun `results keep refining progressively as later categories finish loading, without waiting for the full catalog`() {
        createViewModel()
        stubLiveChannels("1", listOf(chan1))
        stubLiveChannels("2", listOf(chan2))
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory, newsCategory))
        // Movies/Series left in Resource.Loading — Search should not wait for them to already
        // show the Live matches found so far, and should keep isLoading true until they resolve.
        testDispatcher.scheduler.runCurrent()

        viewModel.onQueryChange("a")
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        // Both live categories have already been fetched sequentially by the time runCurrent()
        // drains the coroutine, so both channels are already reflected.
        assertEquals(2, state.liveResults.size)
        assertTrue(state.isLoading) // Movies/Series categories still loading.
    }

    // ── Global language filter (Task 4) ───────────────────────────────────────

    private val frSportCategory = Category(id = "30", name = "FR | Sport", type = ContentType.LIVE)
    private val enNewsCategory = Category(id = "31", name = "EN - News", type = ContentType.LIVE)
    private val untaggedKidsCategory = Category(id = "32", name = "Kids", type = ContentType.LIVE)
    private val frActionCategory = Category(id = "40", name = "FR | Action", type = ContentType.MOVIE)
    private val esActionCategory = Category(id = "41", name = "ES | Accion", type = ContentType.MOVIE)
    private val frDramaCategory = Category(id = "50", name = "FR | Drames", type = ContentType.SERIES)

    private val frChannel = Channel(id = "c10", name = "Chaîne Sport FR", logoUrl = null, categoryId = "30", epgChannelId = null)
    private val enChannel = Channel(id = "c11", name = "Actu News EN", logoUrl = null, categoryId = "31", epgChannelId = null)
    private val untaggedChannel = Channel(id = "c12", name = "Kids Actu", logoUrl = null, categoryId = "32", epgChannelId = null)

    private val frMovie = Movie(
        id = "m10",
        title = "Action Totale",
        posterUrl = null,
        plot = null,
        categoryId = "40",
        rating = null,
        year = null,
        addedMillis = null,
        durationMillis = null,
        containerExtension = null,
    )

    private val frSeries = Series(
        id = "s10",
        title = "Drame Actu",
        coverUrl = null,
        plot = null,
        categoryId = "50",
        rating = null,
        year = null,
    )

    @Test
    fun `available languages is the union of distinct tags across all three content types, as each type's categories resolve in sequence`() {
        // Categories are resolved once per content type (a single small list, unlike the
        // progressively-accumulating item lists) — see loadCategoryScopedItems KDoc. Since content
        // types load strictly sequentially (Live, then Movies, then Series), the union grows one
        // content type at a time as each one's categoriesFlow resolves.
        stubLiveChannels("30", listOf(frChannel))
        stubLiveChannels("31", emptyList())
        stubLiveChannels("32", emptyList())
        stubMovies("40", listOf(frMovie))
        stubMovies("41", emptyList())
        stubSeries("50", listOf(frSeries))
        createViewModel()

        // Live resolves first: FR + EN, "Kids" (untagged) never contributes a tag.
        liveCategoriesFlow.value = Resource.Success(listOf(frSportCategory, enNewsCategory, untaggedKidsCategory))
        testDispatcher.scheduler.runCurrent()
        assertEquals(setOf("FR", "EN"), viewModel.uiState.value.availableLanguages.toSet())
        assertEquals(2, viewModel.uiState.value.availableLanguages.size)

        // Movies resolve next: FR (already present, stays distinct) + a brand-new ES tag.
        vodCategoriesFlow.value = Resource.Success(listOf(frActionCategory, esActionCategory))
        testDispatcher.scheduler.runCurrent()
        assertEquals(setOf("FR", "EN", "ES"), viewModel.uiState.value.availableLanguages.toSet())
        assertEquals(3, viewModel.uiState.value.availableLanguages.size)

        // Series resolve last: FR again — union does not grow further.
        seriesCategoriesFlow.value = Resource.Success(listOf(frDramaCategory))
        testDispatcher.scheduler.runCurrent()
        assertEquals(setOf("FR", "EN", "ES"), viewModel.uiState.value.availableLanguages.toSet())
        assertEquals(3, viewModel.uiState.value.availableLanguages.size)
        assertNull(viewModel.uiState.value.selectedLanguage)
    }

    @Test
    fun `selecting a language filters all three result sections at once, excluding untagged or non-matching categories`() {
        createViewModel()
        stubLiveChannels("30", listOf(frChannel))
        stubLiveChannels("31", listOf(enChannel))
        stubLiveChannels("32", listOf(untaggedChannel))
        stubMovies("40", listOf(frMovie))
        stubSeries("50", listOf(frSeries))

        liveCategoriesFlow.value = Resource.Success(listOf(frSportCategory, enNewsCategory, untaggedKidsCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(frActionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(frDramaCategory))
        testDispatcher.scheduler.runCurrent()

        viewModel.onQueryChange("a")
        testDispatcher.scheduler.runCurrent()

        // Sanity: before filtering, all three sections have matches ("a" is contained in all titles).
        val unfiltered = viewModel.uiState.value
        assertEquals(3, unfiltered.liveResults.size)
        assertTrue(unfiltered.movieResults.isNotEmpty())
        assertTrue(unfiltered.seriesResults.isNotEmpty())

        viewModel.onLanguageSelected("FR")
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals("FR", state.selectedLanguage)
        // Only the FR-tagged channel remains — the EN one and the untagged "Kids" one are both excluded.
        assertEquals(listOf("c10"), state.liveResults.map { it.id })
        assertEquals(listOf("m10"), state.movieResults.map { it.id })
        assertEquals(listOf("s10"), state.seriesResults.map { it.id })
    }

    @Test
    fun `selecting Toutes (null) restores every previously filtered result across all three sections`() {
        createViewModel()
        stubLiveChannels("30", listOf(frChannel))
        stubLiveChannels("31", listOf(enChannel))
        stubMovies("40", listOf(frMovie))
        stubSeries("50", listOf(frSeries))

        liveCategoriesFlow.value = Resource.Success(listOf(frSportCategory, enNewsCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(frActionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(frDramaCategory))
        testDispatcher.scheduler.runCurrent()

        viewModel.onQueryChange("a")
        testDispatcher.scheduler.runCurrent()

        viewModel.onLanguageSelected("FR")
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf("c10"), viewModel.uiState.value.liveResults.map { it.id })

        viewModel.onLanguageSelected(null)
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertNull(state.selectedLanguage)
        assertEquals(setOf("c10", "c11"), state.liveResults.map { it.id }.toSet())
    }

    @Test
    fun `language filter composes with an active text query — both must match`() {
        createViewModel()
        stubLiveChannels("30", listOf(frChannel))
        stubLiveChannels("31", listOf(enChannel))

        liveCategoriesFlow.value = Resource.Success(listOf(frSportCategory, enNewsCategory))
        vodCategoriesFlow.value = Resource.Success(emptyList())
        seriesCategoriesFlow.value = Resource.Success(emptyList())
        testDispatcher.scheduler.runCurrent()

        viewModel.onLanguageSelected("FR")
        testDispatcher.scheduler.runCurrent()

        // Text query matches only the EN channel ("News"), but the FR filter excludes it -> no results.
        viewModel.onQueryChange("News")
        testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.uiState.value.liveResults.isEmpty())

        // Text query matches the FR channel ("Sport"), and it does carry the FR tag -> one result.
        viewModel.onQueryChange("Sport")
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf("c10"), viewModel.uiState.value.liveResults.map { it.id })
    }

    @Test
    fun `onLanguageSelected triggers no additional repository fetch of any kind`() {
        createViewModel()
        stubLiveChannels("30", listOf(frChannel))
        stubMovies("40", listOf(frMovie))
        stubSeries("50", listOf(frSeries))

        liveCategoriesFlow.value = Resource.Success(listOf(frSportCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(frActionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(frDramaCategory))
        testDispatcher.scheduler.runCurrent()

        viewModel.onLanguageSelected("FR")
        testDispatcher.scheduler.runCurrent()
        viewModel.onLanguageSelected(null)
        testDispatcher.scheduler.runCurrent()

        // Purely in-memory post-processing — no new fetch of any kind is triggered.
        verify(exactly = 1) { catalogRepository.observeLiveCategories() }
        verify(exactly = 1) { catalogRepository.observeVodCategories() }
        verify(exactly = 1) { catalogRepository.observeSeriesCategories() }
        verify(exactly = 1) { catalogRepository.getLiveChannels("30") }
        verify(exactly = 1) { catalogRepository.getMovies("40") }
        verify(exactly = 1) { catalogRepository.getSeriesList("50") }
        verify(exactly = 0) { catalogRepository.invalidateCaches() }
    }
}

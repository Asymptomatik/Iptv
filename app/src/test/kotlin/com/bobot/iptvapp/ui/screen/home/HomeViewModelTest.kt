package com.bobot.iptvapp.ui.screen.home

import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.data.source.CredentialsProvider
import com.bobot.iptvapp.domain.model.Category
import com.bobot.iptvapp.domain.model.Channel
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.Episode
import com.bobot.iptvapp.domain.model.FavoriteItem
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.PlaybackProgress
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.repository.FavoritesRepository
import com.bobot.iptvapp.domain.repository.PlaybackProgressRepository
import com.bobot.iptvapp.domain.usecase.FilterCatalogByLanguageUseCase
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
 * Unit tests for [HomeViewModel] (Task 17 + Task 22 + Task 23 + Task 24-25 + on-demand catalog
 * loading OOM fix).
 *
 * Follows the exact `viewModelScope` testing convention established by
 * [com.bobot.iptvapp.ui.screen.profiles.ProfilesViewModelTest] (Task 16):
 * [Dispatchers.setMain] swaps in a [StandardTestDispatcher], and
 * `testDispatcher.scheduler.runCurrent()` deterministically drains the `init` block's reactive
 * collector (which never itself completes) after each simulated repository emission.
 *
 * [CatalogRepository] is a `mockk()` double (strict — every method invoked during a test must be
 * explicitly stubbed). Its three category Flow methods (`observeLiveCategories`,
 * `observeVodCategories`, `observeSeriesCategories`) are each stubbed to return one of this test's
 * own [MutableStateFlow]s directly (mirroring
 * [com.bobot.iptvapp.ui.screen.profiles.ProfilesViewModelTest]'s
 * `every { profileRepository.observeProfiles() } returns profilesFlow` pattern) so tests push new
 * [Resource] values to simulate the repository's reactive updates.
 *
 * ## On-demand catalog loading (OOM fix)
 * [HomeViewModel] no longer fetches any content type automatically at `init`. Instead,
 * [HomeViewModel.onCatalogTabSelected] must be called explicitly (as [HomeScreen] does when the
 * user switches tabs) before that content type's categories/items are fetched at all — see the
 * "On-demand loading" test group below, which asserts zero repository catalog calls before any
 * such call. Once triggered, items are still fetched **one category at a time** (never the
 * unfiltered, whole-catalog `categoryId = null` overload) — this test's [stubLiveChannels] /
 * [stubMovies] / [stubSeries] helpers stub the per-category overload (`getLiveChannels(categoryId)`
 * etc.) for a specific category id, mirroring how the production code calls it. A per-category Flow
 * is stubbed as an already-completed `flowOf(Resource.Success(...))` — [HomeViewModel] only ever
 * awaits its first non-[Resource.Loading] value, so a bare terminal value is sufficient and keeps
 * tests deterministic under [StandardTestDispatcher] without extra `runCurrent()` steps.
 *
 * Task 24-25 adds a default `coEvery { catalogRepository.getCachedEpisodeWithSeries(any()) }
 * returns null` stub in [setUp], overridden per-test with a specific episode id where a SERIES
 * cache hit is needed. The on-demand loading fix's MOVIE/SERIES fallbacks
 * ([HomeViewModel.resolveMovieOrFallback] / a private `resolveSeriesOrFallback`) are exercised via
 * `coEvery { catalogRepository.getMovieDetail(...) }` / `coEvery { catalogRepository.getSeriesDetail(...) }`
 * stubs, added per-test only where an entry is expected to miss the shared catalog state (e.g.
 * because its tab was never selected) and trigger the fallback — [CatalogRepository] being a strict
 * mock means any unstubbed fallback call would fail the test loudly instead of silently, which is
 * itself a useful correctness check. Crucially, several tests below deliberately never call
 * [HomeViewModel.onCatalogTabSelected] at all, to prove "Reprendre"/"Ma liste" populate correctly
 * even when no catalog tab has ever been opened.
 *
 * Task 22: [FavoritesRepository] and [AppPreferencesStore] are also `mockk()` doubles. The
 * preferences store's [getActiveProfileId] is stubbed to return a test profile ID or null;
 * the favorites repository's [observeFavorites] is stubbed to return a test-controlled
 * [MutableStateFlow] of [FavoriteItem] lists.
 *
 * Task 23: [PlaybackProgressRepository] and [CredentialsProvider] are also `mockk()` doubles,
 * following the exact same pattern — [observeContinueWatching] is stubbed to return a
 * test-controlled [MutableStateFlow] of [PlaybackProgress] lists, and [getCredentials] is
 * stubbed to return a test [XtreamCredentials] value used to build "Reprendre" cards'
 * `resumeStreamUrl`.
 */
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var catalogRepository: CatalogRepository
    private lateinit var favoritesRepository: FavoritesRepository
    private lateinit var playbackProgressRepository: PlaybackProgressRepository
    private lateinit var appPreferencesStore: AppPreferencesStore
    private lateinit var credentialsProvider: CredentialsProvider
    private lateinit var viewModel: HomeViewModel

    private lateinit var liveCategoriesFlow: MutableStateFlow<Resource<List<Category>>>
    private lateinit var vodCategoriesFlow: MutableStateFlow<Resource<List<Category>>>
    private lateinit var seriesCategoriesFlow: MutableStateFlow<Resource<List<Category>>>
    private lateinit var favoritesFlow: MutableStateFlow<List<FavoriteItem>>
    private lateinit var continueWatchingFlow: MutableStateFlow<List<PlaybackProgress>>

    private val sportCategory = Category(id = "1", name = "Sport", type = ContentType.LIVE)
    private val newsCategory = Category(id = "2", name = "News", type = ContentType.LIVE)
    private val chan1 = Channel(id = "c1", name = "Chan1", logoUrl = null, categoryId = "1", epgChannelId = null)

    private val actionCategory = Category(id = "10", name = "Action", type = ContentType.MOVIE)
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
    private val movie2 = Movie(
        id = "m2",
        title = "Vengeance Nocturne",
        posterUrl = null,
        plot = null,
        categoryId = "10",
        rating = null,
        year = null,
        addedMillis = null,
        durationMillis = null,
        containerExtension = "mkv",
    )

    private val dramaCategory = Category(id = "20", name = "Drames", type = ContentType.SERIES)
    private val series1 = Series(
        id = "s1",
        title = "La Casa de Papel",
        coverUrl = null,
        plot = null,
        categoryId = "20",
        rating = null,
        year = null,
    )

    // Task 24-25: series poster used specifically to assert Continue Watching series cards use
    // the parent series' title/poster (never the episode's own metadata).
    private val seriesForContinueWatching = Series(
        id = "s2",
        title = "Breaking Code",
        coverUrl = "http://example.com/s2-cover.jpg",
        plot = null,
        categoryId = "20",
        rating = null,
        year = null,
    )
    private val episode1 = Episode(
        id = "e1",
        title = "Pilot",
        episodeNumber = 1,
        seasonNumber = 1,
        plot = null,
        durationMillis = null,
        containerExtension = null,
        coverUrl = null,
    )
    private val episode2 = Episode(
        id = "e2",
        title = "Episode 2",
        episodeNumber = 2,
        seasonNumber = 1,
        plot = null,
        durationMillis = null,
        containerExtension = "mkv",
        coverUrl = null,
    )

    private val testCredentials = XtreamCredentials(
        baseUrl = "http://example.com:8080",
        username = "user",
        password = "pass",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        catalogRepository = mockk()
        favoritesRepository = mockk()
        playbackProgressRepository = mockk()
        appPreferencesStore = mockk()
        credentialsProvider = mockk()

        liveCategoriesFlow = MutableStateFlow(Resource.Loading)
        vodCategoriesFlow = MutableStateFlow(Resource.Loading)
        seriesCategoriesFlow = MutableStateFlow(Resource.Loading)
        favoritesFlow = MutableStateFlow(emptyList())
        continueWatchingFlow = MutableStateFlow(emptyList())

        every { catalogRepository.observeLiveCategories() } returns liveCategoriesFlow
        every { catalogRepository.observeVodCategories() } returns vodCategoriesFlow
        every { catalogRepository.observeSeriesCategories() } returns seriesCategoriesFlow
        every { catalogRepository.invalidateCaches() } just Runs

        // Task 22: Stub the preferences store to return a test profile ID by default.
        coEvery { appPreferencesStore.getActiveProfileId() } returns "profile-1"
        // Task 22: Stub the favorites repository to return the test favorites Flow.
        every { favoritesRepository.observeFavorites("profile-1") } returns favoritesFlow
        // Task 23: Stub the playback progress repository and credentials provider.
        every { playbackProgressRepository.observeContinueWatching("profile-1") } returns continueWatchingFlow
        coEvery { credentialsProvider.getCredentials() } returns testCredentials
        // Task 24-25: default stub for the cache-only series/episode resolution used by SERIES
        // continue-watching entries — catalogRepository is a strict mock, so every test must have
        // this stubbed even when it does not exercise the SERIES path.
        coEvery { catalogRepository.getCachedEpisodeWithSeries(any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Creates [viewModel] and drains its `init` block's reactive collector. */
    private fun createViewModel() {
        viewModel = HomeViewModel(
            catalogRepository,
            favoritesRepository,
            playbackProgressRepository,
            appPreferencesStore,
            credentialsProvider,
            FilterCatalogByLanguageUseCase(),
        )
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

    // ── On-demand catalog loading (OOM fix) ───────────────────────────────────

    @Test
    fun `no catalog content is fetched from the repository until a tab is selected`() {
        createViewModel()

        verify(exactly = 0) { catalogRepository.observeLiveCategories() }
        verify(exactly = 0) { catalogRepository.observeVodCategories() }
        verify(exactly = 0) { catalogRepository.observeSeriesCategories() }
        verify(exactly = 0) { catalogRepository.getLiveChannels(any()) }
        verify(exactly = 0) { catalogRepository.getMovies(any()) }
        verify(exactly = 0) { catalogRepository.getSeriesList(any()) }

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.hasAnyRows)
        assertNull(state.errorMessage)
        assertTrue(state.liveRows.isEmpty())
        assertTrue(state.movieRows.isEmpty())
        assertTrue(state.seriesRows.isEmpty())
    }

    @Test
    fun `onCatalogTabSelected triggers loading only for the requested content type`() {
        createViewModel()
        stubLiveChannels("1", listOf(chan1))

        viewModel.onCatalogTabSelected(ContentType.LIVE)
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) { catalogRepository.observeLiveCategories() }
        verify(exactly = 1) { catalogRepository.getLiveChannels("1") }
        // Movies and Series were never requested — must remain untouched.
        verify(exactly = 0) { catalogRepository.observeVodCategories() }
        verify(exactly = 0) { catalogRepository.observeSeriesCategories() }
        verify(exactly = 0) { catalogRepository.getMovies(any()) }
        verify(exactly = 0) { catalogRepository.getSeriesList(any()) }

        val state = viewModel.uiState.value
        assertEquals(1, state.liveRows.size)
        assertTrue(state.movieRows.isEmpty())
        assertTrue(state.seriesRows.isEmpty())
    }

    @Test
    fun `onCatalogTabSelected is idempotent — a second call for the same content type does not re-fetch`() {
        createViewModel()
        stubLiveChannels("1", listOf(chan1))

        viewModel.onCatalogTabSelected(ContentType.LIVE)
        viewModel.onCatalogTabSelected(ContentType.LIVE) // queued before the first load resolves
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        testDispatcher.scheduler.runCurrent()

        viewModel.onCatalogTabSelected(ContentType.LIVE) // called again after the load fully resolved
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) { catalogRepository.observeLiveCategories() }
        verify(exactly = 1) { catalogRepository.getLiveChannels("1") }
    }

    @Test
    fun `every category is fetched individually via getXxx(categoryId), never the unfiltered categoryId = null call`() {
        createViewModel()
        stubLiveChannels("1", listOf(chan1))
        stubLiveChannels("2", emptyList())
        stubMovies("10", listOf(movie1))
        stubSeries("20", listOf(series1))

        viewModel.onCatalogTabSelected(ContentType.LIVE)
        viewModel.onCatalogTabSelected(ContentType.MOVIE)
        viewModel.onCatalogTabSelected(ContentType.SERIES)
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

    // ── Grouping categories with content ──────────────────────────────────────

    @Test
    fun `sections group items by category and drop categories with no matching items`() {
        createViewModel()
        viewModel.onCatalogTabSelected(ContentType.LIVE)
        viewModel.onCatalogTabSelected(ContentType.MOVIE)
        viewModel.onCatalogTabSelected(ContentType.SERIES)

        stubLiveChannels("1", listOf(chan1))
        stubLiveChannels("2", emptyList())
        stubMovies("10", listOf(movie1))
        stubSeries("20", listOf(series1))

        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory, newsCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(dramaCategory))
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)

        // "News" has no channels -> dropped from the rows.
        assertEquals(
            listOf(
                HomeRow(
                    categoryId = "1",
                    title = "Sport",
                    items = listOf(
                        HomeCardItem(id = "c1", title = "Chan1", imageUrl = null, contentType = ContentType.LIVE),
                    ),
                ),
            ),
            state.liveRows,
        )
        assertEquals(
            listOf(
                HomeRow(
                    categoryId = "10",
                    title = "Action",
                    items = listOf(
                        HomeCardItem(id = "m1", title = "Explosion Totale", imageUrl = null, contentType = ContentType.MOVIE),
                    ),
                ),
            ),
            state.movieRows,
        )
        assertEquals(
            listOf(
                HomeRow(
                    categoryId = "20",
                    title = "Drames",
                    items = listOf(
                        HomeCardItem(id = "s1", title = "La Casa de Papel", imageUrl = null, contentType = ContentType.SERIES),
                    ),
                ),
            ),
            state.seriesRows,
        )
    }

    // ── Partial failure ────────────────────────────────────────────────────────

    @Test
    fun `an error in one requested tab is surfaced without wiping rows already loaded by another tab`() {
        createViewModel()
        viewModel.onCatalogTabSelected(ContentType.LIVE)
        viewModel.onCatalogTabSelected(ContentType.MOVIE)
        viewModel.onCatalogTabSelected(ContentType.SERIES)

        stubLiveChannels("1", listOf(chan1))
        stubMovies("10", listOf(movie1))

        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        seriesCategoriesFlow.value = Resource.Error(message = "Panne série")
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Panne série", state.errorMessage)
        assertTrue(state.liveRows.isNotEmpty())
        assertTrue(state.movieRows.isNotEmpty())
        assertTrue(state.seriesRows.isEmpty())
    }

    // ── Retry ──────────────────────────────────────────────────────────────────

    @Test
    fun `onRetry clears the error immediately and invalidates the repository cache`() {
        createViewModel()
        viewModel.onCatalogTabSelected(ContentType.LIVE)
        liveCategoriesFlow.value = Resource.Error(message = "Hors ligne")
        testDispatcher.scheduler.runCurrent()
        assertEquals("Hors ligne", viewModel.uiState.value.errorMessage)

        viewModel.onRetry()

        assertNull(viewModel.uiState.value.errorMessage)
        verify(exactly = 1) { catalogRepository.invalidateCaches() }
    }

    @Test
    fun `onRetry re-fetches every category for a tab already selected`() {
        stubLiveChannels("1", listOf(chan1))
        stubMovies("10", listOf(movie1))
        stubSeries("20", listOf(series1))

        createViewModel()
        viewModel.onCatalogTabSelected(ContentType.LIVE)
        viewModel.onCatalogTabSelected(ContentType.MOVIE)
        viewModel.onCatalogTabSelected(ContentType.SERIES)
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

    @Test
    fun `onRetry never fetches a content type whose tab was never selected`() {
        stubLiveChannels("1", listOf(chan1))

        createViewModel()
        viewModel.onCatalogTabSelected(ContentType.LIVE)
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        testDispatcher.scheduler.runCurrent()

        viewModel.onRetry()
        testDispatcher.scheduler.runCurrent()

        // Movies/Series tabs were never opened — onRetry must not start loading them.
        verify(exactly = 0) { catalogRepository.observeVodCategories() }
        verify(exactly = 0) { catalogRepository.observeSeriesCategories() }
        verify(exactly = 0) { catalogRepository.getMovies(any()) }
        verify(exactly = 0) { catalogRepository.getSeriesList(any()) }
    }

    // ── Task 22: Favorites ("Ma liste") ───────────────────────────────────────

    @Test
    fun `empty favorites list results in an empty myListRows section`() {
        createViewModel()
        viewModel.onCatalogTabSelected(ContentType.LIVE)
        viewModel.onCatalogTabSelected(ContentType.MOVIE)
        viewModel.onCatalogTabSelected(ContentType.SERIES)

        stubLiveChannels("1", listOf(chan1))
        stubMovies("10", listOf(movie1))
        stubSeries("20", listOf(series1))
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(dramaCategory))
        favoritesFlow.value = emptyList() // No favorites
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.myListRows.isEmpty())
        assertTrue(state.hasAnyRows) // Live/Films/Series rows are loaded — only "Ma liste" is empty
    }

    @Test
    fun `favorites row is populated when favorites exist and matching catalog items are loaded`() {
        createViewModel()
        viewModel.onCatalogTabSelected(ContentType.LIVE)
        viewModel.onCatalogTabSelected(ContentType.MOVIE)
        viewModel.onCatalogTabSelected(ContentType.SERIES)

        stubLiveChannels("1", listOf(chan1))
        stubMovies("10", listOf(movie1))
        stubSeries("20", listOf(series1))
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(dramaCategory))
        // FavoritesRepository returns items ordered by most recently added first.
        favoritesFlow.value = listOf(
            FavoriteItem(profileId = "profile-1", contentId = "s1", contentType = ContentType.SERIES, addedAt = 3000),
            FavoriteItem(profileId = "profile-1", contentId = "c1", contentType = ContentType.LIVE, addedAt = 2000),
            FavoriteItem(profileId = "profile-1", contentId = "m1", contentType = ContentType.MOVIE, addedAt = 1000),
        )
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(1, state.myListRows.size)
        val myListRow = state.myListRows.first()
        assertEquals("Ma liste", myListRow.title)
        assertEquals("my-list", myListRow.categoryId)
        assertEquals(3, myListRow.items.size)

        // Verify that items are in the correct order (most recently added first per FavoritesRepository contract).
        assertEquals("s1", myListRow.items[0].id)
        assertEquals("c1", myListRow.items[1].id)
        assertEquals("m1", myListRow.items[2].id)

        // All three types were already resolved from the loaded catalog tabs — no fallback needed.
        coVerify(exactly = 0) { catalogRepository.getMovieDetail(any()) }
        coVerify(exactly = 0) { catalogRepository.getSeriesDetail(any()) }
    }

    @Test
    fun `unmatched favorites are silently skipped instead of crashing`() {
        createViewModel()
        viewModel.onCatalogTabSelected(ContentType.LIVE)
        viewModel.onCatalogTabSelected(ContentType.MOVIE)
        viewModel.onCatalogTabSelected(ContentType.SERIES)

        stubLiveChannels("1", listOf(chan1))
        stubMovies("10", listOf(movie1))
        stubSeries("20", listOf(series1))
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(dramaCategory))
        // The unmatched MOVIE favorite below now triggers the getMovieDetail fallback (on-demand
        // loading fix) — stub it to also miss, so the entry is still silently skipped end-to-end.
        coEvery { catalogRepository.getMovieDetail("nonexistent") } returns Resource.Error(message = "not found")
        // Include an unmatched favorite; it should be silently skipped.
        favoritesFlow.value = listOf(
            FavoriteItem(profileId = "profile-1", contentId = "c1", contentType = ContentType.LIVE, addedAt = 3000),
            // This favorite has no matching movie/channel/series in the catalog — it will be skipped:
            FavoriteItem(profileId = "profile-1", contentId = "nonexistent", contentType = ContentType.MOVIE, addedAt = 2000),
            FavoriteItem(profileId = "profile-1", contentId = "m1", contentType = ContentType.MOVIE, addedAt = 1000),
        )
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(1, state.myListRows.size)
        val myListRow = state.myListRows.first()
        // Only the matched items appear; the unmatched "nonexistent" is silently dropped.
        assertEquals(2, myListRow.items.size)
        assertEquals("c1", myListRow.items[0].id)
        assertEquals("m1", myListRow.items[1].id)
    }

    @Test
    fun `no active profile results in an empty myListRows section and no favorites repository call`() {
        // Stub the preferences store to return null (no active profile).
        coEvery { appPreferencesStore.getActiveProfileId() } returns null
        createViewModel()
        viewModel.onCatalogTabSelected(ContentType.LIVE)
        viewModel.onCatalogTabSelected(ContentType.MOVIE)
        viewModel.onCatalogTabSelected(ContentType.SERIES)

        stubLiveChannels("1", listOf(chan1))
        stubMovies("10", listOf(movie1))
        stubSeries("20", listOf(series1))
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(dramaCategory))
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.myListRows.isEmpty())
        verify(exactly = 0) { favoritesRepository.observeFavorites(any()) }
    }

    @Test
    fun `favorites row reacts live to observeFavorites emitting a new list`() {
        createViewModel()
        viewModel.onCatalogTabSelected(ContentType.LIVE)
        viewModel.onCatalogTabSelected(ContentType.MOVIE)
        viewModel.onCatalogTabSelected(ContentType.SERIES)

        stubLiveChannels("1", listOf(chan1))
        stubMovies("10", listOf(movie1))
        stubSeries("20", listOf(series1))
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(dramaCategory))
        favoritesFlow.value = listOf(
            FavoriteItem(profileId = "profile-1", contentId = "m1", contentType = ContentType.MOVIE, addedAt = 1000),
        )
        testDispatcher.scheduler.runCurrent()

        var state = viewModel.uiState.value
        assertEquals(1, state.myListRows.size)
        assertEquals(1, state.myListRows.first().items.size)

        // Emit a new favorites list with a second item (most recently added first).
        favoritesFlow.value = listOf(
            FavoriteItem(profileId = "profile-1", contentId = "c1", contentType = ContentType.LIVE, addedAt = 2000),
            FavoriteItem(profileId = "profile-1", contentId = "m1", contentType = ContentType.MOVIE, addedAt = 1000),
        )
        testDispatcher.scheduler.runCurrent()

        state = viewModel.uiState.value
        assertEquals(1, state.myListRows.size)
        assertEquals(2, state.myListRows.first().items.size)
    }

    @Test
    fun `myListRows are preserved during partial reload when other sections start loading`() {
        createViewModel()
        viewModel.onCatalogTabSelected(ContentType.LIVE)
        viewModel.onCatalogTabSelected(ContentType.MOVIE)
        viewModel.onCatalogTabSelected(ContentType.SERIES)

        stubLiveChannels("1", listOf(chan1))
        stubMovies("10", listOf(movie1))
        stubSeries("20", listOf(series1))
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(dramaCategory))
        favoritesFlow.value = listOf(
            FavoriteItem(profileId = "profile-1", contentId = "m1", contentType = ContentType.MOVIE, addedAt = 1000),
        )
        testDispatcher.scheduler.runCurrent()

        val state1 = viewModel.uiState.value
        assertEquals(1, state1.myListRows.size)

        // Simulate a partial reload: some section starts loading, others remain.
        liveCategoriesFlow.value = Resource.Loading
        testDispatcher.scheduler.runCurrent()

        val state2 = viewModel.uiState.value
        // myListRows is preserved (not wiped to empty list) even though live categories are loading.
        assertEquals(1, state2.myListRows.size)
        assertEquals(state1.myListRows, state2.myListRows)
    }

    // ── "Ma liste" without any catalog tab selected (on-demand loading fix) ───

    @Test
    fun `MOVIE favorite falls back to getMovieDetail when the Films tab was never selected`() {
        createViewModel()
        coEvery { catalogRepository.getMovieDetail("m1") } returns Resource.Success(movie1)

        favoritesFlow.value = listOf(
            FavoriteItem(profileId = "profile-1", contentId = "m1", contentType = ContentType.MOVIE, addedAt = 1000),
        )
        testDispatcher.scheduler.runCurrent()

        val myListRow = viewModel.uiState.value.myListRows.first()
        assertEquals(listOf("m1"), myListRow.items.map { it.id })
        assertEquals("Explosion Totale", myListRow.items.first().title)
        coVerify(exactly = 1) { catalogRepository.getMovieDetail("m1") }
        // Confirms the fix: no catalog tab was ever selected for this scenario.
        verify(exactly = 0) { catalogRepository.observeVodCategories() }
    }

    @Test
    fun `MOVIE favorite already present in a loaded category does not trigger the getMovieDetail fallback`() {
        createViewModel()
        viewModel.onCatalogTabSelected(ContentType.MOVIE)
        stubMovies("10", listOf(movie1))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))

        favoritesFlow.value = listOf(
            FavoriteItem(profileId = "profile-1", contentId = "m1", contentType = ContentType.MOVIE, addedAt = 1000),
        )
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, viewModel.uiState.value.myListRows.first().items.size)
        coVerify(exactly = 0) { catalogRepository.getMovieDetail(any()) }
    }

    @Test
    fun `SERIES favorite falls back to getSeriesDetail when the Series tab was never selected`() {
        createViewModel()
        coEvery { catalogRepository.getSeriesDetail("s1") } returns Resource.Success(series1)

        favoritesFlow.value = listOf(
            FavoriteItem(profileId = "profile-1", contentId = "s1", contentType = ContentType.SERIES, addedAt = 1000),
        )
        testDispatcher.scheduler.runCurrent()

        val myListRow = viewModel.uiState.value.myListRows.first()
        assertEquals(listOf("s1"), myListRow.items.map { it.id })
        assertEquals("La Casa de Papel", myListRow.items.first().title)
        coVerify(exactly = 1) { catalogRepository.getSeriesDetail("s1") }
        verify(exactly = 0) { catalogRepository.observeSeriesCategories() }
    }

    @Test
    fun `SERIES favorite already present in a loaded category does not trigger the getSeriesDetail fallback`() {
        createViewModel()
        viewModel.onCatalogTabSelected(ContentType.SERIES)
        stubSeries("20", listOf(series1))
        seriesCategoriesFlow.value = Resource.Success(listOf(dramaCategory))

        favoritesFlow.value = listOf(
            FavoriteItem(profileId = "profile-1", contentId = "s1", contentType = ContentType.SERIES, addedAt = 1000),
        )
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, viewModel.uiState.value.myListRows.first().items.size)
        coVerify(exactly = 0) { catalogRepository.getSeriesDetail(any()) }
    }

    @Test
    fun `SERIES favorite is silently skipped when the getSeriesDetail fallback also misses`() {
        createViewModel()
        coEvery { catalogRepository.getSeriesDetail("missing") } returns Resource.Error(message = "not found")

        favoritesFlow.value = listOf(
            FavoriteItem(profileId = "profile-1", contentId = "missing", contentType = ContentType.SERIES, addedAt = 1000),
        )
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value.myListRows.isEmpty())
    }

    @Test
    fun `LIVE favorite is silently skipped when the Chaines tab was never selected (no fallback endpoint)`() {
        createViewModel()

        favoritesFlow.value = listOf(
            FavoriteItem(profileId = "profile-1", contentId = "c1", contentType = ContentType.LIVE, addedAt = 1000),
        )
        testDispatcher.scheduler.runCurrent()

        // Documented, accepted limitation: no single-item Xtream endpoint exists for a live channel.
        assertTrue(viewModel.uiState.value.myListRows.isEmpty())
    }

    // ── Task 23: Continue Watching ("Reprendre") ──────────────────────────────

    @Test
    fun `empty continue watching history results in an empty continueWatchingRows section`() {
        createViewModel()

        continueWatchingFlow.value = emptyList()
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.continueWatchingRows.isEmpty())
    }

    @Test
    fun `continue watching row is populated for a MOVIE entry via fallback even when the Films tab was never selected`() {
        createViewModel()
        coEvery { catalogRepository.getMovieDetail("m1") } returns Resource.Success(movie1)

        continueWatchingFlow.value = listOf(
            PlaybackProgress(
                contentId = "m1",
                contentType = ContentType.MOVIE,
                positionMillis = 30_000L,
                durationMillis = 100_000L,
                lastUpdatedMillis = 2000L,
                profileId = "profile-1",
            ),
        )
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(1, state.continueWatchingRows.size)
        val row = state.continueWatchingRows.first()
        assertEquals("Reprendre", row.title)
        assertEquals("continue-watching", row.categoryId)
        assertEquals(1, row.items.size)

        val card = row.items.first()
        assertEquals("m1", card.id)
        assertEquals("Explosion Totale", card.title)
        assertEquals(ContentType.MOVIE, card.contentType)
        // movie1.containerExtension is null -> falls back to "mp4" (mirrors MovieDetailViewModel).
        assertEquals("http://example.com:8080/movie/user/pass/m1.mp4", card.resumeStreamUrl)
        coVerify(exactly = 1) { catalogRepository.getMovieDetail("m1") }
        verify(exactly = 0) { catalogRepository.observeVodCategories() }
    }

    @Test
    fun `continue watching row preserves observeContinueWatching ordering without re-sorting`() {
        createViewModel()
        viewModel.onCatalogTabSelected(ContentType.MOVIE)
        stubMovies("10", listOf(movie1, movie2))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        // observeContinueWatching contract: most-recently-updated first. m2 first even though
        // its lastUpdatedMillis is not sorted here — the ViewModel must trust the input order.
        continueWatchingFlow.value = listOf(
            PlaybackProgress(
                contentId = "m2",
                contentType = ContentType.MOVIE,
                positionMillis = 5_000L,
                durationMillis = 50_000L,
                lastUpdatedMillis = 9000L,
                profileId = "profile-1",
            ),
            PlaybackProgress(
                contentId = "m1",
                contentType = ContentType.MOVIE,
                positionMillis = 30_000L,
                durationMillis = 100_000L,
                lastUpdatedMillis = 2000L,
                profileId = "profile-1",
            ),
        )
        testDispatcher.scheduler.runCurrent()

        val items = viewModel.uiState.value.continueWatchingRows.first().items
        assertEquals(listOf("m2", "m1"), items.map { it.id })
        // movie2.containerExtension = "mkv" -> used as-is (no fallback needed).
        assertEquals("http://example.com:8080/movie/user/pass/m2.mkv", items[0].resumeStreamUrl)
        coVerify(exactly = 0) { catalogRepository.getMovieDetail(any()) }
    }

    @Test
    fun `LIVE progress entries are always skipped from the continue watching row`() {
        createViewModel()

        continueWatchingFlow.value = listOf(
            // Defensive filter: a stale LIVE record should never surface here (Task 23).
            PlaybackProgress(
                contentId = "c1",
                contentType = ContentType.LIVE,
                positionMillis = 10_000L,
                durationMillis = 0L,
                lastUpdatedMillis = 5000L,
                profileId = "profile-1",
            ),
        )
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.continueWatchingRows.isEmpty())
    }

    // ── Task 24-25: Continue Watching series support ──────────────────────────

    @Test
    fun `SERIES progress entry resolves to a card using the parent series title, poster and episode id`() {
        coEvery { catalogRepository.getCachedEpisodeWithSeries("e1") } returns (seriesForContinueWatching to episode1)
        createViewModel()

        continueWatchingFlow.value = listOf(
            PlaybackProgress(
                contentId = "e1",
                contentType = ContentType.SERIES,
                positionMillis = 10_000L,
                durationMillis = 40_000L,
                lastUpdatedMillis = 5000L,
                profileId = "profile-1",
            ),
        )
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(1, state.continueWatchingRows.size)
        val items = state.continueWatchingRows.first().items
        assertEquals(1, items.size)

        val card = items.first()
        // Critical correctness property: the card id is the *episode* id, not the series id —
        // HomeScreen forwards this id straight through to the player as the next saveProgress()
        // contentId.
        assertEquals("e1", card.id)
        assertEquals(seriesForContinueWatching.title, card.title)
        assertEquals(seriesForContinueWatching.coverUrl, card.imageUrl)
        assertEquals(ContentType.SERIES, card.contentType)
        // episode1.containerExtension is null -> falls back to "mp4".
        assertEquals("http://example.com:8080/series/user/pass/e1.mp4", card.resumeStreamUrl)
        // Cache-only resolution — no catalog tab needed.
        verify(exactly = 0) { catalogRepository.observeSeriesCategories() }
    }

    @Test
    fun `SERIES progress entry uses the episode container extension as-is when present`() {
        coEvery { catalogRepository.getCachedEpisodeWithSeries("e2") } returns (seriesForContinueWatching to episode2)
        createViewModel()

        continueWatchingFlow.value = listOf(
            PlaybackProgress(
                contentId = "e2",
                contentType = ContentType.SERIES,
                positionMillis = 10_000L,
                durationMillis = 40_000L,
                lastUpdatedMillis = 5000L,
                profileId = "profile-1",
            ),
        )
        testDispatcher.scheduler.runCurrent()

        val card = viewModel.uiState.value.continueWatchingRows.first().items.first()
        assertEquals("e2", card.id)
        // episode2.containerExtension = "mkv" -> used as-is (no fallback needed).
        assertEquals("http://example.com:8080/series/user/pass/e2.mkv", card.resumeStreamUrl)
    }

    @Test
    fun `SERIES progress entry is silently skipped when the episode-series pair is not cached`() {
        // Default setUp() stub already returns null for any episode id — simulate a cache miss.
        createViewModel()

        continueWatchingFlow.value = listOf(
            PlaybackProgress(
                contentId = "e1",
                contentType = ContentType.SERIES,
                positionMillis = 10_000L,
                durationMillis = 0L,
                lastUpdatedMillis = 4000L,
                profileId = "profile-1",
            ),
        )
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.continueWatchingRows.isEmpty())
    }

    @Test
    fun `continue watching row preserves interleaved recency ordering across movie and series entries`() {
        coEvery { catalogRepository.getCachedEpisodeWithSeries("e1") } returns (seriesForContinueWatching to episode1)
        createViewModel()
        viewModel.onCatalogTabSelected(ContentType.MOVIE)
        stubMovies("10", listOf(movie1, movie2))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        // Interleaved recency: movie2 (t=9000) -> series (t=5000) -> movie1 (t=1000). The row must
        // preserve this exact order — grouping by type would silently break it.
        continueWatchingFlow.value = listOf(
            PlaybackProgress(
                contentId = "m2",
                contentType = ContentType.MOVIE,
                positionMillis = 5_000L,
                durationMillis = 50_000L,
                lastUpdatedMillis = 9000L,
                profileId = "profile-1",
            ),
            PlaybackProgress(
                contentId = "e1",
                contentType = ContentType.SERIES,
                positionMillis = 10_000L,
                durationMillis = 40_000L,
                lastUpdatedMillis = 5000L,
                profileId = "profile-1",
            ),
            PlaybackProgress(
                contentId = "m1",
                contentType = ContentType.MOVIE,
                positionMillis = 30_000L,
                durationMillis = 100_000L,
                lastUpdatedMillis = 1000L,
                profileId = "profile-1",
            ),
        )
        testDispatcher.scheduler.runCurrent()

        val items = viewModel.uiState.value.continueWatchingRows.first().items
        assertEquals(listOf("m2", "e1", "m1"), items.map { it.id })
    }

    @Test
    fun `unmatched movie progress is silently skipped instead of crashing`() {
        createViewModel()
        // The unmatched entry below triggers the getMovieDetail fallback (on-demand loading fix) —
        // stub it to also miss, so the entry is still silently skipped end-to-end.
        coEvery { catalogRepository.getMovieDetail("nonexistent") } returns Resource.Error(message = "not found")
        continueWatchingFlow.value = listOf(
            PlaybackProgress(
                contentId = "nonexistent",
                contentType = ContentType.MOVIE,
                positionMillis = 10_000L,
                durationMillis = 0L,
                lastUpdatedMillis = 3000L,
                profileId = "profile-1",
            ),
        )
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.continueWatchingRows.isEmpty())
    }

    @Test
    fun `no active profile results in an empty continueWatchingRows section and no repository call`() {
        coEvery { appPreferencesStore.getActiveProfileId() } returns null
        createViewModel()

        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.continueWatchingRows.isEmpty())
        verify(exactly = 0) { playbackProgressRepository.observeContinueWatching(any()) }
    }

    @Test
    fun `no credentials configured results in an empty continueWatchingRows section`() {
        coEvery { credentialsProvider.getCredentials() } returns null
        createViewModel()

        continueWatchingFlow.value = listOf(
            PlaybackProgress(
                contentId = "m1",
                contentType = ContentType.MOVIE,
                positionMillis = 30_000L,
                durationMillis = 100_000L,
                lastUpdatedMillis = 2000L,
                profileId = "profile-1",
            ),
        )
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.continueWatchingRows.isEmpty())
    }

    @Test
    fun `continue watching row reacts live to observeContinueWatching emitting a new list`() {
        createViewModel()
        viewModel.onCatalogTabSelected(ContentType.MOVIE)
        stubMovies("10", listOf(movie1, movie2))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        continueWatchingFlow.value = listOf(
            PlaybackProgress(
                contentId = "m1",
                contentType = ContentType.MOVIE,
                positionMillis = 30_000L,
                durationMillis = 100_000L,
                lastUpdatedMillis = 2000L,
                profileId = "profile-1",
            ),
        )
        testDispatcher.scheduler.runCurrent()

        var state = viewModel.uiState.value
        assertEquals(1, state.continueWatchingRows.first().items.size)

        continueWatchingFlow.value = listOf(
            PlaybackProgress(
                contentId = "m2",
                contentType = ContentType.MOVIE,
                positionMillis = 5_000L,
                durationMillis = 50_000L,
                lastUpdatedMillis = 9000L,
                profileId = "profile-1",
            ),
            PlaybackProgress(
                contentId = "m1",
                contentType = ContentType.MOVIE,
                positionMillis = 30_000L,
                durationMillis = 100_000L,
                lastUpdatedMillis = 2000L,
                profileId = "profile-1",
            ),
        )
        testDispatcher.scheduler.runCurrent()

        state = viewModel.uiState.value
        assertEquals(2, state.continueWatchingRows.first().items.size)
    }

    @Test
    fun `continueWatchingRows is preserved during partial reload when other sections start loading`() {
        createViewModel()
        viewModel.onCatalogTabSelected(ContentType.LIVE)
        viewModel.onCatalogTabSelected(ContentType.MOVIE)
        stubMovies("10", listOf(movie1))
        liveCategoriesFlow.value = Resource.Success(emptyList())
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        continueWatchingFlow.value = listOf(
            PlaybackProgress(
                contentId = "m1",
                contentType = ContentType.MOVIE,
                positionMillis = 30_000L,
                durationMillis = 100_000L,
                lastUpdatedMillis = 2000L,
                profileId = "profile-1",
            ),
        )
        testDispatcher.scheduler.runCurrent()

        val state1 = viewModel.uiState.value
        assertEquals(1, state1.continueWatchingRows.size)

        // Simulate a partial reload: some section starts loading, others remain.
        liveCategoriesFlow.value = Resource.Loading
        testDispatcher.scheduler.runCurrent()

        val state2 = viewModel.uiState.value
        assertEquals(1, state2.continueWatchingRows.size)
        assertEquals(state1.continueWatchingRows, state2.continueWatchingRows)
    }

    // ── Continue Watching MOVIE fallback vs. shared state reuse ───────────────

    @Test
    fun `MOVIE continue watching entry already present in a loaded category does not trigger the getMovieDetail fallback`() {
        createViewModel()
        viewModel.onCatalogTabSelected(ContentType.MOVIE)
        stubMovies("10", listOf(movie1))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))

        continueWatchingFlow.value = listOf(
            PlaybackProgress(
                contentId = "m1",
                contentType = ContentType.MOVIE,
                positionMillis = 30_000L,
                durationMillis = 100_000L,
                lastUpdatedMillis = 2000L,
                profileId = "profile-1",
            ),
        )
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, viewModel.uiState.value.continueWatchingRows.first().items.size)
        coVerify(exactly = 0) { catalogRepository.getMovieDetail(any()) }
    }

    @Test
    fun `MOVIE continue watching entry is silently skipped when the getMovieDetail fallback also misses`() {
        createViewModel()
        coEvery { catalogRepository.getMovieDetail("missing") } returns Resource.Error(message = "not found")

        continueWatchingFlow.value = listOf(
            PlaybackProgress(
                contentId = "missing",
                contentType = ContentType.MOVIE,
                positionMillis = 1_000L,
                durationMillis = 2_000L,
                lastUpdatedMillis = 500L,
                profileId = "profile-1",
            ),
        )
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value.continueWatchingRows.isEmpty())
    }

    // ── Home tab populates without any catalog tab ever selected ─────────────

    @Test
    fun `Reprendre and Ma liste both populate correctly when no catalog tab has ever been selected`() {
        coEvery { catalogRepository.getMovieDetail("m1") } returns Resource.Success(movie1)
        coEvery { catalogRepository.getSeriesDetail("s1") } returns Resource.Success(series1)
        coEvery { catalogRepository.getCachedEpisodeWithSeries("e1") } returns (seriesForContinueWatching to episode1)
        createViewModel()

        continueWatchingFlow.value = listOf(
            PlaybackProgress(
                contentId = "e1",
                contentType = ContentType.SERIES,
                positionMillis = 10_000L,
                durationMillis = 40_000L,
                lastUpdatedMillis = 5000L,
                profileId = "profile-1",
            ),
        )
        favoritesFlow.value = listOf(
            FavoriteItem(profileId = "profile-1", contentId = "m1", contentType = ContentType.MOVIE, addedAt = 2000),
            FavoriteItem(profileId = "profile-1", contentId = "s1", contentType = ContentType.SERIES, addedAt = 1000),
        )
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(1, state.continueWatchingRows.size)
        assertEquals("e1", state.continueWatchingRows.first().items.first().id)

        assertEquals(1, state.myListRows.size)
        assertEquals(listOf("m1", "s1"), state.myListRows.first().items.map { it.id })

        // No catalog tab was ever requested for this scenario.
        verify(exactly = 0) { catalogRepository.observeLiveCategories() }
        verify(exactly = 0) { catalogRepository.observeVodCategories() }
        verify(exactly = 0) { catalogRepository.observeSeriesCategories() }
        assertTrue(state.liveRows.isEmpty())
        assertTrue(state.movieRows.isEmpty())
        assertTrue(state.seriesRows.isEmpty())
    }

    // ── Per-tab language filter ────────────────────────────────────────────────

    @Test
    fun `available languages for a tab are derived from distinct tags detected in loaded categories, growing as more categories load`() {
        createViewModel()
        val frSport = Category(id = "30", name = "FR | Sport", type = ContentType.LIVE)
        val enNews = Category(id = "31", name = "EN | News", type = ContentType.LIVE)
        val untaggedKids = Category(id = "32", name = "Kids", type = ContentType.LIVE)

        stubLiveChannels("30", listOf(Channel(id = "c30", name = "FRChan", logoUrl = null, categoryId = "30", epgChannelId = null)))

        viewModel.onCatalogTabSelected(ContentType.LIVE)
        liveCategoriesFlow.value = Resource.Success(listOf(frSport))
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("FR"), viewModel.uiState.value.liveLanguages)
        // No filter selected by default -> "Toutes".
        assertNull(viewModel.uiState.value.selectedLiveLanguage)

        // Two more categories load progressively — one more tagged (EN), one untagged (Kids).
        liveCategoriesFlow.value = Resource.Success(listOf(frSport, enNews, untaggedKids))
        testDispatcher.scheduler.runCurrent()

        // The untagged "Kids" category never contributes a tag to the available-languages list.
        assertEquals(listOf("FR", "EN"), viewModel.uiState.value.liveLanguages)
    }

    @Test
    fun `selecting a language filters live rows to only the matching categories, excluding categories with no detectable tag`() {
        createViewModel()
        val frSport = Category(id = "30", name = "FR | Sport", type = ContentType.LIVE)
        val enNews = Category(id = "31", name = "EN | News", type = ContentType.LIVE)
        val untaggedKids = Category(id = "32", name = "Kids", type = ContentType.LIVE)

        stubLiveChannels("30", listOf(Channel(id = "c30", name = "FRChan", logoUrl = null, categoryId = "30", epgChannelId = null)))
        stubLiveChannels("31", listOf(Channel(id = "c31", name = "ENChan", logoUrl = null, categoryId = "31", epgChannelId = null)))
        stubLiveChannels("32", listOf(Channel(id = "c32", name = "KidsChan", logoUrl = null, categoryId = "32", epgChannelId = null)))

        viewModel.onCatalogTabSelected(ContentType.LIVE)
        liveCategoriesFlow.value = Resource.Success(listOf(frSport, enNews, untaggedKids))
        testDispatcher.scheduler.runCurrent()

        // Sanity: under "Toutes" (no filter, default), every category with items is shown.
        assertEquals(setOf("30", "31", "32"), viewModel.uiState.value.liveRows.map { it.categoryId }.toSet())

        viewModel.onLanguageSelected(ContentType.LIVE, "FR")
        testDispatcher.scheduler.runCurrent()

        // Only the FR-tagged category remains — the untagged "Kids" category is excluded, not just
        // the non-matching EN one, per the brief ("une catégorie sans tag détectable est masquée
        // dès qu'un filtre précis est actif").
        assertEquals(listOf("30"), viewModel.uiState.value.liveRows.map { it.categoryId })
        assertEquals("FR", viewModel.uiState.value.selectedLiveLanguage)
    }

    @Test
    fun `selecting Toutes (null) restores every previously filtered row`() {
        createViewModel()
        val frSport = Category(id = "30", name = "FR | Sport", type = ContentType.LIVE)
        val untaggedKids = Category(id = "32", name = "Kids", type = ContentType.LIVE)

        stubLiveChannels("30", listOf(Channel(id = "c30", name = "FRChan", logoUrl = null, categoryId = "30", epgChannelId = null)))
        stubLiveChannels("32", listOf(Channel(id = "c32", name = "KidsChan", logoUrl = null, categoryId = "32", epgChannelId = null)))

        viewModel.onCatalogTabSelected(ContentType.LIVE)
        liveCategoriesFlow.value = Resource.Success(listOf(frSport, untaggedKids))
        testDispatcher.scheduler.runCurrent()

        viewModel.onLanguageSelected(ContentType.LIVE, "FR")
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf("30"), viewModel.uiState.value.liveRows.map { it.categoryId })

        viewModel.onLanguageSelected(ContentType.LIVE, null)
        testDispatcher.scheduler.runCurrent()

        assertEquals(setOf("30", "32"), viewModel.uiState.value.liveRows.map { it.categoryId }.toSet())
        assertNull(viewModel.uiState.value.selectedLiveLanguage)
    }

    @Test
    fun `language selection is independent per content type`() {
        createViewModel()
        viewModel.onCatalogTabSelected(ContentType.LIVE)
        viewModel.onCatalogTabSelected(ContentType.MOVIE)
        stubLiveChannels("1", listOf(chan1))
        stubMovies("10", listOf(movie1))
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        testDispatcher.scheduler.runCurrent()

        viewModel.onLanguageSelected(ContentType.LIVE, "FR")
        testDispatcher.scheduler.runCurrent()

        // Selecting a LIVE-tab filter must not affect the MOVIE tab's own selection or rows.
        assertEquals("FR", viewModel.uiState.value.selectedLiveLanguage)
        assertNull(viewModel.uiState.value.selectedMovieLanguage)
        assertEquals(1, viewModel.uiState.value.movieRows.size)
    }

    @Test
    fun `series categories group every FR-prefixed provider category into a single FR row`() {
        createViewModel()
        val frLatestA = Category(id = "40", name = "SRS | FR - LATEST SERIES", type = ContentType.SERIES)
        val frLatestB = Category(id = "41", name = "ALT | FR - LATEST SERIES", type = ContentType.SERIES)
        val enLatest = Category(id = "42", name = "SRS | EN - LATEST SERIES", type = ContentType.SERIES)
        val seriesA = series1.copy(id = "sf1", categoryId = "40", title = "Serie FR 1")
        val seriesB = series1.copy(id = "sf2", categoryId = "41", title = "Serie FR 2")
        val seriesEn = series1.copy(id = "se1", categoryId = "42", title = "Serie EN")

        stubSeries("40", listOf(seriesA))
        stubSeries("41", listOf(seriesB))
        stubSeries("42", listOf(seriesEn))

        viewModel.onCatalogTabSelected(ContentType.SERIES)
        seriesCategoriesFlow.value = Resource.Success(listOf(frLatestA, frLatestB, enLatest))
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("FR", "EN"), viewModel.uiState.value.seriesLanguages)

        viewModel.onLanguageSelected(ContentType.SERIES, "FR")
        testDispatcher.scheduler.runCurrent()

        assertEquals("FR", viewModel.uiState.value.selectedSeriesLanguage)
        assertEquals(1, viewModel.uiState.value.seriesRows.size)
        assertEquals("FR", viewModel.uiState.value.seriesRows.single().title)
        assertEquals(listOf("sf1", "sf2"), viewModel.uiState.value.seriesRows.single().items.map { it.id })
    }

    @Test
    fun `onLanguageSelected does not trigger any additional repository fetch`() {
        createViewModel()
        stubLiveChannels("1", listOf(chan1))
        viewModel.onCatalogTabSelected(ContentType.LIVE)
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        testDispatcher.scheduler.runCurrent()

        viewModel.onLanguageSelected(ContentType.LIVE, "FR")
        testDispatcher.scheduler.runCurrent()
        viewModel.onLanguageSelected(ContentType.LIVE, null)
        testDispatcher.scheduler.runCurrent()

        // Purely in-memory post-processing — no new fetch of any kind is triggered.
        verify(exactly = 1) { catalogRepository.observeLiveCategories() }
        verify(exactly = 1) { catalogRepository.getLiveChannels("1") }
        verify(exactly = 0) { catalogRepository.observeVodCategories() }
        verify(exactly = 0) { catalogRepository.observeSeriesCategories() }
        verify(exactly = 0) { catalogRepository.getMovies(any()) }
        verify(exactly = 0) { catalogRepository.getSeriesList(any()) }
        verify(exactly = 0) { catalogRepository.invalidateCaches() }
    }
}

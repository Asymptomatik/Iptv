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
import com.bobot.iptvapp.domain.util.Resource
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
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
 * Unit tests for [HomeViewModel] (Task 17 + Task 22 + Task 23 + Task 24-25 + OOM fix).
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
 * ## Category-scoped loading (OOM fix)
 * [HomeViewModel] no longer calls `getLiveChannels(null)` / `getMovies(null)` /
 * `getSeriesList(null)` (the unfiltered, whole-catalog calls that caused the OOM). Instead it
 * fetches items **one category at a time**, sequentially, content type by content type. This
 * test's [stubLiveChannels] / [stubMovies] / [stubSeries] helpers stub the per-category overload
 * (`getLiveChannels(categoryId)` etc.) for a specific category id, mirroring how the production
 * code now calls it. A per-category Flow is stubbed as an already-completed
 * `flowOf(Resource.Success(...))` — [HomeViewModel] only ever awaits its first non-[Resource.Loading]
 * value, so a bare terminal value is sufficient and keeps tests deterministic under
 * [StandardTestDispatcher] without extra `runCurrent()` steps.
 *
 * Task 24-25 adds a default `coEvery { catalogRepository.getCachedEpisodeWithSeries(any()) }
 * returns null` stub in [setUp], overridden per-test with a specific episode id where a SERIES
 * cache hit is needed. The OOM fix's MOVIE fallback ([HomeViewModel.resolveMovieOrFallback]) is
 * exercised via `coEvery { catalogRepository.getMovieDetail(...) }` stubs, added per-test only
 * where a MOVIE entry is expected to miss the category-scoped movies state and trigger the
 * fallback — [CatalogRepository] being a strict mock means any unstubbed fallback call would fail
 * the test loudly instead of silently, which is itself a useful correctness check.
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

    // ── Initial loading state ─────────────────────────────────────────────────

    @Test
    fun `init with every section still loading shows the loading state and no rows`() {
        createViewModel()

        val state = viewModel.uiState.value
        assertTrue(state.isLoading)
        assertFalse(state.hasAnyRows)
        assertNull(state.errorMessage)
    }

    // ── Grouping categories with content ──────────────────────────────────────

    @Test
    fun `sections group items by category and drop categories with no matching items`() {
        createViewModel()

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
    fun `an error in one section is surfaced without wiping rows already loaded by another section`() {
        createViewModel()

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
        liveCategoriesFlow.value = Resource.Error(message = "Hors ligne")
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

    // ── Task 22: Favorites ("Ma liste") ───────────────────────────────────────

    @Test
    fun `empty favorites list results in an empty myListRows section`() {
        createViewModel()

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
    }

    @Test
    fun `unmatched favorites are silently skipped instead of crashing`() {
        createViewModel()

        stubLiveChannels("1", listOf(chan1))
        stubMovies("10", listOf(movie1))
        stubSeries("20", listOf(series1))
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(dramaCategory))
        // The unmatched MOVIE favorite below now triggers the getMovieDetail fallback (OOM fix) —
        // stub it to also miss, so the entry is still silently skipped end-to-end.
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
    fun `no active profile results in an empty myListRows section`() {
        // Stub the preferences store to return null (no active profile).
        coEvery { appPreferencesStore.getActiveProfileId() } returns null
        createViewModel()

        stubLiveChannels("1", listOf(chan1))
        stubMovies("10", listOf(movie1))
        stubSeries("20", listOf(series1))
        liveCategoriesFlow.value = Resource.Success(listOf(sportCategory))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        seriesCategoriesFlow.value = Resource.Success(listOf(dramaCategory))
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.myListRows.isEmpty())
    }

    @Test
    fun `favorites row reacts live to observeFavorites emitting a new list`() {
        createViewModel()

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

    // ── OOM fix: MOVIE fallback for "Ma liste" ─────────────────────────────────

    @Test
    fun `MOVIE favorite not found in any loaded category falls back to getMovieDetail instead of being skipped`() {
        createViewModel()

        // Live must resolve too — the sequential loader processes Live before Movies (see class
        // KDoc "Category-scoped loading"), so Movies never progresses past Loading otherwise.
        liveCategoriesFlow.value = Resource.Success(emptyList())
        // No movie categories loaded at all for "m1" — the category-scoped state never resolves it.
        vodCategoriesFlow.value = Resource.Success(emptyList())
        coEvery { catalogRepository.getMovieDetail("m1") } returns Resource.Success(movie1)

        favoritesFlow.value = listOf(
            FavoriteItem(profileId = "profile-1", contentId = "m1", contentType = ContentType.MOVIE, addedAt = 1000),
        )
        testDispatcher.scheduler.runCurrent()

        val myListRow = viewModel.uiState.value.myListRows.first()
        assertEquals(listOf("m1"), myListRow.items.map { it.id })
        assertEquals("Explosion Totale", myListRow.items.first().title)
        coVerify(exactly = 1) { catalogRepository.getMovieDetail("m1") }
    }

    @Test
    fun `MOVIE favorite already present in a loaded category does not trigger the getMovieDetail fallback`() {
        createViewModel()

        // Live must resolve too — the sequential loader processes Live before Movies, so Movies
        // never progresses past Loading (and movie1 would never be found) otherwise.
        liveCategoriesFlow.value = Resource.Success(emptyList())
        stubMovies("10", listOf(movie1))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))

        favoritesFlow.value = listOf(
            FavoriteItem(profileId = "profile-1", contentId = "m1", contentType = ContentType.MOVIE, addedAt = 1000),
        )
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, viewModel.uiState.value.myListRows.first().items.size)
        coVerify(exactly = 0) { catalogRepository.getMovieDetail(any()) }
    }

    // ── Task 23: Continue Watching ("Reprendre") ──────────────────────────────

    @Test
    fun `empty continue watching history results in an empty continueWatchingRows section`() {
        createViewModel()

        liveCategoriesFlow.value = Resource.Success(emptyList())
        stubMovies("10", listOf(movie1))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        continueWatchingFlow.value = emptyList()
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.continueWatchingRows.isEmpty())
    }

    @Test
    fun `continue watching row is populated for MOVIE entries with a resolved stream URL`() {
        createViewModel()

        // Live must resolve first — the sequential loader processes Live before Movies (see class
        // KDoc "Category-scoped loading"), so Movies never progresses past Loading otherwise.
        liveCategoriesFlow.value = Resource.Success(emptyList())
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
    }

    @Test
    fun `continue watching row preserves observeContinueWatching ordering without re-sorting`() {
        createViewModel()

        liveCategoriesFlow.value = Resource.Success(emptyList())
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
    }

    @Test
    fun `LIVE progress entries are always skipped from the continue watching row`() {
        createViewModel()

        liveCategoriesFlow.value = Resource.Success(emptyList())
        stubMovies("10", listOf(movie1))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
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
        val items = state.continueWatchingRows.first().items
        assertEquals(1, items.size)
        assertEquals("m1", items.first().id)
    }

    // ── Task 24-25: Continue Watching series support ──────────────────────────

    @Test
    fun `SERIES progress entry resolves to a card using the parent series title, poster and episode id`() {
        coEvery { catalogRepository.getCachedEpisodeWithSeries("e1") } returns (seriesForContinueWatching to episode1)
        createViewModel()

        vodCategoriesFlow.value = Resource.Success(emptyList())
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
    }

    @Test
    fun `SERIES progress entry uses the episode container extension as-is when present`() {
        coEvery { catalogRepository.getCachedEpisodeWithSeries("e2") } returns (seriesForContinueWatching to episode2)
        createViewModel()

        vodCategoriesFlow.value = Resource.Success(emptyList())
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

        vodCategoriesFlow.value = Resource.Success(emptyList())
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

        liveCategoriesFlow.value = Resource.Success(emptyList())
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

        liveCategoriesFlow.value = Resource.Success(emptyList())
        stubMovies("10", listOf(movie1))
        vodCategoriesFlow.value = Resource.Success(listOf(actionCategory))
        // The unmatched entry below now triggers the getMovieDetail fallback (OOM fix) — stub it
        // to also miss, so the entry is still silently skipped end-to-end.
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

        vodCategoriesFlow.value = Resource.Success(emptyList())
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.continueWatchingRows.isEmpty())
        verify(exactly = 0) { playbackProgressRepository.observeContinueWatching(any()) }
    }

    @Test
    fun `no credentials configured results in an empty continueWatchingRows section`() {
        coEvery { credentialsProvider.getCredentials() } returns null
        createViewModel()

        vodCategoriesFlow.value = Resource.Success(emptyList())
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

        liveCategoriesFlow.value = Resource.Success(emptyList())
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

        liveCategoriesFlow.value = Resource.Success(emptyList())
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

        val state1 = viewModel.uiState.value
        assertEquals(1, state1.continueWatchingRows.size)

        // Simulate a partial reload: some section starts loading, others remain.
        liveCategoriesFlow.value = Resource.Loading
        testDispatcher.scheduler.runCurrent()

        val state2 = viewModel.uiState.value
        assertEquals(1, state2.continueWatchingRows.size)
        assertEquals(state1.continueWatchingRows, state2.continueWatchingRows)
    }

    // ── OOM fix: MOVIE fallback for "Reprendre" ────────────────────────────────

    @Test
    fun `MOVIE continue watching entry not found in any loaded category falls back to getMovieDetail`() {
        createViewModel()

        // Live must resolve too — the sequential loader processes Live before Movies (see class
        // KDoc "Category-scoped loading"), so Movies never progresses past Loading otherwise.
        liveCategoriesFlow.value = Resource.Success(emptyList())
        // No movie categories loaded at all for "m2" — the category-scoped state never resolves it.
        vodCategoriesFlow.value = Resource.Success(emptyList())
        coEvery { catalogRepository.getMovieDetail("m2") } returns Resource.Success(movie2)

        continueWatchingFlow.value = listOf(
            PlaybackProgress(
                contentId = "m2",
                contentType = ContentType.MOVIE,
                positionMillis = 5_000L,
                durationMillis = 50_000L,
                lastUpdatedMillis = 9000L,
                profileId = "profile-1",
            ),
        )
        testDispatcher.scheduler.runCurrent()

        val items = viewModel.uiState.value.continueWatchingRows.first().items
        assertEquals(listOf("m2"), items.map { it.id })
        // movie2.containerExtension = "mkv" -> resolved via the fallback, still used as-is.
        assertEquals("http://example.com:8080/movie/user/pass/m2.mkv", items.first().resumeStreamUrl)
        coVerify(exactly = 1) { catalogRepository.getMovieDetail("m2") }
    }

    @Test
    fun `MOVIE continue watching entry already present in a loaded category does not trigger the getMovieDetail fallback`() {
        createViewModel()

        // Live must resolve too — the sequential loader processes Live before Movies, so Movies
        // never progresses past Loading (and movie1 would never be found) otherwise.
        liveCategoriesFlow.value = Resource.Success(emptyList())
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

        liveCategoriesFlow.value = Resource.Success(emptyList())
        vodCategoriesFlow.value = Resource.Success(emptyList())
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
}

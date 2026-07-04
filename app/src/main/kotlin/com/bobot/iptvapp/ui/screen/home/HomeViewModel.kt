package com.bobot.iptvapp.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.data.remote.XtreamUrlBuilder
import com.bobot.iptvapp.data.source.CredentialsProvider
import com.bobot.iptvapp.domain.model.Category
import com.bobot.iptvapp.domain.model.Channel
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.Episode
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.repository.FavoritesRepository
import com.bobot.iptvapp.domain.repository.PlaybackProgressRepository
import com.bobot.iptvapp.domain.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A single poster card as rendered on [HomeScreen] — a UI-layer projection of whichever domain
 * model ([Channel], [Movie], or [Series]) it was built from by [HomeViewModel.toCardItem].
 *
 * @property id          Domain identifier, forwarded as-is to
 *                          [com.bobot.iptvapp.navigation.Detail.contentId] on click.
 * @property title       Poster title, rendered by [com.bobot.iptvapp.ui.components.FocusableCard].
 * @property imageUrl     Poster/logo/cover URL, or `null` — [com.bobot.iptvapp.ui.components.FocusableCard]
 *                          already renders a placeholder for a `null` image (brief edge case:
 *                          "content without poster -> placeholder").
 * @property contentType Which [ContentType] this card represents — determines the
 *                          `contentType` argument passed to [com.bobot.iptvapp.navigation.Detail]
 *                          on click (see [HomeScreen]'s `toDetailContentType` mapping).
 * @property resumeStreamUrl Task 23: non-null only for cards built by [HomeViewModel.buildContinueWatchingFlow]
 *                          ("Reprendre" row). When set, [HomeScreen] navigates directly to
 *                          [com.bobot.iptvapp.navigation.Player] with this URL instead of to
 *                          [com.bobot.iptvapp.navigation.Detail] — Continue Watching cards resume
 *                          playback immediately on click, matching Netflix-style behavior, rather
 *                          than opening a detail page first.
 */
data class HomeCardItem(
    val id: String,
    val title: String,
    val imageUrl: String?,
    val contentType: ContentType,
    val resumeStreamUrl: String? = null,
)

/**
 * A single horizontal category row: [Category.name] as the row header, and every item from
 * that category's content list mapped to a [HomeCardItem].
 *
 * Rows with an empty [items] list are filtered out before being placed into [HomeUiState] (see
 * [HomeViewModel.toRows]) — an empty category contributes nothing to render.
 */
data class HomeRow(
    val categoryId: String,
    val title: String,
    val items: List<HomeCardItem>,
)

/**
 * Quintet-resource (ContinueWatching, MyList, Live, Movies, Series) produced by
 * [HomeViewModel.buildAllSectionsFlow]. Task 23: "Reprendre" (Continue Watching) is positioned
 * first, ahead of "Ma liste", per Netflix convention (see [HomeScreen] KDoc).
 */
private data class HomeSectionResources(
    val continueWatching: Resource<List<HomeRow>>,
    val myList: Resource<List<HomeRow>>,
    val live: Resource<List<HomeRow>>,
    val movies: Resource<List<HomeRow>>,
    val series: Resource<List<HomeRow>>,
)

/**
 * UI state consumed by [HomeScreen].
 *
 * @property continueWatchingRows A single row (0 or 1 item) built from
 *                            [PlaybackProgressRepository.observeContinueWatching] combined with the
 *                            movies catalog Flow (MOVIE entries) and [CatalogRepository]'s cache
 *                            resolution (SERIES entries), positioned first (ahead of "Ma liste") per
 *                            Netflix convention. See [HomeViewModel.buildContinueWatchingFlow] KDoc
 *                            for the MOVIE + SERIES resolution details (Task 23 / Task 24-25). Keeps
 *                            its last known value across an in-flight reload (see [HomeViewModel]
 *                            KDoc "Partial reloads").
 * @property myListRows    A single row (0 or 1 item) built from [FavoritesRepository.observeFavorites]
 *                            combined with the three shared, category-scoped content states (see
 *                            [HomeViewModel] KDoc "Category-scoped, on-demand loading (OOM fix)"),
 *                            positioned second per Netflix convention to highlight personalized
 *                            content. Keeps its last known value across an in-flight reload (see
 *                            [HomeViewModel] KDoc "Partial reloads").
 * @property liveRows      Rows built from [CatalogRepository.observeLiveCategories] combined with
 *                            [CatalogRepository.getLiveChannels] fetched one category at a time (see
 *                            [HomeViewModel] KDoc "Category-scoped, on-demand loading (OOM fix)").
 *                            Keeps its last known value across an in-flight reload (see
 *                            [HomeViewModel] KDoc "Partial reloads").
 * @property movieRows     Rows built from [CatalogRepository.observeVodCategories] combined with
 *                            [CatalogRepository.getMovies] fetched one category at a time.
 * @property seriesRows    Rows built from [CatalogRepository.observeSeriesCategories] combined with
 *                            [CatalogRepository.getSeriesList] fetched one category at a time.
 * @property isLoading     `true` while any of the five sections above is still in
 *                            [Resource.Loading]. Drives the full-screen spinner (loading state)
 *                            when nothing has ever loaded yet (see [HomeScreen]'s state-selection
 *                            logic).
 * @property errorMessage  Human-readable message from the first section currently in
 *                            [Resource.Error], or `null` if none. Drives both the full-screen error
 *                            state (nothing loaded yet) and the non-blocking retry banner (partial
 *                            failure, other sections already showing content).
 */
data class HomeUiState(
    val continueWatchingRows: List<HomeRow> = emptyList(),
    val myListRows: List<HomeRow> = emptyList(),
    val liveRows: List<HomeRow> = emptyList(),
    val movieRows: List<HomeRow> = emptyList(),
    val seriesRows: List<HomeRow> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
) {
    /** `true` once at least one row exists in any section — used to pick which visual state to render. */
    val hasAnyRows: Boolean
        get() = continueWatchingRows.isNotEmpty() ||
            myListRows.isNotEmpty() ||
            liveRows.isNotEmpty() ||
            movieRows.isNotEmpty() ||
            seriesRows.isNotEmpty()
}

/**
 * Hilt ViewModel driving [HomeScreen] (Task 17 + Task 22 + Task 23) — follows the `@HiltViewModel` +
 * `@Inject constructor` convention established by
 * [com.bobot.iptvapp.ui.screen.profiles.ProfilesViewModel] (Task 16): only a
 * `domain.repository` / `data.preferences` / `data.source` collaborators are injected (never
 * concrete `data.remote` / `data.local` types), and a single `StateFlow<HomeUiState>` exposes
 * everything [HomeScreen] needs to render.
 *
 * ## Category-scoped, on-demand loading (OOM fix)
 * A production `OutOfMemoryError` was diagnosed (reproduced identically on both a physical device
 * and the emulator) in the previous design, which fired three *concurrent*, *unfiltered*
 * (`categoryId = null`, "fetch the entire catalog in one HTTP call") network requests via
 * `combine()` — each one fully buffered in memory by Retrofit/kotlinx.serialization/okio before
 * the first byte of grouping logic could run. [buildAllSectionsFlow] now replaces that pattern
 * with "fetch categories first (cheap, small), then fetch items **one category at a time** from
 * the server" via [loadCategoryScopedItems]:
 *  - Live channels, movies, and series are loaded **strictly sequentially, one content type after
 *    another** (Movies' per-category loop does not start until every Live category has been
 *    fetched; Series waits for Movies) — see the single `launch { ... }` block inside
 *    [buildAllSectionsFlow] where the three [loadCategoryScopedItems] calls are awaited in order,
 *    never combined/started concurrently.
 *  - Within one content type, categories are fetched **one at a time** (a plain `for` loop over
 *    suspend calls — no `async`/`combine` fan-out), so at most one category's raw+parsed payload
 *    is ever held in memory at once, across all three content types combined.
 *  - All categories are eventually loaded (not just the first N) — this was chosen over a
 *    "first N eagerly, rest on some trigger" split because it requires no new lazy-loading
 *    trigger/UI (out of scope per the brief: no new category-browse screen, no pagination UI) and
 *    is simpler to reason about/verify while still fully bounding peak memory to ~1 category.
 *  - Each content type's accumulated items are published progressively to a private
 *    [MutableStateFlow] (`channelsState` / `moviesState` / `seriesState`) after every category, so
 *    [buildRowsFlow] (unchanged internally) renders newly available rows as soon as they arrive
 *    instead of waiting for the entire content type to finish — the Home screen therefore now
 *    populates section-by-section (and row-by-row within a section) rather than all at once (see
 *    "Concerns / Trade-offs" in the delivery report for this being an accepted, documented UX
 *    change rather than a regression to fix here).
 *  - A single category's fetch failing is treated as "no items for that category" (the row is
 *    simply absent) rather than failing the whole section — one flaky category endpoint should
 *    not blank out rows already successfully built from other categories. Only a failure of the
 *    *categories* list itself (few, cheap requests) surfaces as a section-level [Resource.Error].
 *  - [CatalogRepository]'s `categoryId = null` methods are never called from this loading path
 *    anymore; its in-memory full-list caches (`cachedAllChannels`/`cachedAllMovies`/
 *    `cachedAllSeries`) simply go unused by Home now (other callers, e.g. Search, are a separate
 *    concern/fix).
 *
 * ## Grouping categories with content, per content type
 * [buildRowsFlow] combines a categories Flow with an items Flow (now the progressively-growing
 * [MutableStateFlow] described above, instead of a single unfiltered items Flow) and groups the
 * items by [Channel.categoryId] / [Movie.categoryId] / [Series.categoryId] in memory. Categories
 * with no matching items (yet, or ever) are dropped (see [toRows]) so the UI never renders an
 * empty row.
 *
 * ## Sharing the category-scoped item state across consumers
 * `channelsState` / `moviesState` / `seriesState` (local `MutableStateFlow`s created inside
 * [buildAllSectionsFlow]) are each created exactly ONCE per [buildAllSectionsFlow] call and
 * shared across [buildRowsFlow], [buildMyListFlow], and
 * [buildContinueWatchingFlow] — preserving the single-fetch-per-consumer-set guarantee introduced
 * by the Task 23 hoist, just sourced from the new category-scoped loader instead of a single
 * unfiltered repository Flow.
 *
 * ## "Reprendre" (Continue Watching) row — Task 23 / Task 24-25 / OOM fix (MOVIE fallback)
 * [buildContinueWatchingFlow] combines [PlaybackProgressRepository.observeContinueWatching] with
 * the shared movies state to build a single synthetic [HomeRow], positioned first (ahead of "Ma
 * liste") per Netflix convention. **Scope: MOVIE + SERIES** (Task 24-25 closes the gap left by
 * Task 23) — see [buildContinueWatchingFlow] KDoc for how [ContentType.SERIES] entries are
 * resolved via [CatalogRepository.getCachedEpisodeWithSeries] (untouched by the OOM fix — already
 * cache-only). [ContentType.LIVE] remains excluded (unchanged since Task 23 — see
 * [com.bobot.iptvapp.ui.screen.player.PlayerViewModel.saveProgress]). Since the category-scoped
 * loader above no longer guarantees the *entire* movie catalog is resident in memory at the
 * moment a MOVIE entry is resolved, a MOVIE entry not (yet) present in the shared movies state now
 * falls back to a one-shot [CatalogRepository.getMovieDetail] call via [resolveMovieOrFallback]
 * instead of being silently skipped — see that function's KDoc.
 *
 * ## Credentials caching (Task 23)
 * [activeCredentials] is fetched once at [init] (same lifecycle as [activeProfileId]) and reused
 * synchronously inside [buildContinueWatchingFlow]'s `combine` lambda to build each matched
 * movie's or episode's direct-play stream URL via [XtreamUrlBuilder.buildMovieUrl] /
 * [XtreamUrlBuilder.buildEpisodeUrl] — mirroring
 * [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModel]'s "fetch once, build
 * synchronously" pattern. This works because the `combine` overload used here resolves its
 * `transform` lambda as a `suspend (T1, T2) -> R` type (see [buildContinueWatchingFlow] KDoc
 * "Suspend calls inside `combine`"), so `activeCredentials` can simply be read as a plain field
 * without needing a `flatMapLatest`/suspend-block restructuring.
 *
 * ## "Ma liste" (My List / Favorites) row — OOM fix (MOVIE fallback)
 * Task 22 adds a profile-scoped favorites row ("Ma liste") combining
 * [FavoritesRepository.observeFavorites] with the three shared catalog item states to build a
 * single synthetic [HomeRow] that includes every favorited item currently available in the loaded
 * catalog (matched by contentId + contentType). LIVE and SERIES items whose catalog entry is not
 * (yet) loaded are silently skipped (unchanged); MOVIE items now fall back to
 * [CatalogRepository.getMovieDetail] via [resolveMovieOrFallback] before being skipped — same
 * rationale as the "Reprendre" row above. The row disappears entirely when empty. The active
 * profile ID is fetched once at initialization and cannot change during the ViewModel's lifetime
 * (consistent with [AppNavGraph]'s behavior of re-creating [HomeViewModel] on profile switches).
 *
 * ## Partial reloads
 * [reduceUiState] only overwrites a section's rows when that section's [Resource] is currently
 * [Resource.Success] — while one section is [Resource.Loading] (e.g. right after [onRetry]) or
 * [Resource.Error], the previous rows for that section are preserved in [HomeUiState] instead of
 * being wiped to an empty list, so a slow or failing section never blanks content that already
 * rendered successfully from a different section.
 *
 * ## Retry (Resource contract)
 * [com.bobot.iptvapp.domain.util.Resource] documents that every [Resource.Error] consumer "should
 * show an error card ... with a retry action". [onRetry] implements that contract: it clears
 * [CatalogRepository]'s session cache via [CatalogRepository.invalidateCaches] and re-emits on
 * [retryTrigger], which every section Flow is re-built from via `flatMapLatest` — so a retry
 * genuinely re-invokes the repository (and, transitively, re-fetches from the data source now
 * that the cache is empty) rather than just re-reading a stale cached value.
 *
 * @param catalogRepository Read access to categories and content lists for all three content types.
 * @param favoritesRepository Read access to the active profile's favorites list.
 * @param playbackProgressRepository Read access to the active profile's Continue Watching history.
 * @param appPreferencesStore Resolves the active profile ID that scopes favorites/progress.
 * @param credentialsProvider Resolves the Xtream credentials used to build Continue Watching
 *                            cards' direct-play stream URLs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val favoritesRepository: FavoritesRepository,
    private val playbackProgressRepository: PlaybackProgressRepository,
    private val appPreferencesStore: AppPreferencesStore,
    private val credentialsProvider: CredentialsProvider,
) : ViewModel() {

    private companion object {
        /** Fallback used when [Movie.containerExtension] is `null` or blank — mirrors
         *  [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModel.DEFAULT_CONTAINER_EXTENSION]. */
        const val DEFAULT_CONTAINER_EXTENSION = "mp4"
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Replay = 1 so the initial load happens without requiring an explicit external trigger. */
    private val retryTrigger = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

    /** Cached active profile ID (fetched once at init, remains constant for this ViewModel's lifetime). */
    private var activeProfileId: String? = null

    /** Cached Xtream credentials (fetched once at init) — see class KDoc "Credentials caching". */
    private var activeCredentials: XtreamCredentials? = null

    init {
        viewModelScope.launch {
            // Fetch the active profile ID once; HomeViewModel is re-created on profile switch per AppNavGraph.
            activeProfileId = appPreferencesStore.getActiveProfileId()
            activeCredentials = credentialsProvider.getCredentials()

            retryTrigger
                .flatMapLatest { buildAllSectionsFlow() }
                .collect { (continueWatching, myList, live, movies, series) ->
                    _uiState.update { current -> reduceUiState(current, continueWatching, myList, live, movies, series) }
                }
        }
    }

    /**
     * Clears the repository's session cache and re-triggers every section Flow — see class KDoc
     * "Retry (Resource contract)". Also eagerly clears any previously shown error message so the
     * retry banner/full-screen error disappears immediately while the new load is in flight.
     */
    fun onRetry() {
        catalogRepository.invalidateCaches()
        _uiState.update { it.copy(errorMessage = null) }
        retryTrigger.tryEmit(Unit)
    }

    /**
     * Builds the combined Flow of all sections' row [Resource]s, including "Reprendre" (Task 23)
     * and "Ma liste" (Task 22). Called fresh from inside `flatMapLatest` on every [retryTrigger]
     * emission so each repository method is re-invoked (necessary for [onRetry] to actually
     * re-fetch instead of replaying a completed cold Flow).
     *
     * See class KDoc "Category-scoped, on-demand loading (OOM fix)" for why this no longer opens
     * three concurrent unfiltered (`categoryId = null`) item Flows. Instead:
     *  - `channelsState` / `moviesState` / `seriesState` are private [MutableStateFlow]s, each
     *    created exactly once here and shared across [buildRowsFlow], [buildMyListFlow], and
     *    [buildContinueWatchingFlow] (same hoisting goal as the previous design, see class KDoc
     *    "Sharing the category-scoped item state across consumers").
     *  - The returned [Flow] is a [channelFlow] that launches a single child coroutine which
     *    sequentially awaits [loadCategoryScopedItems] for Live, then Movies, then Series — a
     *    plain sequential `launch { ... }` body (no `async`, no `combine` of the three loaders),
     *    which is what actually guarantees at most one content type's per-category fetch loop is
     *    ever running at a time, on top of [loadCategoryScopedItems] itself guaranteeing at most
     *    one category's fetch is in flight within that content type. Concurrently, the
     *    `channelFlow` body also collects and forwards `sectionsFlow` (the reactive `combine()` of
     *    the five section Flows) so the UI keeps receiving updates as the sequential loader
     *    progresses, and so [FavoritesRepository.observeFavorites] /
     *    [PlaybackProgressRepository.observeContinueWatching] emissions continue to update "Ma
     *    liste"/"Reprendre" live after the initial load completes (unchanged behavior).
     */
    private fun buildAllSectionsFlow(): Flow<HomeSectionResources> {
        val liveCategoriesFlow = catalogRepository.observeLiveCategories()
        val vodCategoriesFlow = catalogRepository.observeVodCategories()
        val seriesCategoriesFlow = catalogRepository.observeSeriesCategories()

        val channelsState = MutableStateFlow<Resource<List<Channel>>>(Resource.Loading)
        val moviesState = MutableStateFlow<Resource<List<Movie>>>(Resource.Loading)
        val seriesState = MutableStateFlow<Resource<List<Series>>>(Resource.Loading)

        val continueWatchingFlow = if (activeProfileId != null) {
            buildContinueWatchingFlow(activeProfileId!!, moviesState)
        } else {
            // No active profile → empty Continue Watching row.
            flowOf(Resource.Success(emptyList()))
        }

        val myListFlow = if (activeProfileId != null) {
            buildMyListFlow(activeProfileId!!, channelsState, moviesState, seriesState)
        } else {
            // No active profile → empty My List.
            flowOf(Resource.Success(emptyList()))
        }

        val sectionsFlow = combine(
            continueWatchingFlow,
            myListFlow,
            buildRowsFlow(
                categoriesFlow = liveCategoriesFlow,
                itemsFlow = channelsState,
                categoryIdOf = Channel::categoryId,
                toCard = { channel: Channel -> toCardItem(channel) },
            ),
            buildRowsFlow(
                categoriesFlow = vodCategoriesFlow,
                itemsFlow = moviesState,
                categoryIdOf = Movie::categoryId,
                toCard = { movie: Movie -> toCardItem(movie) },
            ),
            buildRowsFlow(
                categoriesFlow = seriesCategoriesFlow,
                itemsFlow = seriesState,
                categoryIdOf = Series::categoryId,
                toCard = { series: Series -> toCardItem(series) },
            ),
        ) { continueWatching, myList, live, movies, series ->
            HomeSectionResources(continueWatching, myList, live, movies, series)
        }

        return channelFlow {
            launch {
                // Strictly sequential across content types — Movies' per-category loop is not
                // started until Live's has fully finished, and Series waits for Movies — see
                // class KDoc "Category-scoped, on-demand loading (OOM fix)".
                loadCategoryScopedItems(liveCategoriesFlow, channelsState) { categoryId ->
                    catalogRepository.getLiveChannels(categoryId).first { it !is Resource.Loading }
                }
                loadCategoryScopedItems(vodCategoriesFlow, moviesState) { categoryId ->
                    catalogRepository.getMovies(categoryId).first { it !is Resource.Loading }
                }
                loadCategoryScopedItems(seriesCategoriesFlow, seriesState) { categoryId ->
                    catalogRepository.getSeriesList(categoryId).first { it !is Resource.Loading }
                }
            }
            sectionsFlow.collect { send(it) }
        }
    }

    /**
     * Fetches one content type's items **one category at a time** instead of a single unfiltered
     * `categoryId = null` call — see class KDoc "Category-scoped, on-demand loading (OOM fix)"
     * for the memory-bounding rationale.
     *
     * Awaits [categoriesFlow]'s terminal (non-[Resource.Loading]) value first. When that is a
     * [Resource.Error], it is forwarded to [itemsState] as-is and no per-category fetch is
     * attempted (categories are cheap/small, so a failure there is a real, worth-surfacing
     * problem). Otherwise, [itemsState] is immediately set to `Resource.Success(emptyList())` —
     * correctly resolving the zero-categories edge case without a per-category loop — and then
     * each category's items are fetched in turn via [fetchCategoryItems] (a plain suspend `for`
     * loop, never `async`/`combine`), merging into a running accumulator and re-publishing the
     * accumulated list to [itemsState] after every category so [buildRowsFlow] can render newly
     * available rows immediately.
     *
     * A single category's [fetchCategoryItems] call returning [Resource.Error] is treated as "no
     * items for that category" (silently skipped, loop continues) rather than aborting the whole
     * content type — a single flaky category endpoint must not blank out rows already
     * successfully built from other categories.
     */
    private suspend fun <T> loadCategoryScopedItems(
        categoriesFlow: Flow<Resource<List<Category>>>,
        itemsState: MutableStateFlow<Resource<List<T>>>,
        fetchCategoryItems: suspend (categoryId: String) -> Resource<List<T>>,
    ) {
        when (val categoriesResource = categoriesFlow.first { it !is Resource.Loading }) {
            is Resource.Error -> {
                itemsState.value = Resource.Error(categoriesResource.throwable, categoriesResource.message)
            }
            is Resource.Success -> {
                val accumulated = mutableListOf<T>()
                itemsState.value = Resource.Success(accumulated.toList())
                for (category in categoriesResource.data) {
                    val itemsResource = fetchCategoryItems(category.id)
                    if (itemsResource is Resource.Success) {
                        accumulated += itemsResource.data
                    }
                    itemsState.value = Resource.Success(accumulated.toList())
                }
            }
            Resource.Loading -> Unit // Unreachable: the `first` predicate above excludes Loading.
        }
    }

    /** Combines a categories Flow with an items Flow into a Flow of grouped [HomeRow]s. */
    private fun <T> buildRowsFlow(
        categoriesFlow: Flow<Resource<List<Category>>>,
        itemsFlow: Flow<Resource<List<T>>>,
        categoryIdOf: (T) -> String,
        toCard: (T) -> HomeCardItem,
    ): Flow<Resource<List<HomeRow>>> =
        combine(categoriesFlow, itemsFlow) { categoriesResource, itemsResource ->
            toRows(categoriesResource, itemsResource, categoryIdOf, toCard)
        }

    /**
     * Resolves a MOVIE-type entry by [movieId] — used by both [buildContinueWatchingFlow] and
     * [buildMyListFlow] for their MOVIE branch (Task 2 / OOM fix follow-up).
     *
     * Looks up [movieMap] first (the movies loaded so far by [loadCategoryScopedItems]); if not
     * found there (its category has not, or not yet, been loaded — or it was otherwise excluded),
     * falls back to a one-shot [CatalogRepository.getMovieDetail] network call
     * (`get_vod_info` — a genuine single-item Xtream endpoint) to resolve it individually instead
     * of silently skipping it.
     *
     * MOVIE-only by design: [ContentType.SERIES] already has its own cache-based resolution
     * ([CatalogRepository.getCachedEpisodeWithSeries], untouched) and [ContentType.LIVE] has no
     * single-item Xtream endpoint to fall back to — an accepted, documented limitation (live
     * channels whose category has not loaded yet are silently skipped, unchanged).
     */
    private suspend fun resolveMovieOrFallback(movieId: String, movieMap: Map<String, Movie>): Movie? =
        movieMap[movieId] ?: (catalogRepository.getMovieDetail(movieId) as? Resource.Success)?.data

    /**
     * Builds the "Reprendre" (Continue Watching) row by combining the active profile's playback
     * progress history ([PlaybackProgressRepository.observeContinueWatching], already ordered
     * most-recently-updated-first) with the shared, category-scoped movies state (see class KDoc
     * "Category-scoped, on-demand loading (OOM fix)"). Returns a [Resource] containing a 0 or
     * 1-item list.
     *
     * ## Scope: MOVIE + SERIES (Task 24-25 — closes the Task 23 "Option A" gap)
     * [com.bobot.iptvapp.domain.model.PlaybackProgress.contentType] can be LIVE, MOVIE, or SERIES:
     *  - **MOVIE** entries are resolved against [moviesFlow]'s current [Movie] list first, falling
     *    back to [resolveMovieOrFallback]'s one-shot [CatalogRepository.getMovieDetail] call when
     *    not (yet) present there — see [resolveMovieOrFallback] KDoc.
     *  - **SERIES** entries are now resolved via [CatalogRepository.getCachedEpisodeWithSeries],
     *    which reads exclusively from the offline-first Room catalog cache populated by
     *    `CatalogRepositoryImpl.getSeriesDetail` (see that method's KDoc) — i.e. resolution only
     *    succeeds once the user has opened that series' detail screen at least once, which is what
     *    fetches and caches its season/episode tree. When the episode or its parent series is not
     *    (yet, or no longer) in the cache, [CatalogRepository.getCachedEpisodeWithSeries] returns
     *    `null` and the entry is silently skipped — this is expected graceful degradation
     *    (documented brief assumption), not an error.
     *  - **LIVE** remains excluded, unchanged since Task 23 —
     *    [com.bobot.iptvapp.ui.screen.player.PlayerViewModel.saveProgress] no longer persists LIVE
     *    progress at all, so no LIVE record should appear here going forward. A defensive filter is
     *    still applied below in case a stale LIVE record exists from before that fix.
     *
     * ## Preserving recency ordering across two content types
     * [progressList] is walked in a single `mapNotNull` pass, in its original (already
     * recency-ordered) position, dispatching per-entry on [PlaybackProgress.contentType] to
     * resolve either a movie (in-memory map lookup) or a series episode (suspend cache lookup).
     * Entries are **not** partitioned into "all movies" then "all series" and concatenated — doing
     * so would silently reorder interleaved MOVIE/SERIES updates and break the
     * most-recently-updated-first contract whenever the two types alternate in recency.
     *
     * ## Suspend calls inside `combine`
     * Resolving a SERIES entry requires a `suspend` call
     * ([CatalogRepository.getCachedEpisodeWithSeries]). The `combine(flow, flow2, transform)`
     * overload used below resolves `transform`'s declared type as `suspend (T1, T2) -> R` (see
     * `kotlinx.coroutines.flow.Combine.kt`) — the lambda passed here does not need an explicit
     * `suspend` keyword; Kotlin infers it from the expected parameter type, the same way a
     * `Flow.collect { ... }` lambda can call suspend functions without being marked `suspend`
     * itself. This means the suspend cache lookup can be called directly inside the existing
     * `combine` lambda below, without restructuring this function into `flatMapLatest` — a smaller,
     * lower-risk diff than the structural rewrite originally anticipated, verified here directly
     * against the `combine` overload actually imported/used in this file (2-Flow arity).
     *
     * ## Stream URL resolution
     * Each matched [Movie] or resolved episode gets a direct-play `resumeStreamUrl` built the same
     * way [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModel] builds
     * `MovieDetailUiState.streamUrl` (via [XtreamUrlBuilder.buildMovieUrl] /
     * [XtreamUrlBuilder.buildEpisodeUrl], defaulting to "mp4" when the container extension is
     * null/blank), using [activeCredentials] cached at [init] (see class KDoc "Credentials
     * caching"). A series card's `id` is the resolved [Episode.id] (matching
     * [com.bobot.iptvapp.domain.model.PlaybackProgress]'s per-content-type `contentId` contract for
     * SERIES), *not* the parent [Series.id] — [HomeScreen] forwards a "Reprendre" card's `id`
     * straight through to the player as the `streamId` used for the next `saveProgress()` call, so
     * using the series id here would corrupt progress tracking. Title/poster come from the resolved
     * [Series] (per the brief: "une carte série affiche le poster/titre de la série"). When no
     * credentials are configured, the row is simply empty — there is no play action to disable on a
     * home-screen card the way [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailScreen] disables
     * its play button, so an unplayable card is not shown at all.
     */
    private fun buildContinueWatchingFlow(
        profileId: String,
        moviesFlow: Flow<Resource<List<Movie>>>,
    ): Flow<Resource<List<HomeRow>>> =
        combine(
            playbackProgressRepository.observeContinueWatching(profileId),
            moviesFlow,
        ) { progressList, moviesResource ->
            val credentials = activeCredentials
            val items = if (credentials == null) {
                emptyList()
            } else {
                val movies = (moviesResource as? Resource.Success)?.data.orEmpty()
                val movieMap = movies.associateBy { it.id }

                progressList.mapNotNull { progress ->
                    when (progress.contentType) {
                        ContentType.MOVIE ->
                            resolveMovieOrFallback(progress.contentId, movieMap)
                                ?.let { movie -> toContinueWatchingCardItem(movie, credentials) }

                        ContentType.SERIES ->
                            catalogRepository.getCachedEpisodeWithSeries(progress.contentId)
                                ?.let { (series, episode) -> toContinueWatchingCardItem(series, episode, credentials) }

                        ContentType.LIVE -> null
                    }
                }
            }

            if (items.isEmpty()) {
                Resource.Success(emptyList())
            } else {
                Resource.Success(
                    listOf(
                        HomeRow(
                            categoryId = "continue-watching",
                            title = "Reprendre",
                            items = items,
                        ),
                    ),
                )
            }
        }

    /**
     * Builds a single "Ma liste" (favorites) row by combining favorites with the three shared,
     * category-scoped content states (see class KDoc "Sharing the category-scoped item state
     * across consumers"). Returns a [Resource] containing a 0 or 1-item list (empty list when no
     * favorites, or when favorites list is empty after filtering out unmatched items).
     *
     * ## How it works
     * Combines [FavoritesRepository.observeFavorites] with the three shared content states,
     * extracting any available Success data from the Resource wrappers. For each FavoriteItem,
     * looks up the corresponding Channel/Movie/Series by (contentId, contentType):
     *  - **LIVE** and **SERIES**: direct lookup only — a favorite whose catalog entry has not
     *    (yet) loaded is silently skipped (unchanged; explicitly out of scope for the OOM fix's
     *    MOVIE fallback per the brief — LIVE has no single-item Xtream endpoint, and SERIES'
     *    resolution path is a distinct, untouched concern from Continue Watching's).
     *  - **MOVIE**: falls back to [resolveMovieOrFallback]'s one-shot
     *    [CatalogRepository.getMovieDetail] call when not found in the shared movies state.
     * The result is an ordered list (matching [FavoritesRepository]'s "most recently added first"
     * ordering) of [HomeCardItem]s wrapped in a single synthetic [HomeRow], or an empty list.
     */
    private fun buildMyListFlow(
        profileId: String,
        channelsFlow: Flow<Resource<List<Channel>>>,
        moviesFlow: Flow<Resource<List<Movie>>>,
        seriesFlow: Flow<Resource<List<Series>>>,
    ): Flow<Resource<List<HomeRow>>> =
        combine(
            channelsFlow,
            moviesFlow,
            seriesFlow,
            favoritesRepository.observeFavorites(profileId),
        ) { channelsResource, moviesResource, seriesResource, favorites ->
            // Extract success data, or use empty list if still loading/error.
            val channels = (channelsResource as? Resource.Success)?.data.orEmpty()
            val movies = (moviesResource as? Resource.Success)?.data.orEmpty()
            val series = (seriesResource as? Resource.Success)?.data.orEmpty()

            // Build lookup tables for quick matching by contentId.
            val channelMap = channels.associateBy { it.id }
            val movieMap = movies.associateBy { it.id }
            val seriesMap = series.associateBy { it.id }

            // For each favorite, find the matching catalog item and convert to HomeCardItem.
            val items = favorites.mapNotNull { favorite ->
                when (favorite.contentType) {
                    ContentType.LIVE -> channelMap[favorite.contentId]?.let { toCardItem(it) }
                    ContentType.MOVIE -> resolveMovieOrFallback(favorite.contentId, movieMap)?.let { toCardItem(it) }
                    ContentType.SERIES -> seriesMap[favorite.contentId]?.let { toCardItem(it) }
                }
            }

            // Return a single row if there are items, otherwise empty list (row will not be rendered).
            if (items.isEmpty()) {
                Resource.Success(emptyList())
            } else {
                Resource.Success(
                    listOf(
                        HomeRow(
                            categoryId = "my-list",
                            title = "Ma liste",
                            items = items,
                        ),
                    ),
                )
            }
        }

    /**
     * Reduces a pair of [Resource]s (categories + items) to a single [Resource] of grouped
     * [HomeRow]s. Propagates [Resource.Error] / [Resource.Loading] from either input as-is;
     * only when both are [Resource.Success] are the items grouped by category (see class KDoc
     * "Grouping categories with content, per content type").
     */
    private fun <T> toRows(
        categoriesResource: Resource<List<Category>>,
        itemsResource: Resource<List<T>>,
        categoryIdOf: (T) -> String,
        toCard: (T) -> HomeCardItem,
    ): Resource<List<HomeRow>> {
        if (categoriesResource is Resource.Error) {
            return Resource.Error(categoriesResource.throwable, categoriesResource.message)
        }
        if (itemsResource is Resource.Error) {
            return Resource.Error(itemsResource.throwable, itemsResource.message)
        }
        if (categoriesResource !is Resource.Success) {
            return Resource.Loading
        }
        if (itemsResource !is Resource.Success) {
            return Resource.Loading
        }

        val categories: List<Category> = categoriesResource.data
        val items: List<T> = itemsResource.data
        val itemsByCategory = items.groupBy(categoryIdOf)
        val rows = categories.mapNotNull { category ->
            val categoryItems = itemsByCategory[category.id].orEmpty()
            if (categoryItems.isEmpty()) {
                null
            } else {
                HomeRow(
                    categoryId = category.id,
                    title = category.name,
                    items = categoryItems.map(toCard),
                )
            }
        }
        return Resource.Success(rows)
    }

    /**
     * Folds the five freshly-combined section [Resource]s into the next [HomeUiState].
     * Task 23: continueWatching is included in the calculation of [HomeUiState.isLoading] and
     * error aggregation, but its partial-failure preservation follows the same pattern as the
     * other four sections.
     */
    private fun reduceUiState(
        current: HomeUiState,
        continueWatching: Resource<List<HomeRow>>,
        myList: Resource<List<HomeRow>>,
        live: Resource<List<HomeRow>>,
        movies: Resource<List<HomeRow>>,
        series: Resource<List<HomeRow>>,
    ): HomeUiState {
        val resources = listOf(continueWatching, myList, live, movies, series)
        val isLoading = resources.any { it is Resource.Loading }
        // First error wins, in ContinueWatching -> MyList -> Live -> Movies -> Series order — good
        // enough to surface *a* meaningful message; the retry action re-fetches all sections
        // regardless of which failed.
        val errorMessage = resources.filterIsInstance<Resource.Error>().firstOrNull()?.message

        return current.copy(
            continueWatchingRows = if (continueWatching is Resource.Success) continueWatching.data else current.continueWatchingRows,
            myListRows = if (myList is Resource.Success) myList.data else current.myListRows,
            liveRows = if (live is Resource.Success) live.data else current.liveRows,
            movieRows = if (movies is Resource.Success) movies.data else current.movieRows,
            seriesRows = if (series is Resource.Success) series.data else current.seriesRows,
            isLoading = isLoading,
            errorMessage = errorMessage,
        )
    }

    private fun toCardItem(channel: Channel) = HomeCardItem(
        id = channel.id,
        title = channel.name,
        imageUrl = channel.logoUrl,
        contentType = ContentType.LIVE,
    )

    private fun toCardItem(movie: Movie) = HomeCardItem(
        id = movie.id,
        title = movie.title,
        imageUrl = movie.posterUrl,
        contentType = ContentType.MOVIE,
    )

    private fun toCardItem(series: Series) = HomeCardItem(
        id = series.id,
        title = series.title,
        imageUrl = series.coverUrl,
        contentType = ContentType.SERIES,
    )

    /** See class KDoc "Stream URL resolution" on [buildContinueWatchingFlow]. */
    private fun toContinueWatchingCardItem(movie: Movie, credentials: XtreamCredentials): HomeCardItem {
        val extension = movie.containerExtension?.takeIf { it.isNotBlank() } ?: DEFAULT_CONTAINER_EXTENSION
        val streamUrl = XtreamUrlBuilder.buildMovieUrl(
            baseUrl = credentials.baseUrl,
            username = credentials.username,
            password = credentials.password,
            streamId = movie.id,
            containerExtension = extension,
        )
        return HomeCardItem(
            id = movie.id,
            title = movie.title,
            imageUrl = movie.posterUrl,
            contentType = ContentType.MOVIE,
            resumeStreamUrl = streamUrl,
        )
    }

    /**
     * See class KDoc "Stream URL resolution" on [buildContinueWatchingFlow]. The card's `id` is
     * the [episode]'s id (not [Series.id]) — see that KDoc section for why this is critical.
     */
    private fun toContinueWatchingCardItem(series: Series, episode: Episode, credentials: XtreamCredentials): HomeCardItem {
        val extension = episode.containerExtension?.takeIf { it.isNotBlank() } ?: DEFAULT_CONTAINER_EXTENSION
        val streamUrl = XtreamUrlBuilder.buildEpisodeUrl(
            baseUrl = credentials.baseUrl,
            username = credentials.username,
            password = credentials.password,
            episodeId = episode.id,
            containerExtension = extension,
        )
        return HomeCardItem(
            id = episode.id,
            title = series.title,
            imageUrl = series.coverUrl,
            contentType = ContentType.SERIES,
            resumeStreamUrl = streamUrl,
        )
    }
}

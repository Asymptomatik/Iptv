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
import com.bobot.iptvapp.domain.model.LanguageFilterState
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.repository.FavoritesRepository
import com.bobot.iptvapp.domain.repository.PlaybackProgressRepository
import com.bobot.iptvapp.domain.usecase.FilterCatalogByLanguageUseCase
import com.bobot.iptvapp.domain.util.displayName
import com.bobot.iptvapp.domain.util.languageTag
import com.bobot.iptvapp.domain.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
 * Quintet-resource (ContinueWatching, MyList, Live, Movies, Series) combined in [HomeViewModel]'s
 * `init` block from the two always-active personalization flows
 * ([HomeViewModel.buildContinueWatchingFlow], [HomeViewModel.buildMyListFlow]) and the three
 * on-demand catalog tab row states (see [HomeViewModel] KDoc "On-demand catalog loading"). Task
 * 23: "Reprendre" (Continue Watching) is positioned first, ahead of "Ma liste", per Netflix
 * convention (see [HomeScreen] KDoc).
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
 *                            shared movies catalog state (MOVIE entries) and [CatalogRepository]'s
 *                            cache resolution (SERIES entries), positioned first (ahead of "Ma
 *                            liste") per Netflix convention. See [HomeViewModel.buildContinueWatchingFlow]
 *                            KDoc for the MOVIE + SERIES resolution details (Task 23 / Task 24-25).
 *                            Never blocked on a catalog tab having been opened — see [HomeViewModel]
 *                            KDoc "On-demand catalog loading". Keeps its last known value across an
 *                            in-flight reload (see [HomeViewModel] KDoc "Partial reloads").
 * @property myListRows    A single row (0 or 1 item) built from [FavoritesRepository.observeFavorites]
 *                            combined with the three shared, category-scoped content states (see
 *                            [HomeViewModel] KDoc "Sharing the category-scoped item state across
 *                            consumers"), positioned second per Netflix convention to highlight
 *                            personalized content. Never blocked on a catalog tab having been opened
 *                            — see [HomeViewModel] KDoc "On-demand catalog loading". Keeps its last
 *                            known value across an in-flight reload (see [HomeViewModel] KDoc
 *                            "Partial reloads").
 * @property liveRows      Rows built from [CatalogRepository.observeLiveCategories] combined with
 *                            [CatalogRepository.getLiveChannels] fetched one category at a time —
 *                            but only once [HomeViewModel.onCatalogTabSelected] has been called with
 *                            [ContentType.LIVE] (see [HomeViewModel] KDoc "On-demand catalog
 *                            loading"). Empty until then. Keeps its last known value across an
 *                            in-flight reload (see [HomeViewModel] KDoc "Partial reloads").
 * @property movieRows     Rows built from [CatalogRepository.observeVodCategories] combined with
 *                            [CatalogRepository.getMovies] fetched one category at a time, only once
 *                            [HomeViewModel.onCatalogTabSelected] has been called with
 *                            [ContentType.MOVIE]. Empty until then.
 * @property seriesRows    Rows built from [CatalogRepository.observeSeriesCategories] combined with
 *                            [CatalogRepository.getSeriesList] fetched one category at a time, only
 *                            once [HomeViewModel.onCatalogTabSelected] has been called with
 *                            [ContentType.SERIES]. Empty until then.
 * @property isLoading     `true` while any of the five sections above is still in
 *                            [Resource.Loading]. A catalog tab section that has never been
 *                            requested via [HomeViewModel.onCatalogTabSelected] is
 *                            [Resource.Success] with an empty list (not [Resource.Loading]) — see
 *                            [HomeViewModel] KDoc "On-demand catalog loading" — so this flag never
 *                            gets stuck `true` just because the user has not opened every tab yet.
 *                            Drives the full-screen spinner (loading state) when nothing has ever
 *                            loaded yet (see [HomeScreen]'s state-selection logic).
 * @property errorMessage  Human-readable message from the first section currently in
 *                            [Resource.Error], or `null` if none. Drives both the full-screen error
 *                            state (nothing loaded yet) and the non-blocking retry banner (partial
 *                            failure, other sections already showing content).
 * @property liveLanguages  Distinct language tags ([com.bobot.iptvapp.domain.util.Category.languageTag])
 *                            found among [liveRows]' underlying, currently loaded Chaines categories
 *                            — derived dynamically as categories load progressively (see
 *                            [HomeViewModel] KDoc "Per-tab language filter"). Never includes `null`.
 * @property movieLanguages Films tab equivalent of [liveLanguages].
 * @property seriesLanguages Series tab equivalent of [liveLanguages].
 * @property selectedLiveLanguage The Chaines tab's currently active language filter — `null` means
 *                            "Toutes" (no filter, every loaded category shown). Set via
 *                            [HomeViewModel.onLanguageSelected].
 * @property selectedMovieLanguage Films tab equivalent of [selectedLiveLanguage].
 * @property selectedSeriesLanguage Series tab equivalent of [selectedLiveLanguage].
 */
data class HomeUiState(
    val continueWatchingRows: List<HomeRow> = emptyList(),
    val myListRows: List<HomeRow> = emptyList(),
    val liveRows: List<HomeRow> = emptyList(),
    val movieRows: List<HomeRow> = emptyList(),
    val seriesRows: List<HomeRow> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val liveLanguages: List<String> = emptyList(),
    val movieLanguages: List<String> = emptyList(),
    val seriesLanguages: List<String> = emptyList(),
    val selectedLiveLanguage: String? = null,
    val selectedMovieLanguage: String? = null,
    val selectedSeriesLanguage: String? = null,
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
 * Hilt ViewModel driving [HomeScreen] (Task 17 + Task 22 + Task 23 + on-demand loading OOM fix) —
 * follows the `@HiltViewModel` + `@Inject constructor` convention established by
 * [com.bobot.iptvapp.ui.screen.profiles.ProfilesViewModel] (Task 16): only a
 * `domain.repository` / `data.preferences` / `data.source` collaborators are injected (never
 * concrete `data.remote` / `data.local` types), and a single `StateFlow<HomeUiState>` exposes
 * everything [HomeScreen] needs to render.
 *
 * ## On-demand catalog loading (OOM fix)
 * A production `OutOfMemoryError` was diagnosed on a real, large Xtream account: the *previous*
 * design fetched Live channels, Movies, and Series **unconditionally, sequentially, from `init`**
 * (category-by-category — see git history for the original per-category-fetch fix) regardless of
 * which tab, if any, the user actually opened. On a big-enough catalog, the accumulated in-memory
 * lists alone exceeded the available heap — notably, the crash reproduced while the user opened a
 * movie from the Films tab, having never visited the (largest) Chaines tab, whose channels were
 * nonetheless being fetched in the background.
 *
 * The fix: **no content type is fetched until [onCatalogTabSelected] is called for it.**
 *  - [HomeScreen] calls [onCatalogTabSelected] from a `LaunchedEffect(selectedTab)` whenever the
 *    user switches to the Chaines/Films/Series tab (never for Accueil — see [HomeScreen] KDoc).
 *  - [onCatalogTabSelected] is **idempotent per [ContentType] for this ViewModel instance**: the
 *    first call for a given type starts [loadCatalogTab]; every subsequent call for that same type
 *    is a no-op, guarded by [requestedContentTypes] (a plain `MutableSet`, checked/inserted
 *    synchronously via [MutableSet.add] before anything is launched — safe because both
 *    [onCatalogTabSelected] and Compose's `LaunchedEffect` run on the main thread).
 *  - [channelsState] / [moviesState] / [seriesState] (the shared, category-scoped item states) and
 *    [liveRowsState] / [movieRowsState] / [seriesRowsState] (their grouped-by-category row
 *    projections, exposed via [HomeUiState]) are each created **once**, at construction, and
 *    default to `Resource.Success(emptyList())` — not `Resource.Loading` — so an unrequested
 *    catalog tab reads as "nothing to show yet" rather than "stuck loading forever" in
 *    [HomeUiState.isLoading] (see that property's KDoc).
 *  - Within one requested content type, [loadCategoryScopedItems] is unchanged from the previous
 *    fix: categories are fetched first (cheap, small), then items are fetched **one category at a
 *    time** (a plain suspend `for` loop, never `async`/`combine`), so at most one category's
 *    raw+parsed payload is held in memory at once *for that content type*. Because loading is now
 *    triggered by distinct user actions (opening a tab) rather than one single automatic startup
 *    sequence, two or three content types can in principle be mid-load concurrently if the user
 *    switches tabs fast enough — an accepted trade-off (see "Concerns / Trade-offs" in the delivery
 *    report) since the user has explicitly asked for each of those tabs, unlike the original bug
 *    where an unopened tab's content was fetched anyway.
 *  - [onRetry] re-triggers [loadCatalogTab] only for content types already present in
 *    [requestedContentTypes] — a tab that was never opened has nothing to retry (see [onRetry]
 *    KDoc).
 *  - [CatalogRepository]'s `categoryId = null` methods are still never called from this loading
 *    path (unchanged since the previous fix).
 *
 * ## Grouping categories with content, per content type
 * [buildRowsFlow] combines a categories Flow with an items Flow (the progressively-growing
 * [MutableStateFlow] populated by [loadCategoryScopedItems] once its content type is requested)
 * and groups the items by [Channel.categoryId] / [Movie.categoryId] / [Series.categoryId] in
 * memory. Categories with no matching items (yet, or ever) are dropped (see [toRows]) so the UI
 * never renders an empty row. [loadCatalogTab] forwards every emission of this combined Flow into
 * the relevant `*RowsState` so the tab's rows render progressively as categories complete.
 *
 * ## Sharing the category-scoped item state across consumers
 * [channelsState] / [moviesState] / [seriesState] are created exactly once, as instance
 * properties, and shared across [buildRowsFlow] (via [loadCatalogTab]), [buildMyListFlow], and
 * [buildContinueWatchingFlow] — the same single-fetch-per-consumer-set guarantee as the previous
 * design, just persistent for the ViewModel's lifetime instead of being recreated per retry (see
 * "On-demand catalog loading" above for why they now default to an empty [Resource.Success]
 * instead of [Resource.Loading]).
 *
 * ## "Reprendre" (Continue Watching) row — Task 23 / Task 24-25 / on-demand loading
 * [buildContinueWatchingFlow] combines [PlaybackProgressRepository.observeContinueWatching] with
 * the shared movies state to build a single synthetic [HomeRow], positioned first (ahead of "Ma
 * liste") per Netflix convention. **Scope: MOVIE + SERIES** (Task 24-25 closes the Task 23 gap) —
 * see [buildContinueWatchingFlow] KDoc for how [ContentType.SERIES] entries are resolved via
 * [CatalogRepository.getCachedEpisodeWithSeries] (cache-only — inherently independent of whether
 * the Series tab has ever been opened, since it reads the Room cache populated by
 * `getSeriesDetail`, not the on-demand catalog state). [ContentType.LIVE] remains excluded
 * (unchanged since Task 23 — see [com.bobot.iptvapp.ui.screen.player.PlayerViewModel.saveProgress]).
 * Because catalog loading is now on-demand, [moviesState] may legitimately never contain a given
 * MOVIE entry (its tab may never be opened this session) — a MOVIE entry not present there falls
 * back to a one-shot [CatalogRepository.getMovieDetail] call via [resolveMovieOrFallback] instead
 * of being silently skipped, so "Reprendre" renders correctly even when the Films tab was never
 * visited — see that function's KDoc.
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
 * ## "Ma liste" (My List / Favorites) row — on-demand loading (MOVIE + SERIES fallback)
 * Task 22 adds a profile-scoped favorites row ("Ma liste") combining
 * [FavoritesRepository.observeFavorites] with the three shared catalog item states to build a
 * single synthetic [HomeRow] that includes every favorited item currently available in the loaded
 * catalog (matched by contentId + contentType). Because catalog loading is on-demand, an entry's
 * content type may never have been requested this session, so each type falls back to a one-shot
 * lookup when missing from the shared state, matching [buildContinueWatchingFlow]'s MOVIE fallback:
 *  - **MOVIE** falls back to [CatalogRepository.getMovieDetail] via [resolveMovieOrFallback].
 *  - **SERIES** falls back to [CatalogRepository.getSeriesDetail] via [resolveSeriesOrFallback] —
 *    a genuine single-item Xtream endpoint (`get_series_info`), the Series-side equivalent of
 *    [CatalogRepository.getMovieDetail]. (Note this is a different resolution path than
 *    [buildContinueWatchingFlow]'s SERIES handling: Continue Watching stores an *episode* id and
 *    resolves it cache-only via [CatalogRepository.getCachedEpisodeWithSeries], whereas a favorite
 *    stores the *series* id directly, so [CatalogRepository.getSeriesDetail] — keyed by series id
 *    — is the correct one-shot fallback here instead.)
 *  - **LIVE** has no single-item Xtream endpoint to fall back to (unchanged, accepted, documented
 *    limitation): a favorited channel whose category has not (yet, or ever) been loaded via the
 *    Chaines tab is silently skipped.
 * The row disappears entirely when empty. The active profile ID is fetched once at initialization
 * and cannot change during the ViewModel's lifetime (consistent with [AppNavGraph]'s behavior of
 * re-creating [HomeViewModel] on profile switches).
 *
 * ## Partial reloads
 * [reduceUiState] only overwrites a section's rows when that section's [Resource] is currently
 * [Resource.Success] — while one section is [Resource.Loading] (e.g. right after [onCatalogTabSelected]
 * or [onRetry]) or [Resource.Error], the previous rows for that section are preserved in
 * [HomeUiState] instead of being wiped to an empty list, so a slow or failing section never blanks
 * content that already rendered successfully from a different section.
 *
 * ## Retry (Resource contract)
 * [com.bobot.iptvapp.domain.util.Resource] documents that every [Resource.Error] consumer "should
 * show an error card ... with a retry action". [onRetry] implements that contract for catalog tabs:
 * it clears [CatalogRepository]'s session cache via [CatalogRepository.invalidateCaches] and
 * re-runs [loadCatalogTab] for every content type already in [requestedContentTypes] — a tab that
 * was never opened has no error to retry and is left alone. "Reprendre"/"Ma liste" are not
 * explicitly re-triggered by [onRetry]: they react live to their own source Flows
 * ([PlaybackProgressRepository.observeContinueWatching] / [FavoritesRepository.observeFavorites])
 * and to [channelsState]/[moviesState]/[seriesState] changing as a side effect of the catalog tabs
 * being retried, and [CatalogRepository.invalidateCaches] clearing the repository's in-memory
 * caches means their [resolveMovieOrFallback]/[resolveSeriesOrFallback] one-shot fallbacks will
 * genuinely re-fetch rather than replay a stale in-memory value on their next recomposition.
 *
 * ## Per-tab language filter
 * Each catalog tab (LIVE/MOVIE/SERIES) has its own independent language filter, derived via the
 * shared [FilterCatalogByLanguageUseCase] (Task 1/3 — extracted from what used to be logic
 * duplicated inline in this ViewModel and in
 * [com.bobot.iptvapp.ui.screen.search.SearchViewModel]) on top of
 * [com.bobot.iptvapp.domain.util.CategoryLanguage] / [com.bobot.iptvapp.domain.util.languageTag].
 * Each tab's available-languages/selected-language pair is carried by a single
 * [LanguageFilterState] instance (Task 2/3):
 *  - [liveLanguageFilterState] / [movieLanguageFilterState] / [seriesLanguageFilterState] each hold
 *    that tab's [LanguageFilterState.available] (the distinct, non-null [com.bobot.iptvapp.domain.util.Category.languageTag]
 *    values currently present among that tab's loaded categories) and
 *    [LanguageFilterState.selected] (the tab's current selection, `null` = "Toutes" / no filter).
 *  - [LanguageFilterState.available] is recomputed, as a side effect, via
 *    [FilterCatalogByLanguageUseCase.deriveAvailableLanguages] every time [buildRowsFlow]'s
 *    `combine` re-runs with a fresh [Resource.Success] categories emission — see [buildRowsFlow]
 *    KDoc — so it grows reactively as categories load progressively, without any dedicated extra
 *    collector on the categories Flow (see that KDoc for why this matters). Because this write
 *    targets the same [MutableStateFlow] that [buildRowsFlow] also reads the selection from,
 *    [buildRowsFlow] derives its selection input via `.map { it.selected }.distinctUntilChanged()`
 *    so an available-only update (selection unchanged) never spuriously re-triggers the `combine`.
 *  - [LanguageFilterState.selected] is updated by [onLanguageSelected] via [LanguageFilterState.withSelection].
 *  - The filter itself is applied inside [toRows] (shared, generic across the three content types —
 *    see that function's KDoc) via [FilterCatalogByLanguageUseCase.filterCategories]: a `null`
 *    selection keeps every row; a non-null selection keeps only categories whose
 *    [com.bobot.iptvapp.domain.util.Category.languageTag] exactly matches it, dropping (not just hiding) any category with no
 *    detectable tag — per the brief ("une catégorie sans tag détectable est masquée dès qu'un
 *    filtre précis est actif").
 *  - Purely in-memory post-processing: changing a tab's selection only makes [buildRowsFlow]'s
 *    `combine` re-run (in-process filtering) — it never touches [requestedContentTypes],
 *    [loadCatalogTab], or [loadCategoryScopedItems], so no new network fetch is ever triggered by
 *    [onLanguageSelected].
 *  - Three dedicated `init`-time collectors forward [liveLanguageFilterState] /
 *    [movieLanguageFilterState] / [seriesLanguageFilterState] into [HomeUiState] via
 *    `_uiState.update { ... }` — deliberately independent of the five-Resource `combine` that
 *    drives [reduceUiState], since these three values never carry a
 *    [Resource.Loading]/[Resource.Error] state to reconcile and are simplest left as their own
 *    reactive projections. Each collector maps [LanguageFilterState.available] /
 *    [LanguageFilterState.selected] onto [HomeUiState]'s pre-existing, unchanged
 *    `*Languages`/`selected*Language` properties — [HomeUiState]'s public shape is deliberately
 *    left untouched by this internal refactor (see that class's KDoc), since
 *    [com.bobot.iptvapp.ui.screen.home.HomeScreen] consumes it directly.
 *
 * @param catalogRepository Read access to categories and content lists for all three content types.
 * @param favoritesRepository Read access to the active profile's favorites list.
 * @param playbackProgressRepository Read access to the active profile's Continue Watching history.
 * @param appPreferencesStore Resolves the active profile ID that scopes favorites/progress.
 * @param credentialsProvider Resolves the Xtream credentials used to build Continue Watching
 *                            cards' direct-play stream URLs.
 * @param filterCatalogByLanguageUseCase Shared domain logic for deriving available language tags
 *                            and filtering categories by language — see class KDoc "Per-tab
 *                            language filter".
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val favoritesRepository: FavoritesRepository,
    private val playbackProgressRepository: PlaybackProgressRepository,
    private val appPreferencesStore: AppPreferencesStore,
    private val credentialsProvider: CredentialsProvider,
    private val filterCatalogByLanguageUseCase: FilterCatalogByLanguageUseCase,
) : ViewModel() {

    private companion object {
        /** Fallback used when [Movie.containerExtension] is `null` or blank — mirrors
         *  [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModel.DEFAULT_CONTAINER_EXTENSION]. */
        const val DEFAULT_CONTAINER_EXTENSION = "mp4"
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Cached active profile ID (fetched once at init, remains constant for this ViewModel's lifetime). */
    private var activeProfileId: String? = null

    /** Cached Xtream credentials (fetched once at init) — see class KDoc "Credentials caching". */
    private var activeCredentials: XtreamCredentials? = null

    // ── On-demand catalog loading state (OOM fix) — see class KDoc "On-demand catalog loading" ──

    /**
     * Shared, category-scoped item states — one [MutableStateFlow] per content type, created once
     * and reused for this ViewModel's lifetime. Default to an empty [Resource.Success] (not
     * [Resource.Loading]) so an unrequested content type reads as "nothing to show yet" rather
     * than "loading forever" — see class KDoc.
     */
    private val channelsState = MutableStateFlow<Resource<List<Channel>>>(Resource.Success(emptyList()))
    private val moviesState = MutableStateFlow<Resource<List<Movie>>>(Resource.Success(emptyList()))
    private val seriesState = MutableStateFlow<Resource<List<Series>>>(Resource.Success(emptyList()))

    /** Grouped-by-category rows per catalog tab, exposed via [HomeUiState] — see class KDoc. */
    private val liveRowsState = MutableStateFlow<Resource<List<HomeRow>>>(Resource.Success(emptyList()))
    private val movieRowsState = MutableStateFlow<Resource<List<HomeRow>>>(Resource.Success(emptyList()))
    private val seriesRowsState = MutableStateFlow<Resource<List<HomeRow>>>(Resource.Success(emptyList()))

    // ── Per-tab language filter state — see class KDoc "Per-tab language filter" ─────────────────

    /**
     * One [LanguageFilterState] per catalog tab — each tab's available language tags (recomputed
     * as a side effect inside [buildRowsFlow] every time a fresh [Resource.Success] categories
     * emission arrives, so [LanguageFilterState.available] grows reactively as categories load
     * progressively) plus that tab's current selection (`null` means "Toutes" / no filter), set by
     * [onLanguageSelected].
     */
    private val liveLanguageFilterState = MutableStateFlow(LanguageFilterState())
    private val movieLanguageFilterState = MutableStateFlow(LanguageFilterState())
    private val seriesLanguageFilterState = MutableStateFlow(LanguageFilterState())

    /**
     * Content types for which [onCatalogTabSelected] has already triggered a load, for this
     * ViewModel instance — the idempotency guard described in class KDoc "On-demand catalog
     * loading". Only ever mutated from the main thread (both [onCatalogTabSelected] and Compose's
     * `LaunchedEffect` run there), so a plain `MutableSet` is safe without extra synchronization.
     */
    private val requestedContentTypes = mutableSetOf<ContentType>()

    /**
     * The currently active loading [Job] per content type, so [onRetry] can cancel a still-running
     * load before starting a fresh one instead of letting two loads for the same type race.
     */
    private val catalogTabJobs = mutableMapOf<ContentType, Job>()

    init {
        viewModelScope.launch {
            // Fetch the active profile ID once; HomeViewModel is re-created on profile switch per AppNavGraph.
            activeProfileId = appPreferencesStore.getActiveProfileId()
            activeCredentials = credentialsProvider.getCredentials()

            val continueWatchingFlow = activeProfileId?.let { buildContinueWatchingFlow(it, moviesState) }
                ?: flowOf(Resource.Success(emptyList()))

            val myListFlow = activeProfileId?.let { buildMyListFlow(it, channelsState, moviesState, seriesState) }
                ?: flowOf(Resource.Success(emptyList()))

            combine(
                continueWatchingFlow,
                myListFlow,
                liveRowsState,
                movieRowsState,
                seriesRowsState,
            ) { continueWatching, myList, live, movies, series ->
                HomeSectionResources(continueWatching, myList, live, movies, series)
            }.collect { (continueWatching, myList, live, movies, series) ->
                _uiState.update { current -> reduceUiState(current, continueWatching, myList, live, movies, series) }
            }
        }

        // ── Per-tab language filter — see class KDoc "Per-tab language filter" ────────────────────
        // Three independent collectors, deliberately kept out of the five-Resource combine above:
        // none of these three LanguageFilterState values ever carries a Loading/Error state to
        // reconcile, so each is simplest projected into HomeUiState on its own. HomeUiState's public
        // shape is unchanged — each LanguageFilterState is unpacked into its pre-existing pair of
        // `*Languages`/`selected*Language` properties.
        viewModelScope.launch {
            liveLanguageFilterState.collect { state ->
                _uiState.update { it.copy(liveLanguages = state.available, selectedLiveLanguage = state.selected) }
            }
        }
        viewModelScope.launch {
            movieLanguageFilterState.collect { state ->
                _uiState.update { it.copy(movieLanguages = state.available, selectedMovieLanguage = state.selected) }
            }
        }
        viewModelScope.launch {
            seriesLanguageFilterState.collect { state ->
                _uiState.update { it.copy(seriesLanguages = state.available, selectedSeriesLanguage = state.selected) }
            }
        }
    }

    /**
     * Updates the language filter selection for [contentType]'s catalog tab (Chaines/Films/Series
     * are independent — see class KDoc "Per-tab language filter"). `language = null` means "Toutes"
     * (no filter, every loaded category shown).
     *
     * Pure in-memory post-processing: this only updates a plain [MutableStateFlow] consumed by
     * [buildRowsFlow]'s `combine`, which re-filters already-loaded rows in place. It never touches
     * [requestedContentTypes], [loadCatalogTab], or [loadCategoryScopedItems] — selecting a language
     * never triggers a new network fetch.
     */
    fun onLanguageSelected(contentType: ContentType, language: String?) {
        when (contentType) {
            ContentType.LIVE -> liveLanguageFilterState.update { it.withSelection(language) }
            ContentType.MOVIE -> movieLanguageFilterState.update { it.withSelection(language) }
            ContentType.SERIES -> seriesLanguageFilterState.update { it.withSelection(language) }
        }
    }

    /**
     * Triggers on-demand loading of [contentType]'s catalog (see class KDoc "On-demand catalog
     * loading"). Called by [HomeScreen] whenever the user switches to the Chaines/Films/Series tab
     * (never for Accueil).
     *
     * Idempotent per [contentType] for this ViewModel instance: the first call starts
     * [loadCatalogTab] via [startCatalogTabLoad]; every subsequent call for the same [contentType]
     * is a no-op ([requestedContentTypes] already contains it).
     */
    fun onCatalogTabSelected(contentType: ContentType) {
        if (!requestedContentTypes.add(contentType)) return
        startCatalogTabLoad(contentType)
    }

    /**
     * Clears the repository's session cache and re-triggers loading for every catalog tab already
     * requested via [onCatalogTabSelected] — see class KDoc "Retry (Resource contract)". A tab
     * that has never been opened has nothing to retry and is left untouched; opening it later still
     * goes through the normal [onCatalogTabSelected] path. Also eagerly clears any previously shown
     * error message so the retry banner/full-screen error disappears immediately while the new load
     * is in flight.
     */
    fun onRetry() {
        catalogRepository.invalidateCaches()
        _uiState.update { it.copy(errorMessage = null) }
        requestedContentTypes.toList().forEach { startCatalogTabLoad(it) }
    }

    /**
     * Starts (or restarts, for [onRetry]) the loading [Job] for [contentType], cancelling any
     * previous still-running job for the same type first so two loads for one type never race
     * writes into the same shared state.
     */
    private fun startCatalogTabLoad(contentType: ContentType) {
        catalogTabJobs[contentType]?.cancel()
        catalogTabJobs[contentType] = viewModelScope.launch {
            when (contentType) {
                ContentType.LIVE -> loadCatalogTab(
                    contentType = ContentType.LIVE,
                    categoriesFlow = catalogRepository.observeLiveCategories(),
                    itemsState = channelsState,
                    rowsState = liveRowsState,
                    languageFilterState = liveLanguageFilterState,
                    categoryIdOf = Channel::categoryId,
                    toCard = { channel: Channel -> toCardItem(channel) },
                    fetchCategoryItems = { categoryId ->
                        catalogRepository.getLiveChannels(categoryId).first { it !is Resource.Loading }
                    },
                )

                ContentType.MOVIE -> loadCatalogTab(
                    contentType = ContentType.MOVIE,
                    categoriesFlow = catalogRepository.observeVodCategories(),
                    itemsState = moviesState,
                    rowsState = movieRowsState,
                    languageFilterState = movieLanguageFilterState,
                    categoryIdOf = Movie::categoryId,
                    toCard = { movie: Movie -> toCardItem(movie) },
                    fetchCategoryItems = { categoryId ->
                        catalogRepository.getMovies(categoryId).first { it !is Resource.Loading }
                    },
                )

                ContentType.SERIES -> loadCatalogTab(
                    contentType = ContentType.SERIES,
                    categoriesFlow = catalogRepository.observeSeriesCategories(),
                    itemsState = seriesState,
                    rowsState = seriesRowsState,
                    languageFilterState = seriesLanguageFilterState,
                    categoryIdOf = Series::categoryId,
                    toCard = { series: Series -> toCardItem(series) },
                    fetchCategoryItems = { categoryId ->
                        catalogRepository.getSeriesList(categoryId).first { it !is Resource.Loading }
                    },
                )
            }
        }
    }

    /**
     * Loads one catalog tab's content type: resets [itemsState]/[rowsState] to [Resource.Loading],
     * launches a child coroutine forwarding [buildRowsFlow]'s grouped rows into [rowsState] for the
     * rest of this ViewModel's lifetime (or until [startCatalogTabLoad] cancels the enclosing job on
     * retry), then runs [loadCategoryScopedItems] to actually fetch the categories/items — see class
     * KDoc "On-demand catalog loading" and "Grouping categories with content, per content type".
     */
    private suspend fun <T> CoroutineScope.loadCatalogTab(
        contentType: ContentType,
        categoriesFlow: Flow<Resource<List<Category>>>,
        itemsState: MutableStateFlow<Resource<List<T>>>,
        rowsState: MutableStateFlow<Resource<List<HomeRow>>>,
        languageFilterState: MutableStateFlow<LanguageFilterState>,
        categoryIdOf: (T) -> String,
        toCard: (T) -> HomeCardItem,
        fetchCategoryItems: suspend (categoryId: String) -> Resource<List<T>>,
    ) {
        itemsState.value = Resource.Loading
        rowsState.value = Resource.Loading
        launch {
            buildRowsFlow(contentType, categoriesFlow, itemsState, languageFilterState, categoryIdOf, toCard)
                .collect { rowsState.value = it }
        }
        loadCategoryScopedItems(categoriesFlow, itemsState, fetchCategoryItems)
    }

    /**
     * Fetches one content type's items **one category at a time** instead of a single unfiltered
     * `categoryId = null` call — see class KDoc "On-demand catalog loading" for the
     * memory-bounding rationale.
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

    /**
     * Combines a categories Flow, an items Flow, and [languageFilterState]'s selection into a Flow
     * of grouped, language-filtered [HomeRow]s (see class KDoc "Per-tab language filter").
     *
     * As a side effect, every time a fresh [Resource.Success] categories emission flows through,
     * [languageFilterState]'s [LanguageFilterState.available] is recomputed via
     * [FilterCatalogByLanguageUseCase.deriveAvailableLanguages] — deliberately done here (inside the
     * same `combine` already collecting [categoriesFlow] for row building) rather than via a
     * dedicated extra collector on [categoriesFlow], so this reactive "available languages"
     * derivation never adds another concurrent collector to a Flow that may trigger its own network
     * fetch on each independent collection (see `CatalogRepositoryImpl.observeLiveCategories` and
     * siblings).
     *
     * The `combine` reads the current selection via `languageFilterState.map { it.selected }
     * .distinctUntilChanged()` rather than [languageFilterState] directly — since the side effect
     * above writes back into that same [MutableStateFlow], reading its raw emissions would make an
     * available-only update (selection unchanged) spuriously re-run this `combine`;
     * `distinctUntilChanged` filters those out.
     */
    private fun <T> buildRowsFlow(
        contentType: ContentType,
        categoriesFlow: Flow<Resource<List<Category>>>,
        itemsFlow: Flow<Resource<List<T>>>,
        languageFilterState: MutableStateFlow<LanguageFilterState>,
        categoryIdOf: (T) -> String,
        toCard: (T) -> HomeCardItem,
    ): Flow<Resource<List<HomeRow>>> {
        val selectedLanguageFlow = languageFilterState.map { it.selected }.distinctUntilChanged()
        return combine(categoriesFlow, itemsFlow, selectedLanguageFlow) { categoriesResource, itemsResource, selectedLanguage ->
            if (categoriesResource is Resource.Success) {
                languageFilterState.update { filterCatalogByLanguageUseCase.deriveAvailableLanguages(categoriesResource.data, it) }
            }
            toRows(contentType, categoriesResource, itemsResource, selectedLanguage, categoryIdOf, toCard)
        }
    }

    /**
     * Resolves a MOVIE-type entry by [movieId] — used by both [buildContinueWatchingFlow] and
     * [buildMyListFlow] for their MOVIE branch (OOM fix follow-up / on-demand loading).
     *
     * Looks up [movieMap] first (the movies loaded so far by [loadCategoryScopedItems], if the
     * Films tab has been opened this session); if not found there (its category has not, or not
     * yet, been loaded — or the Films tab was never opened at all — or it was otherwise excluded),
     * falls back to a one-shot [CatalogRepository.getMovieDetail] network call
     * (`get_vod_info` — a genuine single-item Xtream endpoint) to resolve it individually instead
     * of silently skipping it.
     *
     * MOVIE-only by design: [ContentType.SERIES] has its own single-item fallback
     * ([resolveSeriesOrFallback] for "Ma liste", [CatalogRepository.getCachedEpisodeWithSeries] for
     * "Reprendre") and [ContentType.LIVE] has no single-item Xtream endpoint to fall back to — an
     * accepted, documented limitation (live channels whose category has not loaded yet are
     * silently skipped, unchanged).
     */
    private suspend fun resolveMovieOrFallback(movieId: String, movieMap: Map<String, Movie>): Movie? =
        movieMap[movieId] ?: (catalogRepository.getMovieDetail(movieId) as? Resource.Success)?.data

    /**
     * Resolves a SERIES-type entry by [seriesId] — used by [buildMyListFlow] for its SERIES branch.
     * Series-side equivalent of [resolveMovieOrFallback]: looks up [seriesMap] first (the series
     * loaded so far by [loadCategoryScopedItems], if the Series tab has been opened this session);
     * if not found there, falls back to a one-shot [CatalogRepository.getSeriesDetail] network call
     * (`get_series_info` — a genuine single-item Xtream endpoint, keyed by series id) instead of
     * silently skipping it.
     *
     * Not used by [buildContinueWatchingFlow]: a Continue Watching SERIES entry stores an *episode*
     * id, not a series id, and is resolved cache-only via
     * [CatalogRepository.getCachedEpisodeWithSeries] instead — see that function's call site KDoc.
     */
    private suspend fun resolveSeriesOrFallback(seriesId: String, seriesMap: Map<String, Series>): Series? =
        seriesMap[seriesId] ?: (catalogRepository.getSeriesDetail(seriesId) as? Resource.Success)?.data

    /**
     * Builds the "Reprendre" (Continue Watching) row by combining the active profile's playback
     * progress history ([PlaybackProgressRepository.observeContinueWatching], already ordered
     * most-recently-updated-first) with the shared, category-scoped movies state (see class KDoc
     * "Sharing the category-scoped item state across consumers"). Returns a [Resource] containing
     * a 0 or 1-item list.
     *
     * ## Scope: MOVIE + SERIES (Task 24-25 — closes the Task 23 "Option A" gap)
     * [com.bobot.iptvapp.domain.model.PlaybackProgress.contentType] can be LIVE, MOVIE, or SERIES:
     *  - **MOVIE** entries are resolved against [moviesFlow]'s current [Movie] list first, falling
     *    back to [resolveMovieOrFallback]'s one-shot [CatalogRepository.getMovieDetail] call when
     *    not (yet, or ever, this session) present there — see [resolveMovieOrFallback] KDoc.
     *  - **SERIES** entries are resolved via [CatalogRepository.getCachedEpisodeWithSeries], which
     *    reads exclusively from the offline-first Room catalog cache populated by
     *    `CatalogRepositoryImpl.getSeriesDetail` (see that method's KDoc) — i.e. resolution only
     *    succeeds once the user has opened that series' detail screen at least once, which is what
     *    fetches and caches its season/episode tree. This is inherently independent of whether the
     *    Series *tab* (as opposed to a specific series' detail screen) has ever been opened. When
     *    the episode or its parent series is not (yet, or no longer) in the cache,
     *    [CatalogRepository.getCachedEpisodeWithSeries] returns `null` and the entry is silently
     *    skipped — this is expected graceful degradation (documented brief assumption), not an
     *    error.
     *  - **LIVE** remains excluded, unchanged since Task 23 —
     *    [com.bobot.iptvapp.ui.screen.player.PlayerViewModel.saveProgress] no longer persists LIVE
     *    progress at all, so no LIVE record should appear here going forward. A defensive filter is
     *    still applied below in case a stale LIVE record exists from before that fix.
     *
     * ## Preserving recency ordering across two content types
     * [progressList] is walked in a single `mapNotNull` pass, in its original (already
     * recency-ordered) position, dispatching per-entry on [PlaybackProgress.contentType] to
     * resolve either a movie (in-memory map lookup + fallback) or a series episode (suspend cache
     * lookup). Entries are **not** partitioned into "all movies" then "all series" and
     * concatenated — doing so would silently reorder interleaved MOVIE/SERIES updates and break
     * the most-recently-updated-first contract whenever the two types alternate in recency.
     *
     * ## Suspend calls inside `combine`
     * Resolving a MOVIE or SERIES entry requires a `suspend` call
     * ([resolveMovieOrFallback] / [CatalogRepository.getCachedEpisodeWithSeries]). The
     * `combine(flow, flow2, transform)` overload used below resolves `transform`'s declared type
     * as `suspend (T1, T2) -> R` (see `kotlinx.coroutines.flow.Combine.kt`) — the lambda passed
     * here does not need an explicit `suspend` keyword; Kotlin infers it from the expected
     * parameter type, the same way a `Flow.collect { ... }` lambda can call suspend functions
     * without being marked `suspend` itself.
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
     * resolves the corresponding Channel/Movie/Series by (contentId, contentType):
     *  - **LIVE**: direct lookup only — a favorite whose catalog entry has not (yet, or ever) been
     *    loaded (i.e. the Chaines tab was never opened) is silently skipped. No single-item Xtream
     *    endpoint exists for a live channel, so there is no fallback to generalize here — an
     *    accepted, documented limitation (see [resolveMovieOrFallback] KDoc).
     *  - **MOVIE**: falls back to [resolveMovieOrFallback]'s one-shot
     *    [CatalogRepository.getMovieDetail] call when not found in the shared movies state.
     *  - **SERIES**: falls back to [resolveSeriesOrFallback]'s one-shot
     *    [CatalogRepository.getSeriesDetail] call when not found in the shared series state.
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

            // For each favorite, find the matching catalog item (falling back to a one-shot fetch
            // for MOVIE/SERIES when not already loaded) and convert to HomeCardItem.
            val items = favorites.mapNotNull { favorite ->
                when (favorite.contentType) {
                    ContentType.LIVE -> channelMap[favorite.contentId]?.let { toCardItem(it) }
                    ContentType.MOVIE -> resolveMovieOrFallback(favorite.contentId, movieMap)?.let { toCardItem(it) }
                    ContentType.SERIES -> resolveSeriesOrFallback(favorite.contentId, seriesMap)?.let { toCardItem(it) }
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
     * Reduces a pair of [Resource]s (categories + items) to a single [Resource] of grouped,
     * language-filtered [HomeRow]s. Propagates [Resource.Error] / [Resource.Loading] from either
     * input as-is; only when both are [Resource.Success] are the items grouped by category (see
     * class KDoc "Grouping categories with content, per content type").
     *
     * [selectedLanguage] applies the class KDoc "Per-tab language filter" via
     * [FilterCatalogByLanguageUseCase.filterCategories]: `null` ("Toutes") keeps every category; a
     * non-null value keeps only categories whose [com.bobot.iptvapp.domain.util.Category.languageTag] exactly equals it — a
     * category with no detectable tag (`languageTag()` returns `null`) never matches a non-null
     * [selectedLanguage] and is therefore excluded, per the brief. Categories are filtered *before*
     * grouping so a filtered-out category never contributes a row regardless of whether it has
     * matching items.
     */
    private fun <T> toRows(
        contentType: ContentType,
        categoriesResource: Resource<List<Category>>,
        itemsResource: Resource<List<T>>,
        selectedLanguage: String?,
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
        val rows = filterCatalogByLanguageUseCase.filterCategories(categories, selectedLanguage)
            .groupBy(
                keySelector = { category -> rowGroupingKey(category, contentType) },
                valueTransform = { category -> category to itemsByCategory[category.id].orEmpty() },
            )
            .mapNotNull { (displayName, groupedCategories) ->
                val mergedItems = groupedCategories
                    .flatMap { (_, categoryItems) -> categoryItems }
                    .map(toCard)
                if (mergedItems.isEmpty()) {
                    null
                } else {
                    HomeRow(
                        categoryId = groupedCategories.first().first.id,
                        title = displayName,
                        items = mergedItems,
                    )
                }
            }
        return Resource.Success(rows)
    }

    private fun rowGroupingKey(category: Category, contentType: ContentType): String =
        when (contentType) {
            ContentType.LIVE -> category.displayName()
            ContentType.MOVIE, ContentType.SERIES -> category.languageTag() ?: category.displayName()
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
        // enough to surface *a* meaningful message; the retry action re-fetches all requested
        // catalog tabs regardless of which failed.
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

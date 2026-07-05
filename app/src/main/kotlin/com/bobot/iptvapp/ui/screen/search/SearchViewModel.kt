package com.bobot.iptvapp.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobot.iptvapp.domain.model.Category
import com.bobot.iptvapp.domain.model.Channel
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.util.Resource
import com.bobot.iptvapp.domain.util.languageTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A single search result card as rendered on [SearchScreen] — a UI-layer projection of whichever
 * domain model ([Channel], [Movie], or [Series]) it was built from by [SearchViewModel.toResultItem].
 *
 * Deliberately **not** [com.bobot.iptvapp.ui.screen.home.HomeCardItem] — this codebase's convention
 * (established by [com.bobot.iptvapp.ui.screen.home.HomeScreen] / `HomeCardItem`) is for each screen
 * to own its own UI-projection type instead of reaching into another screen's package, even though
 * the shape is nearly identical.
 *
 * @property id          Domain identifier, forwarded as-is to
 *                        [com.bobot.iptvapp.navigation.Detail.contentId] on click.
 * @property title       Result title, rendered by [com.bobot.iptvapp.ui.components.FocusableCard].
 * @property imageUrl    Poster/logo/cover URL, or `null` — [com.bobot.iptvapp.ui.components.FocusableCard]
 *                        already renders a placeholder for a `null` image.
 * @property contentType Which [ContentType] this result represents — determines the `contentType`
 *                        argument passed to [com.bobot.iptvapp.navigation.Detail] on click (see
 *                        [SearchScreen]'s `toDetailContentType` mapping).
 */
data class SearchResultItem(
    val id: String,
    val title: String,
    val imageUrl: String?,
    val contentType: ContentType,
)

/**
 * UI state consumed by [SearchScreen].
 *
 * @property query          The current (untrimmed) search query, as typed by the user — echoed
 *                           back from [SearchViewModel] rather than owned locally by
 *                           [SearchScreen] so the field always reflects the single source of
 *                           truth used for filtering.
 * @property liveResults    Channels whose [Channel.name] matches [query] (case-insensitive
 *                           substring). Empty whenever [query] is blank — see [SearchViewModel]
 *                           KDoc "Client-side filtering".
 * @property movieResults   Movies whose [Movie.title] matches [query].
 * @property seriesResults  Series whose [Series.title] matches [query].
 * @property isLoading      `true` while any of the three underlying catalog states is still in
 *                           [Resource.Loading] — mirrors
 *                           [com.bobot.iptvapp.ui.screen.home.HomeUiState.isLoading]. Becomes
 *                           `false` as soon as each content type's categories have resolved (its
 *                           per-category accumulation may still be progressing in the background —
 *                           see class KDoc "Category-scoped, on-demand loading (OOM fix)").
 * @property errorMessage   Human-readable message from the first catalog section currently in
 *                           [Resource.Error], or `null` if none — mirrors
 *                           [com.bobot.iptvapp.ui.screen.home.HomeUiState.errorMessage].
 * @property availableLanguages Distinct, non-null [com.bobot.iptvapp.domain.util.Category.languageTag]
 *                           values found among the categories currently loaded across **all three**
 *                           content types (union, not per-type) — unlike
 *                           [com.bobot.iptvapp.ui.screen.home.HomeUiState] (one list per tab), Search
 *                           shows Live/Movies/Series simultaneously so it exposes a single global
 *                           list. See [SearchViewModel] KDoc "Global language filter".
 * @property selectedLanguage The single, global language filter applied to all three result
 *                           sections at once — `null` means "Toutes" (no filter). Set via
 *                           [SearchViewModel.onLanguageSelected].
 */
data class SearchUiState(
    val query: String = "",
    val liveResults: List<SearchResultItem> = emptyList(),
    val movieResults: List<SearchResultItem> = emptyList(),
    val seriesResults: List<SearchResultItem> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val availableLanguages: List<String> = emptyList(),
    val selectedLanguage: String? = null,
) {
    /** `true` once at least one result exists in any section. */
    val hasAnyResults: Boolean
        get() = liveResults.isNotEmpty() || movieResults.isNotEmpty() || seriesResults.isNotEmpty()
}

/**
 * Combined categories (all three content types) + global language selection driving both
 * [SearchUiState.availableLanguages] (its [availableLanguages] property) and the per-item language
 * filter — see [SearchViewModel] KDoc "Global language filter". Kept as a single intermediate value
 * so [SearchViewModel.buildSearchResultsFlow]'s outer `combine` stays within the 5-flow direct
 * overload despite having 8 logical inputs overall (see that KDoc section
 * "Available languages + filter context...").
 */
private data class SearchFilterContext(
    val liveCategories: List<Category>,
    val vodCategories: List<Category>,
    val seriesCategories: List<Category>,
    val selectedLanguage: String?,
) {
    /** Union of distinct, non-null language tags across all three content types' categories. */
    val availableLanguages: List<String>
        get() = (liveCategories + vodCategories + seriesCategories).mapNotNull { it.languageTag() }.distinct()
}

/**
 * Hilt ViewModel driving [SearchScreen] (Task 21).
 *
 * ## No server-side search endpoint
 * [CatalogRepository] exposes no text-search method — neither does the underlying Xtream Codes
 * API. This ViewModel instead builds up the same "all items across categories" lists that
 * [com.bobot.iptvapp.ui.screen.home.HomeViewModel] builds per-section, and filters them in-memory
 * against a [query] this ViewModel owns.
 *
 * ## Category-scoped, on-demand loading (OOM fix)
 * A production `OutOfMemoryError` was diagnosed and fixed in
 * [com.bobot.iptvapp.ui.screen.home.HomeViewModel] (see that class's KDoc "Category-scoped,
 * on-demand loading (OOM fix)") for its previous design, which fired *concurrent*, *unfiltered*
 * (`categoryId = null`, "fetch the entire catalog in one HTTP call") network requests via
 * `combine()`. This ViewModel had the **exact same bug**: [buildSearchResultsFlow] previously
 * combined three `catalogRepository.getLiveChannels(categoryId = null)` /
 * `getMovies(categoryId = null)` / `getSeriesList(categoryId = null)` Flows concurrently, each
 * fully buffered in memory before the first byte of filtering logic could run — equally capable of
 * OOMing on a large real-world catalog.
 *
 * [buildSearchResultsFlow] now applies the exact same fix as [com.bobot.iptvapp.ui.screen.home.HomeViewModel]:
 * "fetch categories first (cheap, small), then fetch items **one category at a time** from the
 * server" via [loadCategoryScopedItems] (an intentional near-duplicate of
 * [com.bobot.iptvapp.ui.screen.home.HomeViewModel]'s private helper of the same name and shape —
 * this codebase does not share ViewModel-internal helpers across screen packages, see
 * [SearchResultItem] KDoc "Deliberately not HomeCardItem" for the same per-screen-ownership
 * convention applied to types):
 *  - Live channels, movies, and series are loaded **strictly sequentially, one content type after
 *    another** (Movies' per-category loop does not start until every Live category has been
 *    fetched; Series waits for Movies) — see the single `launch { ... }` block inside
 *    [buildSearchResultsFlow] where the three [loadCategoryScopedItems] calls are awaited in
 *    order, never combined/started concurrently.
 *  - Within one content type, categories are fetched **one at a time** (a plain `for` loop over
 *    suspend calls inside [loadCategoryScopedItems] — no `async`/`combine` fan-out), so at most one
 *    category's raw+parsed payload is ever held in memory at once, across all three content types
 *    combined.
 *  - Each content type's accumulated items are published progressively to a private
 *    [MutableStateFlow] (`channelsState` / `moviesState` / `seriesState`) after every category, so
 *    the [combine] powering [reduceUiState] re-filters against a growing list as soon as new items
 *    arrive.
 *  - [CatalogRepository]'s `categoryId = null` overloads are never called from this ViewModel
 *    anymore.
 *
 * ## Does Search need the *entire* catalog, and how is memory still bounded?
 * Unlike [com.bobot.iptvapp.ui.screen.home.HomeViewModel] (where showing only the categories loaded
 * so far is an acceptable, even Netflix-typical, partial UX), a search for e.g. "Sport" is only
 * genuinely useful once **every** category across all three content types has been searched — a
 * user should not get a false "no results" just because the category containing the actual match
 * has not loaded yet. So, unlike Home, this ViewModel does eventually walk every category of every
 * content type (same as before the fix).
 *
 * The OOM fix here is therefore specifically about eliminating the *concurrent* /
 * *unfiltered-in-one-network-call* memory spike — not about avoiding loading the full catalog
 * eventually. Memory stays bounded because:
 *  - at most **one** category's payload (across all three content types) is ever in flight/parsed
 *    at a time (never 3 concurrent full-catalog HTTP responses buffered simultaneously);
 *  - filtering re-runs against whatever has accumulated so far and **refines progressively** as
 *    more categories arrive (matching [SearchUiState]'s existing "re-filter on every recombination"
 *    contract) rather than the UI blocking until the entire catalog has loaded — so a query typed
 *    early already returns partial matches from whichever categories have loaded so far, and those
 *    results only grow/refine as loading continues in the background, never regress.
 *
 * ## Client-side filtering
 * Every recombination of [query] with the three progressively-growing item states re-filters the
 * full in-memory lists accumulated so far by a case-insensitive substring match against
 * [Channel.name] / [Movie.title] / [Series.title]. Filtering is cheap (no network I/O per
 * keystroke, just a list scan over already-cached data), so **no debounce is applied** — unchanged
 * from before this fix, a deliberate choice for simplicity/correctness over premature optimisation.
 *
 * ## Retry (Resource contract, without `flatMapLatest`)
 * [com.bobot.iptvapp.domain.util.Resource] documents that every [Resource.Error] consumer "should
 * show an error card ... with a retry action". This ViewModel keeps a single cancellable
 * [searchJob]: [onRetry] invalidates the repository's session cache, cancels the current job, and
 * starts a fresh one via [startCollecting] — which re-invokes every category/content Flow (via a
 * brand-new [buildSearchResultsFlow] call, so fresh category and per-category Flow instances are
 * obtained, genuinely re-running the fetch) while [query] (a hot [MutableStateFlow]) immediately
 * replays its current value into the new [combine] chain, preserving whatever the user had already
 * typed.
 *
 * ## Global language filter
 * Unlike [com.bobot.iptvapp.ui.screen.home.HomeViewModel] (one language filter per catalog tab),
 * Search shows Live/Movies/Series results simultaneously in a single screen, so it exposes **one
 * global selector** ([onLanguageSelected]) filtering all three sections at once — same "filter, not
 * sort" semantics and same "untagged/unmatched category excluded whenever a filter is active" rule
 * as Home, derived from the same, already-approved
 * [com.bobot.iptvapp.domain.util.CategoryLanguage]/[com.bobot.iptvapp.domain.util.languageTag] utility.
 *
 * [SearchResultItem] carries no category or language info, so a result's language is resolved by
 * looking its originating [Channel.categoryId] / [Movie.categoryId] / [Series.categoryId] up in
 * that content type's currently-known categories list.
 *
 * ### No second subscription to the cold category Flows
 * [loadCategoryScopedItems] is already the **only** place each `categoriesFlow` (`liveCategoriesFlow`
 * / `vodCategoriesFlow` / `seriesCategoriesFlow`) is ever collected, via its single `categoriesFlow
 * .first { ... }` call — those Flows are cold and redo I/O on every new collector (see class KDoc
 * "Category-scoped, on-demand loading (OOM fix)"). Rather than adding a second `.first`/`.collect`
 * on any of them to learn each type's resolved categories list, [loadCategoryScopedItems] now takes
 * an optional `onCategoriesResolved` callback, invoked exactly once per call, right where the
 * existing `Resource.Success` branch already has `categoriesResource.data` in hand — zero-cost,
 * zero-extra-subscription. [buildSearchResultsFlow] passes callbacks that publish into
 * [liveCategoriesState] / [vodCategoriesState] / [seriesCategoriesState] (plain [MutableStateFlow]s,
 * *not* re-subscriptions to the repository Flows).
 *
 * ### Available languages + filter context, without exceeding `combine`'s 5-arg overloads
 * [buildSearchResultsFlow] now has eight logical inputs (`_query`, `channelsState`, `moviesState`,
 * `seriesState`, `selectedLanguageState`, plus the three `*CategoriesState`s) — more than
 * `kotlinx.coroutines.flow.combine`'s direct 2-5-flow overloads support, and the reflective
 * `Array<*>`-vararg overload would require an unchecked cast. Instead, the three categories states
 * and [selectedLanguageState] are first combined into one intermediate [SearchFilterContext] (which
 * also derives [SearchUiState.availableLanguages] as the union of distinct language tags across all
 * three content types' currently-known categories), and that single combined Flow becomes the fifth
 * input alongside `_query`/`channelsState`/`moviesState`/`seriesState` — fitting the direct 5-arg
 * `combine` overload with no unchecked casts anywhere.
 *
 * ### Filtering
 * [filterChannels]/[filterMovies]/[filterSeries] apply the language filter *in addition to* (never
 * instead of) the existing substring query match: for each item, its category is looked up by
 * `categoryId` in that content type's currently-known categories, and the item is kept only when
 * [SearchFilterContext.selectedLanguage] is `null` or the resolved category's
 * [com.bobot.iptvapp.domain.util.languageTag] equals it exactly — an item whose category cannot be
 * found, or whose category has no detectable tag, is excluded whenever a filter is active, same as
 * Home.
 *
 * ### No new network fetch
 * [onLanguageSelected] only writes to [selectedLanguageState], a plain in-memory
 * [MutableStateFlow] purely re-filtering already-accumulated state through [combine] — it never
 * touches [loadCategoryScopedItems] or [buildSearchResultsFlow], so selecting a language never
 * triggers a new network fetch, mirroring [com.bobot.iptvapp.ui.screen.home.HomeViewModel.onLanguageSelected].
 *
 * @param catalogRepository Read access to categories and content lists for all three content types.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")

    /** Global language filter selection — see class KDoc "Global language filter". `null` = "Toutes". */
    private val selectedLanguageState = MutableStateFlow<String?>(null)

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        startCollecting()
    }

    /** Updates the query driving in-memory filtering — see class KDoc "Client-side filtering". */
    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    /**
     * Updates the single, global language filter applied to all three result sections at once —
     * see class KDoc "Global language filter". `language = null` means "Toutes" (no filter).
     *
     * Pure in-memory post-processing: only updates [selectedLanguageState], consumed by
     * [buildSearchResultsFlow]'s `combine`, which re-filters already-loaded results in place. It
     * never touches [loadCategoryScopedItems] or [buildSearchResultsFlow] itself, so selecting a
     * language never triggers a new network fetch.
     */
    fun onLanguageSelected(language: String?) {
        selectedLanguageState.value = language
    }

    /**
     * Clears the repository's session cache and restarts collection so every underlying
     * category/content Flow is genuinely re-invoked — see class KDoc "Retry (Resource contract,
     * without `flatMapLatest`)". Also eagerly clears any previously shown error message so the
     * retry banner/full-screen error disappears immediately while the new load is in flight.
     */
    fun onRetry() {
        catalogRepository.invalidateCaches()
        _uiState.update { it.copy(errorMessage = null) }
        startCollecting()
    }

    /**
     * (Re)subscribes to [buildSearchResultsFlow], cancelling any previously running collection
     * first so [onRetry] never leaves two collectors racing to update [_uiState].
     */
    private fun startCollecting() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            buildSearchResultsFlow().collect { newState -> _uiState.value = newState }
        }
    }

    /**
     * Builds the Flow of [SearchUiState] driving [uiState] — see class KDoc "Category-scoped,
     * on-demand loading (OOM fix)" for why this no longer opens three concurrent unfiltered
     * (`categoryId = null`) item Flows. Instead:
     *  - `channelsState` / `moviesState` / `seriesState` are private [MutableStateFlow]s, each
     *    created exactly once per call and combined with [_query] to produce [reduceUiState]'s
     *    input.
     *  - The returned [Flow] is a [channelFlow] that launches a single child coroutine which
     *    sequentially awaits [loadCategoryScopedItems] for Live, then Movies, then Series — a
     *    plain sequential `launch { ... }` body (no `async`, no `combine` of the three loaders),
     *    which is what actually guarantees at most one content type's per-category fetch loop is
     *    ever running at a time, on top of [loadCategoryScopedItems] itself guaranteeing at most
     *    one category's fetch is in flight within that content type. Concurrently, the
     *    `channelFlow` body also collects and forwards the query/results `combine()` so the UI
     *    keeps receiving progressively refined results as the sequential loader advances.
     */
    private fun buildSearchResultsFlow(): Flow<SearchUiState> {
        val liveCategoriesFlow = catalogRepository.observeLiveCategories()
        val vodCategoriesFlow = catalogRepository.observeVodCategories()
        val seriesCategoriesFlow = catalogRepository.observeSeriesCategories()

        val channelsState = MutableStateFlow<Resource<List<Channel>>>(Resource.Loading)
        val moviesState = MutableStateFlow<Resource<List<Movie>>>(Resource.Loading)
        val seriesState = MutableStateFlow<Resource<List<Series>>>(Resource.Loading)

        // Each content type's currently-known categories list, published from the single point
        // loadCategoryScopedItems already resolves categoriesFlow.first { ... } — never a second
        // subscription to the cold liveCategoriesFlow/vodCategoriesFlow/seriesCategoriesFlow — see
        // class KDoc "No second subscription to the cold category Flows".
        val liveCategoriesState = MutableStateFlow<List<Category>>(emptyList())
        val vodCategoriesState = MutableStateFlow<List<Category>>(emptyList())
        val seriesCategoriesState = MutableStateFlow<List<Category>>(emptyList())

        // Intermediate combine keeping the outer resultsFlow combine within the direct 5-flow
        // overload despite 8 logical inputs — see class KDoc "Available languages + filter context...".
        val filterContextFlow = combine(
            liveCategoriesState,
            vodCategoriesState,
            seriesCategoriesState,
            selectedLanguageState,
        ) { live, vod, series, selectedLanguage ->
            SearchFilterContext(live, vod, series, selectedLanguage)
        }

        val resultsFlow = combine(
            _query,
            channelsState,
            moviesState,
            seriesState,
            filterContextFlow,
        ) { query, live, movies, series, filterContext ->
            reduceUiState(query, live, movies, series, filterContext)
        }

        return channelFlow {
            launch {
                // Strictly sequential across content types — Movies' per-category loop is not
                // started until Live's has fully finished, and Series waits for Movies — see
                // class KDoc "Category-scoped, on-demand loading (OOM fix)".
                loadCategoryScopedItems(
                    liveCategoriesFlow,
                    channelsState,
                    onCategoriesResolved = { categories -> liveCategoriesState.value = categories },
                ) { categoryId ->
                    catalogRepository.getLiveChannels(categoryId).first { it !is Resource.Loading }
                }
                loadCategoryScopedItems(
                    vodCategoriesFlow,
                    moviesState,
                    onCategoriesResolved = { categories -> vodCategoriesState.value = categories },
                ) { categoryId ->
                    catalogRepository.getMovies(categoryId).first { it !is Resource.Loading }
                }
                loadCategoryScopedItems(
                    seriesCategoriesFlow,
                    seriesState,
                    onCategoriesResolved = { categories -> seriesCategoriesState.value = categories },
                ) { categoryId ->
                    catalogRepository.getSeriesList(categoryId).first { it !is Resource.Loading }
                }
            }
            resultsFlow.collect { send(it) }
        }
    }

    /**
     * Fetches one content type's items **one category at a time** instead of a single unfiltered
     * `categoryId = null` call — see class KDoc "Category-scoped, on-demand loading (OOM fix)"
     * for the memory-bounding rationale. Near-identical to
     * [com.bobot.iptvapp.ui.screen.home.HomeViewModel]'s private helper of the same name/shape.
     *
     * Awaits [categoriesFlow]'s terminal (non-[Resource.Loading]) value first. When that is a
     * [Resource.Error], it is forwarded to [itemsState] as-is and no per-category fetch is
     * attempted (categories are cheap/small, so a failure there is a real, worth-surfacing
     * problem). Otherwise, [itemsState] is immediately set to `Resource.Success(emptyList())` —
     * correctly resolving the zero-categories edge case without a per-category loop — and then
     * each category's items are fetched in turn via [fetchCategoryItems] (a plain suspend `for`
     * loop, never `async`/`combine`), merging into a running accumulator and re-publishing the
     * accumulated list to [itemsState] after every category so search results keep refining
     * progressively (see class KDoc "Does Search need the entire catalog...").
     *
     * A single category's [fetchCategoryItems] call returning [Resource.Error] is treated as "no
     * items for that category" (silently skipped, loop continues) rather than aborting the whole
     * content type — a single flaky category endpoint must not blank out matches already found in
     * other categories.
     *
     * @param onCategoriesResolved Invoked exactly once, only on the [Resource.Success] branch,
     *   with [Resource.Success.data] — lets callers capture the resolved categories list (e.g. into
     *   a [MutableStateFlow] for the global language filter, see class KDoc "Global language
     *   filter") without ever adding a second subscription to [categoriesFlow] itself.
     */
    private suspend fun <T> loadCategoryScopedItems(
        categoriesFlow: Flow<Resource<List<Category>>>,
        itemsState: MutableStateFlow<Resource<List<T>>>,
        onCategoriesResolved: (categories: List<Category>) -> Unit = {},
        fetchCategoryItems: suspend (categoryId: String) -> Resource<List<T>>,
    ) {
        when (val categoriesResource = categoriesFlow.first { it !is Resource.Loading }) {
            is Resource.Error -> {
                itemsState.value = Resource.Error(categoriesResource.throwable, categoriesResource.message)
            }
            is Resource.Success -> {
                onCategoriesResolved(categoriesResource.data)
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
     * Folds the latest query + three content [Resource]s + [SearchFilterContext] (global language
     * filter — see class KDoc "Global language filter") into the next [SearchUiState].
     */
    private fun reduceUiState(
        query: String,
        live: Resource<List<Channel>>,
        movies: Resource<List<Movie>>,
        series: Resource<List<Series>>,
        filterContext: SearchFilterContext,
    ): SearchUiState {
        val resources = listOf(live, movies, series)
        val isLoading = resources.any { it is Resource.Loading }
        // First error wins, in Live -> Movies -> Series order — good enough to surface *a*
        // meaningful message; the retry action re-fetches all three regardless of which failed.
        val errorMessage = resources.filterIsInstance<Resource.Error>().firstOrNull()?.message

        val trimmedQuery = query.trim()
        val selectedLanguage = filterContext.selectedLanguage
        val liveResults = if (trimmedQuery.isEmpty()) {
            emptyList()
        } else {
            filterChannels(live, trimmedQuery, filterContext.liveCategories, selectedLanguage)
        }
        val movieResults = if (trimmedQuery.isEmpty()) {
            emptyList()
        } else {
            filterMovies(movies, trimmedQuery, filterContext.vodCategories, selectedLanguage)
        }
        val seriesResults = if (trimmedQuery.isEmpty()) {
            emptyList()
        } else {
            filterSeries(series, trimmedQuery, filterContext.seriesCategories, selectedLanguage)
        }

        return SearchUiState(
            query = query,
            liveResults = liveResults,
            movieResults = movieResults,
            seriesResults = seriesResults,
            isLoading = isLoading,
            errorMessage = errorMessage,
            availableLanguages = filterContext.availableLanguages,
            selectedLanguage = selectedLanguage,
        )
    }

    /**
     * `selectedLanguage == null` keeps every substring-matching channel; otherwise a channel is
     * also kept only when its [Channel.categoryId] resolves (in [categories]) to a category whose
     * [com.bobot.iptvapp.domain.util.languageTag] equals [selectedLanguage] exactly — an
     * unresolvable category or one with no detectable tag is excluded, per class KDoc "Filtering".
     */
    private fun filterChannels(
        resource: Resource<List<Channel>>,
        query: String,
        categories: List<Category>,
        selectedLanguage: String?,
    ): List<SearchResultItem> {
        val categoryMap = categories.associateBy { it.id }
        return (resource as? Resource.Success)?.data
            ?.filter { it.name.contains(query, ignoreCase = true) }
            ?.filter { channel -> matchesLanguage(categoryMap[channel.categoryId], selectedLanguage) }
            ?.map { channel: Channel -> toResultItem(channel) }
            .orEmpty()
    }

    /** Movies equivalent of [filterChannels]. */
    private fun filterMovies(
        resource: Resource<List<Movie>>,
        query: String,
        categories: List<Category>,
        selectedLanguage: String?,
    ): List<SearchResultItem> {
        val categoryMap = categories.associateBy { it.id }
        return (resource as? Resource.Success)?.data
            ?.filter { it.title.contains(query, ignoreCase = true) }
            ?.filter { movie -> matchesLanguage(categoryMap[movie.categoryId], selectedLanguage) }
            ?.map { movie: Movie -> toResultItem(movie) }
            .orEmpty()
    }

    /** Series equivalent of [filterChannels]. */
    private fun filterSeries(
        resource: Resource<List<Series>>,
        query: String,
        categories: List<Category>,
        selectedLanguage: String?,
    ): List<SearchResultItem> {
        val categoryMap = categories.associateBy { it.id }
        return (resource as? Resource.Success)?.data
            ?.filter { it.title.contains(query, ignoreCase = true) }
            ?.filter { series -> matchesLanguage(categoryMap[series.categoryId], selectedLanguage) }
            ?.map { series: Series -> toResultItem(series) }
            .orEmpty()
    }

    /**
     * `selectedLanguage == null` ("Toutes") always matches. Otherwise [category] must be resolved
     * (non-`null`) *and* its [com.bobot.iptvapp.domain.util.languageTag] must equal
     * [selectedLanguage] exactly — an unresolved category or one with no detectable tag never
     * matches a non-null [selectedLanguage], per class KDoc "Filtering".
     */
    private fun matchesLanguage(category: Category?, selectedLanguage: String?): Boolean =
        selectedLanguage == null || category?.languageTag() == selectedLanguage

    private fun toResultItem(channel: Channel) = SearchResultItem(
        id = channel.id,
        title = channel.name,
        imageUrl = channel.logoUrl,
        contentType = ContentType.LIVE,
    )

    private fun toResultItem(movie: Movie) = SearchResultItem(
        id = movie.id,
        title = movie.title,
        imageUrl = movie.posterUrl,
        contentType = ContentType.MOVIE,
    )

    private fun toResultItem(series: Series) = SearchResultItem(
        id = series.id,
        title = series.title,
        imageUrl = series.coverUrl,
        contentType = ContentType.SERIES,
    )
}

package com.bobot.iptvapp.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobot.iptvapp.domain.model.Category
import com.bobot.iptvapp.domain.model.Channel
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.usecase.FilterCatalogByLanguageUseCase
import com.bobot.iptvapp.domain.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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
 *                           truth used for filtering. Echoed on every keystroke, independently of
 *                           whether that keystroke has triggered a catalog load — see
 *                           [SearchViewModel] KDoc "Lazy loading".
 * @property liveResults    Channels whose [Channel.name] matches [query] (case-insensitive
 *                           substring). Empty whenever [query] is blank — see [SearchViewModel]
 *                           KDoc "Client-side filtering".
 * @property movieResults   Movies whose [Movie.title] matches [query].
 * @property seriesResults  Series whose [Series.title] matches [query].
 * @property isLoading      `true` while any of the three underlying catalog states is still in
 *                           [Resource.Loading] — mirrors
 *                           [com.bobot.iptvapp.ui.screen.home.HomeUiState.isLoading]. `false` by
 *                           default and until the first non-blank keystroke *settles* past the
 *                           debounce delay, since nothing is being fetched before then — see
 *                           [SearchViewModel] KDoc "Lazy loading" and "Debounce".
 *                           Becomes `false` again as soon as each content type's categories have
 *                           resolved (its per-category accumulation may still be progressing in the
 *                           background — see class KDoc "Category-scoped, on-demand loading (OOM
 *                           fix)").
 * @property errorMessage   Human-readable message from the first catalog section currently in
 *                           [Resource.Error], or `null` if none — mirrors
 *                           [com.bobot.iptvapp.ui.screen.home.HomeUiState.errorMessage].
 * @property availableLanguages Distinct, non-null [com.bobot.iptvapp.domain.util.Category.languageTag]
 *                           values found among the categories currently loaded across **all three**
 *                           content types (union, not per-type) — unlike
 *                           [com.bobot.iptvapp.ui.screen.home.HomeUiState] (one list per tab), Search
 *                           shows Live/Movies/Series simultaneously so it exposes a single global
 *                           list. Empty until the catalog load has been triggered — see
 *                           [SearchViewModel] KDoc "Global language filter" and "Lazy loading".
 * @property selectedLanguage The single, global language filter applied to all three result
 *                           sections at once — `null` means "Toutes" (no filter). Set via
 *                           [SearchViewModel.onLanguageSelected], which may be called before the
 *                           catalog load has ever been triggered (see [SearchViewModel] KDoc
 *                           "Global language filter").
 */
data class SearchUiState(
    val query: String = "",
    val liveResults: List<SearchResultItem> = emptyList(),
    val movieResults: List<SearchResultItem> = emptyList(),
    val seriesResults: List<SearchResultItem> = emptyList(),
    val isLoading: Boolean = false,
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
 * [SearchUiState.availableLanguages] (its [availableLanguages] field) and the per-item language
 * filter — see [SearchViewModel] KDoc "Global language filter". Kept as a single intermediate value
 * so [SearchViewModel.buildSearchResultsFlow]'s outer `combine` stays within the 5-flow direct
 * overload despite having 8 logical inputs overall (see that KDoc section
 * "Available languages + filter context...").
 *
 * [availableLanguages] is computed once per emission by [SearchViewModel.buildSearchResultsFlow]'s
 * `filterContextFlow` combine lambda (the only place with access to the injected
 * [FilterCatalogByLanguageUseCase]), via [FilterCatalogByLanguageUseCase.availableLanguages] applied
 * to the union of [liveCategories]/[vodCategories]/[seriesCategories] — this plain data class stays
 * a dependency-free holder.
 */
private data class SearchFilterContext(
    val liveCategories: List<Category>,
    val vodCategories: List<Category>,
    val seriesCategories: List<Category>,
    val selectedLanguage: String?,
    val availableLanguages: List<String>,
)

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
 * [buildSearchResultsFlow] applies the exact same fix as [com.bobot.iptvapp.ui.screen.home.HomeViewModel]:
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
 * ## Lazy loading (Task 4)
 * [buildSearchResultsFlow] (and therefore every network call it triggers, plus the language
 * derivation described below) used to be started unconditionally from `init` — an eager load of the
 * entire catalog even if the user never typed anything. This duplicated, for Search, the exact
 * "fetch data nobody asked for yet" problem [com.bobot.iptvapp.ui.screen.home.HomeViewModel] already
 * solved for its catalog tabs (see class KDoc "Category-scoped, on-demand loading (OOM fix)" there):
 * a user who opens Search but never types anything (or backs out immediately) should not cause any
 * catalog fetch at all.
 *
 * `init` therefore no longer calls [startCollecting] directly. Instead:
 *  - A dedicated collector unconditionally echoes every [_query] emission into
 *    [SearchUiState.query] (`_uiState.update { it.copy(query = query) }`), independently of loading —
 *    so the [SearchScreen] search field, a controlled Compose `TextField`, never appears to reject a
 *    keystroke just because nothing has loaded yet.
 *  - A second collector watches [debouncedQuery] (not raw [_query] — see class KDoc "Debounce")
 *    and calls [triggerLazyLoadOnce] the first time `query.trim()` is non-empty. [triggerLazyLoadOnce]
 *    is guarded by [requestedLoad] (a plain `Boolean`, the single-filter equivalent of
 *    [com.bobot.iptvapp.ui.screen.home.HomeViewModel]'s `requestedContentTypes` `Set`): the first
 *    non-blank *settled* (post-debounce) query starts [startCollecting]; every subsequent keystroke
 *    (blank or not) is a no-op for loading purposes — the catalog, once fetched, stays in memory in
 *    [channelsState]/[moviesState]/[seriesState] and is only re-filtered, never re-fetched, exactly
 *    matching the "catalogue téléchargé une seule fois" requirement. Because the trigger reads the
 *    debounced value, a fast "type then immediately clear" sequence that never settles on a
 *    non-blank value never triggers a load at all — see class KDoc "Debounce".
 *  - Until the first non-blank keystroke, [SearchUiState.isLoading] stays at its default, `false` —
 *    nothing is loading, so a spinner would be misleading (see [SearchUiState.isLoading] KDoc).
 *    [SearchScreen]'s state-selection `when` already renders `SearchEmptyQueryState` first whenever
 *    `query.isBlank()`, ahead of the `isLoading` branch, so this default is never actually visible
 *    as a loading spinner regardless.
 *  - [triggerLazyLoadOnce] sets [SearchUiState.isLoading] to `true` **synchronously**, before
 *    launching [startCollecting]'s coroutine — the same "post `Resource.Loading` synchronously
 *    before the async fetch starts" pattern already used by
 *    [com.bobot.iptvapp.ui.screen.home.HomeViewModel.loadCatalogTab]. Without this, there is a real
 *    frame where the query-echo collector has already set [SearchUiState.query] to a non-blank
 *    value but [buildSearchResultsFlow]'s `combine` has not yet produced its first
 *    (`Resource.Loading`-derived) emission — during which `query` non-blank + `isLoading == false` +
 *    `hasAnyResults == false` would make [SearchScreen] briefly render `SearchNoResultsState`
 *    ("Aucun résultat"), a misleading false negative the brief forbids. [reduceUiState] still
 *    recomputes `isLoading` correctly on every subsequent emission, so this synchronous flag is only
 *    ever a head start, never a stale value.
 *  - [onRetry] mirrors [com.bobot.iptvapp.ui.screen.home.HomeViewModel.onRetry]'s "nothing requested,
 *    nothing to retry" rule: if [requestedLoad] is still `false`, [onRetry] is a no-op — see that
 *    function's KDoc.
 *
 * ### Debounce (Task 5)
 * Two of [_query]'s three consumers do genuinely expensive work per emission — the lazy-load
 * trigger (a network fetch, once) and [buildSearchResultsFlow]'s `combine` (a full re-filter of
 * every accumulated item, on every recombination) — while the third, the query echo into
 * [SearchUiState.query], is a cheap `copy()` that must stay instantaneous for the `TextField` to
 * feel responsive. [debouncedQuery] exists to feed exactly the first two, while [_query] itself
 * (raw, un-debounced) keeps feeding the echo collector directly:
 *  - [debouncedQuery] is a single, ViewModel-scoped, **shared** [StateFlow] —
 *    `_query.debounce(SEARCH_DEBOUNCE_MS).stateIn(viewModelScope, SharingStarted.Eagerly, "")` —
 *    rather than an expression re-evaluated at each call site. Sharing matters here: it guarantees
 *    exactly *one* debounce timer runs for the whole ViewModel instance (not one per collector),
 *    and — because a [StateFlow] always immediately replays its latest value to a brand-new
 *    collector instead of waiting for the next upstream emission — [buildSearchResultsFlow]'s
 *    `combine`, freshly (re)subscribing to [debouncedQuery] the moment [triggerLazyLoadOnce] or
 *    [onRetry] starts it, sees the already-settled current value with **no additional** debounce
 *    delay stacked on top of the one the trigger collector already waited out. A plain
 *    `_query.debounce(SEARCH_DEBOUNCE_MS)` expression duplicated at both call sites would instead
 *    start a brand-new, independent 300ms timer for each subscriber, adding a second, redundant
 *    debounce wait before the first filtered results could ever render.
 *  - The lazy-load trigger collector (class KDoc "Lazy loading") and [buildSearchResultsFlow]'s
 *    `combine` both read [debouncedQuery]. A fast "type non-blank, then clear before the debounce
 *    settles" sequence therefore never reaches either: the debounce timer keeps resetting on every
 *    keystroke and only ever emits the value present once typing pauses for [SEARCH_DEBOUNCE_MS] —
 *    which, in that sequence, is the cleared, blank value — so neither [triggerLazyLoadOnce] fires
 *    nor does a single extra re-filter run for the discarded keystroke.
 *  - The query-echo collector (class KDoc "Lazy loading", first bullet) reads raw [_query], never
 *    [debouncedQuery] — the search field must never appear to lag behind typing.
 *  - [debounce] is still annotated `@FlowPreview` in this project's coroutines version (1.9.0, per
 *    `gradle/libs.versions.toml`'s `coroutines` version and `app/build.gradle.kts`'s
 *    `libs.coroutines.core` dependency) even though its behavior has been stable for years — hence
 *    [debouncedQuery]'s `@OptIn(FlowPreview::class)`.
 *
 * ## Does Search need the *entire* catalog, and how is memory still bounded?
 * Unlike [com.bobot.iptvapp.ui.screen.home.HomeViewModel] (where showing only the categories loaded
 * so far is an acceptable, even Netflix-typical, partial UX), a search for e.g. "Sport" is only
 * genuinely useful once **every** category across all three content types has been searched — a
 * user should not get a false "no results" just because the category containing the actual match
 * has not loaded yet. So, unlike Home, this ViewModel does eventually walk every category of every
 * content type, once loading has started (see class KDoc "Lazy loading").
 *
 * The OOM fix here is therefore specifically about eliminating the *concurrent* /
 * *unfiltered-in-one-network-call* memory spike — not about avoiding loading the full catalog
 * eventually, once the user has shown intent to search. Memory stays bounded because:
 *  - at most **one** category's payload (across all three content types) is ever in flight/parsed
 *    at a time (never 3 concurrent full-catalog HTTP responses buffered simultaneously);
 *  - filtering re-runs against whatever has accumulated so far and **refines progressively** as
 *    more categories arrive (matching [SearchUiState]'s existing "re-filter on every recombination"
 *    contract) rather than the UI blocking until the entire catalog has loaded — so a query typed
 *    early already returns partial matches from whichever categories have loaded so far, and those
 *    results only grow/refine as loading continues in the background, never regress.
 *
 * ## Client-side filtering
 * Every recombination of [debouncedQuery] with the three progressively-growing item states
 * re-filters the full in-memory lists accumulated so far by a case-insensitive substring match
 * against [Channel.name] / [Movie.title] / [Series.title]. Filtering itself is cheap (no network
 * I/O, just a list scan over already-cached data), but on a large catalog re-scanning three lists
 * on *every single keystroke* is still wasted work once the user is mid-word — [debouncedQuery]
 * (class KDoc "Debounce") coalesces a fast typing burst into a single re-filter once the user
 * pauses for [SEARCH_DEBOUNCE_MS].
 *
 * ## Retry (Resource contract, without `flatMapLatest`)
 * [com.bobot.iptvapp.domain.util.Resource] documents that every [Resource.Error] consumer "should
 * show an error card ... with a retry action". [onRetry] implements that contract, but only once
 * loading has actually been triggered at least once (see class KDoc "Lazy loading" — a screen that
 * never loaded anything has nothing to retry). Once triggered, this ViewModel keeps a single
 * cancellable [searchJob]: [onRetry] invalidates the repository's session cache, cancels the current
 * job, and starts a fresh one via [startCollecting] — which re-invokes every category/content Flow
 * (via a brand-new [buildSearchResultsFlow] call, so fresh category and per-category Flow instances
 * are obtained, genuinely re-running the fetch) while [debouncedQuery] (a hot, shared [StateFlow] —
 * see class KDoc "Debounce") immediately replays its current, already-settled value into the new
 * [combine] chain, preserving whatever the user had already typed, with no extra debounce wait.
 *
 * [onRetry] also sets [SearchUiState.isLoading] back to `true` synchronously, before
 * [startCollecting] launches its coroutine — the exact same "close the transient-frame gap" reason
 * documented on [triggerLazyLoadOnce]: without it, the frame between clearing the previous error
 * and [buildSearchResultsFlow]'s first new emission would have query non-blank + `isLoading == false`
 * + `hasAnyResults == false`, which [SearchScreen] would misread as "no results" instead of
 * "retrying".
 *
 * ## Global language filter
 * Unlike [com.bobot.iptvapp.ui.screen.home.HomeViewModel] (one [com.bobot.iptvapp.domain.model.LanguageFilterState]
 * per catalog tab), Search shows Live/Movies/Series results simultaneously in a single screen, so it
 * exposes **one global selector** ([onLanguageSelected]) filtering all three sections at once — same
 * "filter, not sort" semantics and same "untagged/unmatched category excluded whenever a filter is
 * active" rule as Home, now derived from the same, shared
 * [FilterCatalogByLanguageUseCase] both this ViewModel and
 * [com.bobot.iptvapp.ui.screen.home.HomeViewModel] use (Task 3/4 — extracted from what used to be
 * independently duplicated logic in each ViewModel; see [FilterCatalogByLanguageUseCase] KDoc).
 *
 * [selectedLanguageState] deliberately stays a plain `MutableStateFlow<String?>` rather than a
 * [com.bobot.iptvapp.domain.model.LanguageFilterState] instance: unlike Home's per-tab filters
 * (where [com.bobot.iptvapp.domain.model.LanguageFilterState.available] is *written back* into the
 * same [MutableStateFlow] a `combine` also reads the selection from — requiring the
 * `distinctUntilChanged` anti-feedback-loop pattern documented on
 * [com.bobot.iptvapp.ui.screen.home.HomeViewModel] KDoc "Per-tab language filter"), Search's
 * [selectedLanguageState] is *only ever read*, never written to, by [buildSearchResultsFlow]'s
 * `filterContextFlow` — [SearchUiState.availableLanguages] is instead computed fresh on every
 * `combine` emission (see "Available languages + filter context..." below), so there is no feedback
 * loop to guard against, and no benefit to pairing "available" and "selected" into one instance
 * here. [onLanguageSelected] may be called before the catalog load has ever been triggered (see
 * class KDoc "Lazy loading"): it simply records the selection, which is applied once (and if) a
 * catalog load starts. This matches the codebase's principle that selecting a language must never,
 * by itself, cause a fetch.
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
 * on any of them to learn each type's resolved categories list, [loadCategoryScopedItems] takes an
 * optional `onCategoriesResolved` callback, invoked exactly once per call, right where the existing
 * `Resource.Success` branch already has `categoriesResource.data` in hand — zero-cost, zero-extra-
 * subscription. [buildSearchResultsFlow] passes callbacks that publish into
 * [liveCategoriesState] / [vodCategoriesState] / [seriesCategoriesState] (plain [MutableStateFlow]s,
 * *not* re-subscriptions to the repository Flows).
 *
 * ### Available languages + filter context, without exceeding `combine`'s 5-arg overloads
 * [buildSearchResultsFlow] has eight logical inputs ([debouncedQuery], `channelsState`,
 * `moviesState`, `seriesState`, `selectedLanguageState`, plus the three `*CategoriesState`s) — more
 * than `kotlinx.coroutines.flow.combine`'s direct 2-5-flow overloads support, and the reflective
 * `Array<*>`-vararg overload would require an unchecked cast. Instead, the three categories states
 * and [selectedLanguageState] are first combined into one intermediate [SearchFilterContext], whose
 * lambda also derives [SearchUiState.availableLanguages] via
 * [FilterCatalogByLanguageUseCase.availableLanguages] applied to the union of the three currently-
 * known categories lists — and that single combined Flow becomes the fifth input alongside
 * [debouncedQuery]/`channelsState`/`moviesState`/`seriesState` — fitting the direct 5-arg `combine`
 * overload with no unchecked casts anywhere.
 *
 * ### Filtering
 * [filterChannels]/[filterMovies]/[filterSeries] apply the language filter *in addition to* (never
 * instead of) the existing substring query match: for each item, its category is looked up by
 * `categoryId` in that content type's currently-known categories, and the item is kept only when
 * [FilterCatalogByLanguageUseCase.matches] returns `true` for that category and
 * [SearchFilterContext.selectedLanguage] — an item whose category cannot be found, or whose category
 * has no detectable tag, is excluded whenever a filter is active, same as Home.
 *
 * ### No new network fetch
 * [onLanguageSelected] only writes to [selectedLanguageState], a plain in-memory
 * [MutableStateFlow] purely re-filtering already-accumulated state through [combine] (once a load
 * has started — see class KDoc "Lazy loading") — it never touches [loadCategoryScopedItems] or
 * [buildSearchResultsFlow] itself, so selecting a language never triggers a new network fetch,
 * mirroring [com.bobot.iptvapp.ui.screen.home.HomeViewModel.onLanguageSelected].
 *
 * @param catalogRepository Read access to categories and content lists for all three content types.
 * @param filterCatalogByLanguageUseCase Shared domain logic for deriving available language tags
 *                            and matching a category against a selection — see class KDoc "Global
 *                            language filter".
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val filterCatalogByLanguageUseCase: FilterCatalogByLanguageUseCase,
) : ViewModel() {

    private companion object {
        /**
         * Debounce delay applied to [_query] before it reaches [debouncedQuery] — see class KDoc
         * "Debounce". 300ms is the common Android search-field debounce default: long enough to
         * coalesce a normal typing cadence into a single settled value, short enough that the
         * delay stays imperceptible once the user actually pauses. No other debounce convention
         * exists yet elsewhere in this codebase to align with, so this is a sensible default
         * rather than a value derived from an existing constant — revisit if real-device/TV-remote
         * UX testing shows it too short (refiltering/re-fetch still visible per keystroke) or too
         * long (search feels laggy).
         */
        const val SEARCH_DEBOUNCE_MS = 300L
    }

    private val _query = MutableStateFlow("")

    /**
     * [_query], debounced by [SEARCH_DEBOUNCE_MS] and shared/hot for this ViewModel's lifetime —
     * see class KDoc "Debounce" for the full rationale (single timer, no double-debounce on a
     * fresh [buildSearchResultsFlow] subscription, echo stays un-debounced).
     */
    @OptIn(FlowPreview::class)
    private val debouncedQuery: StateFlow<String> =
        _query.debounce(SEARCH_DEBOUNCE_MS).stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Global language filter selection — see class KDoc "Global language filter". `null` = "Toutes". */
    private val selectedLanguageState = MutableStateFlow<String?>(null)

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    /**
     * `true` once [triggerLazyLoadOnce] has started [startCollecting] for this ViewModel instance —
     * see class KDoc "Lazy loading". Only ever mutated from the main thread (both the `init`
     * collector calling it and Compose run there), so a plain `Boolean` is safe without extra
     * synchronization, mirroring [com.bobot.iptvapp.ui.screen.home.HomeViewModel]'s
     * `requestedContentTypes`.
     */
    private var requestedLoad = false

    init {
        // Unconditional, un-debounced query echo — never gated behind loading or the debounce
        // delay, so the search field never appears to lag behind typing or to reject a keystroke
        // just because nothing has loaded yet. See class KDoc "Lazy loading" and "Debounce".
        viewModelScope.launch {
            _query.collect { query -> _uiState.update { it.copy(query = query) } }
        }

        // Lazy-load trigger — debounced (reads debouncedQuery, not raw _query) so a fast
        // "type then clear" burst never fires a fetch. See class KDoc "Lazy loading" and "Debounce".
        viewModelScope.launch {
            debouncedQuery.collect { query ->
                if (query.trim().isNotEmpty()) {
                    triggerLazyLoadOnce()
                }
            }
        }
    }

    /** Updates the query driving in-memory filtering — see class KDoc "Client-side filtering". */
    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    /**
     * Updates the single, global language filter applied to all three result sections at once —
     * see class KDoc "Global language filter". `language = null` means "Toutes" (no filter). May be
     * called before the catalog load has ever been triggered; the selection is simply recorded and
     * applied once (and if) [triggerLazyLoadOnce] starts a load.
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
     * without `flatMapLatest`)". A no-op when the catalog has never been loaded in the first place
     * ([requestedLoad] still `false`, see class KDoc "Lazy loading") — there is nothing to retry
     * until the user has typed something at least once. Also eagerly clears any previously shown
     * error message and sets [SearchUiState.isLoading] back to `true`, synchronously, before
     * [startCollecting] even launches its coroutine — same "close the transient-frame gap" reason as
     * [triggerLazyLoadOnce]'s KDoc: without it, the frame between clearing the previous error and
     * [buildSearchResultsFlow]'s first new emission would have query non-blank + `isLoading == false`
     * + `hasAnyResults == false`, which [SearchScreen] would misread as "no results" rather than
     * "retrying".
     */
    fun onRetry() {
        if (!requestedLoad) return
        catalogRepository.invalidateCaches()
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        startCollecting()
    }

    /**
     * Starts [startCollecting] the first time it is called for this ViewModel instance, guarded by
     * [requestedLoad] — every subsequent call (further non-blank keystrokes) is a no-op, so the
     * catalog is fetched at most once per ViewModel instance. See class KDoc "Lazy loading".
     *
     * Sets [SearchUiState.isLoading] to `true` synchronously, before [startCollecting] even launches
     * its coroutine, to close the transient-frame gap described in class KDoc "Lazy loading" (last
     * bullet) — otherwise a frame could exist where the query is already non-blank but nothing yet
     * reports loading, which [SearchScreen] could misread as "no results".
     */
    private fun triggerLazyLoadOnce() {
        if (requestedLoad) return
        requestedLoad = true
        _uiState.update { it.copy(isLoading = true) }
        startCollecting()
    }

    /**
     * (Re)subscribes to [buildSearchResultsFlow], cancelling any previously running collection
     * first so [onRetry] never leaves two collectors racing to update [_uiState].
     *
     * Every emission is applied via `.copy(query = current.query)` rather than a wholesale
     * `_uiState.value = newState` — [buildSearchResultsFlow]'s `resultsFlow` derives its
     * [SearchUiState.query] from [debouncedQuery] (class KDoc "Debounce"), which can legitimately
     * lag a few hundred milliseconds behind the latest keystroke. Progressive category loading
     * (class KDoc "Category-scoped, on-demand loading (OOM fix)") can make `resultsFlow` re-emit
     * at any time, including while the user is mid-keystroke inside the debounce window — a
     * wholesale assignment would then flash the `TextField` back to the *stale*, already-superseded
     * debounced query, exactly the "regression UX" the un-debounced echo collector (`init`, first
     * bullet) exists to prevent. Preserving `current.query` keeps the query field permanently owned
     * by that un-debounced echo, while every other field ([SearchUiState.liveResults], etc.) still
     * comes from [buildSearchResultsFlow].
     */
    private fun startCollecting() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            buildSearchResultsFlow().collect { newState ->
                _uiState.update { current -> newState.copy(query = current.query) }
            }
        }
    }

    /**
     * Builds the Flow of [SearchUiState] driving [uiState] — see class KDoc "Category-scoped,
     * on-demand loading (OOM fix)" for why this no longer opens three concurrent unfiltered
     * (`categoryId = null`) item Flows. Instead:
     *  - `channelsState` / `moviesState` / `seriesState` are private [MutableStateFlow]s, each
     *    created exactly once per call and combined with [debouncedQuery] (not raw [_query] — see
     *    class KDoc "Debounce") to produce [reduceUiState]'s input.
     *  - The returned [Flow] is a [channelFlow] that launches a single child coroutine which
     *    sequentially awaits [loadCategoryScopedItems] for Live, then Movies, then Series — a
     *    plain sequential `launch { ... }` body (no `async`, no `combine` of the three loaders),
     *    which is what actually guarantees at most one content type's per-category fetch loop is
     *    ever running at a time, on top of [loadCategoryScopedItems] itself guaranteeing at most
     *    one category's fetch is in flight within that content type. Concurrently, the
     *    `channelFlow` body also collects and forwards the query/results `combine()` so the UI
     *    keeps receiving progressively refined results as the sequential loader advances.
     *
     * Only ever called once (and if) [triggerLazyLoadOnce] fires for the first time, or again from
     * [onRetry] — never from `init` directly, see class KDoc "Lazy loading".
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
            SearchFilterContext(
                liveCategories = live,
                vodCategories = vod,
                seriesCategories = series,
                selectedLanguage = selectedLanguage,
                availableLanguages = filterCatalogByLanguageUseCase.availableLanguages(live + vod + series),
            )
        }

        val resultsFlow = combine(
            debouncedQuery,
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
     * Folds the latest (debounced — see class KDoc "Debounce") query + three content [Resource]s +
     * [SearchFilterContext] (global language filter — see class KDoc "Global language filter") into
     * the next [SearchUiState]. The returned [SearchUiState.query] is only ever a transient value
     * here: [startCollecting] immediately overwrites it back to the un-debounced, always up-to-date
     * one before publishing to [uiState] — see that function's KDoc.
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
     * also kept only when [FilterCatalogByLanguageUseCase.matches] returns `true` for its resolved
     * category (looked up in [categories] by [Channel.categoryId]) and [selectedLanguage] — an
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
            ?.filter { channel -> filterCatalogByLanguageUseCase.matches(categoryMap[channel.categoryId], selectedLanguage) }
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
            ?.filter { movie -> filterCatalogByLanguageUseCase.matches(categoryMap[movie.categoryId], selectedLanguage) }
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
            ?.filter { series -> filterCatalogByLanguageUseCase.matches(categoryMap[series.categoryId], selectedLanguage) }
            ?.map { series: Series -> toResultItem(series) }
            .orEmpty()
    }

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

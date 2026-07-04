package com.bobot.iptvapp.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobot.iptvapp.domain.model.Channel
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
 * @property isLoading      `true` while any of the three underlying catalog Flows is still in
 *                           [Resource.Loading] — mirrors
 *                           [com.bobot.iptvapp.ui.screen.home.HomeUiState.isLoading].
 * @property errorMessage   Human-readable message from the first catalog section currently in
 *                           [Resource.Error], or `null` if none — mirrors
 *                           [com.bobot.iptvapp.ui.screen.home.HomeUiState.errorMessage].
 */
data class SearchUiState(
    val query: String = "",
    val liveResults: List<SearchResultItem> = emptyList(),
    val movieResults: List<SearchResultItem> = emptyList(),
    val seriesResults: List<SearchResultItem> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
) {
    /** `true` once at least one result exists in any section. */
    val hasAnyResults: Boolean
        get() = liveResults.isNotEmpty() || movieResults.isNotEmpty() || seriesResults.isNotEmpty()
}

/**
 * Hilt ViewModel driving [SearchScreen] (Task 21).
 *
 * ## No server-side search endpoint
 * [CatalogRepository] exposes no text-search method — neither does the underlying Xtream Codes
 * API. This ViewModel instead collects the same "all items across categories" Flows
 * ([CatalogRepository.getLiveChannels], [CatalogRepository.getMovies],
 * [CatalogRepository.getSeriesList], each called with `categoryId = null`) that
 * [com.bobot.iptvapp.ui.screen.home.HomeViewModel] already combines per-section, and filters them
 * in-memory against a [query] this ViewModel owns.
 *
 * ## Client-side filtering
 * [combine] takes the query Flow plus the three content Flows (four inputs total — within
 * [kotlinx.coroutines.flow.combine]'s stable, non-experimental overload range, so no
 * `flatMapLatest` / `@OptIn(ExperimentalCoroutinesApi::class)` is needed here, unlike
 * [com.bobot.iptvapp.ui.screen.home.HomeViewModel]'s per-section category grouping). Every
 * recombination re-filters the full in-memory lists by a case-insensitive substring match against
 * [Channel.name] / [Movie.title] / [Series.title]. Filtering is cheap (no network I/O per
 * keystroke, just a list scan over already-cached data), so **no debounce is applied** — this is a
 * deliberate choice for simplicity/correctness over premature optimisation; a debounce could be
 * added later purely for UX polish (fewer state recompositions while typing fast) without changing
 * behaviour.
 *
 * ## Retry (Resource contract, without `flatMapLatest`)
 * [com.bobot.iptvapp.domain.util.Resource] documents that every [Resource.Error] consumer "should
 * show an error card ... with a retry action". Because the production
 * [com.bobot.iptvapp.data.repository.CatalogRepositoryImpl] Flows are cold and single-shot (they
 * complete after their first [Resource.Success]/[Resource.Error] emission), simply calling
 * [CatalogRepository.invalidateCaches] would not, by itself, cause an already-completed collection
 * to re-emit. Rather than reaching for `flatMapLatest` (as
 * [com.bobot.iptvapp.ui.screen.home.HomeViewModel] does, keyed off a replayed `retryTrigger`), this
 * ViewModel keeps a single cancellable [searchJob]: [onRetry] invalidates the repository's session
 * cache, cancels the current job, and starts a fresh one via [startCollecting] — which re-invokes
 * [CatalogRepository.getLiveChannels] / [getMovies] / [getSeriesList] to obtain brand-new Flow
 * instances (so the fetch genuinely re-runs) while [query] (a hot [MutableStateFlow]) immediately
 * replays its current value into the new [combine] chain, preserving whatever the user had already
 * typed.
 *
 * @param catalogRepository Read access to the unfiltered content lists for all three content types.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")

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
     * Clears the repository's session cache and restarts collection so every underlying content
     * Flow is genuinely re-invoked — see class KDoc "Retry (Resource contract, without
     * `flatMapLatest`)". Also eagerly clears any previously shown error message so the retry
     * banner/full-screen error disappears immediately while the new load is in flight.
     */
    fun onRetry() {
        catalogRepository.invalidateCaches()
        _uiState.update { it.copy(errorMessage = null) }
        startCollecting()
    }

    /**
     * (Re)subscribes to [query] combined with the three unfiltered content Flows, cancelling any
     * previously running collection first so [onRetry] never leaves two collectors racing to
     * update [_uiState].
     */
    private fun startCollecting() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            combine(
                _query,
                catalogRepository.getLiveChannels(categoryId = null),
                catalogRepository.getMovies(categoryId = null),
                catalogRepository.getSeriesList(categoryId = null),
            ) { query, live, movies, series ->
                reduceUiState(query, live, movies, series)
            }.collect { newState -> _uiState.value = newState }
        }
    }

    /** Folds the latest query + three content [Resource]s into the next [SearchUiState]. */
    private fun reduceUiState(
        query: String,
        live: Resource<List<Channel>>,
        movies: Resource<List<Movie>>,
        series: Resource<List<Series>>,
    ): SearchUiState {
        val resources = listOf(live, movies, series)
        val isLoading = resources.any { it is Resource.Loading }
        // First error wins, in Live -> Movies -> Series order — good enough to surface *a*
        // meaningful message; the retry action re-fetches all three regardless of which failed.
        val errorMessage = resources.filterIsInstance<Resource.Error>().firstOrNull()?.message

        val trimmedQuery = query.trim()
        val liveResults = if (trimmedQuery.isEmpty()) emptyList() else filterChannels(live, trimmedQuery)
        val movieResults = if (trimmedQuery.isEmpty()) emptyList() else filterMovies(movies, trimmedQuery)
        val seriesResults = if (trimmedQuery.isEmpty()) emptyList() else filterSeries(series, trimmedQuery)

        return SearchUiState(
            query = query,
            liveResults = liveResults,
            movieResults = movieResults,
            seriesResults = seriesResults,
            isLoading = isLoading,
            errorMessage = errorMessage,
        )
    }

    private fun filterChannels(resource: Resource<List<Channel>>, query: String): List<SearchResultItem> =
        (resource as? Resource.Success)?.data
            ?.filter { it.name.contains(query, ignoreCase = true) }
            ?.map { channel: Channel -> toResultItem(channel) }
            .orEmpty()

    private fun filterMovies(resource: Resource<List<Movie>>, query: String): List<SearchResultItem> =
        (resource as? Resource.Success)?.data
            ?.filter { it.title.contains(query, ignoreCase = true) }
            ?.map { movie: Movie -> toResultItem(movie) }
            .orEmpty()

    private fun filterSeries(resource: Resource<List<Series>>, query: String): List<SearchResultItem> =
        (resource as? Resource.Success)?.data
            ?.filter { it.title.contains(query, ignoreCase = true) }
            ?.map { series: Series -> toResultItem(series) }
            .orEmpty()

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

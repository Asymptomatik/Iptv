package com.bobot.iptvapp.ui.screen.seriesdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.data.remote.XtreamUrlBuilder
import com.bobot.iptvapp.data.source.CredentialsProvider
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.Episode
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.repository.FavoritesRepository
import com.bobot.iptvapp.domain.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state consumed by [SeriesDetailScreen] (Task 19).
 *
 * @property isLoading             `true` while the one-shot [CatalogRepository.getSeriesDetail]
 *                                  fetch is in flight. Drives the full-screen spinner (same
 *                                  pattern as [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailUiState]).
 * @property errorMessage          Human-readable message when the fetch failed, or `null`. Only
 *                                  rendered as a full-screen error when [series] is also `null`,
 *                                  mirroring [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailUiState]'s
 *                                  convention.
 * @property series                The fetched [Series] with its full season/episode tree
 *                                  populated, or `null` before the first successful load.
 * @property isFavorite            Whether [series] (the whole series, not a single episode — see
 *                                  [SeriesDetailViewModel] KDoc "Favorite scope") is in the active
 *                                  profile's favorites list. Always `false` when no profile is
 *                                  active.
 * @property selectedSeasonNumber  [com.bobot.iptvapp.domain.model.Season.seasonNumber] of the
 *                                  season currently displayed by the season selector, or `null`
 *                                  before content loads / when [series] has no seasons. Defaults
 *                                  to the first season in [Series.seasons] once loaded (seasons
 *                                  are already sorted ascending by the domain layer).
 * @property hasCredentials        `true` when Xtream credentials were available at load time (see
 *                                  [SeriesDetailViewModel] KDoc "Missing credentials"). When
 *                                  `false`, [SeriesDetailScreen] disables episode rows instead of
 *                                  navigating with an unusable URL.
 */
data class SeriesDetailUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val series: Series? = null,
    val isFavorite: Boolean = false,
    val selectedSeasonNumber: Int? = null,
    val hasCredentials: Boolean = true,
) {
    /**
     * Episodes of the season matching [selectedSeasonNumber] within [series], already sorted
     * ascending by [com.bobot.iptvapp.domain.model.Episode.episodeNumber] per the domain model's
     * contract. Empty when [series] is `null`, has no seasons, [selectedSeasonNumber] does not
     * match any season, or the matching season itself has no episodes (defensive — the fake
     * catalog exercises this last case deliberately).
     */
    val selectedSeasonEpisodes: List<Episode>
        get() = series?.seasons
            ?.firstOrNull { it.seasonNumber == selectedSeasonNumber }
            ?.episodes
            .orEmpty()
}

/**
 * Hilt ViewModel driving [SeriesDetailScreen] (Task 19) — mirrors the structure established by
 * [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModel] (Task 18, just-passed-review
 * sibling task): `@HiltViewModel` + single [StateFlow], idempotent [initialize], favorite Flow
 * observed via a cancellable child [Job] (not `flatMapLatest`, avoiding the need for
 * `@OptIn(ExperimentalCoroutinesApi::class)`), and defensive missing-credentials / no-active-profile
 * guards. Adapted here for the series' season/episode hierarchy instead of a single playable item.
 *
 * ## Favorite scope
 * Per [com.bobot.iptvapp.domain.repository.FavoritesRepository] and how
 * [com.bobot.iptvapp.ui.screen.home.HomeScreen]'s `HomeCardItem` for
 * [ContentType.SERIES] already uses `contentId = series.id`, favorites are tracked at the
 * **series** level — one favorite per whole series, never per-episode.
 *
 * ## Season selection
 * [Series.seasons] is already sorted ascending by
 * [com.bobot.iptvapp.domain.model.Season.seasonNumber] by the domain layer. On a successful load,
 * [SeriesDetailUiState.selectedSeasonNumber] defaults to the first season's number (or `null` if
 * the series has no seasons at all — defensively handled, not expected in practice). Switching
 * seasons via [onSelectSeason] is a pure local state update; it never re-fetches from the network
 * since [catalogRepository]'s one-shot [CatalogRepository.getSeriesDetail] call already returned
 * the full season/episode tree up front.
 *
 * ## Missing container extension
 * [Episode.containerExtension] is nullable, mirroring
 * [com.bobot.iptvapp.domain.model.Movie.containerExtension]. When absent or blank,
 * [DEFAULT_CONTAINER_EXTENSION] ("mp4") is used as a fallback, same rationale as
 * [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModel].
 *
 * ## Missing credentials
 * [credentials] is resolved once during [loadSeries] (alongside the series fetch) and cached for
 * the lifetime of this ViewModel instance, rather than re-fetched on every episode tap — see
 * [buildEpisodeStreamUrl] KDoc. When [CredentialsProvider.getCredentials] returns `null` (should
 * not normally happen once onboarding is complete, but defensively handled exactly like
 * [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModel]), [SeriesDetailUiState.hasCredentials]
 * is `false` and [SeriesDetailScreen] disables episode rows instead of navigating with an unusable
 * URL.
 *
 * ## No active profile
 * When [AppPreferencesStore.getActiveProfileId] returns `null`, favorite state stays at its
 * default (`false`) and [onToggleFavorite] becomes a no-op, mirroring
 * [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModel].
 *
 * ## Out of scope (Task 19)
 * Per-episode resume/progress indicators are explicitly out of scope for this task (Task 23's
 * job) — episode playback always starts from the beginning via [buildEpisodeStreamUrl]; the
 * player itself independently re-reads stored progress on its own (see
 * [com.bobot.iptvapp.ui.screen.player.PlayerViewModel.initialize]), untouched here.
 *
 * @param catalogRepository   One-shot series detail fetch ([CatalogRepository.getSeriesDetail]) —
 *                            returns the full season/episode tree, unlike
 *                            [CatalogRepository.getSeriesList].
 * @param favoritesRepository Favorite toggle + reactive observation for the heart button, scoped
 *                            to the whole series (see "Favorite scope" above).
 * @param appPreferencesStore Resolves the active profile ID that scopes favorites.
 * @param credentialsProvider Resolves the Xtream credentials used to build per-episode stream
 *                            URLs (see "Missing credentials" above).
 */
@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val favoritesRepository: FavoritesRepository,
    private val appPreferencesStore: AppPreferencesStore,
    private val credentialsProvider: CredentialsProvider,
) : ViewModel() {

    private companion object {
        /** Fallback used when [Episode.containerExtension] is `null` or blank — see class KDoc. */
        const val DEFAULT_CONTAINER_EXTENSION = "mp4"
    }

    private val _uiState = MutableStateFlow(SeriesDetailUiState())
    val uiState: StateFlow<SeriesDetailUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var seriesId: String? = null
    private var activeProfileId: String? = null
    private var credentials: XtreamCredentials? = null
    private var favoriteObservationJob: Job? = null

    /**
     * Loads the series identified by [seriesId]. Idempotent — only the first call per ViewModel
     * instance has an effect (same guard pattern as
     * [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModel.initialize]), so
     * [SeriesDetailScreen] can safely call this from a `LaunchedEffect` keyed on `seriesId`
     * without re-triggering the fetch on every recomposition.
     */
    fun initialize(seriesId: String) {
        if (initialized) return
        initialized = true
        this.seriesId = seriesId
        loadSeries(seriesId)
    }

    /** Re-runs the fetch for the current [seriesId] — wired to the error state's retry action. */
    fun onRetry() {
        val id = seriesId ?: return
        loadSeries(id)
    }

    /**
     * Switches the season currently displayed by the season selector. A no-op in the sense that
     * it never re-fetches — see class KDoc "Season selection". Does not validate that
     * [seasonNumber] actually exists in [SeriesDetailUiState.series]; an unmatched value simply
     * yields an empty [SeriesDetailUiState.selectedSeasonEpisodes] list (defensive, mirrors that
     * property's own null-safety).
     */
    fun onSelectSeason(seasonNumber: Int) {
        _uiState.update { it.copy(selectedSeasonNumber = seasonNumber) }
    }

    /**
     * Toggles the favorite state of the current series (whole-series scope — see class KDoc
     * "Favorite scope") for the active profile. A no-op when no series has loaded yet or no
     * profile is active — the UI state update itself is driven reactively by
     * [favoritesRepository]'s `isFavorite` Flow via [observeFavorite], not by this function's
     * completion.
     */
    fun onToggleFavorite() {
        val profileId = activeProfileId ?: return
        val id = seriesId ?: return
        viewModelScope.launch {
            favoritesRepository.toggleFavorite(profileId, id, ContentType.SERIES)
        }
    }

    /**
     * Builds the direct-play URL for [episode] using [credentials] cached once during
     * [loadSeries] — see class KDoc "Missing credentials". Synchronous and safe to call directly
     * from a Compose click lambda (e.g. [SeriesDetailScreen]'s per-episode `onClick`): no
     * async/`LaunchedEffect` navigation-event indirection is needed since credentials are already
     * resident by the time episodes are rendered. Returns `null` when no credentials are
     * configured; the caller (screen) does not navigate in that case.
     */
    fun buildEpisodeStreamUrl(episode: Episode): String? {
        val creds = credentials ?: return null
        val extension = episode.containerExtension?.takeIf { it.isNotBlank() } ?: DEFAULT_CONTAINER_EXTENSION

        return XtreamUrlBuilder.buildEpisodeUrl(
            baseUrl = creds.baseUrl,
            username = creds.username,
            password = creds.password,
            episodeId = episode.id,
            containerExtension = extension,
        )
    }

    // ─── Internal ────────────────────────────────────────────────────────────────

    private fun loadSeries(seriesId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val profileId = appPreferencesStore.getActiveProfileId()
            activeProfileId = profileId

            credentials = credentialsProvider.getCredentials()
            _uiState.update { it.copy(hasCredentials = credentials != null) }

            when (val result = catalogRepository.getSeriesDetail(seriesId)) {
                is Resource.Success -> {
                    val series = result.data
                    val firstSeasonNumber = series.seasons.firstOrNull()?.seasonNumber

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = null,
                            series = series,
                            selectedSeasonNumber = firstSeasonNumber,
                        )
                    }

                    if (profileId != null) {
                        observeFavorite(profileId, seriesId)
                    }
                }

                is Resource.Error -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
                }

                // Resource docs: "Suspend methods do not emit Loading" — kept only for
                // `when` exhaustiveness over the sealed Resource type.
                Resource.Loading -> Unit
            }
        }
    }

    /**
     * Collects [FavoritesRepository.isFavorite] for the lifetime of the ViewModel so
     * [SeriesDetailUiState.isFavorite] stays in sync with [onToggleFavorite] and with changes made
     * elsewhere. Cancels any previous collection first so [onRetry] never accumulates duplicate
     * collectors against the same Flow — identical pattern to
     * [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModel.observeFavorite].
     */
    private fun observeFavorite(profileId: String, seriesId: String) {
        favoriteObservationJob?.cancel()
        favoriteObservationJob = viewModelScope.launch {
            favoritesRepository.isFavorite(profileId, seriesId, ContentType.SERIES)
                .collect { isFavorite -> _uiState.update { it.copy(isFavorite = isFavorite) } }
        }
    }
}

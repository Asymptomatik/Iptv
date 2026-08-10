package com.bobot.iptvapp.ui.screen.moviedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.data.remote.XtreamUrlBuilder
import com.bobot.iptvapp.data.source.CredentialsProvider
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.DownloadContentType
import com.bobot.iptvapp.domain.model.DownloadRequestData
import com.bobot.iptvapp.domain.model.DownloadRequestId
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.OfflineDownload
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.repository.DownloadRepository
import com.bobot.iptvapp.domain.repository.FavoritesRepository
import com.bobot.iptvapp.domain.repository.PlaybackProgressRepository
import com.bobot.iptvapp.domain.util.Resource
import com.bobot.iptvapp.domain.util.displayTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state consumed by [MovieDetailScreen] (Task 18).
 *
 * @property isLoading     `true` while the one-shot [CatalogRepository.getMovieDetail] fetch is
 *                          in flight. Drives the full-screen spinner (see [MovieDetailScreen]'s
 *                          state-selection logic, mirroring [com.bobot.iptvapp.ui.screen.home.HomeUiState]).
 * @property errorMessage  Human-readable message when the fetch failed, or `null`. Only
 *                          rendered as a full-screen error when [movie] is also `null` — an
 *                          error surfacing *after* content already loaded once (e.g. a failed
 *                          [MovieDetailViewModel.onRetry]) is not currently reachable since
 *                          [onRetry] always starts from a blank state, but the guard is kept
 *                          consistent with the [com.bobot.iptvapp.ui.screen.home.HomeUiState]
 *                          convention regardless.
 * @property movie         The fetched [Movie], or `null` before the first successful load.
 * @property isFavorite    Whether [movie] is in the active profile's favorites list. Always
 *                          `false` when no profile is active (see [MovieDetailViewModel] KDoc
 *                          "No active profile").
 * @property canResume     `true` when a prior playback position exists for [movie] and is
 *                          "resumable" per [MovieDetailViewModel]'s eligibility rule (see class
 *                          KDoc "Resume eligibility"). Drives the play button's label ("Reprendre"
 *                          vs "Lire") on [MovieDetailScreen].
 * @property streamUrl     Direct-play URL built by [XtreamUrlBuilder.buildMovieUrl], or `null`
 *                          when no Xtream credentials are configured (should not normally happen
 *                          once onboarding is complete, but the play button is disabled rather
 *                          than crashing — see [MovieDetailViewModel] KDoc "Missing credentials").
 */
data class MovieDetailUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val movie: Movie? = null,
    val isFavorite: Boolean = false,
    val canResume: Boolean = false,
    val streamUrl: String? = null,
    val download: OfflineDownload? = null,
)

/**
 * Hilt ViewModel driving [MovieDetailScreen] (Task 18) — follows the `@HiltViewModel` +
 * `@Inject constructor` convention established by [com.bobot.iptvapp.ui.screen.home.HomeViewModel]
 * (only `domain.repository` / `data.preferences` / `data.source` collaborators injected, never
 * concrete `data.local` types) and the `initialize(id)` idempotent-entry-point pattern
 * established by [com.bobot.iptvapp.ui.screen.player.PlayerViewModel] (navigation arguments are
 * not read from `SavedStateHandle`; the composable passes them explicitly once, from the value
 * `AppNavGraph` already decoded via `toRoute<Detail>()`).
 *
 * ## Resume eligibility
 * The brief only asks for "bouton lecture (+ reprise si une progression existe)" without
 * specifying an exact threshold, so this ViewModel makes an explicit assumption (documented here
 * per the task's instructions):
 *  - a position under [MIN_RESUMABLE_POSITION_MS] (5s) is treated as "not really started" —
 *    ignoring accidental/negligible positions (e.g. the player was opened and immediately closed);
 *  - a position at or beyond [COMPLETION_THRESHOLD_RATIO] (95%) of the known duration is treated
 *    as "already finished" — resuming a movie 30 seconds from the end is not useful, "Lire" (i.e.
 *    play from the start) is the more sensible label and action;
 *  - when the record's duration is unknown (`0L`), only the minimum-position rule applies.
 * [canResume] only affects the play button's **label**; the actual navigation to
 * [com.bobot.iptvapp.ui.screen.player.PlayerScreen] always passes the same [streamUrl] — the
 * player itself independently re-reads [PlaybackProgressRepository.getProgress] and resumes from
 * the stored position on its own (see [com.bobot.iptvapp.ui.screen.player.PlayerViewModel.initialize]),
 * so this ViewModel never needs to pass a start position explicitly.
 *
 * ## Missing container extension
 * [Movie.containerExtension] is nullable. When absent or blank, [DEFAULT_CONTAINER_EXTENSION]
 * ("mp4") is used as a reasonable fallback — the most broadly compatible container for Media3
 * playback among the formats Xtream Codes VOD typically serves.
 *
 * ## Missing credentials
 * [buildStreamUrl] returns `null` when [CredentialsProvider.getCredentials] returns `null` (no
 * Xtream account configured — should not normally happen once onboarding, Task 14, is complete,
 * but is defensively handled). [MovieDetailScreen] disables the play button in that case instead
 * of navigating with an unusable URL.
 *
 * ## No active profile
 * When [AppPreferencesStore.getActiveProfileId] returns `null` (should not normally happen once
 * profile selection, Task 16, is wired — mirrors the same defensive guard in
 * [com.bobot.iptvapp.ui.screen.player.PlayerViewModel.initialize]), favorite/resume state simply
 * stays at its default (`false`) and [onToggleFavorite] becomes a no-op, rather than crashing.
 *
 * @param catalogRepository          One-shot movie metadata fetch ([CatalogRepository.getMovieDetail]).
 * @param favoritesRepository        Favorite toggle + reactive observation for the heart button.
 * @param playbackProgressRepository Resume-eligibility lookup (read-only here; writes happen in
 *                                   the player, see "Resume eligibility" above).
 * @param appPreferencesStore        Resolves the active profile ID that scopes favorites/progress.
 * @param credentialsProvider        Resolves the Xtream credentials used to build [MovieDetailUiState.streamUrl].
 */
@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val favoritesRepository: FavoritesRepository,
    private val playbackProgressRepository: PlaybackProgressRepository,
    private val downloadRepository: DownloadRepository,
    private val appPreferencesStore: AppPreferencesStore,
    private val credentialsProvider: CredentialsProvider,
) : ViewModel() {

    private companion object {
        /** Fallback used when [Movie.containerExtension] is `null` or blank — see class KDoc. */
        const val DEFAULT_CONTAINER_EXTENSION = "mp4"

        /** Minimum position (ms) below which a saved progress record is ignored — see class KDoc. */
        const val MIN_RESUMABLE_POSITION_MS = 5_000L

        /** Position/duration ratio at or beyond which a movie is treated as finished — see class KDoc. */
        const val COMPLETION_THRESHOLD_RATIO = 0.95
    }

    private val _uiState = MutableStateFlow(MovieDetailUiState())
    val uiState: StateFlow<MovieDetailUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var movieId: String? = null
    private var activeProfileId: String? = null
    private var favoriteObservationJob: Job? = null
    private var downloadObservationJob: Job? = null

    /**
     * Loads the movie identified by [movieId]. Idempotent — only the first call per ViewModel
     * instance has an effect (same guard pattern as
     * [com.bobot.iptvapp.ui.screen.player.PlayerViewModel.initialize]), so [MovieDetailScreen]
     * can safely call this from a `LaunchedEffect` keyed on `movieId` without re-triggering the
     * fetch on every recomposition.
     */
    fun initialize(movieId: String) {
        if (initialized) return
        initialized = true
        this.movieId = movieId
        loadMovie(movieId)
    }

    /** Re-runs the fetch for the current [movieId] — wired to the error state's retry action. */
    fun onRetry() {
        val id = movieId ?: return
        loadMovie(id)
    }

    /**
     * Toggles the favorite state of the current movie for the active profile. A no-op when no
     * movie has loaded yet or no profile is active (see class KDoc "No active profile") — the UI
     * state update itself is driven reactively by [favoritesRepository]'s `isFavorite` Flow via
     * [observeFavorite], not by this function's completion.
     */
    fun onToggleFavorite() {
        val profileId = activeProfileId ?: return
        val id = movieId ?: return
        viewModelScope.launch {
            favoritesRepository.toggleFavorite(profileId, id, ContentType.MOVIE)
        }
    }

    fun onDownloadClick() {
        val movie = _uiState.value.movie ?: return
        val streamUrl = _uiState.value.streamUrl ?: return
        viewModelScope.launch {
            downloadRepository.enqueue(
                DownloadRequestData(
                    contentType = DownloadContentType.MOVIE,
                    contentId = movie.id,
                    // Stored stripped (QA finding N4) so the Téléchargements list reads the same
                    // as the card the user tapped.
                    title = movie.displayTitle(),
                    artworkUrl = movie.posterUrl,
                    streamUrl = streamUrl,
                ),
            )
        }
    }

    fun onPauseDownload() {
        val downloadId = _uiState.value.download?.downloadId ?: return
        viewModelScope.launch { downloadRepository.pause(downloadId) }
    }

    fun onResumeDownload() {
        val downloadId = _uiState.value.download?.downloadId ?: return
        viewModelScope.launch { downloadRepository.resume(downloadId) }
    }

    // ─── Internal ────────────────────────────────────────────────────────────────

    private fun loadMovie(movieId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val profileId = appPreferencesStore.getActiveProfileId()
            activeProfileId = profileId

            when (val result = catalogRepository.getMovieDetail(movieId)) {
                is Resource.Success -> {
                    val movie = result.data
                    val streamUrl = buildStreamUrl(movie)
                    val canResume = if (profileId != null) resolveCanResume(profileId, movieId) else false

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = null,
                            movie = movie,
                            streamUrl = streamUrl,
                            canResume = canResume,
                        )
                    }

                    observeDownload(movieId)

                    if (profileId != null) {
                        observeFavorite(profileId, movieId)
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
     * [MovieDetailUiState.isFavorite] stays in sync with [onToggleFavorite] and with changes made
     * elsewhere (e.g. a future favorites-management screen). Cancels any previous collection
     * first so [onRetry] never accumulates duplicate collectors against the same Flow.
     */
    private fun observeFavorite(profileId: String, movieId: String) {
        favoriteObservationJob?.cancel()
        favoriteObservationJob = viewModelScope.launch {
            favoritesRepository.isFavorite(profileId, movieId, ContentType.MOVIE)
                .collect { isFavorite -> _uiState.update { it.copy(isFavorite = isFavorite) } }
        }
    }

    private fun observeDownload(movieId: String) {
        downloadObservationJob?.cancel()
        val downloadId = DownloadRequestId.create(DownloadContentType.MOVIE, movieId)
        downloadObservationJob = viewModelScope.launch {
            downloadRepository.observeDownload(downloadId)
                .collect { download -> _uiState.update { it.copy(download = download) } }
        }
    }

    /** See class KDoc "Resume eligibility". */
    private suspend fun resolveCanResume(profileId: String, movieId: String): Boolean {
        val progress = playbackProgressRepository.getProgress(
            profileId = profileId,
            contentId = movieId,
            contentType = ContentType.MOVIE,
        ) ?: return false

        if (progress.positionMillis < MIN_RESUMABLE_POSITION_MS) return false

        if (progress.durationMillis > 0) {
            val completionRatio = progress.positionMillis.toDouble() / progress.durationMillis.toDouble()
            if (completionRatio >= COMPLETION_THRESHOLD_RATIO) return false
        }

        return true
    }

    /** See class KDoc "Missing container extension" and "Missing credentials". */
    private suspend fun buildStreamUrl(movie: Movie): String? {
        val credentials = credentialsProvider.getCredentials() ?: return null
        val extension = movie.containerExtension?.takeIf { it.isNotBlank() } ?: DEFAULT_CONTAINER_EXTENSION

        return XtreamUrlBuilder.buildMovieUrl(
            baseUrl = credentials.baseUrl,
            username = credentials.username,
            password = credentials.password,
            streamId = movie.id,
            containerExtension = extension,
        )
    }
}

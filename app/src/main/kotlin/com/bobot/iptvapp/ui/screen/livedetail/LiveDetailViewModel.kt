package com.bobot.iptvapp.ui.screen.livedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.data.remote.XtreamUrlBuilder
import com.bobot.iptvapp.data.source.CredentialsProvider
import com.bobot.iptvapp.domain.model.Channel
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.EpgProgram
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
 * UI state consumed by [LiveDetailScreen] (Task 20).
 *
 * @property isLoading         `true` while [Channel] resolution (see [LiveDetailViewModel] KDoc
 *                              "Resolving the channel") is in flight. Drives the full-screen
 *                              spinner, mirroring [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailUiState].
 * @property errorMessage      Human-readable message when the channel could not be resolved, or
 *                              `null`. Only rendered as a full-screen error when [channel] is also
 *                              `null`, same convention as
 *                              [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailUiState].
 * @property channel           The resolved [Channel], or `null` before the first successful load.
 * @property isFavorite        Whether [channel] is in the active profile's favorites list. Always
 *                              `false` when no profile is active.
 * @property streamUrl         Direct-play URL built once from cached credentials by
 *                              [XtreamUrlBuilder.buildLiveUrl], or `null` when no Xtream
 *                              credentials are configured — see [LiveDetailViewModel] KDoc
 *                              "Missing credentials". Always plays from the start; there is no
 *                              resume label for Live (see [LiveDetailViewModel] KDoc "Out of
 *                              scope").
 * @property isEpgLoading      `true` while the one-shot [CatalogRepository.getEpg] fetch (or the
 *                              no-EPG-mapping short-circuit) is in flight/pending, for the
 *                              duration between [channel] resolving and the EPG section settling.
 * @property currentProgram    The currently-airing [EpgProgram] for [channel], or `null` when no
 *                              programme is airing right now or no EPG data is available.
 * @property upcomingPrograms  [EpgProgram]s starting after [currentProgram] (or after "now" when
 *                              no programme is currently airing), ascending by
 *                              [EpgProgram.startMillis].
 * @property epgMessage        Graceful fallback message (e.g. "Aucun programme disponible") shown
 *                              in the EPG section when [channel] has no [Channel.epgChannelId], the
 *                              fetch returned an empty list, or the fetch failed. `null` once real
 *                              EPG content is available.
 */
data class LiveDetailUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val channel: Channel? = null,
    val isFavorite: Boolean = false,
    val streamUrl: String? = null,
    val isEpgLoading: Boolean = true,
    val currentProgram: EpgProgram? = null,
    val upcomingPrograms: List<EpgProgram> = emptyList(),
    val epgMessage: String? = null,
)

/**
 * Hilt ViewModel driving [LiveDetailScreen] (Task 20) — mirrors the structure established by
 * [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModel] (Task 18): `@HiltViewModel` +
 * single [StateFlow], idempotent [initialize], favorite Flow observed via a cancellable child
 * [Job] (not `flatMapLatest`, avoiding the need for `@OptIn(ExperimentalCoroutinesApi::class)`),
 * and defensive missing-credentials / no-active-profile guards. Adapted here for a channel + its
 * EPG instead of a single one-shot detail fetch.
 *
 * ## Resolving the channel
 * Unlike [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModel.loadMovie] /
 * [com.bobot.iptvapp.ui.screen.seriesdetail.SeriesDetailViewModel.loadSeries], there is no
 * per-channel one-shot detail endpoint on [CatalogRepository] — Xtream Codes has no
 * live-stream-info endpoint analogous to `get_vod_info` / `get_series_info` (confirmed by reading
 * [CatalogRepository] in full: [Channel] carries no extra metadata beyond what
 * [CatalogRepository.getLiveChannels] already returns). This is a deliberate, accepted repository
 * design, not a workaround. The channel is instead resolved by collecting
 * [CatalogRepository.getLiveChannels] with `categoryId = null` (the same reactive Flow
 * [com.bobot.iptvapp.ui.screen.home.HomeViewModel] already uses) and finding the [Channel] whose
 * [Channel.id] matches the navigation `contentId`. Note that, per
 * [com.bobot.iptvapp.data.repository.CatalogRepositoryImpl]'s KDoc, this Flow is a cold, one-shot
 * emission sequence (`Loading` then exactly one terminal `Success`/`Error`, backed by an in-memory
 * session cache) rather than a continuously-observed live query, so a single `collect` call here
 * behaves like a suspend fetch and completes naturally — no extra cancellation bookkeeping is
 * needed for it (unlike the perpetual favorite-state collector below).
 *
 * ## Channel description ("description chaîne")
 * [Channel] has no plot/description field of its own (confirmed by reading [Channel] in full).
 * The brief's "description chaîne" requirement is satisfied using the current EPG programme's
 * [EpgProgram.title] / [EpgProgram.description] as the descriptive content — not an invented
 * channel-level field. When a channel has no EPG data at all (null [Channel.epgChannelId], an
 * empty [CatalogRepository.getEpg] result, or a failed fetch), [LiveDetailScreen] falls back to
 * showing just the channel name/logo with no description, per [LiveDetailUiState.epgMessage].
 *
 * ## EPG fetch (current / upcoming split)
 * [CatalogRepository.getEpg] is only called when [Channel.epgChannelId] is non-null, passing
 * [Channel.epgChannelId] (not [Channel.id]) as the `channelId` argument — matching the interface's
 * own KDoc ("For the fake source, pass Channel.epgChannelId") and this project's environment,
 * which always runs against [com.bobot.iptvapp.data.source.fake.FakeXtreamSource] here
 * (`BuildConfig.USE_MOCK_DATA = true`, no real Xtream account configured in this execution
 * context).
 *
 * **Known pre-existing inconsistency (carried forward, not fixed here)**:
 * [com.bobot.iptvapp.data.source.RemoteXtreamSource.getShortEpg]'s own KDoc contradicts
 * [CatalogRepository.getEpg]'s contract, stating the real Xtream `get_short_epg` endpoint
 * actually expects [Channel.id], not [Channel.epgChannelId]. This predates Task 20 and only
 * matters once the real Xtream source path is ever exercised (never yet, in this project's
 * history) — flagged here for visibility, out of scope to fix in this task.
 *
 * The returned list is sorted defensively ascending by [EpgProgram.startMillis] (the fake source
 * already returns 4 chronologically-ordered slots, but input order is not assumed). The "now
 * playing" entry is the one where `startMillis <= now < endMillis` (see [EpgProgram] KDoc);
 * everything strictly after it (or after "now" when nothing is currently airing) is exposed as
 * [LiveDetailUiState.upcomingPrograms].
 *
 * ## Missing credentials
 * [streamUrl] is resolved once during [loadChannel] and cached — `null` when
 * [CredentialsProvider.getCredentials] returns `null` (should not normally happen once onboarding
 * is complete, but defensively handled exactly like
 * [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModel]). [LiveDetailScreen] disables the
 * play button in that case instead of navigating with an unusable URL. Unlike the per-episode
 * [com.bobot.iptvapp.ui.screen.seriesdetail.SeriesDetailViewModel.buildEpisodeStreamUrl] function
 * shape, a single cached [LiveDetailUiState.streamUrl] value is exposed directly — the channel id
 * does not change per-item the way episodes do within one screen instance, closer to
 * [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModel]'s shape.
 *
 * ## No active profile
 * When [AppPreferencesStore.getActiveProfileId] returns `null`, favorite state stays at its
 * default (`false`) and [onToggleFavorite] becomes a no-op, mirroring
 * [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModel].
 *
 * ## Out of scope (Task 20)
 * Whether [ContentType.LIVE] should ever have playback progress saved/resumed is an explicit open
 * decision reserved for Task 23 ("Continue Watching") — this ViewModel never depends on
 * [com.bobot.iptvapp.domain.repository.PlaybackProgressRepository] and the play button always
 * means "play from now", mirroring how live playback already behaves independently of this
 * screen's changes (see [com.bobot.iptvapp.ui.screen.player.PlayerViewModel.initialize], untouched
 * here).
 *
 * @param catalogRepository    Resolves the channel (via [CatalogRepository.getLiveChannels]) and
 *                             fetches its EPG (via [CatalogRepository.getEpg]).
 * @param favoritesRepository  Favorite toggle + reactive observation for the heart button, scoped
 *                             to the whole channel (`ContentType.LIVE`).
 * @param appPreferencesStore  Resolves the active profile ID that scopes favorites.
 * @param credentialsProvider  Resolves the Xtream credentials used to build [LiveDetailUiState.streamUrl].
 */
@HiltViewModel
class LiveDetailViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val favoritesRepository: FavoritesRepository,
    private val appPreferencesStore: AppPreferencesStore,
    private val credentialsProvider: CredentialsProvider,
) : ViewModel() {

    private companion object {
        /** See [LiveDetailUiState] KDoc "epgMessage". */
        const val NO_EPG_MESSAGE = "Aucun programme disponible"

        /** Shown when no channel matches the navigation `contentId` — see [loadChannel]. */
        const val CHANNEL_NOT_FOUND_MESSAGE = "Chaîne introuvable."
    }

    private val _uiState = MutableStateFlow(LiveDetailUiState())
    val uiState: StateFlow<LiveDetailUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var channelId: String? = null
    private var activeProfileId: String? = null
    private var favoriteObservationJob: Job? = null

    /**
     * Loads the channel identified by [channelId]. Idempotent — only the first call per ViewModel
     * instance has an effect (same guard pattern as
     * [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModel.initialize]), so
     * [LiveDetailScreen] can safely call this from a `LaunchedEffect` keyed on `channelId` without
     * re-triggering the fetch on every recomposition.
     */
    fun initialize(channelId: String) {
        if (initialized) return
        initialized = true
        this.channelId = channelId
        loadChannel(channelId)
    }

    /** Re-runs the resolution for the current [channelId] — wired to the error state's retry action. */
    fun onRetry() {
        val id = channelId ?: return
        loadChannel(id)
    }

    /**
     * Toggles the favorite state of the current channel for the active profile. A no-op when no
     * channel has loaded yet or no profile is active — the UI state update itself is driven
     * reactively by [favoritesRepository]'s `isFavorite` Flow via [observeFavorite], not by this
     * function's completion.
     */
    fun onToggleFavorite() {
        val profileId = activeProfileId ?: return
        val id = channelId ?: return
        viewModelScope.launch {
            favoritesRepository.toggleFavorite(profileId, id, ContentType.LIVE)
        }
    }

    // ─── Internal ────────────────────────────────────────────────────────────────

    private fun loadChannel(channelId: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    isEpgLoading = true,
                    currentProgram = null,
                    upcomingPrograms = emptyList(),
                    epgMessage = null,
                )
            }

            val profileId = appPreferencesStore.getActiveProfileId()
            activeProfileId = profileId

            val streamUrl = buildStreamUrl(channelId)

            // The cache is asked first, and it answers with a single row. The fallback below
            // resolves this same channel out of `getLiveChannels(categoryId = null)` — the
            // *unfiltered* bouquet, the heaviest call the API offers, downloaded in full to read
            // one entry from it. Since the catalog screens load per category (the OOM fix), that
            // unfiltered list is never memoized either, so before this lookup existed the cost was
            // paid again on every single channel opened.
            val cached = catalogRepository.getCachedChannel(channelId)
            if (cached != null) {
                onChannelResolved(cached, streamUrl, profileId)
                return@launch
            }

            catalogRepository.getLiveChannels(categoryId = null).collect { resource ->
                when (resource) {
                    is Resource.Success -> {
                        val channel = resource.data.firstOrNull { it.id == channelId }
                        if (channel != null) {
                            onChannelResolved(channel, streamUrl, profileId)
                        } else {
                            _uiState.update {
                                it.copy(isLoading = false, errorMessage = CHANNEL_NOT_FOUND_MESSAGE)
                            }
                        }
                    }

                    is Resource.Error -> {
                        _uiState.update { it.copy(isLoading = false, errorMessage = resource.message) }
                    }

                    // Resource docs: emitted first, before the terminal Success/Error — already
                    // reflected by LiveDetailUiState's default isLoading = true.
                    Resource.Loading -> Unit
                }
            }
        }
    }

    /**
     * Publishes a resolved channel and starts the two loads that depend on it, identically for
     * the cached and the network path — a channel read from Room is not a lesser one, and must
     * still get its favourite state observed and its EPG fetched.
     */
    private suspend fun onChannelResolved(channel: Channel, streamUrl: String?, profileId: String?) {
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = null,
                channel = channel,
                streamUrl = streamUrl,
            )
        }

        if (profileId != null) {
            observeFavorite(profileId, channel.id)
        }

        loadEpg(channel)
    }

    /** See class KDoc "EPG fetch (current / upcoming split)". */
    private suspend fun loadEpg(channel: Channel) {
        val epgChannelId = channel.epgChannelId
        if (epgChannelId == null) {
            _uiState.update {
                it.copy(isEpgLoading = false, currentProgram = null, upcomingPrograms = emptyList(), epgMessage = NO_EPG_MESSAGE)
            }
            return
        }

        when (val result = catalogRepository.getEpg(epgChannelId)) {
            is Resource.Success -> {
                val programs = result.data.sortedBy { it.startMillis }
                if (programs.isEmpty()) {
                    _uiState.update {
                        it.copy(isEpgLoading = false, currentProgram = null, upcomingPrograms = emptyList(), epgMessage = NO_EPG_MESSAGE)
                    }
                    return
                }

                val now = System.currentTimeMillis()
                val current = programs.firstOrNull { it.startMillis <= now && now < it.endMillis }
                val upcoming = if (current != null) {
                    programs.filter { it.startMillis >= current.endMillis }
                } else {
                    programs.filter { it.startMillis > now }
                }

                _uiState.update {
                    it.copy(isEpgLoading = false, currentProgram = current, upcomingPrograms = upcoming, epgMessage = null)
                }
            }

            is Resource.Error -> {
                _uiState.update {
                    it.copy(isEpgLoading = false, currentProgram = null, upcomingPrograms = emptyList(), epgMessage = NO_EPG_MESSAGE)
                }
            }

            // Resource docs: "Suspend methods do not emit Loading" — kept only for `when`
            // exhaustiveness over the sealed Resource type.
            Resource.Loading -> Unit
        }
    }

    /**
     * Collects [FavoritesRepository.isFavorite] for the lifetime of the ViewModel so
     * [LiveDetailUiState.isFavorite] stays in sync with [onToggleFavorite] and with changes made
     * elsewhere. Cancels any previous collection first so [onRetry] never accumulates duplicate
     * collectors against the same Flow — identical pattern to
     * [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModel.observeFavorite].
     */
    private fun observeFavorite(profileId: String, channelId: String) {
        favoriteObservationJob?.cancel()
        favoriteObservationJob = viewModelScope.launch {
            favoritesRepository.isFavorite(profileId, channelId, ContentType.LIVE)
                .collect { isFavorite -> _uiState.update { it.copy(isFavorite = isFavorite) } }
        }
    }

    /** See class KDoc "Missing credentials". */
    private suspend fun buildStreamUrl(channelId: String): String? {
        val credentials = credentialsProvider.getCredentials() ?: return null

        return XtreamUrlBuilder.buildLiveUrl(
            baseUrl = credentials.baseUrl,
            username = credentials.username,
            password = credentials.password,
            streamId = channelId,
        )
    }
}

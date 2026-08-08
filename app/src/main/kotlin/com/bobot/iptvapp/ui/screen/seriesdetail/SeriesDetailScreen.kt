package com.bobot.iptvapp.ui.screen.seriesdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.bobot.iptvapp.domain.model.Episode
import com.bobot.iptvapp.domain.model.DownloadState
import com.bobot.iptvapp.domain.model.OfflineDownload
import com.bobot.iptvapp.domain.model.Season
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.ui.components.CategoryChip
import com.bobot.iptvapp.ui.components.FocusableTextButton
import com.bobot.iptvapp.ui.components.GhostButton
import com.bobot.iptvapp.ui.components.GlassIconButton
import com.bobot.iptvapp.ui.components.SectionTitle
import com.bobot.iptvapp.ui.components.glassSurface
import com.bobot.iptvapp.ui.theme.AccentSolid
import com.bobot.iptvapp.ui.theme.BackgroundBase
import com.bobot.iptvapp.ui.theme.BackgroundElevated
import com.bobot.iptvapp.ui.theme.CardDimens
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import com.bobot.iptvapp.ui.theme.LayoutDimens
import com.bobot.iptvapp.ui.theme.RadiusMd
import com.bobot.iptvapp.ui.theme.SemanticError
import com.bobot.iptvapp.ui.theme.Spacing
import com.bobot.iptvapp.ui.theme.TextDimmed
import com.bobot.iptvapp.ui.theme.TextPrimary
import com.bobot.iptvapp.ui.theme.TextSecondary
import com.bobot.iptvapp.ui.util.rememberIsTvDevice
import com.bobot.iptvapp.ui.util.rememberNotificationPermissionRequester
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Series detail screen reskinned to "Cinematic Glass" V2.
 * Presentation only — no ViewModel or navigation changes.
 *
 * Hero (compact) + season selector using CategoryChip + glass episode rows.
 *
 * BUG FIX: episode-row background was `if (isFocused) BackgroundElevated else BackgroundElevated`
 * (both branches identical — D-pad focus was not visually distinct).
 * Fixed: glassSurface(strong = isFocused) provides clear focused vs rest distinction.
 *
 * TV BUG FIX: this screen previously used a `LazyColumn`. On a 16:9 TV screen the hero banner
 * (`Modifier.fillMaxWidth().aspectRatio(16f/9f)`) fills nearly the whole initial viewport, so the
 * lazy list's windowed composition never composed the season selector / episode rows below it —
 * they were outside the initially-measured viewport, not merely off-screen. Fixed by switching to
 * `Column(Modifier.fillMaxSize()).verticalScroll(rememberScrollState())`, matching the pattern
 * already used in MovieDetailScreen.kt and LiveDetailScreen.kt, which composes all children
 * eagerly regardless of hero height.
 */
@Composable
fun SeriesDetailScreen(
    seriesId: String,
    onNavigateToPlayer: (streamUrl: String, streamId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeriesDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(seriesId) {
        viewModel.initialize(seriesId)
    }

    SeriesDetailContent(
        uiState = uiState,
        onEpisodeClick = { episode ->
            viewModel.buildEpisodeStreamUrl(episode)?.let { url -> onNavigateToPlayer(url, episode.id) }
        },
        onDownloadEpisode = viewModel::onDownloadEpisode,
        onPauseEpisodeDownload = viewModel::onPauseEpisodeDownload,
        onResumeEpisodeDownload = viewModel::onResumeEpisodeDownload,
        onSeasonSelected = viewModel::onSelectSeason,
        onFavoriteClick = viewModel::onToggleFavorite,
        onRetry = viewModel::onRetry,
        modifier = modifier,
    )
}

@Composable
private fun SeriesDetailContent(
    uiState: SeriesDetailUiState,
    onEpisodeClick: (Episode) -> Unit,
    onDownloadEpisode: (Episode) -> Unit = {},
    onPauseEpisodeDownload: (Episode) -> Unit = {},
    onResumeEpisodeDownload: (Episode) -> Unit = {},
    onSeasonSelected: (Int) -> Unit,
    onFavoriteClick: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundBase),
    ) {
        val series = uiState.series
        when {
            uiState.isLoading -> SeriesDetailLoadingState()
            uiState.errorMessage != null && series == null -> SeriesDetailErrorState(
                message = uiState.errorMessage,
                onRetry = onRetry,
            )
            series != null -> SeriesDetailBody(
                series = series,
                uiState = uiState,
                onEpisodeClick = onEpisodeClick,
                onDownloadEpisode = onDownloadEpisode,
                onPauseEpisodeDownload = onPauseEpisodeDownload,
                onResumeEpisodeDownload = onResumeEpisodeDownload,
                onSeasonSelected = onSeasonSelected,
                onFavoriteClick = onFavoriteClick,
            )
            else -> SeriesDetailErrorState(message = null, onRetry = onRetry)
        }
    }
}

// ─── Loading / error states ──────────────────────────────────────────────────

@Composable
private fun SeriesDetailLoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AccentSolid)
    }
}

@Composable
private fun SeriesDetailErrorState(
    message: String?,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Impossible de charger cette série.",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            if (!message.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticError,
                )
            }
            Spacer(modifier = Modifier.height(Spacing.lg))
            FocusableTextButton(label = "Réessayer", onClick = onRetry)
        }
    }
}

// ─── Content body ────────────────────────────────────────────────────────────

@Composable
private fun SeriesDetailBody(
    series: Series,
    uiState: SeriesDetailUiState,
    onEpisodeClick: (Episode) -> Unit,
    onDownloadEpisode: (Episode) -> Unit,
    onPauseEpisodeDownload: (Episode) -> Unit,
    onResumeEpisodeDownload: (Episode) -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onFavoriteClick: () -> Unit,
) {
    val isTv = rememberIsTvDevice()
    val horizontalPadding = if (isTv) LayoutDimens.ContentPaddingTv else LayoutDimens.ContentPaddingPhone
    val favoriteFocusRequester = remember { FocusRequester() }
    val requestNotificationPermission = rememberNotificationPermissionRequester()

    // TV BUG FIX: the previous implementation wrapped requestFocus() in a bare, silently-swallowing
    // runCatching { ... } with a single attempt. Now that the layout below is an eager Column
    // (rather than a LazyColumn), the target is guaranteed to be part of the composition, but its
    // LayoutCoordinates may not be attached yet on the very first composition pass. Retry with a
    // short delay, bounded, until the request succeeds or attempts are exhausted — reliable without
    // risking an infinite loop.
    LaunchedEffect(series.id) {
        var attempt = 0
        while (isActive && attempt < INITIAL_FOCUS_MAX_ATTEMPTS) {
            val focusRequested = runCatching { favoriteFocusRequester.requestFocus() }.isSuccess
            if (focusRequested) break
            attempt++
            delay(INITIAL_FOCUS_RETRY_DELAY_MILLIS)
        }
    }

    val episodes = uiState.selectedSeasonEpisodes

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SeriesDetailHero(series = series, horizontalPadding = horizontalPadding)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = Spacing.md),
        ) {
            SeriesDetailMetadataChips(series = series)

            val plot = series.plot
            if (!plot.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(Spacing.md))
                Text(
                    text = plot,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                GlassIconButton(
                    icon = if (uiState.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (uiState.isFavorite) "Retirer de ma liste" else "Ajouter à ma liste",
                    onClick = onFavoriteClick,
                    modifier = Modifier.focusRequester(favoriteFocusRequester),
                )
                Text(
                    text = if (uiState.isFavorite) "Ma liste" else "Ajouter à ma liste",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
            }

            if (!uiState.hasCredentials) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = "Lecture indisponible : identifiants du serveur manquants.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticError,
                )
            }
        }

        if (series.seasons.isEmpty()) {
            Text(
                text = "Aucune saison disponible pour cette série.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = horizontalPadding, vertical = Spacing.md),
            )
        } else {
            SeriesSeasonSelector(
                seasons = series.seasons,
                selectedSeasonNumber = uiState.selectedSeasonNumber,
                onSeasonSelected = onSeasonSelected,
                horizontalPadding = horizontalPadding,
            )

            SectionTitle(
                title = "Épisodes",
                modifier = Modifier.padding(horizontal = horizontalPadding, vertical = Spacing.xs),
            )

            if (episodes.isEmpty()) {
                Text(
                    text = "Aucun épisode disponible pour cette saison.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = horizontalPadding, vertical = Spacing.md),
                )
            } else {
                episodes.forEach { episode ->
                    SeriesDetailEpisodeRow(
                        episode = episode,
                        download = uiState.episodeDownloads[episode.id],
                        enabled = uiState.hasCredentials,
                        onClick = { onEpisodeClick(episode) },
                        onDownloadClick = {
                            requestNotificationPermission()
                            onDownloadEpisode(episode)
                        },
                        onPauseDownloadClick = { onPauseEpisodeDownload(episode) },
                        onResumeDownloadClick = { onResumeEpisodeDownload(episode) },
                        modifier = Modifier.padding(horizontal = horizontalPadding, vertical = Spacing.xs),
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.xl))
        }
    }
}

/** Bounded retry budget for the initial D-pad focus request (see [SeriesDetailBody]). */
private const val INITIAL_FOCUS_MAX_ATTEMPTS = 5

/** Delay between initial focus retry attempts, in milliseconds. */
private const val INITIAL_FOCUS_RETRY_DELAY_MILLIS = 32L

/**
 * Hero: backdrop with gradient scrim + eyebrow "Série" + large title.
 */
@Composable
private fun SeriesDetailHero(series: Series, horizontalPadding: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(CardDimens.BannerAspectRatio),
    ) {
        val placeholder = remember { ColorPainter(BackgroundElevated) }

        AsyncImage(
            model = series.coverUrl,
            contentDescription = series.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            placeholder = placeholder,
            error = placeholder,
            fallback = placeholder,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.30f to Color.Transparent,
                            1.00f to BackgroundBase,
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = Spacing.md),
        ) {
            Text(
                text = "Série",
                style = MaterialTheme.typography.labelMedium,
                color = TextDimmed,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = series.title,
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
            )
        }
    }
}

/**
 * Metadata chips: year / rating / season count.
 */
@Composable
private fun SeriesDetailMetadataChips(series: Series) {
    val chips = buildList {
        series.year?.let { add(it.toString()) }
        series.rating?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (series.seasons.isNotEmpty()) {
            val count = series.seasons.size
            add(if (count > 1) "$count saisons" else "1 saison")
        }
    }

    if (chips.isNotEmpty()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(chips) { label ->
                CategoryChip(
                    label = label,
                    selected = false,
                    onClick = {},
                    enabled = false,
                )
            }
        }
    }
}

// ─── Season selector ─────────────────────────────────────────────────────────

/** Horizontal [CategoryChip] row — selected chip uses AccentGradient fill. */
@Composable
private fun SeriesSeasonSelector(
    seasons: List<Season>,
    selectedSeasonNumber: Int?,
    onSeasonSelected: (Int) -> Unit,
    horizontalPadding: Dp,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = Spacing.xs),
    ) {
        items(seasons, key = { it.seasonNumber }) { season ->
            CategoryChip(
                label = season.name?.takeIf { it.isNotBlank() } ?: "Saison ${season.seasonNumber}",
                selected = season.seasonNumber == selectedSeasonNumber,
                onClick = { onSeasonSelected(season.seasonNumber) },
            )
        }
    }
}

// ─── Episode row ─────────────────────────────────────────────────────────────

/**
 * Glass episode row: landscape thumbnail + title + "S x E y - duration" + synopsis (2 lines max).
 *
 * BUG FIX applied here: the prior implementation had
 *   `.background(if (isFocused) BackgroundElevated else BackgroundElevated)`
 * where both branches were identical, making focus invisible.
 * Now uses `.glassSurface(strong = isFocused)` so rest state is subtle glass
 * and focused state is the stronger glass variant — visually distinct on D-pad.
 *
 * ## Why the row is a focus *group* rather than a focus target
 * The row is [clickable] so a touch anywhere on it starts playback. But `clickable` also makes
 * the row itself focusable, and a focusable node is a leaf as far as directional focus search is
 * concerned: once the row held the focus, neither `DPAD_RIGHT` nor `DPAD_DOWN` could reach the
 * "Lire" and "Télécharger" buttons nested inside it. On an Android TV emulator on 2026-08-08
 * those two actions were simply unreachable with a remote, even though the UI tree reported them
 * as focusable and clickable.
 *
 * So the row keeps its click handler for touch, but gives up its own focusability
 * (`focusProperties { canFocus = false }`, which applies to the `clickable` that follows it) and
 * declares itself a [focusGroup] instead. D-pad navigation now enters the row and lands directly
 * on its buttons. The highlight tracks [FocusState.hasFocus] rather than `isFocused` so the whole
 * row still lights up while any of its buttons is focused — the behaviour a TV user expects.
 */
@Composable
private fun SeriesDetailEpisodeRow(
    episode: Episode,
    download: OfflineDownload?,
    enabled: Boolean,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onPauseDownloadClick: () -> Unit,
    onResumeDownloadClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val rowShape = RoundedCornerShape(RadiusMd)
    val textColor = if (enabled) TextPrimary else TextSecondary

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            // BUG FIX: was `.background(if (isFocused) BackgroundElevated else BackgroundElevated)`
            // Now: rest = GlassFill + GlassBorder; focused = GlassFillStrong + GlassBorderStrong
            .glassSurface(shape = rowShape, strong = isFocused)
            .onFocusChanged { focusState -> isFocused = focusState.hasFocus }
            .focusGroup()
            .focusProperties { canFocus = false }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(Spacing.md),
    ) {
        val placeholder = remember { ColorPainter(BackgroundElevated) }

        // Landscape thumbnail
        AsyncImage(
            model = episode.coverUrl,
            contentDescription = episode.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(120.dp)
                .aspectRatio(CardDimens.BannerAspectRatio)
                .clip(RoundedCornerShape(RadiusMd)),
            placeholder = placeholder,
            error = placeholder,
            fallback = placeholder,
        )

        Spacer(modifier = Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.titleSmall,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // "S x E y - duration"
            val meta = buildList {
                add("S${episode.seasonNumber} E${episode.episodeNumber}")
                episode.durationMillis?.let { add(formatDurationLabel(it)) }
            }.joinToString(" - ")

            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )

            val plot = episode.plot
            if (!plot.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = plot,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.width(Spacing.sm))

        GlassIconButton(
            icon = Icons.Default.PlayArrow,
            contentDescription = "Lire",
            onClick = onClick,
            enabled = enabled,
        )

        Spacer(modifier = Modifier.width(Spacing.xs))

        when (download?.state) {
            DownloadState.QUEUED,
            DownloadState.DOWNLOADING -> GhostButton(
                label = "Mettre en pause",
                onClick = onPauseDownloadClick,
            )

            DownloadState.PAUSED -> GhostButton(
                label = "Reprendre",
                onClick = onResumeDownloadClick,
            )

            DownloadState.COMPLETED -> GhostButton(
                label = "Téléchargé",
                onClick = {},
                enabled = false,
            )

            DownloadState.NOT_DOWNLOADED,
            DownloadState.FAILED,
            null -> GlassIconButton(
                icon = Icons.Default.Add,
                contentDescription = "Télécharger",
                onClick = onDownloadClick,
                enabled = enabled,
            )
        }
    }
}

/** Formats duration millis as "1h 45min" or "45min". */
private fun formatDurationLabel(durationMillis: Long): String {
    val totalMinutes = (durationMillis / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}min" else "${minutes}min"
}

// ─── Previews ────────────────────────────────────────────────────────────────

private val previewEpisodesSeason1 = listOf(
    Episode(
        id = "e1",
        title = "Pilote",
        episodeNumber = 1,
        seasonNumber = 1,
        plot = "Un chimiste devient fabricant de drogue pour subvenir aux besoins de sa famille.",
        durationMillis = 2_820_000L,
        containerExtension = "mkv",
        coverUrl = null,
    ),
    Episode(
        id = "e2",
        title = "Le Chat est dans le sac",
        episodeNumber = 2,
        seasonNumber = 1,
        plot = null,
        durationMillis = 2_760_000L,
        containerExtension = null,
        coverUrl = null,
    ),
)

private val previewSeries = Series(
    id = "s1",
    title = "Breaking Bad",
    coverUrl = null,
    plot = "Un professeur de chimie atteint d'un cancer se lance dans la fabrication de méthamphétamine.",
    categoryId = "3",
    rating = "9.5",
    year = 2008,
    seasons = listOf(
        Season(seasonNumber = 1, name = "Saison 1", coverUrl = null, episodes = previewEpisodesSeason1),
        Season(seasonNumber = 2, name = "Saison 2", coverUrl = null, episodes = emptyList()),
    ),
)

@Preview(name = "SeriesDetail — content", showBackground = true, backgroundColor = 0xFF0A0A0F, heightDp = 1200)
@Composable
private fun SeriesDetailContentPreview() {
    IptvAppTheme {
        SeriesDetailContent(
            uiState = SeriesDetailUiState(
                isLoading = false,
                series = previewSeries,
                isFavorite = false,
                selectedSeasonNumber = 1,
                hasCredentials = true,
            ),
            onEpisodeClick = {},
            onSeasonSelected = {},
            onFavoriteClick = {},
            onRetry = {},
        )
    }
}

@Preview(name = "SeriesDetail — empty season, favorite", showBackground = true, backgroundColor = 0xFF0A0A0F, heightDp = 900)
@Composable
private fun SeriesDetailEmptySeasonPreview() {
    IptvAppTheme {
        SeriesDetailContent(
            uiState = SeriesDetailUiState(
                isLoading = false,
                series = previewSeries,
                isFavorite = true,
                selectedSeasonNumber = 2,
                hasCredentials = true,
            ),
            onEpisodeClick = {},
            onSeasonSelected = {},
            onFavoriteClick = {},
            onRetry = {},
        )
    }
}

@Preview(name = "SeriesDetail — missing credentials", showBackground = true, backgroundColor = 0xFF0A0A0F, heightDp = 1200)
@Composable
private fun SeriesDetailMissingCredentialsPreview() {
    IptvAppTheme {
        SeriesDetailContent(
            uiState = SeriesDetailUiState(
                isLoading = false,
                series = previewSeries,
                isFavorite = false,
                selectedSeasonNumber = 1,
                hasCredentials = false,
            ),
            onEpisodeClick = {},
            onSeasonSelected = {},
            onFavoriteClick = {},
            onRetry = {},
        )
    }
}

@Preview(name = "SeriesDetail — loading", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun SeriesDetailLoadingPreview() {
    IptvAppTheme {
        SeriesDetailContent(
            uiState = SeriesDetailUiState(isLoading = true),
            onEpisodeClick = {},
            onSeasonSelected = {},
            onFavoriteClick = {},
            onRetry = {},
        )
    }
}

@Preview(name = "SeriesDetail — error", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun SeriesDetailErrorPreview() {
    IptvAppTheme {
        SeriesDetailContent(
            uiState = SeriesDetailUiState(isLoading = false, errorMessage = "Série introuvable."),
            onEpisodeClick = {},
            onSeasonSelected = {},
            onFavoriteClick = {},
            onRetry = {},
        )
    }
}

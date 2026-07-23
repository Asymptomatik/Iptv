package com.bobot.iptvapp.ui.screen.moviedetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.bobot.iptvapp.domain.model.DownloadState
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.ui.components.CategoryChip
import com.bobot.iptvapp.ui.components.FocusableTextButton
import com.bobot.iptvapp.ui.components.GhostButton
import com.bobot.iptvapp.ui.components.GlassIconButton
import com.bobot.iptvapp.ui.components.PrimaryButton
import com.bobot.iptvapp.ui.theme.AccentSolid
import com.bobot.iptvapp.ui.theme.BackgroundBase
import com.bobot.iptvapp.ui.theme.BackgroundElevated
import com.bobot.iptvapp.ui.theme.CardDimens
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import com.bobot.iptvapp.ui.theme.LayoutDimens
import com.bobot.iptvapp.ui.theme.SemanticError
import com.bobot.iptvapp.ui.theme.Spacing
import com.bobot.iptvapp.ui.theme.TextDimmed
import com.bobot.iptvapp.ui.theme.TextPrimary
import com.bobot.iptvapp.ui.theme.TextSecondary
import com.bobot.iptvapp.ui.util.rememberIsTvDevice
import com.bobot.iptvapp.ui.util.rememberNotificationPermissionRequester

/**
 * Movie detail screen reskinned to "Cinematic Glass" V2.
 * Presentation only — no ViewModel or navigation changes.
 *
 * Hero backdrop with scrim + eyebrow "Film" + large title.
 * Metadata chips (year / duration / rating — omitted when null).
 * Synopsis in TextSecondary.
 * Actions: PrimaryButton (Lire / Reprendre when canResume), GlassIconButton (favorite).
 * FocusRequester on play button preserved.
 */
@Composable
fun MovieDetailScreen(
    movieId: String,
    onNavigateToPlayer: (streamUrl: String, streamId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MovieDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(movieId) {
        viewModel.initialize(movieId)
    }

    MovieDetailContent(
        uiState = uiState,
        onPlayClick = { uiState.streamUrl?.let { url -> onNavigateToPlayer(url, movieId) } },
        onFavoriteClick = viewModel::onToggleFavorite,
        onDownloadClick = viewModel::onDownloadClick,
        onPauseDownload = viewModel::onPauseDownload,
        onResumeDownload = viewModel::onResumeDownload,
        onRetry = viewModel::onRetry,
        modifier = modifier,
    )
}

@Composable
private fun MovieDetailContent(
    uiState: MovieDetailUiState,
    onPlayClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onDownloadClick: () -> Unit = {},
    onPauseDownload: () -> Unit = {},
    onResumeDownload: () -> Unit = {},
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundBase),
    ) {
        val movie = uiState.movie
        when {
            uiState.isLoading -> MovieDetailLoadingState()
            uiState.errorMessage != null && movie == null -> MovieDetailErrorState(
                message = uiState.errorMessage,
                onRetry = onRetry,
            )
            movie != null -> MovieDetailBody(
                movie = movie,
                uiState = uiState,
                onPlayClick = onPlayClick,
                onFavoriteClick = onFavoriteClick,
                onDownloadClick = onDownloadClick,
                onPauseDownload = onPauseDownload,
                onResumeDownload = onResumeDownload,
            )
            else -> MovieDetailErrorState(message = null, onRetry = onRetry)
        }
    }
}

// ─── Loading / error states ──────────────────────────────────────────────────

@Composable
private fun MovieDetailLoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AccentSolid)
    }
}

@Composable
private fun MovieDetailErrorState(
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
                text = "Impossible de charger ce film.",
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
private fun MovieDetailBody(
    movie: Movie,
    uiState: MovieDetailUiState,
    onPlayClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
) {
    val isTv = rememberIsTvDevice()
    val horizontalPadding = if (isTv) LayoutDimens.ContentPaddingTv else LayoutDimens.ContentPaddingPhone
    val playFocusRequester = remember { FocusRequester() }
    val requestNotificationPermission = rememberNotificationPermissionRequester()

    LaunchedEffect(movie.id) {
        runCatching { playFocusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Hero backdrop with scrim gradient
        MovieDetailHero(movie = movie, horizontalPadding = horizontalPadding)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = Spacing.md),
        ) {
            // Metadata chips — year / duration / rating; omit missing fields
            MovieDetailMetadataChips(movie = movie)

            val plot = movie.plot
            if (!plot.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(Spacing.md))
                Text(
                    text = plot,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            // Actions row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                // Primary action: Lire or Reprendre
                val playLabel = if (uiState.canResume) "Reprendre" else "Lire"
                PrimaryButton(
                    label = playLabel,
                    onClick = onPlayClick,
                    enabled = uiState.streamUrl != null,
                    modifier = Modifier.focusRequester(playFocusRequester),
                )

                // Ghost "Recommencer" button shown only when resume mode is active
                if (uiState.canResume) {
                    GhostButton(
                        label = "Recommencer",
                        onClick = onPlayClick,
                        enabled = uiState.streamUrl != null,
                    )
                }

                when (uiState.download?.state) {
                    DownloadState.QUEUED,
                    DownloadState.DOWNLOADING -> GhostButton(
                        label = "Mettre en pause",
                        onClick = onPauseDownload,
                    )

                    DownloadState.PAUSED -> GhostButton(
                        label = "Reprendre",
                        onClick = onResumeDownload,
                    )

                    DownloadState.COMPLETED -> GhostButton(
                        label = "Téléchargé",
                        onClick = {},
                        enabled = false,
                    )

                    DownloadState.NOT_DOWNLOADED,
                    DownloadState.FAILED,
                    null -> GhostButton(
                        label = "Télécharger",
                        onClick = {
                            requestNotificationPermission()
                            onDownloadClick()
                        },
                        enabled = uiState.streamUrl != null,
                    )
                }

                // Favorite icon button — VM has onToggleFavorite, wire directly
                GlassIconButton(
                    icon = if (uiState.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (uiState.isFavorite) "Retirer de ma liste" else "Ajouter à ma liste",
                    onClick = onFavoriteClick,
                )
            }

            if (uiState.streamUrl == null) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = "Lecture indisponible : identifiants du serveur manquants.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticError,
                )
            }
        }
    }
}

/**
 * Hero backdrop: full-width Coil image with a dual-gradient scrim
 * (transparent → BackgroundBase), eyebrow "Film" label, and large title.
 */
@Composable
private fun MovieDetailHero(movie: Movie, horizontalPadding: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(CardDimens.BannerAspectRatio),
    ) {
        val placeholder = remember { ColorPainter(BackgroundElevated) }

        AsyncImage(
            model = movie.posterUrl,
            contentDescription = movie.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            placeholder = placeholder,
            error = placeholder,
            fallback = placeholder,
        )

        // Scrim: transparent → BackgroundBase over bottom 60%
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

        // Title block at bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = Spacing.md),
        ) {
            Text(
                text = "Film",
                style = MaterialTheme.typography.labelMedium,
                color = TextDimmed,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = movie.title,
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
            )
        }
    }
}

/**
 * Horizontal row of [CategoryChip]s for year / duration / rating.
 * Each field is omitted when null/blank — no invented data.
 */
@Composable
private fun MovieDetailMetadataChips(movie: Movie) {
    val chips = buildList {
        movie.year?.let { add(it.toString()) }
        movie.durationMillis?.let { add(formatDurationLabel(it)) }
        movie.rating?.takeIf { it.isNotBlank() }?.let { add(it) }
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

/** Formats duration millis as "1h 45min" or "45min". */
private fun formatDurationLabel(durationMillis: Long): String {
    val totalMinutes = (durationMillis / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}min" else "${minutes}min"
}

// ─── Previews ────────────────────────────────────────────────────────────────

private val previewMovie = Movie(
    id = "m1",
    title = "Explosion Totale",
    posterUrl = null,
    plot = "Un ancien agent secret doit déjouer un complot international avant qu'il ne soit trop tard.",
    categoryId = "10",
    rating = "7.8",
    year = 2023,
    addedMillis = null,
    durationMillis = 6_300_000L,
    containerExtension = "mkv",
)

@Preview(name = "MovieDetail — content", showBackground = true, backgroundColor = 0xFF0A0A0F, heightDp = 900)
@Composable
private fun MovieDetailContentPreview() {
    IptvAppTheme {
        MovieDetailContent(
            uiState = MovieDetailUiState(
                isLoading = false,
                movie = previewMovie,
                isFavorite = false,
                canResume = false,
                streamUrl = "http://example.com/movie/user/pass/m1.mkv",
            ),
            onPlayClick = {},
            onFavoriteClick = {},
            onRetry = {},
        )
    }
}

@Preview(name = "MovieDetail — resume + favorite", showBackground = true, backgroundColor = 0xFF0A0A0F, heightDp = 900)
@Composable
private fun MovieDetailResumePreview() {
    IptvAppTheme {
        MovieDetailContent(
            uiState = MovieDetailUiState(
                isLoading = false,
                movie = previewMovie,
                isFavorite = true,
                canResume = true,
                streamUrl = "http://example.com/movie/user/pass/m1.mkv",
            ),
            onPlayClick = {},
            onFavoriteClick = {},
            onRetry = {},
        )
    }
}

@Preview(name = "MovieDetail — loading", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun MovieDetailLoadingPreview() {
    IptvAppTheme {
        MovieDetailContent(
            uiState = MovieDetailUiState(isLoading = true),
            onPlayClick = {},
            onFavoriteClick = {},
            onRetry = {},
        )
    }
}

@Preview(name = "MovieDetail — error", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun MovieDetailErrorPreview() {
    IptvAppTheme {
        MovieDetailContent(
            uiState = MovieDetailUiState(isLoading = false, errorMessage = "Film introuvable."),
            onPlayClick = {},
            onFavoriteClick = {},
            onRetry = {},
        )
    }
}

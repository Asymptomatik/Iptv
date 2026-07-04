package com.bobot.iptvapp.ui.screen.livedetail

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
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
import com.bobot.iptvapp.domain.model.Channel
import com.bobot.iptvapp.domain.model.EpgProgram
import com.bobot.iptvapp.ui.components.FocusableTextButton
import com.bobot.iptvapp.ui.components.GlassIconButton
import com.bobot.iptvapp.ui.components.PrimaryButton
import com.bobot.iptvapp.ui.components.SectionTitle
import com.bobot.iptvapp.ui.components.glassSurface
import com.bobot.iptvapp.ui.theme.AccentGradient
import com.bobot.iptvapp.ui.theme.AccentSolid
import com.bobot.iptvapp.ui.theme.BackgroundBase
import com.bobot.iptvapp.ui.theme.BackgroundElevated
import com.bobot.iptvapp.ui.theme.CardDimens
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import com.bobot.iptvapp.ui.theme.LayoutDimens
import com.bobot.iptvapp.ui.theme.RadiusMd
import com.bobot.iptvapp.ui.theme.SemanticError
import com.bobot.iptvapp.ui.theme.SemanticLive
import com.bobot.iptvapp.ui.theme.Spacing
import com.bobot.iptvapp.ui.theme.TextPrimary
import com.bobot.iptvapp.ui.theme.TextSecondary
import com.bobot.iptvapp.ui.util.rememberIsTvDevice
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Live channel detail screen reskinned to "Cinematic Glass" V2.
 * Presentation only — no ViewModel or navigation changes.
 *
 * Hero + LIVE pill (SemanticLive dot + "EN DIRECT") + channel name.
 * Actions: PrimaryButton ("Regarder") + GlassIconButton (favorite).
 * EPG timeline: current slot = glassSurface(strong=true) + AccentGradient progress bar;
 * upcoming slots = plain glassSurface. EPG-absent/degraded state shows calm empty message.
 */
@Composable
fun LiveDetailScreen(
    channelId: String,
    onNavigateToPlayer: (streamUrl: String, streamId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LiveDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(channelId) {
        viewModel.initialize(channelId)
    }

    LiveDetailContent(
        uiState = uiState,
        onPlayClick = { uiState.streamUrl?.let { url -> onNavigateToPlayer(url, channelId) } },
        onFavoriteClick = viewModel::onToggleFavorite,
        onRetry = viewModel::onRetry,
        modifier = modifier,
    )
}

@Composable
private fun LiveDetailContent(
    uiState: LiveDetailUiState,
    onPlayClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundBase),
    ) {
        val channel = uiState.channel
        when {
            uiState.isLoading -> LiveDetailLoadingState()
            uiState.errorMessage != null && channel == null -> LiveDetailErrorState(
                message = uiState.errorMessage,
                onRetry = onRetry,
            )
            channel != null -> LiveDetailBody(
                channel = channel,
                uiState = uiState,
                onPlayClick = onPlayClick,
                onFavoriteClick = onFavoriteClick,
            )
            else -> LiveDetailErrorState(message = null, onRetry = onRetry)
        }
    }
}

// ─── Loading / error states ──────────────────────────────────────────────────

@Composable
private fun LiveDetailLoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AccentSolid)
    }
}

@Composable
private fun LiveDetailErrorState(
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
                text = "Impossible de charger cette chaîne.",
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
private fun LiveDetailBody(
    channel: Channel,
    uiState: LiveDetailUiState,
    onPlayClick: () -> Unit,
    onFavoriteClick: () -> Unit,
) {
    val isTv = rememberIsTvDevice()
    val horizontalPadding = if (isTv) LayoutDimens.ContentPaddingTv else LayoutDimens.ContentPaddingPhone
    val playFocusRequester = remember { FocusRequester() }

    LaunchedEffect(channel.id) {
        runCatching { playFocusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        LiveDetailHero(channel = channel, horizontalPadding = horizontalPadding)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = Spacing.md),
        ) {
            // Current program title shown below hero when available
            uiState.currentProgram?.let { current ->
                Text(
                    text = current.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                Text(
                    text = "${formatClockTime(current.startMillis)} - ${formatClockTime(current.endMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(Spacing.md))
            }

            // Actions row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                PrimaryButton(
                    label = "Regarder",
                    onClick = onPlayClick,
                    enabled = uiState.streamUrl != null,
                    modifier = Modifier.focusRequester(playFocusRequester),
                )

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

            Spacer(modifier = Modifier.height(Spacing.lg))

            LiveDetailEpgSection(uiState = uiState)
        }
    }
}

/**
 * Hero: logo centred over BackgroundElevated backdrop + gradient scrim at bottom.
 * Overlays: LIVE pill (SemanticLive dot + "EN DIRECT") + channel name.
 */
@Composable
private fun LiveDetailHero(channel: Channel, horizontalPadding: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(CardDimens.BannerAspectRatio)
            .background(BackgroundElevated),
        contentAlignment = Alignment.Center,
    ) {
        val placeholder = remember { ColorPainter(BackgroundElevated) }

        AsyncImage(
            model = channel.logoUrl,
            contentDescription = channel.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .aspectRatio(CardDimens.BannerAspectRatio)
                .padding(Spacing.lg),
            placeholder = placeholder,
            error = placeholder,
            fallback = placeholder,
        )

        // Bottom scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.50f to Color.Transparent,
                            1.00f to BackgroundBase,
                        ),
                    ),
                ),
        )

        // Channel name + LIVE pill at bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = Spacing.md),
        ) {
            // LIVE pill: red dot + "EN DIRECT"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(SemanticLive),
                )
                Text(
                    text = "EN DIRECT",
                    style = MaterialTheme.typography.labelSmall,
                    color = SemanticLive,
                )
            }
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = channel.name,
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
            )
        }
    }
}

// ─── EPG section ─────────────────────────────────────────────────────────────

/**
 * EPG section. Current slot uses glassSurface(strong=true) + AccentGradient progress bar.
 * Upcoming slots use plain glassSurface. Absent/degraded EPG shows calm empty state.
 */
@Composable
private fun LiveDetailEpgSection(uiState: LiveDetailUiState) {
    SectionTitle(title = "Programme")

    when {
        uiState.isEpgLoading -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = AccentSolid,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = "Chargement du programme…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        }

        uiState.epgMessage != null -> {
            // Calm empty / error state — no crash
            Text(
                text = uiState.epgMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        else -> {
            val current = uiState.currentProgram
            if (current != null) {
                LiveDetailCurrentSlot(program = current)
                Spacer(modifier = Modifier.height(Spacing.sm))
            }

            if (uiState.upcomingPrograms.isNotEmpty()) {
                if (current != null) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = "À venir",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    uiState.upcomingPrograms.forEach { program ->
                        LiveDetailUpcomingSlot(program = program)
                    }
                }
            }

            // If no current and no upcoming, degrade gracefully
            if (current == null && uiState.upcomingPrograms.isEmpty()) {
                Text(
                    text = "Aucun programme disponible",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        }
    }
}

/**
 * Current program slot: glassSurface(strong=true) + AccentGradient progress bar at bottom.
 * Progress is computed from [System.currentTimeMillis] vs start/end millis.
 */
@Composable
private fun LiveDetailCurrentSlot(program: EpgProgram) {
    val now = System.currentTimeMillis()
    val totalDuration = (program.endMillis - program.startMillis).coerceAtLeast(1L)
    val elapsed = (now - program.startMillis).coerceIn(0L, totalDuration)
    val progress = elapsed.toFloat() / totalDuration.toFloat()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape = RoundedCornerShape(RadiusMd), strong = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
        ) {
            // "En ce moment" label
            Text(
                text = "En ce moment",
                style = MaterialTheme.typography.labelMedium,
                color = AccentSolid,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = program.title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            Text(
                text = "${formatClockTime(program.startMillis)} - ${formatClockTime(program.endMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            val description = program.description
            if (!description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        }

        // AccentGradient progress bar at the bottom of the slot
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(3.dp)
                .background(Color.White.copy(alpha = 0.12f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(brush = AccentGradient),
            )
        }
    }
}

/**
 * Upcoming program slot: plain glassSurface, start time + title only.
 */
@Composable
private fun LiveDetailUpcomingSlot(program: EpgProgram) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape = RoundedCornerShape(RadiusMd), strong = false)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatClockTime(program.startMillis),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.width(48.dp),
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = program.title,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
        )
    }
}

/** Formats an epoch-millis timestamp as "HH:mm". */
private fun formatClockTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(millis)

// ─── Previews ────────────────────────────────────────────────────────────────

private val previewChannel = Channel(
    id = "101",
    name = "BBC World News",
    logoUrl = null,
    categoryId = "1",
    epgChannelId = "bbc.world",
)

private val previewCurrentProgram = EpgProgram(
    channelId = "bbc.world",
    title = "Le Journal de 20h",
    description = "Retour sur les principaux événements de la journée en France et dans le monde.",
    startMillis = 1_700_000_000_000L,
    endMillis = 1_700_003_600_000L,
)

private val previewUpcomingPrograms = listOf(
    EpgProgram(
        channelId = "bbc.world",
        title = "Météo",
        description = null,
        startMillis = 1_700_003_600_000L,
        endMillis = 1_700_007_200_000L,
    ),
    EpgProgram(
        channelId = "bbc.world",
        title = "Débat politique",
        description = null,
        startMillis = 1_700_007_200_000L,
        endMillis = 1_700_010_800_000L,
    ),
)

@Preview(name = "LiveDetail — content", showBackground = true, backgroundColor = 0xFF0A0A0F, heightDp = 900)
@Composable
private fun LiveDetailContentPreview() {
    IptvAppTheme {
        LiveDetailContent(
            uiState = LiveDetailUiState(
                isLoading = false,
                channel = previewChannel,
                isFavorite = false,
                streamUrl = "http://example.com/live/user/pass/101.ts",
                isEpgLoading = false,
                currentProgram = previewCurrentProgram,
                upcomingPrograms = previewUpcomingPrograms,
                epgMessage = null,
            ),
            onPlayClick = {},
            onFavoriteClick = {},
            onRetry = {},
        )
    }
}

@Preview(name = "LiveDetail — no EPG", showBackground = true, backgroundColor = 0xFF0A0A0F, heightDp = 900)
@Composable
private fun LiveDetailNoEpgPreview() {
    IptvAppTheme {
        LiveDetailContent(
            uiState = LiveDetailUiState(
                isLoading = false,
                channel = previewChannel.copy(id = "302", name = "Netflix Channel", epgChannelId = null),
                isFavorite = true,
                streamUrl = "http://example.com/live/user/pass/302.ts",
                isEpgLoading = false,
                currentProgram = null,
                upcomingPrograms = emptyList(),
                epgMessage = "Aucun programme disponible",
            ),
            onPlayClick = {},
            onFavoriteClick = {},
            onRetry = {},
        )
    }
}

@Preview(name = "LiveDetail — missing credentials", showBackground = true, backgroundColor = 0xFF0A0A0F, heightDp = 900)
@Composable
private fun LiveDetailMissingCredentialsPreview() {
    IptvAppTheme {
        LiveDetailContent(
            uiState = LiveDetailUiState(
                isLoading = false,
                channel = previewChannel,
                isFavorite = false,
                streamUrl = null,
                isEpgLoading = false,
                currentProgram = previewCurrentProgram,
                upcomingPrograms = previewUpcomingPrograms,
                epgMessage = null,
            ),
            onPlayClick = {},
            onFavoriteClick = {},
            onRetry = {},
        )
    }
}

@Preview(name = "LiveDetail — loading", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun LiveDetailLoadingPreview() {
    IptvAppTheme {
        LiveDetailContent(
            uiState = LiveDetailUiState(isLoading = true),
            onPlayClick = {},
            onFavoriteClick = {},
            onRetry = {},
        )
    }
}

@Preview(name = "LiveDetail — error", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun LiveDetailErrorPreview() {
    IptvAppTheme {
        LiveDetailContent(
            uiState = LiveDetailUiState(isLoading = false, errorMessage = "Chaîne introuvable."),
            onPlayClick = {},
            onFavoriteClick = {},
            onRetry = {},
        )
    }
}

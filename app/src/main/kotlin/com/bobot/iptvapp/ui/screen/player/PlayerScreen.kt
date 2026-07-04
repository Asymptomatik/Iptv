package com.bobot.iptvapp.ui.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player as ExoCommonPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import com.bobot.iptvapp.ui.components.GhostButton
import com.bobot.iptvapp.ui.components.GlassSurface
import com.bobot.iptvapp.ui.components.PrimaryButton
import com.bobot.iptvapp.ui.theme.AccentSolid
import com.bobot.iptvapp.ui.theme.BackgroundSunken
import com.bobot.iptvapp.ui.theme.CardDimens
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import com.bobot.iptvapp.ui.theme.SemanticError
import com.bobot.iptvapp.ui.theme.Spacing
import com.bobot.iptvapp.ui.theme.TextPrimary
import com.bobot.iptvapp.ui.theme.TextSecondary
import java.util.Locale

/**
 * Fullscreen Media3 player screen (Task 13, reskinned Task 11).
 *
 * Wires together:
 *  - [PlayerViewModel] (Hilt) — owns the [PlayerManager][com.bobot.iptvapp.player.PlayerManager]
 *    instance, resume-position lookup, and periodic progress persistence.
 *  - [PlayerView] (Media3 `media3-ui`) — hosted via [AndroidView], `useController = false`
 *    because this screen renders its own Compose control overlay ([PlayerControlsOverlay]).
 *
 * Episode navigation: PlayerViewModel does NOT expose hasNext/hasPrevious/episode navigation.
 * Only seek-forward/seek-backward controls are shown (film-style).
 *
 * @param streamUrl        Direct-play/HLS URL to play.
 * @param streamId         Xtream Codes stream/episode identifier.
 * @param onNavigateBack   Called when the user presses "Retour" on the error overlay.
 */
@Composable
fun PlayerScreen(
    streamUrl: String,
    streamId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(streamUrl, streamId) {
        viewModel.initialize(streamUrl = streamUrl, streamId = streamId)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.releasePlayer() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundSunken),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { playerView ->
                val exoPlayer: ExoCommonPlayer = viewModel.player
                playerView.player = exoPlayer
            },
            onRelease = { playerView -> playerView.player = null },
        )

        if (uiState.isBuffering && !uiState.hasError) {
            CircularProgressIndicator(
                color = AccentSolid,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (!uiState.hasError) {
            PlayerControlsOverlay(
                uiState = uiState,
                onTogglePlayPause = viewModel::togglePlayPause,
                onSeekForward = viewModel::seekForward,
                onSeekBackward = viewModel::seekBackward,
                onSeekTo = viewModel::seekTo,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }

        if (uiState.hasError) {
            PlayerErrorOverlay(
                onRetry = viewModel::retry,
                onNavigateBack = onNavigateBack,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * "Cinematic Glass" bottom control overlay:
 *  - Scrub bar: rgba-white track, AccentSolid active track, round knob. Time labels TextSecondary.
 *  - Controls: current time pinned left, play cluster centered via equal-width Spacers (so the
 *    cluster is truly centered), CC/settings zone pinned right (equal width to left zone).
 *  - Seek buttons clearly labeled "10 s" with direction indicator.
 *  - No episode navigation (PlayerViewModel does not expose it).
 */
@Composable
private fun PlayerControlsOverlay(
    uiState: PlayerUiState,
    onTogglePlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }
    val displayedPositionMs = dragPositionMs ?: uiState.currentPositionMs

    val durationKnown = uiState.durationMs > 0
    val sliderRange = if (durationKnown) uiState.durationMs.toFloat() else 1f

    // Glass overlay scrim — gradient from transparent at top to dark at bottom
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, BackgroundSunken.copy(alpha = 0.92f)),
                ),
            )
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {

            // ── Scrub bar ──────────────────────────────────────────────────────
            // Custom track: white rgba bg track + AccentSolid active track colour (M3 Slider takes a Color, not a Brush).
            Slider(
                value = if (durationKnown) {
                    displayedPositionMs.coerceIn(0L, sliderRange.toLong()).toFloat()
                } else {
                    0f
                },
                onValueChange = { dragPositionMs = it.toLong() },
                onValueChangeFinished = {
                    dragPositionMs?.let(onSeekTo)
                    dragPositionMs = null
                },
                valueRange = 0f..sliderRange,
                enabled = durationKnown,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = AccentSolid,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                    disabledThumbColor = Color.White.copy(alpha = 0.38f),
                    disabledActiveTrackColor = AccentSolid.copy(alpha = 0.38f),
                    disabledInactiveTrackColor = Color.White.copy(alpha = 0.12f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.DirectionRight -> { onSeekForward(); true }
                                Key.DirectionLeft  -> { onSeekBackward(); true }
                                else               -> false
                            }
                        } else {
                            false
                        }
                    },
            )

            Spacer(modifier = Modifier.height(Spacing.xs))

            // ── Controls row: [time | spacer | cluster | spacer | side-zone] ──
            // Left zone and right zone are given equal fixed weight so the cluster
            // in the middle is geometrically centered.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left zone — current time
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = formatTimeMs(displayedPositionMs),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                // Center cluster — seek back | play/pause | seek forward
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    // Seek back 10 s
                    PlayerSeekButton(
                        label = "↺ 10 s",
                        contentDescription = "Reculer de 10 secondes",
                        onClick = onSeekBackward,
                    )

                    // Play / Pause
                    PlayerPlayPauseButton(
                        isPlaying = uiState.isPlaying,
                        onClick = onTogglePlayPause,
                    )

                    // Seek forward 10 s
                    PlayerSeekButton(
                        label = "10 s ↻",
                        contentDescription = "Avancer de 10 secondes",
                        onClick = onSeekForward,
                    )
                }

                // Right zone — equal weight to left zone (keeps cluster truly centered)
                // Reserved for CC/settings; currently empty but spatially balanced.
                Box(modifier = Modifier.weight(1f))
            }

            // Duration label below the controls row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (durationKnown) {
                    Text(
                        text = formatTimeMs(uiState.durationMs),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/**
 * Glass circular seek button — clearly labeled with Unicode arrow + "10 s" text.
 */
@Composable
private fun PlayerSeekButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (isFocused) 0.18f else 0.10f))
            .border(
                width = if (isFocused) CardDimens.FocusBorderWidth else 0.dp,
                color = if (isFocused) AccentSolid else Color.Transparent,
                shape = CircleShape,
            )
            .onFocusChanged { focusState -> isFocused = focusState.isFocused }
            .clickable(onClickLabel = contentDescription, onClick = onClick)
            .padding(horizontal = Spacing.sm2, vertical = Spacing.sm),
    ) {
        Text(
            text = label,
            color = TextPrimary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * Glass circular play/pause button — larger than seek buttons to anchor the cluster.
 */
@Composable
private fun PlayerPlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(
                if (isFocused) AccentSolid.copy(alpha = 0.85f)
                else Color.White.copy(alpha = 0.15f)
            )
            .border(
                width = if (isFocused) CardDimens.FocusBorderWidth else 0.dp,
                color = if (isFocused) AccentSolid else Color.Transparent,
                shape = CircleShape,
            )
            .onFocusChanged { focusState -> isFocused = focusState.isFocused }
            .clickable(
                onClickLabel = if (isPlaying) "Mettre en pause" else "Lecture",
                onClick = onClick,
            ),
    ) {
        if (isPlaying) {
            // Pause glyph drawn as two rounded bars — Icons.Default.Pause is not in
            // material-icons-core, and we avoid pulling in material-icons-extended.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(2) {
                    Box(
                        modifier = Modifier
                            .size(width = 5.dp, height = 26.dp)
                            .clip(CircleShape)
                            .background(TextPrimary),
                    )
                }
            }
        } else {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Lecture",
                tint = TextPrimary,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/**
 * Error overlay shown when [PlayerUiState.hasError] is true.
 */
@Composable
private fun PlayerErrorOverlay(
    onRetry: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier = modifier.padding(Spacing.xl),
        strong = true,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text(
                text = "Impossible de lire le flux.",
                color = SemanticError,
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                PrimaryButton(
                    label = "Réessayer",
                    onClick = onRetry,
                )
                GhostButton(
                    label = "Retour",
                    onClick = onNavigateBack,
                )
            }
        }
    }
}

/**
 * Formats [millis] as `mm:ss`, or `h:mm:ss` once the hour mark is reached.
 * Returns `"0:00"` for negative or unknown (`0L`) values.
 */
internal fun formatTimeMs(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

// ─── Preview ─────────────────────────────────────────────────────────────────

@Preview(name = "PlayerControlsOverlay — playing", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun PlayerControlsOverlayPlayingPreview() {
    IptvAppTheme {
        PlayerControlsOverlay(
            uiState = PlayerUiState(
                isPlaying = true,
                isBuffering = false,
                currentPositionMs = 65_000L,
                durationMs = 5_400_000L,
            ),
            onTogglePlayPause = {},
            onSeekForward = {},
            onSeekBackward = {},
            onSeekTo = {},
        )
    }
}

@Preview(name = "PlayerControlsOverlay — paused", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun PlayerControlsOverlayPausedPreview() {
    IptvAppTheme {
        PlayerControlsOverlay(
            uiState = PlayerUiState(
                isPlaying = false,
                isBuffering = false,
                currentPositionMs = 0L,
                durationMs = 0L,
            ),
            onTogglePlayPause = {},
            onSeekForward = {},
            onSeekBackward = {},
            onSeekTo = {},
        )
    }
}

@Preview(name = "PlayerErrorOverlay", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun PlayerErrorOverlayPreview() {
    IptvAppTheme {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            PlayerErrorOverlay(
                onRetry = {},
                onNavigateBack = {},
            )
        }
    }
}

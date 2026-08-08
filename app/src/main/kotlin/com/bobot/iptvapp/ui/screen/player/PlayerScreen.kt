package com.bobot.iptvapp.ui.screen.player

import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import kotlinx.coroutines.delay
import java.util.Locale

/** Delay of inactivity after which the playback controls auto-hide during active playback. */
private const val CONTROLS_AUTO_HIDE_DELAY_MS = 5_000L

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

    // Keep the screen from dimming/locking while the player is on screen; released on exit.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Auto-landscape on open, binary landscape<->portrait-locked toggle (see
    // PlayerOrientationController for the framework-free decision logic). Neutralized on
    // Android TV and on tablets (smallestScreenWidthDp >= 600) — `manageOrientation` stays
    // false there, so the effects below never touch `requestedOrientation`. Form-factor
    // detection mirrors the exact pattern used in MainActivity.
    val context = LocalContext.current
    val isTv = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
    val smallestScreenWidthDp = LocalConfiguration.current.smallestScreenWidthDp
    val manageOrientation = remember(isTv, smallestScreenWidthDp) {
        shouldManageOrientation(isTv = isTv, smallestScreenWidthDp = smallestScreenWidthDp)
    }
    val activity = view.context as? Activity

    // Not persisted (MVP): resets to auto-landscape (`false`) every time the screen is opened.
    var portraitLocked by remember { mutableStateOf(false) }

    // Applies the requested orientation reactively: first composition (portraitLocked = false)
    // produces the auto-landscape-on-open behaviour; each toggle re-applies it.
    LaunchedEffect(portraitLocked) {
        if (manageOrientation && activity != null) {
            activity.requestedOrientation = toOrientationMode(portraitLocked).toActivityOrientation()
        }
    }

    // Restores the system/manifest-default orientation on exit so leaving the player never
    // strands the rest of the app locked to landscape or portrait.
    DisposableEffect(Unit) {
        onDispose {
            if (manageOrientation && activity != null) {
                activity.requestedOrientation = OrientationMode.SYSTEM.toActivityOrientation()
            }
        }
    }

    // Auto-hide controls after inactivity while actively playing; any interaction reveals them
    // and restarts the timer. `interactionTrigger` is bumped on every user interaction so the
    // LaunchedEffect below re-launches (cancelling the previous delay) without needing the
    // interaction's payload as a key.
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTrigger by remember { mutableStateOf(0) }
    val onUserInteracted: () -> Unit = {
        controlsVisible = true
        interactionTrigger++
    }

    // Remote-friendly way back to the hidden controls. Tapping the video is the only other way
    // to reveal them (see the root Box below) and a TV remote has no tap: once the auto-hide
    // fires, every D-pad key would land on nothing and the player would be stuck playing with
    // no reachable pause, seek or track button. This node therefore claims the focus while the
    // controls are hidden and turns the next directional/OK press into a reveal. It is
    // deliberately *not* focusable while they are visible, so it never inserts an extra focus
    // stop into the overlay's own D-pad order.
    val revealFocusRequester = remember { FocusRequester() }
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) revealFocusRequester.requestFocus()
    }

    // Audio/subtitle selector. Kept here rather than inside PlayerControlsOverlay because it
    // renders over the whole screen (scrim included) and because it has to suppress the
    // auto-hide below: a panel that vanished mid-choice would be unusable.
    var trackSelectorVisible by remember { mutableStateOf(false) }

    BackHandler(enabled = trackSelectorVisible) {
        trackSelectorVisible = false
    }

    // Reading `trackSelectorVisible` as a key (not just in the condition) is what makes closing
    // the panel restart the timer instead of leaving the controls pinned open.
    LaunchedEffect(
        uiState.isPlaying,
        uiState.isBuffering,
        uiState.hasError,
        trackSelectorVisible,
        interactionTrigger,
    ) {
        if (uiState.isPlaying && !uiState.isBuffering && !uiState.hasError && !trackSelectorVisible) {
            delay(CONTROLS_AUTO_HIDE_DELAY_MS)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundSunken)
            // Plain tap-detection (not `clickable`) so this full-screen surface does not become
            // a focusable stop that would disrupt D-pad/TV focus navigation between the
            // controls' buttons and slider.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onUserInteracted() })
            }
            .focusRequester(revealFocusRequester)
            .focusProperties { canFocus = !controlsVisible }
            .focusable()
            .onPreviewKeyEvent { event ->
                // Only the keys that would otherwise do nothing reveal the controls; BACK is
                // left alone so it still leaves the player, and the press is consumed so the
                // reveal never doubles as a seek or a play/pause on a control the user could
                // not see when they pressed it.
                if (controlsVisible || event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.DirectionUp,
                    Key.DirectionDown,
                    Key.DirectionLeft,
                    Key.DirectionRight,
                    Key.DirectionCenter,
                    Key.Enter,
                    -> {
                        onUserInteracted()
                        true
                    }
                    else -> false
                }
            },
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
            // Floating, transparent seek/play/seek cluster. Visible only while not buffering so
            // it never overlaps the centered buffering spinner above (mutually exclusive).
            // Both control clusters step aside while the track selector is open. The scrim
            // already hides them, but leaving them composed would leave their buttons focusable
            // underneath it — the D-pad would walk straight out of the panel onto invisible
            // controls, which is the very trap the selector is meant to avoid.
            AnimatedVisibility(
                visible = controlsVisible && !uiState.isBuffering && !trackSelectorVisible,
                modifier = Modifier.align(Alignment.Center),
            ) {
                PlayerCenterControls(
                    isPlaying = uiState.isPlaying,
                    onSeekBackward = viewModel::seekBackward,
                    onSeekForward = viewModel::seekForward,
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onUserInteracted = onUserInteracted,
                )
            }

            AnimatedVisibility(
                visible = controlsVisible && !trackSelectorVisible,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                PlayerControlsOverlay(
                    uiState = uiState,
                    onSeekForward = viewModel::seekForward,
                    onSeekBackward = viewModel::seekBackward,
                    onSeekTo = viewModel::seekTo,
                    onUserInteracted = onUserInteracted,
                    showOrientationButton = manageOrientation,
                    portraitLocked = portraitLocked,
                    onToggleOrientation = {
                        onUserInteracted()
                        portraitLocked = nextPortraitLocked(portraitLocked)
                    },
                    onOpenTrackSelector = {
                        onUserInteracted()
                        trackSelectorVisible = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (trackSelectorVisible) {
                // Dismiss scrim. Tap-detection rather than `clickable` for the same reason as the
                // root surface above: a focusable full-screen node would swallow the D-pad before
                // the panel ever saw it.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { trackSelectorVisible = false })
                        },
                )

                PlayerTrackSelectorPanel(
                    audioTracks = uiState.audioTracks,
                    subtitleTracks = uiState.subtitleTracks,
                    onSelectAudio = { trackId ->
                        onUserInteracted()
                        viewModel.selectAudioTrack(trackId)
                    },
                    onSelectSubtitle = { trackId ->
                        onUserInteracted()
                        viewModel.selectSubtitleTrack(trackId)
                    },
                    onDisableSubtitles = {
                        onUserInteracted()
                        viewModel.disableSubtitles()
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(Spacing.lg),
                )
            }
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
 *  - Controls: current time (and, on phone, the orientation toggle) pinned left, the audio/subtitle
 *    ("CC") button on the right. The seek/play/seek cluster is NOT part of this bar anymore — it is
 *    floated separately, centered and transparent over the video (see [PlayerCenterControls] and
 *    its call site in [PlayerScreen]), so this bar's left zone gets its full natural width instead
 *    of being starved by a non-weighted center cluster in narrow (portrait) layouts.
 *  - Seek buttons clearly labeled "10 s" with direction indicator (in [PlayerCenterControls]).
 *  - No episode navigation (PlayerViewModel does not expose it).
 *
 * @param showOrientationButton Whether the orientation toggle button should render in the left
 * zone. Driven by [shouldManageOrientation] upstream — `false` on Android TV and on tablets
 * (`smallestScreenWidthDp >= 600`), so the button never appears there.
 * @param portraitLocked Current orientation toggle state, used to pick the button's label and
 * content description (see [PlayerOrientationToggleButton]).
 * @param onToggleOrientation Invoked when the orientation button is pressed.
 * @param onOpenTrackSelector Invoked when the "CC" button is pressed. That button only renders
 * when [hasSelectableTracks] holds for [uiState]'s tracks, so this is never called for a stream
 * with nothing to choose from.
 */
@Composable
private fun PlayerControlsOverlay(
    uiState: PlayerUiState,
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onUserInteracted: () -> Unit,
    showOrientationButton: Boolean,
    portraitLocked: Boolean,
    onToggleOrientation: () -> Unit,
    onOpenTrackSelector: () -> Unit,
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
                onValueChange = {
                    onUserInteracted()
                    dragPositionMs = it.toLong()
                },
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
                                Key.DirectionRight -> { onUserInteracted(); onSeekForward(); true }
                                Key.DirectionLeft  -> { onUserInteracted(); onSeekBackward(); true }
                                else               -> false
                            }
                        } else {
                            false
                        }
                    },
            )

            Spacer(modifier = Modifier.height(Spacing.xs))

            // ── Controls row: [time (+ orientation) | reserved right zone] ──
            // The seek/play/seek cluster used to live here as a non-weighted center Row between
            // two Modifier.weight(1f) side zones; in narrow (portrait) layouts it was measured
            // first and greedily took its intrinsic width, starving the left zone down to near
            // zero and hiding the orientation button. The cluster now floats separately over the
            // video (see PlayerCenterControls), so this row only has to place the left group and
            // reserve the right zone — SpaceBetween keeps them pinned to their edges.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left zone — current time, plus the orientation toggle on phone only
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Text(
                        text = formatTimeMs(displayedPositionMs),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                    )

                    if (showOrientationButton) {
                        PlayerOrientationToggleButton(
                            portraitLocked = portraitLocked,
                            onClick = onToggleOrientation,
                        )
                    }
                }

                // Right zone — audio/subtitle selector, shown only when the stream actually
                // offers a choice (see hasSelectableTracks).
                Box(modifier = Modifier) {
                    if (hasSelectableTracks(uiState.audioTracks, uiState.subtitleTracks)) {
                        PlayerTracksButton(onClick = onOpenTrackSelector)
                    }
                }
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
 * Floating seek/play/seek cluster — seek back | play/pause | seek forward.
 *
 * Rendered directly over the video (not inside [PlayerControlsOverlay]'s opaque bottom bar) so it
 * always has the full screen width to center itself in, regardless of how narrow the bottom bar's
 * own layout gets in portrait. The container [Row] is intentionally transparent and has no
 * `clickable`/`background` modifier: only the three buttons themselves consume taps, so taps in
 * the gaps between them (and everywhere else over the video) still fall through to the root
 * [Box]'s `detectTapGestures` tap-to-toggle-controls handler in [PlayerScreen].
 *
 * Uniform across form factors (phone and TV) — no form-factor branching here.
 */
@Composable
private fun PlayerCenterControls(
    isPlaying: Boolean,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onUserInteracted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // Seek back 10 s
        PlayerSeekButton(
            label = "↺ 10 s",
            contentDescription = "Reculer de 10 secondes",
            onClick = { onUserInteracted(); onSeekBackward() },
        )

        // Play / Pause
        PlayerPlayPauseButton(
            isPlaying = isPlaying,
            onClick = { onUserInteracted(); onTogglePlayPause() },
        )

        // Seek forward 10 s
        PlayerSeekButton(
            label = "10 s ↻",
            contentDescription = "Avancer de 10 secondes",
            onClick = { onUserInteracted(); onSeekForward() },
        )
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
 * Glass circular orientation toggle button — phone only (see
 * [PlayerControlsOverlay.showOrientationButton]). Same "glass circular button" visual pattern as
 * [PlayerSeekButton] (focus border, glass background), but built directly on [Box] here so its
 * content can flip based on [portraitLocked] without introducing a second reusable component for
 * a single call site.
 *
 * Icon/label reflects the *action* the button performs, not the current state: while open in
 * auto-landscape it offers to lock portrait ([Icons.Default.Lock]); while locked in portrait it
 * offers to go back to auto-landscape. `material-icons-core` has no vector counterpart to `Lock`
 * for that second state (`LockOpen` only exists in `material-icons-extended`, which this project
 * does not depend on), so it reuses the plain Unicode arrow glyph language already established by
 * [PlayerSeekButton] (↻) instead of introducing that dependency.
 */
@Composable
private fun PlayerOrientationToggleButton(
    portraitLocked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }

    val contentDescription =
        if (portraitLocked) "Revenir en paysage" else "Verrouiller en portrait"

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (isFocused) 0.18f else 0.10f))
            .border(
                width = if (isFocused) CardDimens.FocusBorderWidth else 0.dp,
                color = if (isFocused) AccentSolid else Color.Transparent,
                shape = CircleShape,
            )
            .onFocusChanged { focusState -> isFocused = focusState.isFocused }
            .clickable(onClickLabel = contentDescription, onClick = onClick)
            .padding(Spacing.sm),
    ) {
        if (portraitLocked) {
            Text(
                text = "↻",
                color = TextPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
        } else {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = contentDescription,
                tint = TextPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
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
            onSeekForward = {},
            onSeekBackward = {},
            onSeekTo = {},
            onUserInteracted = {},
            showOrientationButton = false,
            portraitLocked = false,
            onToggleOrientation = {},
            onOpenTrackSelector = {},
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
            onSeekForward = {},
            onSeekBackward = {},
            onSeekTo = {},
            onUserInteracted = {},
            showOrientationButton = false,
            portraitLocked = false,
            onToggleOrientation = {},
            onOpenTrackSelector = {},
        )
    }
}

@Preview(
    name = "PlayerControlsOverlay — orientation button (phone)",
    showBackground = true,
    backgroundColor = 0xFF0A0A0F,
)
@Composable
private fun PlayerControlsOverlayOrientationButtonPreview() {
    IptvAppTheme {
        PlayerControlsOverlay(
            uiState = PlayerUiState(
                isPlaying = true,
                isBuffering = false,
                currentPositionMs = 65_000L,
                durationMs = 5_400_000L,
            ),
            onSeekForward = {},
            onSeekBackward = {},
            onSeekTo = {},
            onUserInteracted = {},
            showOrientationButton = true,
            portraitLocked = false,
            onToggleOrientation = {},
            onOpenTrackSelector = {},
        )
    }
}

@Preview(name = "PlayerCenterControls — floating cluster", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun PlayerCenterControlsPreview() {
    IptvAppTheme {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            PlayerCenterControls(
                isPlaying = true,
                onSeekBackward = {},
                onSeekForward = {},
                onTogglePlayPause = {},
                onUserInteracted = {},
            )
        }
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

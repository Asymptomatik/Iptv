package com.bobot.iptvapp.ui.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bobot.iptvapp.player.PlayerTrack
import com.bobot.iptvapp.player.PlayerTrackType
import com.bobot.iptvapp.ui.components.glassSurface
import com.bobot.iptvapp.ui.theme.AccentSolid
import com.bobot.iptvapp.ui.theme.CardDimens
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import com.bobot.iptvapp.ui.theme.RadiusLg
import com.bobot.iptvapp.ui.theme.RadiusPill
import com.bobot.iptvapp.ui.theme.Spacing
import com.bobot.iptvapp.ui.theme.TextPrimary
import com.bobot.iptvapp.ui.theme.TextSecondary

/** Label shown for the always-present "no subtitles" entry — see [areSubtitlesDisabled]. */
private const val SUBTITLES_OFF_LABEL = "Désactivés"

/**
 * Glass button opening the audio/subtitle selector, rendered in [PlayerControlsOverlay]'s right
 * zone.
 *
 * Labelled with the plain "CC" glyph rather than an icon: `material-icons-core` has no closed
 * caption vector (`ClosedCaption` only ships in `material-icons-extended`, which this project
 * deliberately does not depend on), and the text-glyph language is already established next door
 * by [PlayerSeekButton]'s "10 s ↻".
 *
 * Its caller is responsible for only rendering it when [shouldShowTracksButton] is true.
 *
 * @param takeFocus  Set once the track panels close, so the D-pad lands back on this button
 *                   instead of on nothing: the panel row that held the focus is gone, and the
 *                   button itself comes back with the controls. Requested from here rather than
 *                   by the caller because only here is the button sure to be composed. Keyboard
 *                   mode only — a touch close never shows a focus ring.
 * @param onFocusTaken Clears [takeFocus] once it has been honoured (or skipped in touch mode), so
 *                   the controls coming back later — after an auto-hide — do not pull the focus
 *                   here again.
 */
@Composable
internal fun PlayerTracksButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    takeFocus: Boolean = false,
    onFocusTaken: () -> Unit = {},
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val inputModeManager = LocalInputModeManager.current

    LaunchedEffect(takeFocus) {
        if (!takeFocus) return@LaunchedEffect
        if (inputModeManager.isKeyboard) focusRequester.requestFocus()
        onFocusTaken()
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .focusRequester(focusRequester)
            // Same surface as the seek and play/pause buttons — see playerControlSurface
            // (QA finding N10).
            .playerControlSurface(isFocused)
            .onFocusChanged { focusState -> isFocused = focusState.isFocused }
            .clickable(onClickLabel = "Audio et sous-titres", onClick = onClick)
            .padding(horizontal = Spacing.sm2, vertical = Spacing.sm),
    ) {
        Text(
            text = "CC",
            color = TextPrimary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * Audio/subtitle selection panel — the UI half of the track-selection feature whose player,
 * `PlayerManager` and `PlayerViewModel` layers already existed and were unit-tested, but which
 * nothing on screen ever exposed.
 *
 * ## Why a panel and not a `ModalBottomSheet`
 * Material 3's modal sheet is built around a touch drag handle and edge-swipe dismissal, neither
 * of which a remote control can operate; it also animates up from the bottom edge, exactly where
 * the player's own scrub bar lives. This is instead a plain glass panel anchored above the
 * controls, made of the same focusable rows the rest of the app uses, so a D-pad walks it top to
 * bottom like any other list.
 *
 * The first row takes the focus when the panel opens ([FocusRequester]) — without it, a TV user
 * would open the panel and have the focus still sitting on the button behind it.
 *
 * ## "Désactivés" is a first-class row, not a toggle
 * Disabling subtitles clears every track's selection rather than setting a separate flag (see
 * [areSubtitlesDisabled]), so "off" is genuinely one option among the others and is rendered as
 * such — present whenever the stream has subtitle tracks, checked exactly when none is applied.
 *
 * ## Online search
 * On a VOD, the section ends with a "Rechercher en ligne…" row that hands over to
 * [OnlineSubtitleSearchPanel] — and the section shows for it alone when the stream announces no
 * subtitle track, since that is exactly when a search is most needed.
 *
 * @param audioTracks       Audio tracks to offer; the section is omitted when there is nothing to
 *                          choose from (fewer than two entries — Media3 always applies one).
 * @param subtitleTracks    Subtitle tracks to offer, in addition to the always-present "off" row.
 * @param onSelectAudio     Invoked with a [PlayerTrack.id] from [audioTracks].
 * @param onSelectSubtitle  Invoked with a [PlayerTrack.id] from [subtitleTracks].
 * @param onDisableSubtitles Invoked when the "Désactivés" row is picked.
 * @param onlineSearchAvailable Whether to end the "Sous-titres" section with the row opening the
 *                          online search — `true` for a VOD with a search context, even when the
 *                          stream announces no subtitle track at all (see [hasSubtitleSection]).
 * @param onOpenOnlineSearch Invoked when that row is picked.
 * @param focusOnlineSearchRow Whether that row, rather than the first one, takes the initial focus —
 *                          set when coming back from the search panel, so the D-pad lands where
 *                          the user left instead of jumping to the top of the list.
 */
@Composable
internal fun PlayerTrackSelectorPanel(
    audioTracks: List<PlayerTrack>,
    subtitleTracks: List<PlayerTrack>,
    onSelectAudio: (String) -> Unit,
    onSelectSubtitle: (String) -> Unit,
    onDisableSubtitles: () -> Unit,
    modifier: Modifier = Modifier,
    onlineSearchAvailable: Boolean = false,
    onOpenOnlineSearch: () -> Unit = {},
    focusOnlineSearchRow: Boolean = false,
) {
    val firstRowFocusRequester = remember { FocusRequester() }
    val onlineSearchFocusRequester = remember { FocusRequester() }

    // Media3 always applies one audio track, so a lone entry is a label, not a choice.
    val showAudioSection = audioTracks.size > 1
    val hasSubtitleTracks = subtitleTracks.isNotEmpty()
    val inputModeManager = LocalInputModeManager.current

    // Once per opening, never again: a track picked inside the panel recomposes it with new
    // tracks, and re-keying on them would yank the D-pad back to the top under the user's feet.
    // Keyboard mode only, so a touch opening never lights up a row.
    LaunchedEffect(Unit) {
        if (!inputModeManager.isKeyboard) return@LaunchedEffect
        when {
            focusOnlineSearchRow && onlineSearchAvailable -> onlineSearchFocusRequester.requestFocus()
            // Nothing carries firstRowFocusRequester in an empty panel — the CC button is not
            // shown for such a stream, but a request on an unattached requester would throw.
            showAudioSection || hasSubtitleSection(subtitleTracks, onlineSearchAvailable) ->
                firstRowFocusRequester.requestFocus()
        }
    }

    Column(
        modifier = modifier
            .widthIn(max = 320.dp)
            .heightIn(max = 320.dp)
            .glassSurface(shape = RoundedCornerShape(RadiusLg), strong = true)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (showAudioSection) {
            PlayerTrackSectionTitle(text = "Audio")

            audioTracks.forEachIndexed { index, track ->
                PlayerTrackRow(
                    label = track.label,
                    isSelected = track.isSelected,
                    onClick = { onSelectAudio(track.id) },
                    modifier = if (index == 0) {
                        Modifier.focusRequester(firstRowFocusRequester)
                    } else {
                        Modifier
                    },
                )
            }
        }

        if (hasSubtitleSection(subtitleTracks, onlineSearchAvailable)) {
            PlayerTrackSectionTitle(text = "Sous-titres")

            if (hasSubtitleTracks) {
                PlayerTrackRow(
                    label = SUBTITLES_OFF_LABEL,
                    isSelected = areSubtitlesDisabled(subtitleTracks),
                    onClick = onDisableSubtitles,
                    // Takes the initial focus only when there is no audio section above it.
                    modifier = if (showAudioSection) {
                        Modifier
                    } else {
                        Modifier.focusRequester(firstRowFocusRequester)
                    },
                )

                subtitleTracks.forEach { track ->
                    PlayerTrackRow(
                        label = track.label,
                        isSelected = track.isSelected,
                        onClick = { onSelectSubtitle(track.id) },
                    )
                }
            }

            if (onlineSearchAvailable) {
                // Last row, and the first focus stop when it is alone in the panel. An action,
                // not an option: it never carries the check mark.
                var searchRowModifier = Modifier.focusRequester(onlineSearchFocusRequester)
                if (!showAudioSection && !hasSubtitleTracks) {
                    searchRowModifier = searchRowModifier.focusRequester(firstRowFocusRequester)
                }
                PlayerTrackRow(
                    label = ONLINE_SEARCH_ROW_LABEL,
                    isSelected = false,
                    onClick = onOpenOnlineSearch,
                    role = Role.Button,
                    modifier = searchRowModifier,
                )
            }
        }
    }
}

/**
 * Whether focus requests should be honoured: keyboard mode is where a TV starts and where a phone
 * goes on its first D-pad press. In touch mode the rows are not focusable anyway
 * (`Focusability.SystemDefined`); checking here keeps a touch user's screen free of focus rings
 * whatever that rule becomes.
 */
internal val InputModeManager.isKeyboard: Boolean
    get() = inputMode == InputMode.Keyboard

/** Section heading inside [PlayerTrackSelectorPanel] and [OnlineSubtitleSearchPanel] — deliberately not focusable. */
@Composable
internal fun PlayerTrackSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = TextSecondary,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier.padding(top = Spacing.sm, bottom = Spacing.xs),
    )
}

/**
 * One selectable track row: label on the left, check mark on the right when applied.
 *
 * The check mark is the only selection affordance — the row does not also tint its background,
 * so the focus highlight stays unambiguously about *where the D-pad is* rather than *what is
 * currently playing*, which the panel needs to keep visually distinct.
 *
 * [role] is [Role.Button] for rows that trigger an action rather than pick an option ("Rechercher
 * en ligne…", "Retour aux pistes"): those are never announced as selected.
 */
@Composable
internal fun PlayerTrackRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.RadioButton,
) {
    var isFocused by remember { mutableStateOf(false) }

    val rowShape = RoundedCornerShape(RadiusPill)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                this.role = role
                if (role == Role.RadioButton) selected = isSelected
            }
            .clip(rowShape)
            .background(Color.White.copy(alpha = if (isFocused) 0.18f else 0f))
            .border(
                width = if (isFocused) CardDimens.FocusBorderWidth else 0.dp,
                color = if (isFocused) AccentSolid else Color.Transparent,
                shape = rowShape,
            )
            .onFocusChanged { focusState -> isFocused = focusState.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm2, vertical = Spacing.sm),
    ) {
        Text(
            text = label,
            color = TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Sélectionné",
                tint = AccentSolid,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

private val PreviewAudioTracks = listOf(
    PlayerTrack(
        id = "aud-fr",
        label = "Français",
        languageCode = "fra",
        isSelected = true,
        type = PlayerTrackType.AUDIO,
    ),
    PlayerTrack(
        id = "aud-en",
        label = "Anglais",
        languageCode = "eng",
        isSelected = false,
        type = PlayerTrackType.AUDIO,
    ),
)

private val PreviewSubtitleTracks = listOf(
    PlayerTrack(
        id = "sub-fr",
        label = "Français",
        languageCode = "fra",
        isSelected = true,
        type = PlayerTrackType.SUBTITLE,
    ),
    PlayerTrack(
        id = "sub-en",
        label = "Anglais (SDH)",
        languageCode = "eng",
        isSelected = false,
        type = PlayerTrackType.SUBTITLE,
    ),
)

@Preview(
    name = "PlayerTrackSelectorPanel — audio + sous-titres",
    showBackground = true,
    backgroundColor = 0xFF0A0A0F,
)
@Composable
private fun PlayerTrackSelectorPanelPreview() {
    IptvAppTheme {
        Box(modifier = Modifier.padding(Spacing.md)) {
            PlayerTrackSelectorPanel(
                audioTracks = PreviewAudioTracks,
                subtitleTracks = PreviewSubtitleTracks,
                onSelectAudio = {},
                onSelectSubtitle = {},
                onDisableSubtitles = {},
            )
        }
    }
}

@Preview(
    name = "PlayerTrackSelectorPanel — sous-titres désactivés",
    showBackground = true,
    backgroundColor = 0xFF0A0A0F,
)
@Composable
private fun PlayerTrackSelectorPanelSubtitlesOffPreview() {
    IptvAppTheme {
        Box(modifier = Modifier.padding(Spacing.md)) {
            PlayerTrackSelectorPanel(
                audioTracks = emptyList(),
                subtitleTracks = PreviewSubtitleTracks.map { it.copy(isSelected = false) },
                onSelectAudio = {},
                onSelectSubtitle = {},
                onDisableSubtitles = {},
            )
        }
    }
}

@Preview(
    name = "PlayerTrackSelectorPanel — VOD sans piste, recherche en ligne",
    showBackground = true,
    backgroundColor = 0xFF0A0A0F,
)
@Composable
private fun PlayerTrackSelectorPanelOnlineOnlyPreview() {
    IptvAppTheme {
        Box(modifier = Modifier.padding(Spacing.md)) {
            PlayerTrackSelectorPanel(
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                onSelectAudio = {},
                onSelectSubtitle = {},
                onDisableSubtitles = {},
                onlineSearchAvailable = true,
            )
        }
    }
}

@Preview(
    name = "PlayerTracksButton",
    showBackground = true,
    backgroundColor = 0xFF0A0A0F,
)
@Composable
private fun PlayerTracksButtonPreview() {
    IptvAppTheme {
        Box(modifier = Modifier.padding(Spacing.md)) {
            PlayerTracksButton(onClick = {})
        }
    }
}

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bobot.iptvapp.domain.model.OnlineSubtitle
import com.bobot.iptvapp.domain.model.SubtitleSearchContext
import com.bobot.iptvapp.ui.components.glassSurface
import com.bobot.iptvapp.ui.theme.AccentSolid
import com.bobot.iptvapp.ui.theme.CardDimens
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import com.bobot.iptvapp.ui.theme.RadiusLg
import com.bobot.iptvapp.ui.theme.RadiusMd
import com.bobot.iptvapp.ui.theme.SemanticError
import com.bobot.iptvapp.ui.theme.Spacing
import com.bobot.iptvapp.ui.theme.TextPrimary
import com.bobot.iptvapp.ui.theme.TextSecondary

/**
 * Online subtitle search, the sub-view the track selector's "Rechercher en ligne…" row opens.
 *
 * Same glass panel and same focusable rows as [PlayerTrackSelectorPanel], swapped in its place by
 * [PlayerScreen] rather than stacked on it, so the D-pad only ever walks one list. It renders
 * [OnlineSubtitlesUiState] and nothing else; the wording comes from `OnlineSubtitlePresentation.kt`.
 *
 * ## Focus
 * The panel always holds the focus somewhere reachable: the "Retour aux pistes" row while the
 * search runs, then the first result, or "Réessayer" when there is nothing to pick. Re-targeted
 * only when the search itself changes shape ([searchFocusTarget]) — a pick in progress or a
 * failed pick never moves the focus off the row the user just pressed.
 *
 * ## Nothing is applied on its own
 * Each result is a row the user picks by hand ([onSelect]); the one being downloaded says so, and
 * the one the player carries gets the same check mark as a stream track.
 *
 * @param state        The search and the pick, from [PlayerUiState.onlineSubtitles].
 * @param context      What is being searched for, shown under the title; `null` hides that line.
 * @param onBack       Back to the track selector — also what the system BACK key does.
 * @param onRetry      Searches again.
 * @param onSelect     Downloads and applies one result.
 * @param onDismissError Clears [OnlineSubtitlesUiState.applyError].
 */
@Composable
internal fun OnlineSubtitleSearchPanel(
    state: OnlineSubtitlesUiState,
    context: SubtitleSearchContext?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (OnlineSubtitle) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backFocusRequester = remember { FocusRequester() }
    val retryFocusRequester = remember { FocusRequester() }
    val firstResultFocusRequester = remember { FocusRequester() }

    val search = state.search
    val focusTarget = searchFocusTarget(search)

    val inputModeManager = LocalInputModeManager.current

    val focusOnTarget: () -> Unit = {
        if (inputModeManager.isKeyboard) {
            when (focusTarget) {
                SearchFocusTarget.BACK -> backFocusRequester.requestFocus()
                SearchFocusTarget.RETRY -> retryFocusRequester.requestFocus()
                SearchFocusTarget.FIRST_RESULT -> firstResultFocusRequester.requestFocus()
            }
        }
    }

    // Where the focus is, as far as re-targeting cares: whether the panel holds it at all, and
    // which target row (if any) holds it. Written from focus callbacks, read only by the effect
    // below — never by composition, so they cost no recomposition.
    var panelHasFocus by remember { mutableStateOf(false) }
    var focusedTarget by remember { mutableStateOf<SearchFocusTarget?>(null) }
    var previousTarget by remember { mutableStateOf<SearchFocusTarget?>(null) }
    fun Modifier.tracksFocusOf(target: SearchFocusTarget) = onFocusChanged { focusState ->
        if (focusState.isFocused) {
            focusedTarget = target
        } else if (focusedTarget == target) {
            focusedTarget = null
        }
    }

    // On opening, and each time the search changes shape (Loading → Results, retry → Loading…).
    // The focus only follows if it was still on the row the previous state put it on, or was
    // lost with a row that went away: a row the user walked to by hand is left alone.
    LaunchedEffect(focusTarget) {
        val previous = previousTarget
        previousTarget = focusTarget
        val userMovedAway = previous != null && panelHasFocus && focusedTarget != previous
        if (!userMovedAway) focusOnTarget()
    }

    Column(
        modifier = modifier
            .widthIn(max = 420.dp)
            .heightIn(max = 360.dp)
            .glassSurface(shape = RoundedCornerShape(RadiusLg), strong = true)
            .onFocusChanged { focusState -> panelHasFocus = focusState.hasFocus }
            .verticalScroll(rememberScrollState())
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        PlayerTrackRow(
            label = "← $ONLINE_SEARCH_BACK_LABEL",
            isSelected = false,
            onClick = onBack,
            role = Role.Button,
            modifier = Modifier
                .focusRequester(backFocusRequester)
                .tracksFocusOf(SearchFocusTarget.BACK),
        )

        Text(
            text = ONLINE_SEARCH_TITLE,
            color = TextPrimary,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        if (context != null) {
            Text(
                text = describeSearchContext(context),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        state.applyError?.let { message ->
            OnlineSubtitleApplyError(
                message = message,
                // The "OK" row goes away with the message; without this the D-pad would be left
                // on a node that no longer exists.
                onDismiss = {
                    onDismissError()
                    focusOnTarget()
                },
            )
        }

        when (search) {
            // `Idle` only shows for the instant between opening the panel and the search
            // starting — rendered as the search it is about to be.
            OnlineSubtitleSearchState.Idle,
            OnlineSubtitleSearchState.Loading,
            -> OnlineSubtitleSearchStatus(message = ONLINE_SEARCH_LOADING_MESSAGE, isLoading = true)

            OnlineSubtitleSearchState.Empty -> {
                OnlineSubtitleSearchStatus(message = ONLINE_SEARCH_EMPTY_MESSAGE)
                PlayerTrackRow(
                    label = ONLINE_SEARCH_RETRY_LABEL,
                    isSelected = false,
                    onClick = onRetry,
                    role = Role.Button,
                    modifier = Modifier
                        .focusRequester(retryFocusRequester)
                        .tracksFocusOf(SearchFocusTarget.RETRY),
                )
            }

            is OnlineSubtitleSearchState.Failed -> {
                OnlineSubtitleSearchStatus(message = search.message, isError = true)
                if (search.canRetry) {
                    PlayerTrackRow(
                        label = ONLINE_SEARCH_RETRY_LABEL,
                        isSelected = false,
                        onClick = onRetry,
                        role = Role.Button,
                        modifier = Modifier
                            .focusRequester(retryFocusRequester)
                            .tracksFocusOf(SearchFocusTarget.RETRY),
                    )
                }
            }

            is OnlineSubtitleSearchState.Results -> {
                PlayerTrackSectionTitle(text = "Résultats")
                search.subtitles.forEachIndexed { index, subtitle ->
                    OnlineSubtitleResultRow(
                        subtitle = subtitle,
                        isApplying = state.applyingFileId == subtitle.fileId,
                        isApplied = state.appliedFileId == subtitle.fileId,
                        onClick = { onSelect(subtitle) },
                        modifier = if (index == 0) {
                            Modifier
                                .focusRequester(firstResultFocusRequester)
                                .tracksFocusOf(SearchFocusTarget.FIRST_RESULT)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }
}

/** Which row of [OnlineSubtitleSearchPanel] should hold the focus for a given search state. */
internal enum class SearchFocusTarget { BACK, RETRY, FIRST_RESULT }

/**
 * Framework-free focus rule of [OnlineSubtitleSearchPanel] (pinned in
 * `OnlineSubtitlePresentationTest`): the first result when there are results, "Réessayer" when
 * the search ended with nothing but can be retried, "Retour aux pistes" otherwise — the one row
 * that is always there.
 */
internal fun searchFocusTarget(search: OnlineSubtitleSearchState): SearchFocusTarget =
    when (search) {
        is OnlineSubtitleSearchState.Results -> SearchFocusTarget.FIRST_RESULT
        OnlineSubtitleSearchState.Empty -> SearchFocusTarget.RETRY
        is OnlineSubtitleSearchState.Failed ->
            if (search.canRetry) SearchFocusTarget.RETRY else SearchFocusTarget.BACK
        OnlineSubtitleSearchState.Idle,
        OnlineSubtitleSearchState.Loading,
        -> SearchFocusTarget.BACK
    }

/** Loading, empty and failure lines — announced by TalkBack without taking the focus. */
@Composable
private fun OnlineSubtitleSearchStatus(
    message: String,
    isLoading: Boolean = false,
    isError: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = AccentSolid,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = message,
            color = if (isError) SemanticError else TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * A pick that did not take: the message, and an "OK" row to clear it. Stays until dismissed or
 * superseded by the next pick — the video kept playing, so nothing else asks for attention.
 */
@Composable
private fun OnlineSubtitleApplyError(
    message: String,
    onDismiss: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            .background(SemanticError.copy(alpha = 0.12f), RoundedCornerShape(RadiusMd))
            .padding(Spacing.sm),
    ) {
        Text(
            text = message,
            color = SemanticError,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        PlayerTrackRow(
            label = "OK",
            isSelected = false,
            onClick = onDismiss,
            role = Role.Button,
        )
    }
}

/**
 * One result: [onlineSubtitleTitle] over [onlineSubtitleHints], then "Téléchargement…" while it
 * is being fetched or a check mark once the player carries it. Same focus treatment as
 * [PlayerTrackRow], on two lines.
 */
@Composable
private fun OnlineSubtitleResultRow(
    subtitle: OnlineSubtitle,
    isApplying: Boolean,
    isApplied: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }

    val rowShape = RoundedCornerShape(RadiusMd)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                role = Role.RadioButton
                selected = isApplied
                if (isApplying) stateDescription = ONLINE_SUBTITLE_APPLYING_LABEL
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = onlineSubtitleTitle(subtitle),
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = onlineSubtitleHints(subtitle),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        when {
            isApplying -> Text(
                text = ONLINE_SUBTITLE_APPLYING_LABEL,
                color = AccentSolid,
                style = MaterialTheme.typography.labelSmall,
            )
            isApplied -> Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Sélectionné",
                tint = AccentSolid,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

private val PreviewContext = SubtitleSearchContext(
    kind = SubtitleSearchContext.Kind.EPISODE,
    title = "Le chat",
    seriesTitle = "Breaking Bad",
    seasonNumber = 1,
    episodeNumber = 2,
)

private val PreviewResults = listOf(
    OnlineSubtitle(
        fileId = 1L,
        language = "fr",
        release = "Breaking.Bad.S01E02.720p.BluRay.x264",
        downloadCount = 1_234,
        featureYear = 2008,
        seasonNumber = 1,
        episodeNumber = 2,
        isFromTrusted = true,
    ),
    OnlineSubtitle(
        fileId = 2L,
        language = "fr",
        fileName = "breaking.bad.s01e02.srt",
        downloadCount = 87,
        seasonNumber = 1,
        episodeNumber = 2,
        isHearingImpaired = true,
    ),
)

@Preview(name = "OnlineSubtitleSearchPanel — résultats", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun OnlineSubtitleSearchPanelResultsPreview() {
    IptvAppTheme {
        Box(modifier = Modifier.padding(Spacing.md)) {
            OnlineSubtitleSearchPanel(
                state = OnlineSubtitlesUiState(
                    isAvailable = true,
                    isPanelOpen = true,
                    search = OnlineSubtitleSearchState.Results(PreviewResults),
                    applyingFileId = 2L,
                    appliedFileId = 1L,
                ),
                context = PreviewContext,
                onBack = {},
                onRetry = {},
                onSelect = {},
                onDismissError = {},
            )
        }
    }
}

@Preview(name = "OnlineSubtitleSearchPanel — erreur", showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun OnlineSubtitleSearchPanelFailedPreview() {
    IptvAppTheme {
        Box(modifier = Modifier.padding(Spacing.md)) {
            OnlineSubtitleSearchPanel(
                state = OnlineSubtitlesUiState(
                    isAvailable = true,
                    isPanelOpen = true,
                    search = OnlineSubtitleSearchState.Failed(NETWORK_MESSAGE, canRetry = true),
                ),
                context = PreviewContext,
                onBack = {},
                onRetry = {},
                onSelect = {},
                onDismissError = {},
            )
        }
    }
}

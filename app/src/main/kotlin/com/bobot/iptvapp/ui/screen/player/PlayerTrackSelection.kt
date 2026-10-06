package com.bobot.iptvapp.ui.screen.player

import com.bobot.iptvapp.player.PlayerTrack

/**
 * Framework-free decision logic behind the player's audio/subtitle track selector.
 *
 * Mirrors the split established by [PlayerOrientationController][OrientationMode]: everything the
 * selector has to *decide* lives here as plain Kotlin so it runs under plain JUnit on the JVM
 * (see `PlayerTrackSelectionTest`), while `PlayerTrackSelector` only has to render what these
 * functions return. The rules read as one-liners individually, but each of them encodes a
 * product decision worth pinning down with a test rather than re-deriving at the call site.
 */

/**
 * `true` when the track button is worth showing at all.
 *
 * The selector is only useful when the user actually has something to choose. A stream with a
 * single audio track and no subtitles offers no choice, so the button would open an empty panel
 * — on TV it would also be one more focus stop between the scrub bar and nothing. Note the
 * asymmetry: **two** audio tracks are needed (Media3 always applies one, so a lone track is not a
 * choice), but a **single** subtitle track is enough, because "off" is always the alternative —
 * see [areSubtitlesDisabled].
 */
fun hasSelectableTracks(
    audioTracks: List<PlayerTrack>,
    subtitleTracks: List<PlayerTrack>,
): Boolean {
    return audioTracks.size > 1 || subtitleTracks.isNotEmpty()
}

/**
 * `true` when the "CC" button is worth showing: the stream offers a choice of its own (see
 * [hasSelectableTracks]), or an online subtitle search can bring one. The second half is what
 * keeps the button reachable on a VOD whose provider announces no track at all — without it, the
 * search would sit behind a button that never renders. Live channels never have a search
 * ([OnlineSubtitlesUiState.isAvailable] is always `false` there), so their rule is unchanged.
 */
fun shouldShowTracksButton(
    audioTracks: List<PlayerTrack>,
    subtitleTracks: List<PlayerTrack>,
    onlineSearchAvailable: Boolean,
): Boolean {
    return hasSelectableTracks(audioTracks, subtitleTracks) || onlineSearchAvailable
}

/**
 * `true` when the selector's "Sous-titres" section has anything to list: tracks from the stream,
 * or at least the row opening the online search. The "Désactivés" row only comes with actual
 * tracks — with none, "off" is not a choice, it is the only state there is.
 */
fun hasSubtitleSection(
    subtitleTracks: List<PlayerTrack>,
    onlineSearchAvailable: Boolean,
): Boolean {
    return subtitleTracks.isNotEmpty() || onlineSearchAvailable
}

/**
 * `true` when no subtitle track is currently applied — the state the UI labels "Désactivés".
 *
 * There is deliberately no `subtitlesEnabled` flag anywhere in the player stack: disabling
 * subtitles clears every track's selection, so "disabled" and "nothing selected in this list"
 * are the same state Media3 itself exposes (see [PlayerUiState.subtitleTracks]). This function
 * names that derivation once so the panel and its tests agree on it.
 */
fun areSubtitlesDisabled(subtitleTracks: List<PlayerTrack>): Boolean {
    return subtitleTracks.none { it.isSelected }
}

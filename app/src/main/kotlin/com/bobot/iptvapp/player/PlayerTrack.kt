package com.bobot.iptvapp.player

/**
 * The kind of track a [PlayerTrack] represents.
 *
 * Mirrors the two Media3 `C.TRACK_TYPE_*` constants this feature cares about
 * ([androidx.media3.common.C.TRACK_TYPE_AUDIO] and [androidx.media3.common.C.TRACK_TYPE_TEXT])
 * without leaking the Media3 constant into consumers of [PlayerTrack].
 */
enum class PlayerTrackType {
    AUDIO,
    SUBTITLE,
}

/**
 * A single selectable audio or subtitle track embedded in the currently playing stream.
 *
 * Framework-neutral by design (no Media3/Android import) so it can be built, compared and
 * unit-tested — by [ExoPlayerManager] today, and by `PlayerViewModel` (Task 4) and its Compose
 * UI (Task 5) tomorrow — without pulling in `androidx.media3.common.Tracks`/`Format`.
 *
 * @property id Stable identifier for this track, scoped to [type], usable to re-select the
 *   track via [PlayerManager.selectAudioTrack] / [PlayerManager.selectSubtitleTrack]. Derived
 *   from the underlying Media3 `Format.id` when the container advertises one (HLS renditions,
 *   DASH adaptation sets, …), or from the track's position in the current track list otherwise
 *   — see [ExoPlayerManager] for the exact derivation. Only guaranteed to remain valid for the
 *   lifetime of the [androidx.media3.common.Player.getCurrentTracks] `Tracks` snapshot it was
 *   derived from; callers
 *   should re-fetch tracks (e.g. via [PlayerManager.getAudioTracks]) after a track change
 *   before relying on a previously captured [id].
 * @property label Human-readable, user-facing name for the track (e.g. a container-provided
 *   label, or a language display name derived via `LanguageLabel`, or a generic fallback such
 *   as "Track 2" when neither is available). Never blank.
 * @property languageCode Raw ISO 639 language code as reported by the container/renderer
 *   (e.g. `"fra"`, `"en"`), or `null` when the track carries no language metadata.
 * @property isSelected Whether this track is the one currently applied to playback.
 * @property type Whether this is an [PlayerTrackType.AUDIO] or [PlayerTrackType.SUBTITLE] track.
 */
data class PlayerTrack(
    val id: String,
    val label: String,
    val languageCode: String?,
    val isSelected: Boolean,
    val type: PlayerTrackType,
)

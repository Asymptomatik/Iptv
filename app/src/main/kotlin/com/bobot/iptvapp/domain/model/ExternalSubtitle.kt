package com.bobot.iptvapp.domain.model

/**
 * An externally hosted subtitle track (e.g. a `.srt` file) advertised by some
 * Xtream servers alongside VOD extended metadata.
 *
 * Sourced from: Xtream Codes `get_vod_info` `info.subtitles` array, when present.
 * This is best-effort data — most servers omit it entirely — and is never
 * persisted; it is attached to [Movie] only for the lifetime of a single
 * detail-screen fetch.
 *
 * @property url      Direct URL of the subtitle file. Always non-blank.
 * @property language Language code or label as reported by the server (e.g. "en",
 *                     "English"). Null when the server does not provide one.
 * @property trackId  Id the side-loaded track must carry once prepared, so the caller can find it
 *                     again in [com.bobot.iptvapp.player.PlayerManager.getSubtitleTracks] — set
 *                     for a subtitle picked online, which the player must switch to. Null lets
 *                     the player derive one, as for Xtream's own subtitles.
 */
data class ExternalSubtitle(
    val url: String,
    val language: String?,
    val trackId: String? = null,
)

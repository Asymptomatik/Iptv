package com.bobot.iptvapp.domain.model

/**
 * A single episode belonging to a [Season] of a [Series].
 *
 * Episodes are the leaf-level playable unit for [ContentType.SERIES] content.
 * They are nested inside [Season.episodes] and expose a stream identifier used
 * to construct the playback URL.
 *
 * Sourced from: Xtream Codes `get_series_info` response (per-series episode list).
 * Mapped from network DTOs in Task 6. Persisted as Room entities in Task 10.
 *
 * Time fields use epoch-millisecond Long values — see [Movie] for the rationale.
 *
 * @property id                 Domain identifier — string form of Xtream episode `id`.
 * @property title              Display title of the episode.
 * @property episodeNumber      Position of the episode within its season (1-based).
 * @property seasonNumber       Season this episode belongs to (mirrors [Season.seasonNumber]).
 *                              Denormalised here so an episode can be used standalone
 *                              without navigating the series tree.
 * @property plot               Episode synopsis. Null when absent from Xtream metadata.
 * @property durationMillis     Playback duration in milliseconds. Null when absent.
 * @property containerExtension File extension used to build the playback URL (e.g. "mkv").
 *                              Null when absent.
 * @property coverUrl           Thumbnail / still image URL for the episode. Null when absent.
 */
data class Episode(
    val id: String,
    val title: String,
    val episodeNumber: Int,
    val seasonNumber: Int,
    val plot: String?,
    val durationMillis: Long?,
    val containerExtension: String?,
    val coverUrl: String?,
)

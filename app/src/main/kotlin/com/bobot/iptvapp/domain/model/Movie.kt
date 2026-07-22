package com.bobot.iptvapp.domain.model

/**
 * A video-on-demand movie (single playable file).
 *
 * Movies are the domain representation of [ContentType.MOVIE] content.
 * They appear in VOD category rows on the home screen and have a dedicated
 * detail screen showing metadata, a poster, and a play action.
 *
 * Sourced from: Xtream Codes `get_vod_streams` (list) and `get_vod_info`
 * (extended metadata) endpoints. Mapped from network DTOs in Task 6.
 * Persisted as Room entities in Task 10.
 *
 * Time fields use epoch-millisecond Long values throughout this model.
 * Rationale: epoch millis are trivially stored in Room INTEGER columns
 * without TypeConverters, are serialization-friendly, and support arithmetic
 * (e.g. progress percentage) without additional parsing.
 *
 * @property id                 Domain identifier — string form of Xtream `stream_id`.
 * @property title              Display title of the movie.
 * @property posterUrl          Remote URL of the movie poster / cover art. Null when
 *                              the stream info contains no cover image.
 * @property plot               Synopsis or description. Null when absent from the
 *                              Xtream extended metadata response.
 * @property categoryId         Foreign key to [Category.id].
 * @property rating             Content rating or audience score as returned by Xtream
 *                              (e.g. "7.5", "PG-13"). Null when the server omits it.
 *                              Stored as String to accommodate both numeric scores and
 *                              classification codes without loss of information.
 * @property year               Release year (e.g. 2023). Null when absent.
 * @property addedMillis        Epoch-millisecond timestamp of when the stream was added
 *                              to the Xtream server. Null when absent.
 * @property durationMillis     Total playback duration in milliseconds. Null when the
 *                              server does not return duration metadata.
 * @property containerExtension File container or extension (e.g. "mkv", "mp4"). Used
 *                              to construct the direct stream URL. Null when absent.
 * @property externalSubtitles  Best-effort external subtitle tracks (e.g. `.srt`
 *                              file links) advertised by some Xtream servers in the
 *                              `get_vod_info` response. Empty when the server does
 *                              not expose any (the common case). Transient: not
 *                              persisted to the Room cache.
 */
data class Movie(
    val id: String,
    val title: String,
    val posterUrl: String?,
    val plot: String?,
    val categoryId: String,
    val rating: String?,
    val year: Int?,
    val addedMillis: Long?,
    val durationMillis: Long?,
    val containerExtension: String?,
    val externalSubtitles: List<ExternalSubtitle> = emptyList(),
)

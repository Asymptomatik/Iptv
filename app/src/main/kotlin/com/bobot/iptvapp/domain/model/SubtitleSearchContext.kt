package com.bobot.iptvapp.domain.model

/**
 * What an online subtitle search needs to know about the VOD content being played.
 *
 * Built from the catalog metadata the user actually saw (never from the stream id or the Xtream
 * URL, which carry no title) and carried to the player through the
 * [com.bobot.iptvapp.navigation.Player] route. Every field past [title] is best-effort: Xtream
 * servers routinely omit the year, and an offline download only remembers its own title.
 *
 * Live channels never get one — there is nothing to search subtitles for.
 *
 * @property kind          Whether this describes a movie or a series episode.
 * @property title         Visible, prefix-stripped title of the content itself (the movie title,
 *                         or the episode title). Never blank.
 * @property year          Movie release year. Null when unknown; always null for episodes.
 * @property seriesTitle   Visible, prefix-stripped title of the parent series. Null for movies
 *                         and when the series is unknown.
 * @property seasonNumber  Season number, null when unknown (Xtream uses 0 for "missing").
 * @property episodeNumber Episode number within its season, null when unknown.
 */
data class SubtitleSearchContext(
    val kind: Kind,
    val title: String,
    val year: Int? = null,
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
) {
    enum class Kind { MOVIE, EPISODE }
}

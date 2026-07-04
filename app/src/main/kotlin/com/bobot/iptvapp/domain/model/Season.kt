package com.bobot.iptvapp.domain.model

/**
 * A numbered season of a [Series], containing an ordered list of [Episode]s.
 *
 * Seasons are intermediate nodes in the series hierarchy:
 * [Series] → [Season] → [Episode]. The series detail screen groups episodes
 * by season and allows the user to select a season before browsing episodes.
 *
 * Sourced from: Xtream Codes `get_series_info` response (`seasons` array).
 * Mapped from network DTOs in Task 6. Persisted as Room entities in Task 10.
 *
 * @property seasonNumber Number of this season within the parent series (typically 1-based).
 * @property name         Optional display name for the season (e.g. "Season 1" or a subtitle).
 *                        Null when the Xtream server omits the field.
 * @property coverUrl     Remote URL of the season poster or cover art. Null when absent.
 * @property episodes     Ordered list of episodes in this season, sorted by
 *                        [Episode.episodeNumber] ascending. Empty list when the server
 *                        returns a season record with no episode data.
 */
data class Season(
    val seasonNumber: Int,
    val name: String?,
    val coverUrl: String?,
    val episodes: List<Episode> = emptyList(),
)

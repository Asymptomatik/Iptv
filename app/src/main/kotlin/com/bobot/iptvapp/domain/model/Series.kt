package com.bobot.iptvapp.domain.model

/**
 * A multi-season TV series containing an ordered list of [Season]s.
 *
 * Series are the domain representation of [ContentType.SERIES] content.
 * They appear in the series category rows on the home screen and have a
 * dedicated detail screen that exposes season and episode navigation.
 *
 * The full season+episode tree is only loaded on demand (detail screen open)
 * via `get_series_info`. The list endpoint (`get_series`) returns metadata only;
 * [seasons] will be an empty list until the detail is fetched.
 *
 * Sourced from: Xtream Codes `get_series` (metadata list) and `get_series_info`
 * (full season/episode tree) endpoints. Mapped from network DTOs in Task 6.
 * Persisted as Room entities in Task 10.
 *
 * @property id         Domain identifier — string form of Xtream `series_id`.
 * @property title      Display title of the series.
 * @property coverUrl   Remote URL of the series poster / cover art. Null when absent.
 * @property plot       Synopsis or description. Null when absent.
 * @property categoryId Foreign key to [Category.id].
 * @property rating     Content rating or audience score (e.g. "8.1", "TV-MA").
 *                      Null when absent. Stored as String — see [Movie.rating].
 * @property year       Original release or premiere year. Null when absent.
 * @property seasons    Ordered list of [Season]s, sorted by [Season.seasonNumber]
 *                      ascending. Empty until the detail payload is fetched and mapped.
 */
data class Series(
    val id: String,
    val title: String,
    val coverUrl: String?,
    val plot: String?,
    val categoryId: String,
    val rating: String?,
    val year: Int?,
    val seasons: List<Season> = emptyList(),
)

package com.bobot.iptvapp.domain.util

import com.bobot.iptvapp.domain.model.Series

/**
 * The release year the Series tab's "Nouveautés" view sorts by, read without any detail call.
 *
 * ## Priority
 *  1. The year written in the **title**, read exactly like a film's ([MovieReleaseYear]). It wins
 *     over a contradicting list year: it is the one the user sees on the card, whereas
 *     `releaseDate` is often a re-upload or the latest season's date.
 *  2. Otherwise [Series.year] — `releaseDate` of the `get_series` **list** call — when it lies
 *     between [FIRST_SERIES_YEAR] and the caller's `latestPlausibleYear` (so `0` and `1900`
 *     placeholders are rejected). Only the year survives the mapper, so a `"1970-01-01"` epoch
 *     placeholder can't be told from a real 1970 series: both are kept.
 *
 * A season-scoped entry (`"The Crown S03 (2019)"`, `"Dark - Saison 2"`) has **no** year: what it
 * carries dates that season, not the series, so both sources are ignored and it sorts last. The
 * season word needs its number — "Wedding Season (2022)" is a series title.
 *
 * `last_modified` is never used: it says when the provider last touched the entry, not when the
 * series was added or released.
 */
object SeriesReleaseYear {

    /** Before regular TV series were broadcast; an older list year is a placeholder or a typo. */
    private const val FIRST_SERIES_YEAR = 1930

    /**
     * `S03`, `S02E03`, or a season word **followed by its number** (`Season 2`, `Saison 02`). The
     * bare word is part of real titles ("Wedding Season"), and a 4-digit number after it is a year.
     */
    private val SEASON_MARKER =
        Regex("(?i)\\b(?:S\\d{1,2}(?:E\\d{1,3})?|(?:saison|season|temporada|staffel)\\s*\\d{1,2})\\b")

    /** The year [series] sorts by, or `null` when there is none or it cannot be trusted. */
    fun of(series: Series, latestPlausibleYear: Int): Int? {
        if (SEASON_MARKER.containsMatchIn(series.title)) return null
        return MovieReleaseYear.fromTitle(series.title, latestPlausibleYear)
            ?: series.year?.takeIf { it in FIRST_SERIES_YEAR..latestPlausibleYear }
    }
}

/**
 * What [SeriesSort] orders a [Series] by. Built from the list snapshot only, so opening a series
 * (whose detail may carry another title or year) never moves it.
 */
data class SeriesSortKey(val seriesId: String, val releaseYear: Int?)

/** Sort projection of this series; an unknown year stays `null`. */
fun Series.sortKey(latestPlausibleYear: Int): SeriesSortKey =
    SeriesSortKey(seriesId = id, releaseYear = SeriesReleaseYear.of(this, latestPlausibleYear))

/**
 * Orders series for the "Nouveautés" view: release year descending, unknown years last, ties
 * broken by [Series.id] so the result does not depend on the order categories load in.
 */
object SeriesSort {

    /** The order of [SeriesSortKey]s — also what incremental merges must agree with. */
    val comparator: Comparator<SeriesSortKey> =
        compareBy<SeriesSortKey, Int?>(nullsLast(reverseOrder())) { it.releaseYear }
            .thenBy { it.seriesId }

    /** [series] in "Nouveautés" order; the same instances are returned. Each title is parsed once. */
    fun sort(series: List<Series>, latestPlausibleYear: Int): List<Series> =
        series
            .map { it to it.sortKey(latestPlausibleYear) }
            .sortedWith(compareBy(comparator) { it.second })
            .map { it.first }
}

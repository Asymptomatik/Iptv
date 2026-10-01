package com.bobot.iptvapp.domain.util

import com.bobot.iptvapp.domain.model.DownloadContentType
import com.bobot.iptvapp.domain.model.Episode
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.OfflineDownload
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.model.SubtitleSearchContext
import java.util.Calendar

// Builders for [SubtitleSearchContext]. Every title goes through [StreamTitle] so the search
// uses what the user saw on screen; a missing title yields `null` rather than a guessed one.

/**
 * Search context for this movie, or `null` when it has no visible title. The provider's `[TAG]`s
 * (see [PROVIDER_TAG]) and trailing year (`"Avatar [MULTI-SUB] - 2009"`) are dropped from the
 * title — the search API reads them as words of the name — and that year stands in for a missing
 * [Movie.year]. Brackets that are part of the film's name (`"[REC] 2"`) are kept.
 */
fun Movie.subtitleSearchContext(): SubtitleSearchContext? {
    val visibleTitle = displayTitle().takeIf { it.isNotBlank() } ?: return null
    val (title, titleYear) = movieSearchTitle(visibleTitle)
    return SubtitleSearchContext(
        kind = SubtitleSearchContext.Kind.MOVIE,
        title = title,
        year = year?.takeIf { it > 0 } ?: titleYear,
    )
}

private const val PROVIDER_TAG_WORDS =
    "MULTI|SUBS?|ST|STFR|VOSTFR|VOST|VOSTA|VFF|VFQ|VFI|VF2|VF|VO|FR|FRE|FRENCH|TRUEFRENCH|EN|ENG|" +
        "SD|HD|FHD|UHD|4K|8K|HDR10|HDR|DV|3D|\\d{3,4}[PI]|X26[45]|H26[45]|HEVC|AC3|DTS|ATMOS|" +
        "BLURAY|BDRIP|WEBRIP|WEB|DL|HDRIP|DVDRIP|REMUX|IMAX|EXTENDED|CAM|TS|LIGHT"

/**
 * A `[TAG]` made only of separated provider markers — language, version, quality, codec
 * (`[MULTI-SUB]`, `[VOSTFR]`, `[4K HDR]`). Brackets holding anything else belong to the film
 * (`[REC]`) and stay.
 */
private val PROVIDER_TAG = Regex(
    """\[\s*(?:$PROVIDER_TAG_WORDS)(?:[\s\-_./+]+(?:$PROVIDER_TAG_WORDS))*\s*]""",
    RegexOption.IGNORE_CASE,
)

/**
 * A trailing year after a real delimiter or in parentheses / brackets — never a bare one
 * (`Blade Runner 2049`). A number past next year is not a release year but part of the title
 * (`Blade Runner: 2049`), see [latestPlausibleYear].
 */
private val YEAR_SUFFIX = Regex("""^(.+?)\s*(?:[-|:]\s*[(\[]?|[(\[])((?:19|20)\d{2})[)\]]?$""")

/** A catalogue may list a film announced for next year; any later number is not a release year. */
private fun latestPlausibleYear(): Int = Calendar.getInstance().get(Calendar.YEAR) + 1

/** [visibleTitle] without provider `[TAG]`s and trailing year, and that year; [visibleTitle] as is if nothing is left. */
private fun movieSearchTitle(visibleTitle: String): Pair<String, Int?> {
    val untagged = visibleTitle.replace(PROVIDER_TAG, " ").replace(Regex("\\s+"), " ").trim()
    val match = YEAR_SUFFIX.matchEntire(untagged)
        ?.takeIf { it.groupValues[2].toInt() <= latestPlausibleYear() }
    val title = (match?.groupValues?.get(1) ?: untagged).trim().takeIf { it.isNotEmpty() }
        ?: return visibleTitle to null
    return title to match?.groupValues?.get(2)?.toInt()
}

/**
 * Search context for this episode of [series] (`null` when the series is unknown). The episode's
 * own title wins; the series title stands in for a blank one. `null` when neither is visible.
 */
fun Episode.subtitleSearchContext(series: Series?): SubtitleSearchContext? {
    val seriesTitle = series?.displayTitle()?.takeIf { it.isNotBlank() }
    val visibleTitle = StreamTitle.displayTitle(title).takeIf { it.isNotBlank() } ?: seriesTitle ?: return null
    return SubtitleSearchContext(
        kind = SubtitleSearchContext.Kind.EPISODE,
        title = visibleTitle,
        seriesTitle = seriesTitle,
        seasonNumber = seasonNumber.takeIf { it > 0 },
        episodeNumber = episodeNumber.takeIf { it > 0 },
    )
}

/**
 * Search context for an offline copy. A download only stores its own title, so a movie keeps only
 * the year its title spells out and an episode loses its series title and numbers — a known gap,
 * not something to reconstruct.
 */
fun OfflineDownload.subtitleSearchContext(): SubtitleSearchContext? {
    val visibleTitle = StreamTitle.displayTitle(title).takeIf { it.isNotBlank() } ?: return null
    return when (contentType) {
        DownloadContentType.MOVIE -> {
            val (movieTitle, titleYear) = movieSearchTitle(visibleTitle)
            SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = movieTitle, year = titleYear)
        }
        DownloadContentType.EPISODE -> SubtitleSearchContext(SubtitleSearchContext.Kind.EPISODE, title = visibleTitle)
    }
}

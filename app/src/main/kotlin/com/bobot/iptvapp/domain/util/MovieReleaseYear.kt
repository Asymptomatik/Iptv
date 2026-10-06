package com.bobot.iptvapp.domain.util

/**
 * Reads the release year Xtream providers append to VOD **titles** — `"Film (2025)"`,
 * `"Avatar [MULTI-SUB] - 2009"`. The VOD list call carries no year of its own (`Movie.year` is only
 * filled by the per-film detail call), so the title is the only source the "Sortie récente" sort
 * can use without one request per film.
 *
 * ## Contract: reject rather than guess
 * A missing year only sends a film to the end of the list; an invented one puts it in the wrong
 * place with nothing on screen to explain why. So a year is returned **only** when it is written as
 * a trailing vintage marker — `(YYYY)`, `[YYYY]` or ` - YYYY` — possibly followed by known release
 * decorations (`[MULTI-SUB]`, `1080p`, `4K`, `HDR`…), and when:
 *  - some real name is left once markers and decorations are peeled off (`"1917"`, `"FR - 2012"`
 *    and `"4K - 1917"` are films whose *name* may be the number);
 *  - every marker agrees (`"Avatar (2009) - 2010"` is contradictory);
 *  - the year lies between [FIRST_FILM_YEAR] and the caller's `latestPlausibleYear`.
 *
 * A bare number inside the name (`"Blade Runner 2049"`, `"Avatar 2009"`) is never read as a year,
 * and neither are glued forms (`"Film-2009"`, `"Film - 20091080p"`) or ranges (`"(2009-2012)"`).
 */
object MovieReleaseYear {

    /** Earliest surviving motion picture; anything older is a typo or not a year at all. */
    private const val FIRST_FILM_YEAR = 1888

    /** Upper bound on peeled markers/decorations, so a pathological title cannot loop for long. */
    private const val MAX_TRAILING_PARTS = 8

    private val SPACES = Regex("${CategoryLanguage.WS}+")
    private val DASHES = Regex("[\\u2010-\\u2015\\u2212]")

    private val BRACKETED_YEAR = Regex("^(.*?) ?(?:\\((\\d{4})\\)|\\[(\\d{4})])$")
    private val DASH_YEAR = Regex("^(.*\\S) - (\\d{4})$")

    /**
     * A trailing release decoration: a square-bracketed tag without digits, a resolution, or a
     * quality/codec/version word. It must stand alone (start of string or after a space).
     */
    private val DECORATION = Regex(
        "^(|.* )(?:\\[[^\\[\\]\\d]+]|\\d{3,4}[pPiI]|(?i:4K|8K|UHD|FHD|HD|SD|HDR|HDR10|DV|3D|HEVC|" +
            "x264|x265|H264|H265|MULTI|MULTI-SUB|VF|VFF|VFQ|VO|VOST|VOSTFR|FRENCH|TRUEFRENCH))$",
    )

    /** The year written in [rawTitle], or `null` when there is none or it cannot be trusted. */
    fun fromTitle(rawTitle: String, latestPlausibleYear: Int): Int? {
        val normalized = rawTitle.replace(SPACES, " ").replace(DASHES, "-").trim()
        var name = StreamTitle.displayTitle(normalized)
        val years = mutableSetOf<Int>()

        repeat(MAX_TRAILING_PARTS) {
            val bracketed = BRACKETED_YEAR.matchEntire(name)
            val dashed = DASH_YEAR.matchEntire(name)
            val decoration = DECORATION.matchEntire(name)
            name = when {
                bracketed != null -> {
                    years += bracketed.groupValues.drop(2).first { it.isNotEmpty() }.toInt()
                    bracketed.groupValues[1]
                }
                dashed != null -> {
                    years += dashed.groupValues[2].toInt()
                    dashed.groupValues[1]
                }
                decoration != null -> decoration.groupValues[1]
                else -> return@repeat
            }.trim()
        }

        if (name.isEmpty() || years.size != 1) return null
        return years.single().takeIf { it in FIRST_FILM_YEAR..latestPlausibleYear }
    }
}

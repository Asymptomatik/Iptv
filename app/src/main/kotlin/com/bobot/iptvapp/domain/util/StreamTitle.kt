package com.bobot.iptvapp.domain.util

import com.bobot.iptvapp.domain.model.Channel
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.Series

/**
 * Strips the language / version prefix providers put in front of **stream titles** —
 * `"FR - Bangkok Dangerous - 2008"` is shown as `"Bangkok Dangerous - 2008"` (QA finding N4).
 *
 * ## Why this is not [CategoryLanguage]
 * [CategoryLanguage.extractDisplayName] does the same job for *category* names, and the temptation
 * is to reuse it. It would be wrong here, because the two inputs carry very different risk:
 *
 *  - A category name is written by the provider from a small, formulaic vocabulary, so accepting
 *    **any** two-or-three-letter token before a delimiter is safe.
 *  - A title is the name of a real work. `"Up - 2009"`, `"It - Chapitre 2"` and `"Us - 2019"` all
 *    open with a two-letter token followed by ` - `, and that same permissive rule would render
 *    them as `"2009"`, `"Chapitre 2"` and `"2019"`.
 *
 * So titles get the stricter contract: the prefix is removed **only** when the token is in
 * [KNOWN_TITLE_PREFIXES] *and* a real delimiter (`|`, `:`, `-`) follows it. The bare-space form
 * that [CategoryLanguage] tolerates for categories is deliberately not accepted — for a title,
 * a space is just a space.
 *
 * ## What is deliberately left alone
 *  - **Quality markers** (`HD`, `FHD`, `4K`, `SD`). Bouquets routinely carry the same channel at
 *    several bitrates; stripping the marker would collapse `"FHD - TF1"` and `"SD - TF1"` into two
 *    rows that read identically and cannot be told apart.
 *  - **Country/region codes** (`US`, `UK`, `BE`, `CA`, `BR`, `CH`). Same reasoning as
 *    [CategoryLanguage]'s whitelist, and stronger here: `"US - Sports 1"` and `"UK - Sports 1"`
 *    are genuinely different channels, and the prefix is the only thing separating them.
 *  - **`IT`.** [CategoryLanguage] knowingly keeps it because an Italian category beats an
 *    IT-helpdesk one. For titles the balance flips — the film *It* exists and an Italian-language
 *    prefix in a French bouquet does not.
 */
object StreamTitle {

    /**
     * Language and version codes accepted as a title prefix, compared case-insensitively against
     * the uppercased first token. Kept intentionally short: everything outside this set is left
     * untouched, so a missing entry costs a cosmetic prefix, while a wrong entry mangles the name
     * of a real film. `VF`, `VO`, `VOST` and `VOSTFR` are not ISO codes but are the standard French
     * audiovisual version markers and appear as prefixes just as often as the languages do.
     */
    private val KNOWN_TITLE_PREFIXES = setOf(
        "FR", "FRA", "EN", "ENG", "AR", "ES", "SPA", "DE", "GER", "PT", "NL", "TR", "RU", "PL",
        "VF", "VO", "VOST", "VOSTFR",
    )

    /**
     * A leading 2-to-6-letter token, optional whitespace, one delimiter, optional whitespace, then
     * a non-empty remainder. The token is widened past [CategoryLanguage]'s `{2,3}` only to admit
     * `VOSTFR`; [KNOWN_TITLE_PREFIXES] is what actually decides, so the looser quantifier costs
     * nothing.
     */
    private val PREFIX_PATTERN =
        Regex("^([A-Za-z]{2,6})${CategoryLanguage.WS}*[|:-]${CategoryLanguage.WS}*(.+)$")

    /**
     * Upper bound on stacked prefixes. Providers do chain them (`"FR - VF - Le Titre"`), but two
     * is as deep as the test bouquet ever goes; the bound is what stops a pathological title from
     * being peeled down to nothing.
     */
    private const val MAX_PREFIXES = 3

    /**
     * [rawTitle] with its known language/version prefixes removed, or [rawTitle] itself — trimmed —
     * when it carries none. Never returns blank: a title that is *only* a prefix (`"FR -"` alone,
     * or `"FR - VF"`) keeps whatever the last non-blank step produced.
     */
    fun displayTitle(rawTitle: String): String {
        var current = rawTitle.trimUnicodeSpaces()

        repeat(MAX_PREFIXES) {
            val match = PREFIX_PATTERN.matchEntire(current) ?: return current
            val token = match.groupValues[1].uppercase()
            if (token !in KNOWN_TITLE_PREFIXES) return current

            val remainder = match.groupValues[2].trimUnicodeSpaces()
            if (remainder.isBlank()) return current
            current = remainder
        }

        return current
    }
}

/** User-facing channel name with known language/version prefixes stripped — see [StreamTitle]. */
fun Channel.displayName(): String = StreamTitle.displayTitle(name)

/** User-facing movie title with known language/version prefixes stripped — see [StreamTitle]. */
fun Movie.displayTitle(): String = StreamTitle.displayTitle(title)

/** User-facing series title with known language/version prefixes stripped — see [StreamTitle]. */
fun Series.displayTitle(): String = StreamTitle.displayTitle(title)

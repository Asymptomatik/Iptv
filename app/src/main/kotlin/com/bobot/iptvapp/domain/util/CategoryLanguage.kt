package com.bobot.iptvapp.domain.util

import com.bobot.iptvapp.domain.model.Category

/**
 * Heuristically extracts a language tag from an Xtream Codes catalog category name.
 *
 * Xtream catalog providers commonly prefix category names with a short, uppercase language
 * code followed by a delimiter, e.g. `"FR | Sport"`, `"EN - Movies"`, `"FR: Documentaires"`.
 * This is a convention, not a documented part of the Xtream Codes API contract, so extraction
 * is best-effort: it recognises the common shape and returns `null` for anything else rather
 * than guessing.
 *
 * Pure and framework-free on purpose so it can be unit-tested on the JVM without an Android
 * runtime, mirroring the testing approach already used for
 * [com.bobot.iptvapp.player.StreamTypeResolver]. This logic is deliberately centralised here
 * (rather than duplicated in `HomeViewModel` and `SearchViewModel`) to avoid the duplication
 * risk flagged for the language filter feature.
 *
 * The tag is always derived on the fly from [Category.name] — it is never persisted on the
 * domain model or Room entities.
 */
object CategoryLanguage {

    /**
     * Character class standing for "one whitespace character", used everywhere below in place of
     * the regex shorthand `\s`.
     *
     * Java's `\s` is ASCII-only (`[ \t\n\f\r]`) — unlike Python's, it does **not** match the
     * non-breaking space `U+00A0`, and neither does [Char.isWhitespace]. Real providers do emit
     * them: this project's reference catalogue contains `"EU<U+00A0>| FRANCE GENERAL"` categories,
     * which lost their tag entirely *and* had the raw `"EU<U+00A0>|"` prefix shown to users, because
     * every pattern below failed to match on that one character.
     *
     * `\p{Zs}` is the Unicode "space separator" category (`U+00A0`, `U+2000`–`U+200A`, `U+202F`,
     * `U+205F`, `U+3000`); the union with `\s` keeps the ASCII control whitespace (tab, newline, …)
     * that `\p{Zs}` does not itself include.
     */
    internal const val WS = "[\\s\\p{Zs}]"

    /** Negation of [WS] — the Unicode-aware counterpart of the ASCII-only `\S`. */
    internal const val NON_WS = "[^\\s\\p{Zs}]"

    /**
     * Matches a leading 2-3 letter alphabetic tag followed by one of the common delimiters
     * (`|`, `-`, `:`), with optional whitespace around the delimiter.
     *
     * Anchored at the start of the string ([Regex] `^`) so the tag must be the very first
     * token — this is what prevents false positives such as `"4K | Sport"` (starts with a
     * digit) or `"18+ Adult"` (starts with a digit) from ever reaching the letter-based
     * quantifier at all. A longer alphabetic word immediately followed by a delimiter, e.g.
     * `"SPORT - Something"`, also cannot match: the `{2,3}` quantifier only accepts 2 or 3
     * letters before requiring a delimiter (after optional whitespace), and `"SPORT"` has a
     * non-delimiter, non-whitespace character right after any 2- or 3-letter prefix, so the
     * whole match fails rather than truncating to `"SPO"`.
     */
    private val DIRECT_LANGUAGE_TAG_PATTERN = Regex("^([A-Za-z]{2,3})$WS*[|:-]$WS*(.+)$")
    private val NESTED_LANGUAGE_TAG_PATTERN =
        Regex("^([A-Za-z]{2,10})$WS*[|:-]$WS*([A-Za-z]{2,3})$WS*[|:-]$WS*(.+)$")

    /**
     * Matches a leading token followed by one or more plain whitespace characters and a
     * non-empty rest, e.g. `"FR Sport"`. Tried only as a last-resort fallback, after both
     * delimited patterns above have failed to match.
     *
     * Unlike `|`, `:` or `-`, a plain space is an entirely ordinary word separator, so this
     * regex shape alone cannot distinguish a real language prefix (`"FR Sport"`) from any other
     * two-word category name (`"HD Movies"`, `"TV Shows"`). It is therefore deliberately
     * unconstrained on the first token's length or content beyond "one or more non-space
     * characters" — [SPACE_PREFIX_WHITELIST] is what does the actual false-positive filtering:
     * the matched first token is only accepted as a language tag when it exactly equals one of
     * the known language/region codes in that closed list.
     */
    private val SPACE_LANGUAGE_TAG_PATTERN = Regex("^($NON_WS+)$WS+(.+)$")

    /**
     * Closed set of language codes recognised by [SPACE_LANGUAGE_TAG_PATTERN]. Compared
     * case-insensitively against the matched first token (uppercased). Any first token not in
     * this list is rejected, regardless of its length or shape, which is what prevents ordinary
     * two-word category names such as `"HD Movies"` or `"TV Shows"` from being misread as
     * carrying a language prefix.
     *
     * Deliberately **languages only** — the country/region codes `SP`, `UK`, `US`, `BR`, `CA`,
     * `BE` and `CH` are excluded here. A country is not a language, and behind a mere space the
     * signal is too weak to act on: `"US Sports"` would be shown as `"Sports"` filed under a
     * pseudo-language `US`, and every `"CA …"` category would collapse into one row on the
     * Films/Séries tabs, whose row key *is* the language tag. `SP` is not even the code for
     * Spanish (`ES` is) and just as plausibly abbreviates Sport or Special. The delimited
     * patterns are unaffected: `"UK | Sports"`, `"US - Movies"` and `"BR: Novelas"` are still
     * recognised, the explicit delimiter being a deliberate signal from the provider.
     *
     * `VF`, `VO` and `VOSTFR` are not ISO codes but are kept: they are strong French
     * audiovisual conventions that genuinely describe a version's language.
     *
     * `IT` is the one knowingly retained ambiguity (`"IT Support"` would be misread), Italian
     * being far likelier than an IT-helpdesk category in an IPTV catalogue.
     */
    private val SPACE_PREFIX_WHITELIST = setOf(
        "FR", "FRA", "EN", "ENG", "AR", "ES", "DE", "IT", "PT", "NL", "TR", "RU", "PL",
        "VF", "VO", "VOSTFR",
    )

    /**
     * [String.trim] widened to the Unicode space separators, for the same reason [WS] exists:
     * Kotlin's [String.trim] delegates to [Char.isWhitespace], which — like Java's `\s` — reports
     * `false` for the non-breaking spaces. Without this, a name such as `"<U+00A0>FR | Sport"` would
     * keep its leading `U+00A0`, and the `^` anchor of every pattern above would fail to match.
     */
    private fun String.trimSpaces(): String = trimUnicodeSpaces()

    /**
     * Extracts the language tag from [name], or `null` when no recognised pattern is found.
     *
     * The match is case-insensitive on the input, but the returned tag is always normalized
     * to uppercase (e.g. `"fr | sport"` and `"FR | Sport"` both return `"FR"`).
     */
    fun extractLanguageTag(name: String): String? {
        val trimmed = name.trimSpaces()
        if (trimmed.isBlank()) return null

        NESTED_LANGUAGE_TAG_PATTERN.matchEntire(trimmed)?.let { match ->
            return match.groupValues[2].uppercase()
        }

        DIRECT_LANGUAGE_TAG_PATTERN.matchEntire(trimmed)?.let { match ->
            return match.groupValues[1].uppercase()
        }

        SPACE_LANGUAGE_TAG_PATTERN.matchEntire(trimmed)?.let { match ->
            val candidate = match.groupValues[1].uppercase()
            if (candidate in SPACE_PREFIX_WHITELIST) return candidate
        }

        return null
    }

    /**
     * Returns a user-facing category label with provider/language prefixes removed when recognised.
     *
     * Examples:
     *  - `"SRS | FR - LATEST SERIES"` -> `"LATEST SERIES"`
     *  - `"FR | Sport"` -> `"Sport"`
     *  - unrecognised names are returned trimmed as-is.
     */
    fun extractDisplayName(name: String): String {
        val trimmed = name.trimSpaces()
        if (trimmed.isBlank()) return trimmed

        NESTED_LANGUAGE_TAG_PATTERN.matchEntire(trimmed)?.let { match ->
            return match.groupValues[3].trimSpaces()
        }

        DIRECT_LANGUAGE_TAG_PATTERN.matchEntire(trimmed)?.let { match ->
            return match.groupValues[2].trimSpaces()
        }

        SPACE_LANGUAGE_TAG_PATTERN.matchEntire(trimmed)?.let { match ->
            val candidate = match.groupValues[1].uppercase()
            if (candidate in SPACE_PREFIX_WHITELIST) return match.groupValues[2].trimSpaces()
        }

        return trimmed
    }
}

/**
 * Trims leading and trailing whitespace **including** the Unicode space separators
 * ([CharCategory.SPACE_SEPARATOR], e.g. the non-breaking space) that providers sprinkle around
 * their prefixes and that `String.trim()` alone leaves in place.
 *
 * Shared with [StreamTitle], which strips the same family of prefixes off stream titles rather
 * than off category names.
 */
internal fun String.trimUnicodeSpaces(): String =
    trim { it.isWhitespace() || it.category == CharCategory.SPACE_SEPARATOR }

/**
 * Convenience accessor for [CategoryLanguage.extractLanguageTag] applied to this category's
 * [Category.name].
 */
fun Category.languageTag(): String? = CategoryLanguage.extractLanguageTag(name)

/** User-facing category label with known provider/language prefixes stripped. */
fun Category.displayName(): String = CategoryLanguage.extractDisplayName(name)

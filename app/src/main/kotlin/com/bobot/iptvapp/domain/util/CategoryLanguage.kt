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
    private val LANGUAGE_TAG_PATTERN = Regex("^([A-Za-z]{2,3})\\s*[|:-]\\s*\\S")

    /**
     * Extracts the language tag from [name], or `null` when no recognised pattern is found.
     *
     * The match is case-insensitive on the input, but the returned tag is always normalized
     * to uppercase (e.g. `"fr | sport"` and `"FR | Sport"` both return `"FR"`).
     */
    fun extractLanguageTag(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return null

        val match = LANGUAGE_TAG_PATTERN.find(trimmed) ?: return null
        return match.groupValues[1].uppercase()
    }
}

/**
 * Convenience accessor for [CategoryLanguage.extractLanguageTag] applied to this category's
 * [Category.name].
 */
fun Category.languageTag(): String? = CategoryLanguage.extractLanguageTag(name)

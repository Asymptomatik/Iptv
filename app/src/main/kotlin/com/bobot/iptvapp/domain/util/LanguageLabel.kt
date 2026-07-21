package com.bobot.iptvapp.domain.util

import java.util.Locale

/**
 * Maps an ISO 639 language code — as reported by container/renderer track metadata (e.g.
 * Media3 `Format.language`: `"fra"`, `"en"`, `"pt-BR"`, occasionally `"und"` for "undetermined")
 * — to a human-readable display name in a given [Locale].
 *
 * Pure and framework-free on purpose (only `java.util.Locale`, no Android/Media3 import) so it
 * is unit-testable on the JVM, mirroring the testing approach already used for
 * [CategoryLanguage] and [com.bobot.iptvapp.player.StreamTypeResolver]. Placed in `domain/util`
 * (rather than the `player` package) so it can be reused as-is by the player layer (building
 * `PlayerTrack` labels, Task 2) and by future UI/ViewModel code (Task 4/5) without either side
 * depending on the other.
 */
object LanguageLabel {

    /** Xtream/Media3 convention for "no determinable language" — never worth displaying. */
    private const val UNDETERMINED_CODE = "und"

    /**
     * Maps common ISO 639-2 (three-letter) language codes to their ISO 639-1 (two-letter)
     * equivalent, applied to the *language subtag only* before the [Locale] lookup in [forCode].
     *
     * Real-world justification (assumption, not verified against a live JVM — no JDK available
     * in the environment this map was authored in; see [forCode]'s KDoc for the reasoning this
     * is based on): the JDK's bundled CLDR-derived locale display-name data is keyed primarily by
     * two-letter ISO 639-1 codes. [Locale.getDisplayLanguage] is documented to silently echo back
     * a language subtag it cannot resolve to a readable name (rather than throwing or returning
     * blank), so without this map a three-letter code like `"fra"` — which IPTV/Xtream feeds
     * commonly report (ISO 639-2/T) alongside two-letter codes — could surface to users as the
     * raw string `"fra"` instead of a readable name ("French"/"français"). Applying the alias
     * defensively before the lookup, for the handful of languages this feature's feeds are
     * realistically expected to report, costs nothing when it turns out not to be needed (an
     * already-resolvable two-letter code, e.g. the `"pt"` in `"pt-BR"`, is simply not in this map
     * and passes through unchanged) but fixes the common case if it is.
     *
     * Deliberately not exhaustive — covers ISO 639-2/T and ISO 639-2/B (bibliographic) codes for
     * the languages most likely to appear in IPTV/Xtream track metadata.
     */
    private val ISO_639_2_TO_1 = mapOf(
        "fra" to "fr", // French (639-2/T)
        "eng" to "en", // English
        "deu" to "de", // German (639-2/T)
        "ger" to "de", // German (639-2/B)
        "spa" to "es", // Spanish
        "ita" to "it", // Italian
        "por" to "pt", // Portuguese
        "rus" to "ru", // Russian
        "ara" to "ar", // Arabic
        "jpn" to "ja", // Japanese
        "zho" to "zh", // Chinese (639-2/T)
        "chi" to "zh", // Chinese (639-2/B)
        "nld" to "nl", // Dutch (639-2/T)
        "dut" to "nl", // Dutch (639-2/B)
    )

    /**
     * Returns a human-readable display name for [languageCode] in [displayLocale], or `null`
     * when [languageCode] itself is `null`, blank, or the ISO "undetermined" placeholder
     * (`"und"`).
     *
     * Falls back to the raw (trimmed) [languageCode] when [Locale]'s language-tag machinery
     * cannot resolve a readable name for it (e.g. an unrecognised or malformed code) — this
     * never throws, regardless of input.
     */
    fun forCode(languageCode: String?, displayLocale: Locale = Locale.getDefault()): String? {
        val trimmed = languageCode?.trim()
        if (trimmed.isNullOrBlank() || trimmed.equals(UNDETERMINED_CODE, ignoreCase = true)) {
            return null
        }

        val languageTag = withIso6391Alias(trimmed)
        val locale = runCatching { Locale.forLanguageTag(languageTag) }.getOrNull()
        val resolved = locale?.getDisplayLanguage(displayLocale)

        // Locale.getDisplayLanguage() silently falls back to echoing the parsed language
        // subtag when it can't resolve a readable name, rather than throwing or returning
        // null/blank — treat that echo as "unmappable" too, and fall back to the raw
        // (full, possibly composite, e.g. "pt-BR") code ourselves.
        return resolved
            ?.takeIf { it.isNotBlank() && !it.equals(locale?.language, ignoreCase = true) }
            ?: trimmed
    }

    /**
     * Substitutes [tag]'s leading language subtag with its ISO 639-1 equivalent when it is a
     * known [ISO_639_2_TO_1] alias, preserving any trailing subtags (region, script, …)
     * unchanged — e.g. `"fra-BE"` -> `"fr-BE"`. Returns [tag] unchanged when its language subtag
     * isn't a known alias (already covers two-letter codes, including composite ones like
     * `"pt-BR"`, since only the unmapped `"pt"` subtag is looked up and not found).
     */
    private fun withIso6391Alias(tag: String): String {
        val separatorIndex = tag.indexOf('-')
        val languageSubtag = if (separatorIndex == -1) tag else tag.substring(0, separatorIndex)
        val alias = ISO_639_2_TO_1[languageSubtag.lowercase()] ?: return tag
        val rest = if (separatorIndex == -1) "" else tag.substring(separatorIndex)
        return alias + rest
    }
}

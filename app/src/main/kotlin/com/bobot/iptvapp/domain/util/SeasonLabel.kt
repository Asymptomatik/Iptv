package com.bobot.iptvapp.domain.util

import com.bobot.iptvapp.domain.model.Season

/**
 * Picks the label shown on a season chip (QA finding N6).
 *
 * [Season.name] comes straight from the Xtream `get_series_info` payload, and for the overwhelming
 * majority of series it is not a name at all — it is the word "Season" (or the provider's own
 * mistranslation, "Series") followed by the number the chip is already about to display. The screen
 * used to prefer it whenever it was non-blank, which is how a French UI ended up showing
 * "Series 1".
 *
 * So the provider name is kept **only when it says something the number does not**: a real subtitle
 * such as `"Le Trône de fer"` or `"Partie finale"`. Anything that is just a season word, just a
 * number, or the two together falls back to the localized `"Saison N"`.
 */
object SeasonLabel {

    /**
     * Words providers use for "season", in the languages this catalog actually serves, plus the
     * English `series` that the Xtream panels emit. Matched case-insensitively and only when the
     * whole name is that word (optionally followed by a number), so a season genuinely subtitled
     * `"Season of the Witch"` is left alone.
     */
    private val GENERIC_SEASON_NAME = Regex(
        "^(?:season|series|saison|temporada|stagione|staffel|seizoen|sezon)?${CategoryLanguage.WS}*0*\\d*$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Label for season [seasonNumber] of a series whose provider-supplied name is [providerName]:
     * that name when it carries a real subtitle, `"Saison N"` otherwise.
     */
    fun forSeason(seasonNumber: Int, providerName: String?): String {
        val trimmed = providerName?.trimUnicodeSpaces().orEmpty()
        return if (trimmed.isEmpty() || GENERIC_SEASON_NAME.matches(trimmed)) {
            "Saison $seasonNumber"
        } else {
            trimmed
        }
    }
}

/** Label for this season's chip — see [SeasonLabel]. */
fun Season.displayLabel(): String = SeasonLabel.forSeason(seasonNumber, name)

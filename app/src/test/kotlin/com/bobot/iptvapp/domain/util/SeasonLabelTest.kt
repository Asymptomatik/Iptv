package com.bobot.iptvapp.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [SeasonLabel] — QA finding N6 ("Series 1" shown instead of "Saison 1"). */
class SeasonLabelTest {

    @Test
    fun `falls back to the localized label when the provider omits the name`() {
        assertEquals("Saison 1", SeasonLabel.forSeason(1, null))
        assertEquals("Saison 2", SeasonLabel.forSeason(2, ""))
        assertEquals("Saison 3", SeasonLabel.forSeason(3, "   "))
    }

    @Test
    fun `replaces the generic season wordings providers emit`() {
        // The exact shape the recette reported.
        assertEquals("Saison 1", SeasonLabel.forSeason(1, "Series 1"))
        assertEquals("Saison 1", SeasonLabel.forSeason(1, "Season 1"))
        assertEquals("Saison 4", SeasonLabel.forSeason(4, "SEASON 04"))
        assertEquals("Saison 2", SeasonLabel.forSeason(2, "Temporada 2"))
        assertEquals("Saison 5", SeasonLabel.forSeason(5, "Staffel"))
    }

    @Test
    fun `replaces a name that is only a number`() {
        assertEquals("Saison 7", SeasonLabel.forSeason(7, "7"))
    }

    @Test
    fun `normalizes a name that already reads correctly`() {
        assertEquals("Saison 1", SeasonLabel.forSeason(1, "Saison 1"))
    }

    @Test
    fun `keeps a real subtitle`() {
        assertEquals("Le Trône de fer", SeasonLabel.forSeason(1, "Le Trône de fer"))
        // "season" as the first word of a genuine title must survive: the generic rule only fires
        // when the whole name is the season word plus a number.
        assertEquals("Season of the Witch", SeasonLabel.forSeason(1, "Season of the Witch"))
    }

    @Test
    fun `trims a subtitle it keeps`() {
        assertEquals("Partie finale", SeasonLabel.forSeason(9, "  Partie finale  "))
    }
}

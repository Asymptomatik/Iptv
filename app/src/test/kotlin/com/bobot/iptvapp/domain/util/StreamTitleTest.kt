package com.bobot.iptvapp.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [StreamTitle] — the title-side counterpart of [CategoryLanguageTest].
 *
 * The bulk of these cases are about what the stripper must *not* touch. That asymmetry is the
 * point: a missed prefix is cosmetic, whereas a wrongly stripped one renames a film.
 */
class StreamTitleTest {

    // ── Prefixes that must be stripped ───────────────────────────────────────────────────────

    @Test
    fun `strips a dash-delimited language prefix`() {
        assertEquals("Bangkok Dangerous - 2008", StreamTitle.displayTitle("FR - Bangkok Dangerous - 2008"))
    }

    @Test
    fun `strips pipe and colon delimiters too`() {
        assertEquals("TF1", StreamTitle.displayTitle("FR| TF1"))
        assertEquals("Al Jazeera", StreamTitle.displayTitle("AR: Al Jazeera"))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals("Le Titre", StreamTitle.displayTitle("fr - Le Titre"))
    }

    @Test
    fun `strips version markers`() {
        assertEquals("Le Titre", StreamTitle.displayTitle("VOSTFR - Le Titre"))
        assertEquals("Le Titre", StreamTitle.displayTitle("VF - Le Titre"))
    }

    @Test
    fun `strips stacked prefixes`() {
        assertEquals("Le Titre", StreamTitle.displayTitle("FR - VF - Le Titre"))
    }

    @Test
    fun `tolerates non-breaking spaces around the prefix`() {
        assertEquals("TF1", StreamTitle.displayTitle(" FR - TF1 "))
    }

    // ── Titles that must survive untouched ───────────────────────────────────────────────────

    @Test
    fun `keeps a two-letter film title that is not a language code`() {
        // The whole reason this class is not CategoryLanguage: these three would otherwise be
        // rendered as "2009", "Chapitre 2" and "2019".
        assertEquals("Up - 2009", StreamTitle.displayTitle("Up - 2009"))
        assertEquals("It - Chapitre 2", StreamTitle.displayTitle("It - Chapitre 2"))
        assertEquals("Us - 2019", StreamTitle.displayTitle("Us - 2019"))
    }

    @Test
    fun `keeps quality markers so bitrate variants stay distinguishable`() {
        assertEquals("FHD - TF1", StreamTitle.displayTitle("FHD - TF1"))
        assertEquals("SD - TF1", StreamTitle.displayTitle("SD - TF1"))
        assertEquals("4K - TF1", StreamTitle.displayTitle("4K - TF1"))
    }

    @Test
    fun `keeps country prefixes that distinguish two real channels`() {
        assertEquals("US - Sports 1", StreamTitle.displayTitle("US - Sports 1"))
        assertEquals("UK - Sports 1", StreamTitle.displayTitle("UK - Sports 1"))
    }

    @Test
    fun `does not strip across a bare space`() {
        // No delimiter, so nothing is a prefix — unlike category names, where CategoryLanguage
        // does accept this shape.
        assertEquals("FR Rugby", StreamTitle.displayTitle("FR Rugby"))
    }

    @Test
    fun `leaves a title with no prefix alone apart from trimming`() {
        assertEquals("Bangkok Dangerous", StreamTitle.displayTitle("  Bangkok Dangerous  "))
    }

    @Test
    fun `never strips a title down to nothing`() {
        assertEquals("FR -", StreamTitle.displayTitle("FR -"))
        assertEquals("VF", StreamTitle.displayTitle("FR - VF"))
    }

    @Test
    fun `stops after the bounded number of prefixes`() {
        // Four stacked prefixes: the first three go, the fourth is left in place rather than
        // looping until the title is empty.
        assertEquals("VO - Le Titre", StreamTitle.displayTitle("FR - EN - VF - VO - Le Titre"))
    }
}

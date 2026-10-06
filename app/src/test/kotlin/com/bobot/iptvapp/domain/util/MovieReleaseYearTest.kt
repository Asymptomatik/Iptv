package com.bobot.iptvapp.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [MovieReleaseYear].
 *
 * As with [StreamTitleTest], most cases pin down what must be *rejected*: a missing year only
 * sends a film to the end of the "Sortie récente" list, whereas an invented one puts it in the
 * wrong place with nothing on screen to explain why.
 */
class MovieReleaseYearTest {

    /** Fixed so the plausibility bound does not drift with the machine clock. */
    private val latest = 2027

    private fun yearOf(title: String): Int? = MovieReleaseYear.fromTitle(title, latestPlausibleYear = latest)

    // ── Years that must be recognised ────────────────────────────────────────────────────────

    @Test
    fun `reads a parenthesised year`() {
        assertEquals(2025, yearOf("Film (2025)"))
        assertEquals(2009, yearOf("Avatar (2009)"))
    }

    @Test
    fun `reads a bracketed year`() {
        assertEquals(2009, yearOf("Avatar [2009]"))
    }

    @Test
    fun `reads a trailing dash year`() {
        assertEquals(2008, yearOf("Bangkok Dangerous - 2008"))
    }

    @Test
    fun `reads a dash year behind a decoration`() {
        assertEquals(2009, yearOf("Avatar [MULTI-SUB] - 2009"))
    }

    @Test
    fun `reads a year followed by decorations`() {
        assertEquals(2009, yearOf("Avatar - 2009 [MULTI-SUB]"))
        assertEquals(2009, yearOf("Avatar (2009) [MULTI-SUB] 1080p"))
        assertEquals(2009, yearOf("Avatar - 2009 4K HDR"))
    }

    @Test
    fun `reads the year through a language prefix`() {
        assertEquals(2008, yearOf("FR - Bangkok Dangerous - 2008"))
    }

    @Test
    fun `accepts the same year written twice`() {
        assertEquals(2009, yearOf("Avatar (2009) - 2009"))
    }

    @Test
    fun `a sequel number or a year-like title does not hide the real year`() {
        assertEquals(2019, yearOf("1917 - 2019"))
        assertEquals(2017, yearOf("Blade Runner 2049 (2017)"))
        assertEquals(2010, yearOf("Toy Story 3 (2010)"))
    }

    @Test
    fun `tolerates non-breaking spaces and en dashes`() {
        assertEquals(2009, yearOf("Avatar – 2009"))
    }

    // ── Numbers that must not become a year ──────────────────────────────────────────────────

    @Test
    fun `ignores resolution markers`() {
        assertNull(yearOf("Avatar 1080p"))
        assertNull(yearOf("Avatar (2160p)"))
        assertNull(yearOf("Avatar - 1080"))
    }

    @Test
    fun `ignores a bare number inside the title`() {
        assertNull(yearOf("Blade Runner 2049"))
        assertNull(yearOf("Saison 2024"))
        assertNull(yearOf("Avatar 2009"))
    }

    @Test
    fun `a title that is only a year is ambiguous`() {
        // "1917" and "2012" are films: nothing says whether the number is the name or the vintage.
        assertNull(yearOf("1917"))
        assertNull(yearOf("FR - 2012"))
        assertNull(yearOf("(2009)"))
        assertNull(yearOf("4K - 1917"))
    }

    @Test
    fun `rejects contradictory years`() {
        assertNull(yearOf("Avatar (2009) - 2010"))
        assertNull(yearOf("Avatar (2009) (2010)"))
        assertNull(yearOf("Avatar - 2009 (2010)"))
    }

    @Test
    fun `rejects implausible years`() {
        assertNull(yearOf("Film (1850)"))
        assertNull(yearOf("Film (2049)"))
        assertEquals(2027, yearOf("Film (2027)"))
        assertNull(yearOf("Film (2028)"))
    }

    @Test
    fun `rejects ranges and glued digits`() {
        assertNull(yearOf("Série (2009-2012)"))
        assertNull(yearOf("Film - 20091"))
        assertNull(yearOf("Film - 20091080p"))
        assertNull(yearOf("Film-2009"))
    }

    @Test
    fun `rejects a dash year followed by unknown text`() {
        // Not a trailing vintage any more — "- 2012 Le retour" reads as part of the name.
        assertNull(yearOf("Film - 2012 Le retour"))
    }

    @Test
    fun `returns null when nothing looks like a year`() {
        assertNull(yearOf("Avatar"))
        assertNull(yearOf(""))
        assertNull(yearOf("   "))
    }
}

package com.bobot.iptvapp.domain.util

import com.bobot.iptvapp.domain.model.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Unit tests for [SeriesReleaseYear] and [SeriesSort] — the Series tab's "Nouveautés" order. */
class SeriesSortTest {

    private val latest = 2027

    private fun series(id: String, title: String, year: Int? = null, categoryId: String = "20") = Series(
        id = id,
        title = title,
        coverUrl = null,
        plot = null,
        categoryId = categoryId,
        rating = null,
        year = year,
    )

    private fun yearOf(title: String, listYear: Int? = null) =
        SeriesReleaseYear.of(series("s", title, listYear), latest)

    // ── SeriesReleaseYear ────────────────────────────────────────────────────

    @Test
    fun `the title year is read like a film's`() {
        assertEquals(2008, yearOf("Breaking Bad (2008)"))
        assertEquals(2017, yearOf("FR - La Casa de Papel - 2017"))
        assertEquals(2019, yearOf("The Boys [2019] [MULTI-SUB]"))
    }

    @Test
    fun `the list year is the fallback when the title has none`() {
        assertEquals(2016, yearOf("Stranger Things", listYear = 2016))
        assertNull(yearOf("Stranger Things"))
    }

    @Test
    fun `the title year wins over a contradicting list year`() {
        // The year the provider wrote on the poster title is the one the user sees; releaseDate is
        // often a later re-upload or the latest season's date.
        assertEquals(2005, yearOf("Prison Break (2005)", listYear = 2017))
    }

    @Test
    fun `an implausible list year is ignored`() {
        // Placeholders and typos: before regular TV series existed, or in the future.
        assertNull(yearOf("Sans Date", listYear = 0))
        assertNull(yearOf("Sans Date", listYear = 1900))
        assertNull(yearOf("Sans Date", listYear = 1929))
        assertNull(yearOf("Sans Date", listYear = latest + 1))
        assertEquals(1930, yearOf("Ancienne", listYear = 1930))
        assertEquals(latest, yearOf("Annoncee", listYear = latest))
    }

    @Test
    fun `a season-scoped entry has no release year`() {
        // The year on a "season" entry dates that season, not the series: rejected, from the title
        // and from the list metadata alike.
        assertNull(yearOf("The Crown S03 (2019)", listYear = 2019))
        assertNull(yearOf("Dark - Saison 2 - 2019", listYear = 2017))
        assertNull(yearOf("Narcos Season 3 (2017)"))
        assertNull(yearOf("Lupin S2", listYear = 2021))
        assertNull(yearOf("Elite Temporada 4 (2021)"))
        assertNull(yearOf("Dark Staffel 3 (2020)"))
    }

    @Test
    fun `a season marker needs its number`() {
        assertNull(yearOf("Dark Season 2 (2019)", listYear = 2017))
        assertNull(yearOf("Dark - Saison 2", listYear = 2017))
        assertNull(yearOf("Dark Saison 02 (2019)"))
        assertNull(yearOf("Dark Season2 (2019)"))
        assertNull(yearOf("Dark S02E03", listYear = 2017))
        assertNull(yearOf("Dark S02", listYear = 2017))
    }

    @Test
    fun `a bare season word is part of a real title`() {
        // "Wedding Season" is the name of a 2022 series, not a season of one.
        assertEquals(2022, yearOf("Wedding Season (2022)", listYear = 2022))
        assertEquals(2022, yearOf("Wedding Season", listYear = 2022))
        // A 4-digit number after the word is a year, not a season number.
        assertEquals(2022, yearOf("Wedding Season 2022", listYear = 2022))
        assertEquals(2015, yearOf("Hunting Season (2015)"))
        assertEquals(2014, yearOf("La Saison des Amours", listYear = 2014))
        assertEquals(2023, yearOf("Temporada de Huracanes (2023)"))
        assertEquals(2018, yearOf("Staffel Run", listYear = 2018))
    }

    @Test
    fun `a 1970 list year is a real year`() {
        // Only implausible years are dropped; 1970 is not singled out as a placeholder.
        assertEquals(1970, yearOf("Sans Date", listYear = 1970))
    }

    @Test
    fun `a word merely starting like a season marker is not one`() {
        assertEquals(2010, yearOf("Seasons of Love (2010)"))
        assertEquals(2015, yearOf("Sense8 (2015)"))
        assertEquals(2015, yearOf("Sense8", listYear = 2015))
    }

    @Test
    fun `a bare number inside the name is never the year`() {
        assertNull(yearOf("Station 19"))
        assertEquals(2018, yearOf("Station 19", listYear = 2018))
    }

    // ── SeriesSort ───────────────────────────────────────────────────────────

    @Test
    fun `newest release year first, unknown years last, ties by id`() {
        val old = series("3", "Ancienne (2001)")
        val newB = series("b", "Nouvelle B (2024)")
        val newA = series("a", "Nouvelle A", year = 2024)
        val unknown2 = series("z", "Sans Annee")
        val unknown1 = series("y", "Sans Annee Non Plus")
        val mid = series("m", "Milieu", year = 2012)

        val sorted = SeriesSort.sort(listOf(unknown2, old, newB, mid, unknown1, newA), latestPlausibleYear = latest)

        assertEquals(listOf("a", "b", "m", "3", "y", "z"), sorted.map { it.id })
    }

    @Test
    fun `the order does not depend on the input order`() {
        val all = (1..40).map { series("s$it", "Serie $it", year = if (it % 3 == 0) null else 2000 + it % 7) }
        val expected = SeriesSort.sort(all, latest).map { it.id }

        assertEquals(expected, SeriesSort.sort(all.reversed(), latest).map { it.id })
        assertEquals(expected, SeriesSort.sort(all.shuffled(kotlin.random.Random(7)), latest).map { it.id })
    }

    @Test
    fun `sorting returns the same instances, categories untouched`() {
        val a = series("a", "A (2020)", categoryId = "30")
        val b = series("b", "B (2021)", categoryId = "31")

        val sorted = SeriesSort.sort(listOf(a, b), latest)

        assertSame(b, sorted[0])
        assertSame(a, sorted[1])
        assertEquals("30", sorted[1].categoryId)
    }

    @Test
    fun `the detail year never enters the sort key, only the list one`() {
        // Same series as listed, then as re-read after its detail: the key is built from what the
        // caller hands in, so HomeViewModel's list snapshot is the only input.
        val listed = series("s", "Sans Annee", year = 2010)
        assertEquals(SeriesSortKey("s", 2010), listed.sortKey(latest))
    }
}

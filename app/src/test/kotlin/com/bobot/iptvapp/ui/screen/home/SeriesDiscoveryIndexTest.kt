package com.bobot.iptvapp.ui.screen.home

import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.util.SeriesSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SeriesDiscoveryIndex], the per-category index behind the Series tab's
 * "Nouveautés" view. Like [MovieDiscoveryIndexTest], work is measured through the index's counters
 * rather than time.
 */
class SeriesDiscoveryIndexTest {

    private fun series(id: String, title: String, categoryId: String, year: Int? = null) = Series(
        id = id,
        title = title,
        coverUrl = null,
        plot = null,
        categoryId = categoryId,
        rating = null,
        year = year,
    )

    private fun index() = SeriesDiscoveryIndex(latestPlausibleYear = 2027) { series ->
        HomeCardItem(id = series.id, title = series.title, imageUrl = null, contentType = ContentType.SERIES)
    }

    private val catalog = listOf(
        series("a", "Ancienne (2009)", "fr1"),
        series("b", "Sans Annee", "fr1"),
        series("c", "Recente (2025)", "fr2"),
        series("d", "Recente Bis", "fr2", year = 2025),
        series("e", "Moyenne - 2024", "fr2"),
        series("f", "English (2026)", "en"),
    )

    private fun SeriesDiscoveryIndex.ids(categories: List<String>, limit: Int = 60) =
        page(categories, limit).items.map { it.id }

    @Test
    fun `merges every selected category by release year, ties by id, unknown years last`() {
        val index = index().apply { ingest(catalog) }

        assertEquals(listOf("c", "d", "e", "a", "b"), index.ids(listOf("fr1", "fr2")))
    }

    @Test
    fun `only the requested categories contribute`() {
        val index = index().apply { ingest(catalog) }

        assertEquals(listOf("f"), index.ids(listOf("en")))
        assertEquals(listOf("f", "c", "d", "e", "a", "b"), index.ids(listOf("fr1", "fr2", "en")))
    }

    @Test
    fun `a series listed in several categories appears once`() {
        val index = index().apply {
            ingest(catalog + series("c", "Recente (2025)", "fr1") + series("c", "Recente (2025)", "en"))
        }

        assertEquals(listOf("f", "c", "d", "e", "a", "b"), index.ids(listOf("fr1", "fr2", "en")))
    }

    @Test
    fun `the merge agrees with a full sort of the selected series`() {
        val all = (0 until 500).map {
            series("s$it", if (it % 4 == 0) "Serie $it" else "Serie $it (${1990 + it % 35})", "c${it % 7}", year = if (it % 8 == 0) 2001 else null)
        }
        val index = index().apply { ingest(all) }
        val categories = listOf("c1", "c3", "c4", "c6")

        val expected = SeriesSort.sort(all.filter { it.categoryId in categories }, latestPlausibleYear = 2027).map { it.id }
        assertEquals(expected.take(120), index.ids(categories, limit = 120))
    }

    @Test
    fun `pages are bounded by the limit and report whether more series follow`() {
        val index = index().apply { ingest(catalog) }

        val first = index.page(listOf("fr1", "fr2"), limit = 2)
        assertEquals(listOf("c", "d"), first.items.map { it.id })
        assertTrue(first.hasMore)

        val all = index.page(listOf("fr1", "fr2"), limit = 5)
        assertEquals(5, all.items.size)
        assertFalse(all.hasMore)
    }

    @Test
    fun `a bigger page extends the previous one and reuses its cards`() {
        val index = index().apply { ingest(catalog) }
        val first = index.page(listOf("fr1", "fr2"), limit = 2)

        val second = index.page(listOf("fr1", "fr2"), limit = 4)

        assertEquals(first.items, second.items.take(2))
        assertSame(first.items[0], second.items[0])
        assertEquals(4, index.cardsBuilt)
    }

    @Test
    fun `categories arriving one at a time slot into the order without rebuilding older slices`() {
        val index = index()
        val accumulated = ArrayList<Series>()

        accumulated += catalog.filter { it.categoryId == "fr1" }
        index.ingest(accumulated.toList())
        assertEquals(listOf("a", "b"), index.ids(listOf("fr1", "fr2")))

        accumulated += catalog.filter { it.categoryId == "fr2" }
        index.ingest(accumulated.toList())
        assertEquals(listOf("c", "d", "e", "a", "b"), index.ids(listOf("fr1", "fr2")))

        // fr1's two titles were parsed and sorted once, fr2's three once: never the whole list again.
        assertEquals(5, index.keysBuilt)
        assertEquals(2, index.slicesSorted)
    }

    @Test
    fun `a list that does not extend the previous one starts the index over`() {
        val index = index().apply { ingest(catalog) }
        assertEquals(5, index.ids(listOf("fr1", "fr2")).size)

        // A reload: a fresh accumulator, here shorter and with other series.
        index.ingest(listOf(series("x", "Remplacement (2020)", "fr1")))

        assertEquals(listOf("x"), index.ids(listOf("fr1", "fr2")))
    }

    @Test
    fun `a large catalogue only cards the requested page and parses each title once`() {
        val all = (0 until 20_000).map { series("s$it", "Serie $it (${1990 + it % 35})", "c${it % 40}") }
        val index = index().apply { ingest(all) }
        val categories = (0 until 40).map { "c$it" }

        index.page(categories, limit = 60)
        index.page(categories.take(20), limit = 60)
        index.page(categories, limit = 120)

        assertEquals(20_000, index.keysBuilt)
        assertEquals(40, index.slicesSorted)
        assertTrue("cards built: ${index.cardsBuilt}", index.cardsBuilt <= 60 + 60 + 120)
    }
}

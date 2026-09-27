package com.bobot.iptvapp.ui.screen.home

import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.util.MovieSortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MovieDiscoveryIndex], the per-category index behind the Films tab's "Nouveautés"
 * view. The work counters ([MovieDiscoveryIndex.keysBuilt], [MovieDiscoveryIndex.slicesSorted],
 * [MovieDiscoveryIndex.cardsBuilt]) are what these tests measure instead of time, so the bounds on
 * a large catalogue are deterministic.
 */
class MovieDiscoveryIndexTest {

    private fun movie(id: String, title: String, categoryId: String, added: Long?) = Movie(
        id = id,
        title = title,
        posterUrl = null,
        plot = null,
        categoryId = categoryId,
        rating = null,
        year = null,
        addedMillis = added,
        durationMillis = null,
        containerExtension = null,
    )

    private fun index() = MovieDiscoveryIndex(latestPlausibleYear = 2027) { movie ->
        HomeCardItem(id = movie.id, title = movie.title, imageUrl = null, contentType = ContentType.MOVIE)
    }

    private val catalog = listOf(
        movie("a", "Ancien (2009)", "fr1", added = 900),
        movie("b", "Sans Annee", "fr1", added = 1_000),
        movie("c", "Recent (2025)", "fr2", added = 100),
        movie("d", "Recent Bis (2025)", "fr2", added = 500),
        movie("e", "Moyen - 2024", "fr2", added = null),
        movie("f", "English (2026)", "en", added = 2_000),
    )

    private fun MovieDiscoveryIndex.ids(categories: List<String>, mode: MovieSortMode, limit: Int = 60) =
        page(categories, mode, limit).items.map { it.id }

    @Test
    fun `recent release merges every selected category by title year then added date, unknown years last`() {
        val index = index().apply { ingest(catalog) }

        assertEquals(
            listOf("d", "c", "e", "a", "b"),
            index.ids(listOf("fr1", "fr2"), MovieSortMode.RECENT_RELEASE),
        )
    }

    @Test
    fun `recently added ignores the year and puts unknown added dates last`() {
        val index = index().apply { ingest(catalog) }

        assertEquals(
            listOf("b", "a", "d", "c", "e"),
            index.ids(listOf("fr1", "fr2"), MovieSortMode.RECENTLY_ADDED),
        )
    }

    @Test
    fun `only the requested categories contribute`() {
        val index = index().apply { ingest(catalog) }

        assertEquals(listOf("f"), index.ids(listOf("en"), MovieSortMode.RECENT_RELEASE))
        assertEquals(6, index.ids(listOf("fr1", "fr2", "en"), MovieSortMode.RECENT_RELEASE).size)
    }

    @Test
    fun `a film listed in two categories appears once`() {
        val index = index().apply {
            ingest(catalog + movie("d", "Recent Bis (2025)", "fr1", added = 500))
        }

        val ids = index.ids(listOf("fr1", "fr2"), MovieSortMode.RECENT_RELEASE)

        assertEquals(listOf("d", "c", "e", "a", "b"), ids)
    }

    @Test
    fun `pages are bounded by the limit and report whether more films follow`() {
        val index = index().apply { ingest(catalog) }

        val first = index.page(listOf("fr1", "fr2"), MovieSortMode.RECENT_RELEASE, limit = 2)
        assertEquals(listOf("d", "c"), first.items.map { it.id })
        assertTrue(first.hasMore)

        val all = index.page(listOf("fr1", "fr2"), MovieSortMode.RECENT_RELEASE, limit = 5)
        assertEquals(5, all.items.size)
        assertFalse(all.hasMore)
    }

    @Test
    fun `a duplicate left over after the page does not count as more films`() {
        val index = index().apply {
            ingest(listOf(movie("x", "X (2020)", "fr1", 1), movie("x", "X (2020)", "fr2", 1)))
        }

        val page = index.page(listOf("fr1", "fr2"), MovieSortMode.RECENT_RELEASE, limit = 1)

        assertEquals(listOf("x"), page.items.map { it.id })
        assertFalse(page.hasMore)
    }

    @Test
    fun `a growing accumulator only indexes the films appended since the previous call`() {
        val index = index()
        index.ingest(catalog.take(2))
        index.page(listOf("fr1", "fr2"), MovieSortMode.RECENT_RELEASE, 60)
        assertEquals(2, index.keysBuilt)

        index.ingest(catalog)
        assertEquals(listOf("d", "c", "e", "a", "b"), index.ids(listOf("fr1", "fr2"), MovieSortMode.RECENT_RELEASE))
        assertEquals(5, index.keysBuilt)
    }

    @Test
    fun `a list that does not extend the previous one starts the index over`() {
        val index = index()
        index.ingest(listOf(movie("old", "Old (2001)", "fr1", 1)))
        index.page(listOf("fr1"), MovieSortMode.RECENT_RELEASE, 60)

        index.ingest(listOf(movie("new", "New (2002)", "fr1", 1)))

        assertEquals(listOf("new"), index.ids(listOf("fr1"), MovieSortMode.RECENT_RELEASE))
    }

    @Test
    fun `a large catalogue only ever builds the cards of the pages asked for and sorts each slice once`() {
        val categories = (0 until 150).map { "cat$it" }
        val movies = categories.flatMapIndexed { c, categoryId ->
            (0 until 1_000).map { i ->
                val n = c * 1_000 + i
                movie("m$n", "Film $n (${1950 + n % 75})", categoryId, added = (n * 7_919L) % 100_000)
            }
        }
        val index = index()
        index.ingest(movies)

        val first = index.page(categories, MovieSortMode.RECENT_RELEASE, 60)
        assertEquals(60, first.items.size)
        assertTrue(first.hasMore)
        assertEquals(60, index.cardsBuilt)
        assertEquals(150, index.slicesSorted)
        assertEquals(150_000, index.keysBuilt)

        // The year-2024 films come first; within them, the most recently added.
        val top = movies.filter { it.title.endsWith("(2024)") }.sortedByDescending { it.addedMillis }.take(60)
        assertEquals(top.map { it.id }, first.items.map { it.id })

        // Next page: only the 60 new cards are built.
        assertEquals(120, index.page(categories, MovieSortMode.RECENT_RELEASE, 120).items.size)
        assertEquals(120, index.cardsBuilt)

        // Re-emission of the same accumulator: nothing is re-indexed, re-sorted or re-carded.
        index.ingest(movies.toList())
        index.page(categories, MovieSortMode.RECENT_RELEASE, 120)
        assertEquals(150_000, index.keysBuilt)
        assertEquals(150, index.slicesSorted)
        assertEquals(120, index.cardsBuilt)

        // Language change to a subset: no re-sort, at most one page of new cards.
        index.page(categories.take(10), MovieSortMode.RECENT_RELEASE, 60)
        assertEquals(150, index.slicesSorted)
        assertTrue(index.cardsBuilt <= 180)

        // Other mode: each slice sorted once for it, then cached both ways.
        index.page(categories, MovieSortMode.RECENTLY_ADDED, 60)
        index.page(categories, MovieSortMode.RECENT_RELEASE, 60)
        index.page(categories, MovieSortMode.RECENTLY_ADDED, 60)
        assertEquals(300, index.slicesSorted)
        assertEquals(150_000, index.keysBuilt)
    }
}

package com.bobot.iptvapp.domain.util

import com.bobot.iptvapp.domain.model.Movie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [MovieSort] and the [MovieSortKey] projection it sorts on. */
class MovieSortTest {

    private val latest = 2027

    private fun movie(
        id: String,
        title: String,
        addedMillis: Long?,
        categoryId: String = "1",
        year: Int? = null,
    ) = Movie(
        id = id,
        title = title,
        posterUrl = null,
        plot = null,
        categoryId = categoryId,
        rating = null,
        year = year,
        addedMillis = addedMillis,
        durationMillis = null,
        containerExtension = null,
    )

    private fun List<Movie>.sortedIds(mode: MovieSortMode): List<String> =
        MovieSort.sort(this, mode, latestPlausibleYear = latest).map { it.id }

    // ── Projection ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `sort key carries the title year apart from the official year`() {
        val key = movie("7", "Avatar (2009)", addedMillis = 5L, year = 2010).sortKey(latest)

        assertEquals("7", key.movieId)
        assertEquals(2009, key.titleYear)
        assertEquals(5L, key.addedMillis)
    }

    @Test
    fun `sort key never substitutes the added date for a missing year`() {
        // Added in 2024 (epoch millis), no year in the title: the year stays unknown.
        val key = movie("1", "Avatar", addedMillis = 1_717_200_000_000L).sortKey(latest)

        assertNull(key.titleYear)
    }

    // ── Sortie récente ───────────────────────────────────────────────────────────────────────

    @Test
    fun `recent release orders by year descending`() {
        val movies = listOf(
            movie("a", "Old (2024)", addedMillis = 900L),
            movie("b", "New (2025)", addedMillis = 100L),
        )

        assertEquals(listOf("b", "a"), movies.sortedIds(MovieSortMode.RECENT_RELEASE))
    }

    @Test
    fun `recent release breaks a year tie by added date descending`() {
        val movies = listOf(
            movie("a", "Early - 2025", addedMillis = 100L),
            movie("b", "Late (2025)", addedMillis = 300L),
            movie("c", "Middle (2025)", addedMillis = 200L),
        )

        assertEquals(listOf("b", "c", "a"), movies.sortedIds(MovieSortMode.RECENT_RELEASE))
    }

    @Test
    fun `recent release puts unknown years last, ordered by added date`() {
        val movies = listOf(
            movie("a", "No year, recent add", addedMillis = 999L),
            movie("b", "Dated (1990)", addedMillis = 1L),
            movie("c", "No year, old add", addedMillis = 10L),
            movie("d", "Ambiguous 1080p", addedMillis = 500L),
        )

        assertEquals(listOf("b", "a", "d", "c"), movies.sortedIds(MovieSortMode.RECENT_RELEASE))
    }

    @Test
    fun `recent release puts unknown added dates last within a year`() {
        val movies = listOf(
            movie("a", "A (2020)", addedMillis = null),
            movie("b", "B (2020)", addedMillis = 1L),
        )

        assertEquals(listOf("b", "a"), movies.sortedIds(MovieSortMode.RECENT_RELEASE))
    }

    @Test
    fun `recent release ignores the official year from the detail call`() {
        // The ordering must not change once a detail screen has filled in Movie.year.
        val movies = listOf(
            movie("a", "A (2001)", addedMillis = 1L, year = 2030),
            movie("b", "B (2002)", addedMillis = 1L),
        )

        assertEquals(listOf("b", "a"), movies.sortedIds(MovieSortMode.RECENT_RELEASE))
    }

    // ── Ajout récent ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `recently added orders by added date regardless of release year`() {
        val movies = listOf(
            movie("a", "New film (2025)", addedMillis = 100L),
            movie("b", "Classic (1960)", addedMillis = 300L),
            movie("c", "No year", addedMillis = 200L),
        )

        assertEquals(listOf("b", "c", "a"), movies.sortedIds(MovieSortMode.RECENTLY_ADDED))
    }

    @Test
    fun `recently added puts unknown added dates last`() {
        val movies = listOf(
            movie("a", "A (2025)", addedMillis = null),
            movie("b", "B", addedMillis = 1L),
        )

        assertEquals(listOf("b", "a"), movies.sortedIds(MovieSortMode.RECENTLY_ADDED))
    }

    // ── Determinism ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `full ties are broken by id whatever the input order`() {
        val movies = listOf(
            movie("30", "X (2020)", addedMillis = 5L),
            movie("10", "Y (2020)", addedMillis = 5L),
            movie("20", "Z (2020)", addedMillis = 5L),
        )

        for (mode in MovieSortMode.entries) {
            assertEquals(listOf("10", "20", "30"), movies.sortedIds(mode))
            assertEquals(listOf("10", "20", "30"), movies.reversed().sortedIds(mode))
        }
    }

    @Test
    fun `movies with no date at all are ordered by id only`() {
        val movies = listOf(
            movie("b", "Beta", addedMillis = null),
            movie("a", "Alpha", addedMillis = null),
        )

        for (mode in MovieSortMode.entries) {
            assertEquals(listOf("a", "b"), movies.sortedIds(mode))
        }
    }

    @Test
    fun `movies from several categories and languages are sorted together and kept intact`() {
        val fr = movie("1", "FR - Le Film (2023)", addedMillis = 10L, categoryId = "fr-action")
        val en = movie("2", "EN - The Film (2024)", addedMillis = 5L, categoryId = "en-drama")
        val other = movie("3", "Autre (2023)", addedMillis = 20L, categoryId = "fr-comedy")

        val sorted = MovieSort.sort(listOf(fr, en, other), MovieSortMode.RECENT_RELEASE, latest)

        // The very same instances come back, so ids and categories survive for navigation.
        assertEquals(listOf(en, other, fr), sorted)
    }

    @Test
    fun `sorting an empty list returns an empty list`() {
        for (mode in MovieSortMode.entries) {
            assertEquals(emptyList<Movie>(), MovieSort.sort(emptyList(), mode, latest))
        }
    }
}

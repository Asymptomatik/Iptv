package com.bobot.iptvapp.data.source.fake

import com.bobot.iptvapp.data.source.CatalogException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [FakeXtreamSource].
 *
 * Verifies the stub/detail distinction for series, EPG slot timing, and edge cases
 * recommended in the Task 7 review.
 */
class FakeXtreamSourceTest {

    private lateinit var source: FakeXtreamSource

    @Before
    fun setUp() {
        source = FakeXtreamSource()
    }

    // ── getSeriesList — stubs only ────────────────────────────────────────────

    @Test
    fun `getSeriesList returns non-empty list`() = runTest {
        val stubs = source.getSeriesList()
        assertTrue("getSeriesList should return at least one series", stubs.isNotEmpty())
    }

    @Test
    fun `getSeriesList returns stubs with seasons always empty`() = runTest {
        val stubs = source.getSeriesList()
        stubs.forEach { series ->
            assertTrue(
                "Series ${series.id} ('${series.title}') must have empty seasons in stub",
                series.seasons.isEmpty(),
            )
        }
    }

    @Test
    fun `getSeriesList filtered by categoryId returns only matching series`() = runTest {
        val all = source.getSeriesList()
        val categoryIds = all.map { it.categoryId }.distinct()
        val targetCategory = categoryIds.first()

        val filtered = source.getSeriesList(categoryId = targetCategory)
        assertTrue("Filtered list should be non-empty", filtered.isNotEmpty())
        filtered.forEach { series ->
            assertEquals(
                "Every series in filtered list should match requested categoryId",
                targetCategory,
                series.categoryId,
            )
        }
    }

    // ── getSeriesInfo — full tree ─────────────────────────────────────────────

    @Test
    fun `getSeriesInfo for Breaking Bad returns full season tree`() = runTest {
        // Breaking Bad (id = "s101") has 2 seasons in FakeXtreamSource.
        val series = source.getSeriesInfo("s101")

        assertEquals("s101", series.id)
        assertEquals(2, series.seasons.size)
        assertTrue("Season 1 should have episodes", series.seasons[0].episodes.isNotEmpty())
        assertTrue("Season 2 should have episodes", series.seasons[1].episodes.isNotEmpty())
    }

    @Test
    fun `getSeriesInfo stub differs from getSeriesList entry — stub has no seasons`() =
        runTest {
            val stub = source.getSeriesList().first { it.id == "s101" }
            val full = source.getSeriesInfo("s101")

            assertTrue("Stub should have no seasons", stub.seasons.isEmpty())
            assertTrue("Full detail should have seasons", full.seasons.isNotEmpty())
            assertEquals("Both should share the same id", stub.id, full.id)
            assertEquals("Both should share the same title", stub.title, full.title)
        }

    @Test
    fun `getSeriesInfo throws NotFound for unknown seriesId`() = runTest {
        try {
            source.getSeriesInfo("unknown-series-id")
            error("Expected CatalogException.NotFound to be thrown")
        } catch (e: CatalogException.NotFound) {
            assertNotNull(e)
        }
    }

    @Test
    fun `getSeriesInfo for Squid Game returns 1 season`() = runTest {
        val series = source.getSeriesInfo("s301")
        assertEquals("s301", series.id)
        assertEquals(1, series.seasons.size)
        assertEquals(9, series.seasons[0].episodes.size)
    }

    // ── getShortEpg — timing ──────────────────────────────────────────────────

    @Test
    fun `getShortEpg slot index 1 is the now-playing slot within current hour`() = runTest {
        val now = System.currentTimeMillis()
        val hourMs = 60 * 60 * 1_000L
        val hourStart = now - (now % hourMs)
        val hourEnd = hourStart + hourMs

        val programs = source.getShortEpg("bbc.world", limit = null)
        assertTrue("Expected at least 2 EPG slots for bbc.world", programs.size >= 2)

        // Index 1 is the "now playing" slot (started at top of current hour)
        val nowPlaying = programs[1]
        assertEquals(
            "Slot index 1 startMillis should equal hourStart",
            hourStart,
            nowPlaying.startMillis,
        )
        assertEquals(
            "Slot index 1 endMillis should equal hourEnd",
            hourEnd,
            nowPlaying.endMillis,
        )
        assertTrue(
            "nowPlaying.startMillis should be <= System.currentTimeMillis()",
            nowPlaying.startMillis <= System.currentTimeMillis(),
        )
        assertTrue(
            "nowPlaying.endMillis should be > System.currentTimeMillis()",
            nowPlaying.endMillis > System.currentTimeMillis(),
        )
    }

    @Test
    fun `getShortEpg returns exactly 4 slots for known channel with no limit`() = runTest {
        val programs = source.getShortEpg("espn", limit = null)
        assertEquals("Should return 4 EPG slots for known channel", 4, programs.size)
    }

    @Test
    fun `getShortEpg respects limit parameter`() = runTest {
        val programs = source.getShortEpg("bbc.world", limit = 2)
        assertEquals("Should respect limit = 2", 2, programs.size)
    }

    @Test
    fun `getShortEpg returns empty list for unknown channelId`() = runTest {
        val programs = source.getShortEpg("unknown.channel.xyz")
        assertTrue("Unknown channel should return empty EPG list", programs.isEmpty())
    }

    @Test
    fun `getShortEpg returns empty list for channel with no epgChannelId mapping`() = runTest {
        // "Netflix Channel" has epgChannelId = null, hence no EPG data in the fake source.
        // The key "netflix.channel" is not in EPG_DATA so getShortEpg returns empty.
        val programs = source.getShortEpg("netflix.channel")
        assertTrue(programs.isEmpty())
    }

    // ── authenticate ──────────────────────────────────────────────────────────

    @Test
    fun `authenticate always returns success`() = runTest {
        val result = source.authenticate()
        assertTrue("Fake source authenticate should always succeed", result.isSuccess)
    }

    // ── categories ────────────────────────────────────────────────────────────

    @Test
    fun `getLiveCategories returns 4 categories`() = runTest {
        assertEquals(4, source.getLiveCategories().size)
    }

    @Test
    fun `getVodCategories returns 4 categories`() = runTest {
        assertEquals(4, source.getVodCategories().size)
    }

    @Test
    fun `getSeriesCategories returns 3 categories`() = runTest {
        assertEquals(3, source.getSeriesCategories().size)
    }

    // ── getMovieInfo ──────────────────────────────────────────────────────────

    @Test
    fun `getMovieInfo returns movie for known id`() = runTest {
        val movie = source.getMovieInfo("m101")
        assertEquals("m101", movie.id)
        assertEquals("John Wick", movie.title)
    }

    @Test
    fun `getMovieInfo throws NotFound for unknown movieId`() = runTest {
        try {
            source.getMovieInfo("m999")
            error("Expected CatalogException.NotFound")
        } catch (e: CatalogException.NotFound) {
            assertNotNull(e)
        }
    }

    @Test
    fun `getMovieInfo for Forrest Gump returns null posterUrl (edge case)`() = runTest {
        val movie = source.getMovieInfo("m202")
        assertEquals("Forrest Gump", movie.title)
        assertTrue("Forrest Gump has no poster — UI must show placeholder", movie.posterUrl == null)
    }
}

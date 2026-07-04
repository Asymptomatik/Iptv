package com.bobot.iptvapp.data.remote.mapper

import com.bobot.iptvapp.data.remote.dto.EpisodeDto
import com.bobot.iptvapp.data.remote.dto.EpisodeInfoDto
import com.bobot.iptvapp.data.remote.dto.SeasonDto
import com.bobot.iptvapp.data.remote.dto.SeriesDto
import com.bobot.iptvapp.data.remote.dto.SeriesInfoDetailDto
import com.bobot.iptvapp.data.remote.dto.SeriesInfoDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesMapperTest {

    // ── SeriesDto → Series ────────────────────────────────────────────────────

    @Test
    fun `SeriesDto toDomain maps basic fields`() {
        val dto = SeriesDto(
            seriesId = "101",
            name = "Breaking Bad",
            cover = "http://example.com/bb.jpg",
            plot = "A chemistry teacher ...",
            rating = "9.5",
            categoryId = "3",
            releaseDate = "2008",
        )

        val result = dto.toDomain()

        assertEquals("101", result.id)
        assertEquals("Breaking Bad", result.title)
        assertEquals("http://example.com/bb.jpg", result.coverUrl)
        assertEquals("A chemistry teacher ...", result.plot)
        assertEquals("9.5", result.rating)
        assertEquals("3", result.categoryId)
        assertEquals(2008, result.year)
        assertTrue(result.seasons.isEmpty())
    }

    @Test
    fun `SeriesDto toDomain extracts year from full ISO releaseDate`() {
        val dto = SeriesDto(seriesId = "1", name = "S", releaseDate = "2019-09-15")

        assertEquals(2019, dto.toDomain().year)
    }

    @Test
    fun `SeriesDto toDomain uses empty string for absent categoryId`() {
        val dto = SeriesDto(seriesId = "2", name = "Series", categoryId = null)

        assertEquals("", dto.toDomain().categoryId)
    }

    @Test
    fun `SeriesDto toDomain returns null plot for blank plot`() {
        val dto = SeriesDto(seriesId = "3", name = "Series", plot = "")

        assertNull(dto.toDomain().plot)
    }

    // ── SeriesInfoDto → Series ────────────────────────────────────────────────

    @Test
    fun `SeriesInfoDto toDomain maps info fields and seasons with episodes`() {
        val dto = SeriesInfoDto(
            info = SeriesInfoDetailDto(
                name = "Breaking Bad",
                seriesId = "101",
                cover = "http://example.com/bb.jpg",
                plot = "A chemistry teacher ...",
                rating = "9.5",
                categoryId = "3",
                releaseDate = "2008",
            ),
            seasons = listOf(
                SeasonDto(seasonNumber = 1, name = "Season 1"),
            ),
            episodes = mapOf(
                "1" to listOf(
                    EpisodeDto(
                        id = "67890",
                        episodeNum = "1",
                        title = "Pilot",
                        containerExtension = "mkv",
                        season = 1,
                        info = EpisodeInfoDto(durationSecs = 2700, plot = "Walter White starts ..."),
                    ),
                    EpisodeDto(
                        id = "67891",
                        episodeNum = "2",
                        title = "Cat's in the Bag",
                        containerExtension = "mkv",
                        season = 1,
                    ),
                ),
            ),
        )

        val result = dto.toDomain(seriesId = "101")

        assertEquals("101", result.id)
        assertEquals("Breaking Bad", result.title)
        assertEquals(1, result.seasons.size)

        val season = result.seasons[0]
        assertEquals(1, season.seasonNumber)
        assertEquals("Season 1", season.name)
        assertEquals(2, season.episodes.size)

        val ep1 = season.episodes[0]
        assertEquals("67890", ep1.id)
        assertEquals("Pilot", ep1.title)
        assertEquals(1, ep1.episodeNumber)
        assertEquals(1, ep1.seasonNumber)
        assertEquals(2_700L * 1_000L, ep1.durationMillis)
        assertEquals("Walter White starts ...", ep1.plot)
        assertEquals("mkv", ep1.containerExtension)

        val ep2 = season.episodes[1]
        assertEquals("67891", ep2.id)
        assertEquals(2, ep2.episodeNumber)
    }

    @Test
    fun `SeriesInfoDto toDomain sorts seasons ascending`() {
        val dto = SeriesInfoDto(
            info = SeriesInfoDetailDto(name = "Series"),
            seasons = listOf(
                SeasonDto(seasonNumber = 3, name = "S3"),
                SeasonDto(seasonNumber = 1, name = "S1"),
                SeasonDto(seasonNumber = 2, name = "S2"),
            ),
            episodes = emptyMap(),
        )

        val result = dto.toDomain(seriesId = "1")

        assertEquals(listOf(1, 2, 3), result.seasons.map { it.seasonNumber })
    }

    @Test
    fun `SeriesInfoDto toDomain sorts episodes ascending within season`() {
        val dto = SeriesInfoDto(
            info = SeriesInfoDetailDto(name = "Series"),
            seasons = listOf(SeasonDto(seasonNumber = 1)),
            episodes = mapOf(
                "1" to listOf(
                    EpisodeDto(id = "3", episodeNum = "3", title = "E3"),
                    EpisodeDto(id = "1", episodeNum = "1", title = "E1"),
                    EpisodeDto(id = "2", episodeNum = "2", title = "E2"),
                ),
            ),
        )

        val result = dto.toDomain(seriesId = "1")
        val episodes = result.seasons[0].episodes

        assertEquals(listOf("1", "2", "3"), episodes.map { it.id })
    }

    @Test
    fun `SeriesInfoDto toDomain uses seriesId fallback when info lacks it`() {
        val dto = SeriesInfoDto(
            info = SeriesInfoDetailDto(name = "Mystery Show", seriesId = null),
        )

        val result = dto.toDomain(seriesId = "FALLBACK_ID")

        assertEquals("FALLBACK_ID", result.id)
    }

    @Test
    fun `SeriesInfoDto toDomain produces empty season episodes when map has no matching key`() {
        val dto = SeriesInfoDto(
            info = SeriesInfoDetailDto(name = "Series"),
            seasons = listOf(SeasonDto(seasonNumber = 2, name = "S2")),
            episodes = mapOf("1" to listOf(EpisodeDto(id = "1", episodeNum = "1"))),
        )

        val result = dto.toDomain(seriesId = "1")

        // Season 2 has no entry in the episodes map — should produce an empty list
        assertTrue(result.seasons[0].episodes.isEmpty())
    }

    // ── EpisodeDto → Episode ─────────────────────────────────────────────────

    @Test
    fun `EpisodeDto toDomain uses season field over provided seasonNumber`() {
        val dto = EpisodeDto(id = "1", episodeNum = "5", season = 3)

        val result = dto.toDomain(seasonNumber = 1)

        assertEquals(3, result.seasonNumber)
    }

    @Test
    fun `EpisodeDto toDomain falls back to provided seasonNumber when episode season is null`() {
        val dto = EpisodeDto(id = "1", episodeNum = "1", season = null)

        val result = dto.toDomain(seasonNumber = 2)

        assertEquals(2, result.seasonNumber)
    }

    @Test
    fun `EpisodeDto toDomain generates default title when absent`() {
        val dto = EpisodeDto(id = "1", episodeNum = "4", title = null)

        assertEquals("Episode 4", dto.toDomain(seasonNumber = 1).title)
    }

    @Test
    fun `EpisodeDto toDomain converts durationSecs to millis`() {
        val dto = EpisodeDto(
            id = "1",
            episodeNum = "1",
            info = EpisodeInfoDto(durationSecs = 2700),
        )

        assertEquals(2_700L * 1_000L, dto.toDomain(1).durationMillis)
    }

    @Test
    fun `EpisodeDto toDomain returns null durationMillis when info is absent`() {
        val dto = EpisodeDto(id = "1", episodeNum = "1", info = null)

        assertNull(dto.toDomain(1).durationMillis)
    }
}

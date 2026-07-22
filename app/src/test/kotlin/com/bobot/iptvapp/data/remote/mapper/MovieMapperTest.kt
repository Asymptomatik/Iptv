package com.bobot.iptvapp.data.remote.mapper

import com.bobot.iptvapp.data.remote.dto.VodInfoDetailDto
import com.bobot.iptvapp.data.remote.dto.VodInfoDto
import com.bobot.iptvapp.data.remote.dto.VodMovieDataDto
import com.bobot.iptvapp.data.remote.dto.VodStreamDto
import com.bobot.iptvapp.data.remote.dto.VodSubtitleDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieMapperTest {

    /**
     * Mirrors the [Json] configuration provided by
     * `com.bobot.iptvapp.di.NetworkModule.provideJson` (ignoreUnknownKeys,
     * isLenient, coerceInputValues) so raw-JSON tolerant-deserialization tests
     * below exercise the same parsing behavior the app uses in production.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ── VodStreamDto → Movie ──────────────────────────────────────────────────

    @Test
    fun `VodStreamDto toDomain maps basic fields correctly`() {
        val dto = VodStreamDto(
            streamId = "54321",
            name = "Inception",
            streamIcon = "http://example.com/inception.jpg",
            rating = "8.8",
            added = "1620000000",
            categoryId = "12",
            containerExtension = "mkv",
        )

        val result = dto.toDomain()

        assertEquals("54321", result.id)
        assertEquals("Inception", result.title)
        assertEquals("http://example.com/inception.jpg", result.posterUrl)
        assertEquals("8.8", result.rating)
        assertEquals(1_620_000_000L * 1_000L, result.addedMillis)
        assertEquals("12", result.categoryId)
        assertEquals("mkv", result.containerExtension)
        assertNull(result.year)
        assertNull(result.durationMillis)
    }

    @Test
    fun `VodStreamDto toDomain treats blank streamIcon as null posterUrl`() {
        val dto = VodStreamDto(streamId = "1", name = "Movie", streamIcon = "", categoryId = "1")

        assertNull(dto.toDomain().posterUrl)
    }

    @Test
    fun `VodStreamDto toDomain treats null added as null addedMillis`() {
        val dto = VodStreamDto(streamId = "1", name = "Movie", added = null, categoryId = "1")

        assertNull(dto.toDomain().addedMillis)
    }

    @Test
    fun `VodStreamDto toDomain converts epoch seconds to millis`() {
        val dto = VodStreamDto(streamId = "1", name = "Movie", added = "1700000000", categoryId = "1")

        assertEquals(1_700_000_000L * 1_000L, dto.toDomain().addedMillis)
    }

    // ── VodInfoDto → Movie ────────────────────────────────────────────────────

    @Test
    fun `VodInfoDto toDomain maps enriched fields from info block`() {
        val dto = VodInfoDto(
            info = VodInfoDetailDto(
                title = "Inception",
                year = "2010",
                plot = "A skilled thief ...",
                coverBig = "http://example.com/big.jpg",
                durationSecs = 8880,
                rating = "8.8",
                categoryId = "12",
            ),
            movieData = VodMovieDataDto(
                streamId = "54321",
                containerExtension = "mkv",
            ),
        )

        val result = dto.toDomain(fallbackStreamId = "0", fallbackCategoryId = "0")

        assertEquals("54321", result.id)
        assertEquals("Inception", result.title)
        assertEquals("http://example.com/big.jpg", result.posterUrl)
        assertEquals("A skilled thief ...", result.plot)
        assertEquals("8.8", result.rating)
        assertEquals(2010, result.year)
        assertEquals(8_880L * 1_000L, result.durationMillis)
        assertEquals("mkv", result.containerExtension)
        assertEquals("12", result.categoryId)
    }

    @Test
    fun `VodInfoDto toDomain prefers movie_data streamId over fallback`() {
        val dto = VodInfoDto(
            info = VodInfoDetailDto(title = "Film"),
            movieData = VodMovieDataDto(streamId = "999"),
        )

        assertEquals("999", dto.toDomain(fallbackStreamId = "000", fallbackCategoryId = "1").id)
    }

    @Test
    fun `VodInfoDto toDomain uses fallbackStreamId when movie_data is absent`() {
        val dto = VodInfoDto(
            info = VodInfoDetailDto(title = "Film"),
            movieData = null,
        )

        assertEquals("FB01", dto.toDomain(fallbackStreamId = "FB01", fallbackCategoryId = "1").id)
    }

    @Test
    fun `VodInfoDto toDomain uses movie_data categoryId over info categoryId`() {
        val dto = VodInfoDto(
            info = VodInfoDetailDto(title = "Film", categoryId = "info_cat"),
            movieData = VodMovieDataDto(streamId = "1", categoryId = "data_cat"),
        )

        assertEquals("data_cat", dto.toDomain(fallbackStreamId = "1", fallbackCategoryId = "fb").categoryId)
    }

    @Test
    fun `VodInfoDto toDomain falls back to info categoryId when movie_data lacks it`() {
        val dto = VodInfoDto(
            info = VodInfoDetailDto(title = "Film", categoryId = "info_cat"),
            movieData = VodMovieDataDto(streamId = "1", categoryId = null),
        )

        assertEquals("info_cat", dto.toDomain(fallbackStreamId = "1", fallbackCategoryId = "fb").categoryId)
    }

    @Test
    fun `VodInfoDto toDomain falls back to movieImage when coverBig is blank`() {
        val dto = VodInfoDto(
            info = VodInfoDetailDto(
                title = "Film",
                coverBig = "",
                movieImage = "http://example.com/thumb.jpg",
            ),
            movieData = VodMovieDataDto(streamId = "1"),
        )

        assertEquals("http://example.com/thumb.jpg", dto.toDomain("1", "1").posterUrl)
    }

    @Test
    fun `VodInfoDto toDomain extracts year from releasedate when year field is absent`() {
        val dto = VodInfoDto(
            info = VodInfoDetailDto(title = "Film", year = null, releaseDate = "2015-06-01"),
            movieData = VodMovieDataDto(streamId = "1"),
        )

        assertEquals(2015, dto.toDomain("1", "1").year)
    }

    @Test
    fun `VodInfoDto toDomain converts durationSecs to millis`() {
        val dto = VodInfoDto(
            info = VodInfoDetailDto(title = "Film", durationSecs = 7200),
            movieData = VodMovieDataDto(streamId = "1"),
        )

        assertEquals(7_200L * 1_000L, dto.toDomain("1", "1").durationMillis)
    }

    // ── VodInfoDto → Movie: external subtitles mapping (DTO objects) ──────────

    @Test
    fun `VodInfoDto toDomain maps well-formed subtitles to externalSubtitles preserving url and language`() {
        val dto = VodInfoDto(
            info = VodInfoDetailDto(
                title = "Film",
                subtitles = listOf(
                    VodSubtitleDto(url = "http://example.com/en.srt", language = "en"),
                    VodSubtitleDto(url = "http://example.com/fr.srt", language = "fr"),
                ),
            ),
            movieData = VodMovieDataDto(streamId = "1"),
        )

        val result = dto.toDomain("1", "1").externalSubtitles

        assertEquals(2, result.size)
        assertEquals("http://example.com/en.srt", result[0].url)
        assertEquals("en", result[0].language)
        assertEquals("http://example.com/fr.srt", result[1].url)
        assertEquals("fr", result[1].language)
    }

    @Test
    fun `VodInfoDto toDomain drops subtitle entries with null or blank url`() {
        val dto = VodInfoDto(
            info = VodInfoDetailDto(
                title = "Film",
                subtitles = listOf(
                    VodSubtitleDto(url = null, language = "en"),
                    VodSubtitleDto(url = "   ", language = "fr"),
                    VodSubtitleDto(url = "http://example.com/de.srt", language = "de"),
                ),
            ),
            movieData = VodMovieDataDto(streamId = "1"),
        )

        val result = dto.toDomain("1", "1").externalSubtitles

        assertEquals(1, result.size)
        assertEquals("http://example.com/de.srt", result[0].url)
        assertEquals("de", result[0].language)
    }

    @Test
    fun `VodInfoDto toDomain maps blank subtitle language to null`() {
        val dto = VodInfoDto(
            info = VodInfoDetailDto(
                title = "Film",
                subtitles = listOf(VodSubtitleDto(url = "http://example.com/en.srt", language = "   ")),
            ),
            movieData = VodMovieDataDto(streamId = "1"),
        )

        val result = dto.toDomain("1", "1").externalSubtitles

        assertEquals(1, result.size)
        assertNull(result[0].language)
    }

    @Test
    fun `VodInfoDto toDomain yields empty externalSubtitles when subtitles field is absent`() {
        val dto = VodInfoDto(
            info = VodInfoDetailDto(title = "Film", subtitles = null),
            movieData = VodMovieDataDto(streamId = "1"),
        )

        assertTrue(dto.toDomain("1", "1").externalSubtitles.isEmpty())
    }

    // ── info.subtitles raw JSON: tolerant deserialization (NullableVodSubtitleListSerializer) ──
    //
    // These tests decode full VodInfoDto payloads through the same Json configuration as the
    // app (see `json` above, mirroring NetworkModule.provideJson) to prove end-to-end that a
    // missing, empty, or malformed `subtitles` field never breaks parsing of the rest of the
    // response, per the best-effort contract in the approved brief.

    @Test
    fun `raw JSON without a subtitles field parses fine and maps to empty externalSubtitles`() {
        val raw = """
            {
              "info": { "title": "Film" },
              "movie_data": { "stream_id": "1" }
            }
        """.trimIndent()

        val dto = json.decodeFromString<VodInfoDto>(raw)

        assertNull(dto.info.subtitles)
        assertTrue(dto.toDomain("1", "1").externalSubtitles.isEmpty())
    }

    @Test
    fun `raw JSON with an empty subtitles array parses fine and maps to empty externalSubtitles`() {
        val raw = """
            {
              "info": { "title": "Film", "subtitles": [] },
              "movie_data": { "stream_id": "1" }
            }
        """.trimIndent()

        val dto = json.decodeFromString<VodInfoDto>(raw)

        assertNull(dto.info.subtitles)
        assertTrue(dto.toDomain("1", "1").externalSubtitles.isEmpty())
    }

    @Test
    fun `raw JSON with subtitles as a single object instead of an array degrades to null without breaking the rest of the response`() {
        val raw = """
            {
              "info": { "title": "Film", "subtitles": { "url": "http://example.com/en.srt" } },
              "movie_data": { "stream_id": "1" }
            }
        """.trimIndent()

        val dto = json.decodeFromString<VodInfoDto>(raw)

        assertNull(dto.info.subtitles)
        assertEquals("Film", dto.info.title)
        assertEquals("1", dto.movieData?.streamId)
        assertTrue(dto.toDomain("1", "1").externalSubtitles.isEmpty())
    }

    @Test
    fun `raw JSON with subtitles as a string degrades to null without breaking the rest of the response`() {
        val raw = """
            {
              "info": { "title": "Film", "subtitles": "http://example.com/en.srt" },
              "movie_data": { "stream_id": "1" }
            }
        """.trimIndent()

        val dto = json.decodeFromString<VodInfoDto>(raw)

        assertNull(dto.info.subtitles)
        assertEquals("Film", dto.info.title)
        assertTrue(dto.toDomain("1", "1").externalSubtitles.isEmpty())
    }

    @Test
    fun `raw JSON with a mix of a valid and a malformed subtitle entry drops the malformed one and keeps the valid one`() {
        val raw = """
            {
              "info": {
                "title": "Film",
                "subtitles": [
                  { "url": "http://example.com/en.srt", "language": "en" },
                  "not-an-object"
                ]
              },
              "movie_data": { "stream_id": "1" }
            }
        """.trimIndent()

        val dto = json.decodeFromString<VodInfoDto>(raw)

        assertEquals(1, dto.info.subtitles?.size)
        val result = dto.toDomain("1", "1").externalSubtitles
        assertEquals(1, result.size)
        assertEquals("http://example.com/en.srt", result[0].url)
        assertEquals("en", result[0].language)
    }

    @Test
    fun `raw JSON tolerates alternate key names subtitle-file for url and lang-label for language`() {
        val raw = """
            {
              "info": {
                "title": "Film",
                "subtitles": [
                  { "subtitle": "http://example.com/en.srt", "lang": "en" },
                  { "file": "http://example.com/fr.srt", "label": "French" }
                ]
              },
              "movie_data": { "stream_id": "1" }
            }
        """.trimIndent()

        val dto = json.decodeFromString<VodInfoDto>(raw)
        val result = dto.toDomain("1", "1").externalSubtitles

        assertEquals(2, result.size)
        assertEquals("http://example.com/en.srt", result[0].url)
        assertEquals("en", result[0].language)
        assertEquals("http://example.com/fr.srt", result[1].url)
        assertEquals("French", result[1].language)
    }
}

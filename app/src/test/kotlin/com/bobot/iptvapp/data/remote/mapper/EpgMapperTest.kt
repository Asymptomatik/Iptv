package com.bobot.iptvapp.data.remote.mapper

import com.bobot.iptvapp.data.remote.dto.EpgListingDto
import com.bobot.iptvapp.data.remote.dto.EpgProgramDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [EpgMapper].
 *
 * Base64 decoding calls [android.util.Base64] which is not available in JVM unit tests.
 * Tests therefore use plain-text strings for [title] and [description] and verify that
 * [decodeBase64OrSelf] passes them through unchanged (non-base64 strings are returned
 * as-is after the try/catch). Integration tests on device/emulator cover the actual
 * Base64 decode path.
 *
 * Timestamp conversion is fully covered: both the [startTimestamp]/[stopTimestamp] path
 * (epoch seconds × 1000) and the [start]/[end] datetime-string fallback path.
 */
class EpgMapperTest {

    // ── Timestamp: prefer startTimestamp / stopTimestamp fields ───────────────

    @Test
    fun `toDomain converts epoch second timestamps to millis`() {
        val dto = EpgProgramDto(
            id = "1",
            title = "The News",
            channelId = "cnn.us",
            startTimestamp = "1705327200",
            stopTimestamp = "1705330800",
        )

        val result = dto.toDomain()

        assertEquals(1_705_327_200L * 1_000L, result.startMillis)
        assertEquals(1_705_330_800L * 1_000L, result.endMillis)
    }

    @Test
    fun `toDomain maps channelId correctly`() {
        val dto = EpgProgramDto(
            id = "2",
            title = "Sports",
            channelId = "espn.us",
            startTimestamp = "1705327200",
            stopTimestamp = "1705330800",
        )

        assertEquals("espn.us", dto.toDomain().channelId)
    }

    // ── Timestamp: fallback to datetime string when timestamp fields are absent ─

    @Test
    fun `toDomain falls back to datetime string when timestamps are absent`() {
        val dto = EpgProgramDto(
            id = "3",
            title = "Morning Show",
            channelId = "bbc.uk",
            startTimestamp = null,
            stopTimestamp = null,
            start = "2024-01-15 08:00:00",
            end = "2024-01-15 09:00:00",
        )

        val result = dto.toDomain()

        // 2024-01-15 08:00:00 UTC = 1705305600000 ms
        assertEquals(1_705_305_600_000L, result.startMillis)
        // 2024-01-15 09:00:00 UTC = 1705309200000 ms
        assertEquals(1_705_309_200_000L, result.endMillis)
    }

    @Test
    fun `toDomain produces 0L startMillis when all timestamp fields are absent`() {
        val dto = EpgProgramDto(
            id = "4",
            title = "Unknown",
            channelId = "ch1",
            startTimestamp = null,
            stopTimestamp = null,
            start = null,
            end = null,
        )

        val result = dto.toDomain()

        assertEquals(0L, result.startMillis)
        assertEquals(0L, result.endMillis)
    }

    // ── Description nullability ───────────────────────────────────────────────

    @Test
    fun `toDomain maps present description`() {
        val dto = EpgProgramDto(
            id = "5",
            title = "Documentary",
            channelId = "nat.geo",
            startTimestamp = "1705327200",
            stopTimestamp = "1705330800",
            description = "Nature and wildlife",
        )

        assertEquals("Nature and wildlife", dto.toDomain().description)
    }

    @Test
    fun `toDomain maps null description as null`() {
        val dto = EpgProgramDto(
            id = "6",
            title = "Show",
            channelId = "ch1",
            startTimestamp = "1705327200",
            stopTimestamp = "1705330800",
            description = null,
        )

        assertNull(dto.toDomain().description)
    }

    @Test
    fun `toDomain treats blank description as null`() {
        val dto = EpgProgramDto(
            id = "7",
            title = "Show",
            channelId = "ch1",
            startTimestamp = "1705327200",
            stopTimestamp = "1705330800",
            description = "   ",
        )

        // decodeBase64OrSelf on a blank string returns the input; takeIf(isNotBlank) returns null
        assertNull(dto.toDomain().description)
    }

    // ── EpgListingDto → List<EpgProgram> ─────────────────────────────────────

    @Test
    fun `EpgListingDto toDomain maps all programme entries`() {
        val listing = EpgListingDto(
            epgListings = listOf(
                EpgProgramDto(
                    id = "10", title = "Program A", channelId = "ch1",
                    startTimestamp = "1705327200", stopTimestamp = "1705330800",
                ),
                EpgProgramDto(
                    id = "11", title = "Program B", channelId = "ch1",
                    startTimestamp = "1705330800", stopTimestamp = "1705334400",
                ),
            ),
        )

        val results = listing.toDomain()

        assertEquals(2, results.size)
        assertEquals("Program A", results[0].title)
        assertEquals("Program B", results[1].title)
    }

    @Test
    fun `EpgListingDto toDomain returns empty list for empty epgListings`() {
        val listing = EpgListingDto(epgListings = emptyList())

        assertEquals(0, listing.toDomain().size)
    }

    // ── parseEpgDateTime helper ───────────────────────────────────────────────

    @Test
    fun `parseEpgDateTime returns null for null input`() {
        assertNull(parseEpgDateTime(null))
    }

    @Test
    fun `parseEpgDateTime returns null for blank input`() {
        assertNull(parseEpgDateTime("  "))
    }

    @Test
    fun `parseEpgDateTime returns null for unparseable string`() {
        assertNull(parseEpgDateTime("not-a-date"))
    }

    @Test
    fun `parseEpgDateTime parses a valid UTC datetime string`() {
        // 2024-01-15 14:00:00 UTC
        val result = parseEpgDateTime("2024-01-15 14:00:00")

        // Expected: 1705327200000 ms
        assertEquals(1_705_327_200_000L, result)
    }
}

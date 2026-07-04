package com.bobot.iptvapp.ui.screen.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the top-level [formatTimeMs] helper (declared in `PlayerScreen.kt`) — a
 * pure function, unit-tested directly without any Compose test infrastructure.
 */
class FormatTimeMsTest {

    @Test
    fun `formats zero as 0-00`() {
        assertEquals("0:00", formatTimeMs(0L))
    }

    @Test
    fun `formats sub-minute durations as m-ss`() {
        assertEquals("0:09", formatTimeMs(9_000L))
    }

    @Test
    fun `formats minutes and seconds as m-ss`() {
        assertEquals("1:05", formatTimeMs(65_000L))
    }

    @Test
    fun `pads seconds under ten with a leading zero`() {
        assertEquals("2:03", formatTimeMs(123_000L))
    }

    @Test
    fun `formats durations at or beyond one hour as h-mm-ss`() {
        assertEquals("1:00:00", formatTimeMs(3_600_000L))
    }

    @Test
    fun `formats multi-hour durations correctly`() {
        assertEquals("1:30:05", formatTimeMs(5_405_000L))
    }

    @Test
    fun `clamps negative values to 0-00`() {
        assertEquals("0:00", formatTimeMs(-1_000L))
    }
}

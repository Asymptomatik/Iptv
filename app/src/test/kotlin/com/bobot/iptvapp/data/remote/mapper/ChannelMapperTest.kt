package com.bobot.iptvapp.data.remote.mapper

import com.bobot.iptvapp.data.remote.dto.LiveStreamDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelMapperTest {

    @Test
    fun `toDomain maps all present fields correctly`() {
        val dto = LiveStreamDto(
            streamId = "12345",
            name = "CNN",
            streamIcon = "http://example.com/cnn.png",
            epgChannelId = "cnn.us",
            added = "1620000000",
            categoryId = "7",
        )

        val result = dto.toDomain()

        assertEquals("12345", result.id)
        assertEquals("CNN", result.name)
        assertEquals("http://example.com/cnn.png", result.logoUrl)
        assertEquals("7", result.categoryId)
        assertEquals("cnn.us", result.epgChannelId)
    }

    @Test
    fun `toDomain treats blank streamIcon as null logoUrl`() {
        val dto = LiveStreamDto(
            streamId = "1",
            name = "Channel X",
            streamIcon = "",
            categoryId = "1",
        )

        val result = dto.toDomain()

        assertNull(result.logoUrl)
    }

    @Test
    fun `toDomain treats null streamIcon as null logoUrl`() {
        val dto = LiveStreamDto(
            streamId = "1",
            name = "Channel X",
            streamIcon = null,
            categoryId = "1",
        )

        val result = dto.toDomain()

        assertNull(result.logoUrl)
    }

    @Test
    fun `toDomain treats blank epgChannelId as null`() {
        val dto = LiveStreamDto(
            streamId = "2",
            name = "Radio",
            epgChannelId = "  ",
            categoryId = "5",
        )

        val result = dto.toDomain()

        // blank epgChannelId — takeIf { isNotBlank() } returns null
        assertNull(result.epgChannelId)
    }

    @Test
    fun `toDomain treats null epgChannelId as null`() {
        val dto = LiveStreamDto(
            streamId = "3",
            name = "Local TV",
            epgChannelId = null,
            categoryId = "2",
        )

        val result = dto.toDomain()

        assertNull(result.epgChannelId)
    }

    @Test
    fun `list toDomain maps every entry`() {
        val dtos = listOf(
            LiveStreamDto(streamId = "10", name = "BBC", categoryId = "1"),
            LiveStreamDto(streamId = "20", name = "NBC", categoryId = "1"),
        )

        val results = dtos.toDomain()

        assertEquals(2, results.size)
        assertEquals("10", results[0].id)
        assertEquals("20", results[1].id)
    }
}

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

    @Test
    fun `list toDomain drops bouquet separators`() {
        // The provider mixes divider rows into get_live_streams; they carry a stream_id like any
        // other entry, so without this filter they become tappable cards on a dead stream — and
        // the first one of a category becomes the Accueil hero (QA finding Y2).
        val dtos = listOf(
            LiveStreamDto(streamId = "1", name = "##### FRANCE GENERAL FHD #####", categoryId = "1"),
            LiveStreamDto(streamId = "10", name = "TF1", categoryId = "1"),
            LiveStreamDto(streamId = "2", name = "--- SPORT ---", categoryId = "1"),
            LiveStreamDto(streamId = "20", name = "M6", categoryId = "1"),
        )

        val results = dtos.toDomain()

        assertEquals(listOf("TF1", "M6"), results.map { it.name })
    }
}

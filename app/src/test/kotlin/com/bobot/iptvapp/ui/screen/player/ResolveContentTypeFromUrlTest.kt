package com.bobot.iptvapp.ui.screen.player

import com.bobot.iptvapp.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the top-level [resolveContentTypeFromUrl] helper (declared in
 * `PlayerViewModel.kt`) — mirrors [com.bobot.iptvapp.player.StreamTypeResolverTest]'s style
 * for a pure, framework-free URL-shape resolver.
 */
class ResolveContentTypeFromUrlTest {

    @Test
    fun `resolves LIVE for a live stream URL`() {
        val result = resolveContentTypeFromUrl("http://example.com:8080/live/alice/secret/12345.ts")

        assertEquals(ContentType.LIVE, result)
    }

    @Test
    fun `resolves SERIES for a series episode URL`() {
        val result = resolveContentTypeFromUrl("http://example.com:8080/series/alice/secret/555.mkv")

        assertEquals(ContentType.SERIES, result)
    }

    @Test
    fun `resolves MOVIE for a movie URL`() {
        val result = resolveContentTypeFromUrl("http://example.com:8080/movie/alice/secret/98765.mp4")

        assertEquals(ContentType.MOVIE, result)
    }

    @Test
    fun `falls back to MOVIE for an unrecognised URL shape`() {
        val result = resolveContentTypeFromUrl("http://example.com:8080/unknown/alice/secret/1.mp4")

        assertEquals(ContentType.MOVIE, result)
    }
}

package com.bobot.iptvapp.player

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamTypeResolverTest {

    // ── HLS ──────────────────────────────────────────────────────────────────

    @Test
    fun `resolve returns HLS for m3u8 extension`() {
        val result = StreamTypeResolver.resolve("http://example.com:8080/live/alice/secret/12345.m3u8")

        assertEquals(StreamMediaType.HLS, result)
    }

    @Test
    fun `resolve returns HLS for uppercase M3U8 extension`() {
        val result = StreamTypeResolver.resolve("http://example.com:8080/live/alice/secret/12345.M3U8")

        assertEquals(StreamMediaType.HLS, result)
    }

    // ── MPEG-TS ──────────────────────────────────────────────────────────────

    @Test
    fun `resolve returns MPEG_TS for ts extension`() {
        val result = StreamTypeResolver.resolve("http://example.com:8080/live/alice/secret/12345.ts")

        assertEquals(StreamMediaType.MPEG_TS, result)
    }

    // ── MP4 ──────────────────────────────────────────────────────────────────

    @Test
    fun `resolve returns MP4 for mp4 extension`() {
        val result = StreamTypeResolver.resolve("http://example.com:8080/movie/alice/secret/98765.mp4")

        assertEquals(StreamMediaType.MP4, result)
    }

    // ── Query strings / fragments ────────────────────────────────────────────

    @Test
    fun `resolve strips query string before extracting extension`() {
        val result = StreamTypeResolver.resolve("http://example.com:8080/live/alice/secret/12345.m3u8?token=abc&x=1")

        assertEquals(StreamMediaType.HLS, result)
    }

    @Test
    fun `resolve strips fragment before extracting extension`() {
        val result = StreamTypeResolver.resolve("http://example.com:8080/movie/alice/secret/98765.mp4#t=10")

        assertEquals(StreamMediaType.MP4, result)
    }

    // ── Fallback cases ───────────────────────────────────────────────────────

    @Test
    fun `resolve returns OTHER for unrecognised extension`() {
        val result = StreamTypeResolver.resolve("http://example.com:8080/movie/alice/secret/98765.mkv")

        assertEquals(StreamMediaType.OTHER, result)
    }

    @Test
    fun `resolve returns OTHER when no extension is present`() {
        val result = StreamTypeResolver.resolve("http://example.com:8080/live/alice/secret/12345")

        assertEquals(StreamMediaType.OTHER, result)
    }

    @Test
    fun `resolve returns OTHER when dot appears only in an earlier path segment`() {
        val result = StreamTypeResolver.resolve("http://example.com:8080/live/alice/secret/12345")

        assertEquals(StreamMediaType.OTHER, result)
    }

    @Test
    fun `resolve returns OTHER for a host containing a dot but extensionless last segment`() {
        val result = StreamTypeResolver.resolve("http://example.com:8080/live/alice/secret/stream")

        assertEquals(StreamMediaType.OTHER, result)
    }
}

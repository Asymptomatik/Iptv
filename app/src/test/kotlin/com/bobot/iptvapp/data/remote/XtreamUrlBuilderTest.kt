package com.bobot.iptvapp.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class XtreamUrlBuilderTest {

    private val base = "http://example.com:8080"
    private val baseWithSlash = "http://example.com:8080/"
    private val user = "alice"
    private val pass = "secret"

    // ── Live URL ──────────────────────────────────────────────────────────────

    @Test
    fun `buildLiveUrl produces correct default ts URL`() {
        val url = XtreamUrlBuilder.buildLiveUrl(base, user, pass, "12345")

        assertEquals("http://example.com:8080/live/alice/secret/12345.ts", url)
    }

    @Test
    fun `buildLiveUrl with trailing slash in baseUrl produces correct URL`() {
        val url = XtreamUrlBuilder.buildLiveUrl(baseWithSlash, user, pass, "12345")

        assertEquals("http://example.com:8080/live/alice/secret/12345.ts", url)
    }

    @Test
    fun `buildLiveUrl with m3u8 extension`() {
        val url = XtreamUrlBuilder.buildLiveUrl(base, user, pass, "12345", extension = "m3u8")

        assertEquals("http://example.com:8080/live/alice/secret/12345.m3u8", url)
    }

    @Test
    fun `buildLiveUrl LIVE_EXTENSION_TS constant is ts`() {
        assertEquals("ts", XtreamUrlBuilder.LIVE_EXTENSION_TS)
    }

    @Test
    fun `buildLiveUrl LIVE_EXTENSION_HLS constant is m3u8`() {
        assertEquals("m3u8", XtreamUrlBuilder.LIVE_EXTENSION_HLS)
    }

    // ── Movie URL ─────────────────────────────────────────────────────────────

    @Test
    fun `buildMovieUrl produces correct URL`() {
        val url = XtreamUrlBuilder.buildMovieUrl(base, user, pass, "54321", "mkv")

        assertEquals("http://example.com:8080/movie/alice/secret/54321.mkv", url)
    }

    @Test
    fun `buildMovieUrl with trailing slash in baseUrl`() {
        val url = XtreamUrlBuilder.buildMovieUrl(baseWithSlash, user, pass, "54321", "mp4")

        assertEquals("http://example.com:8080/movie/alice/secret/54321.mp4", url)
    }

    @Test
    fun `buildMovieUrl with different extension`() {
        val url = XtreamUrlBuilder.buildMovieUrl(base, user, pass, "1", "avi")

        assertEquals("http://example.com:8080/movie/alice/secret/1.avi", url)
    }

    // ── Episode URL ───────────────────────────────────────────────────────────

    @Test
    fun `buildEpisodeUrl produces correct URL`() {
        val url = XtreamUrlBuilder.buildEpisodeUrl(base, user, pass, "67890", "mkv")

        assertEquals("http://example.com:8080/series/alice/secret/67890.mkv", url)
    }

    @Test
    fun `buildEpisodeUrl with trailing slash in baseUrl`() {
        val url = XtreamUrlBuilder.buildEpisodeUrl(baseWithSlash, user, pass, "67890", "mkv")

        assertEquals("http://example.com:8080/series/alice/secret/67890.mkv", url)
    }

    // ── Credentials with special characters ──────────────────────────────────

    @Test
    fun `buildLiveUrl embeds credentials literally in path`() {
        // Xtream credentials are placed as path segments, not percent-encoded.
        // The URL is passed directly to ExoPlayer/OkHttp as-is.
        val url = XtreamUrlBuilder.buildLiveUrl(base, "user_name", "p@ssword!", "1")

        assertEquals("http://example.com:8080/live/user_name/p@ssword!/1.ts", url)
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `buildLiveUrl with multiple trailing slashes in baseUrl normalises to one`() {
        // trimEnd('/') + "/" handles this correctly.
        val url = XtreamUrlBuilder.buildLiveUrl("http://example.com:8080//", user, pass, "1")

        assertEquals("http://example.com:8080/live/alice/secret/1.ts", url)
    }
}

package com.bobot.iptvapp.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [SubtitleMimeTypeResolver] (Task 3).
 *
 * Pure string logic + [MimeTypes] constants only — no Media3 source/player construction — so
 * this runs on plain JVM exactly like [StreamTypeResolverTest], the file this test class
 * mirrors.
 */
class SubtitleMimeTypeResolverTest {

    // ── SubRip (default) ─────────────────────────────────────────────────────

    @Test
    fun `resolve returns APPLICATION_SUBRIP for srt extension`() {
        val result = SubtitleMimeTypeResolver.resolve("http://example.com/subs/movie.srt")

        assertEquals(MimeTypes.APPLICATION_SUBRIP, result)
    }

    @Test
    fun `resolve returns APPLICATION_SUBRIP for uppercase SRT extension`() {
        val result = SubtitleMimeTypeResolver.resolve("http://example.com/subs/movie.SRT")

        assertEquals(MimeTypes.APPLICATION_SUBRIP, result)
    }

    // ── WebVTT ───────────────────────────────────────────────────────────────

    @Test
    fun `resolve returns TEXT_VTT for vtt extension`() {
        val result = SubtitleMimeTypeResolver.resolve("http://example.com/subs/movie.vtt")

        assertEquals(MimeTypes.TEXT_VTT, result)
    }

    // ── (Sub Station Alpha) ──────────────────────────────────────────────────

    @Test
    fun `resolve returns TEXT_SSA for ass extension`() {
        val result = SubtitleMimeTypeResolver.resolve("http://example.com/subs/movie.ass")

        assertEquals(MimeTypes.TEXT_SSA, result)
    }

    @Test
    fun `resolve returns TEXT_SSA for ssa extension`() {
        val result = SubtitleMimeTypeResolver.resolve("http://example.com/subs/movie.ssa")

        assertEquals(MimeTypes.TEXT_SSA, result)
    }

    // ── Query strings / fragments stripped before extension extraction ───────

    @Test
    fun `resolve strips a query string before extracting the extension`() {
        val result = SubtitleMimeTypeResolver.resolve("http://example.com/subs/movie.vtt?token=abc&x=1")

        assertEquals(MimeTypes.TEXT_VTT, result)
    }

    @Test
    fun `resolve strips a fragment before extracting the extension`() {
        val result = SubtitleMimeTypeResolver.resolve("http://example.com/subs/movie.srt#cue=1")

        assertEquals(MimeTypes.APPLICATION_SUBRIP, result)
    }

    // ── Fallback: unknown/missing extension → SubRip default, never a crash ──

    @Test
    fun `resolve defaults to APPLICATION_SUBRIP for an unrecognised extension`() {
        val result = SubtitleMimeTypeResolver.resolve("http://example.com/subs/movie.txt")

        assertEquals(MimeTypes.APPLICATION_SUBRIP, result)
    }

    @Test
    fun `resolve defaults to APPLICATION_SUBRIP when there is no extension at all`() {
        val result = SubtitleMimeTypeResolver.resolve("http://example.com/subs/movie")

        assertEquals(MimeTypes.APPLICATION_SUBRIP, result)
    }

    @Test
    fun `resolve defaults to APPLICATION_SUBRIP for a blank url`() {
        val result = SubtitleMimeTypeResolver.resolve("")

        assertEquals(MimeTypes.APPLICATION_SUBRIP, result)
    }
}

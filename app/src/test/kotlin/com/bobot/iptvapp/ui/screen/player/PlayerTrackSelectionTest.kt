package com.bobot.iptvapp.ui.screen.player

import com.bobot.iptvapp.player.PlayerTrack
import com.bobot.iptvapp.player.PlayerTrackType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTrackSelectionTest {

    // ── hasSelectableTracks truth table ─────────────────────────────────────

    @Test
    fun `hasSelectableTracks is false when the stream offers no track at all`() {
        val result = hasSelectableTracks(audioTracks = emptyList(), subtitleTracks = emptyList())

        assertFalse(result)
    }

    @Test
    fun `hasSelectableTracks is false for a single audio track and no subtitles`() {
        val result = hasSelectableTracks(
            audioTracks = listOf(audioTrack(id = "aud-fr", isSelected = true)),
            subtitleTracks = emptyList(),
        )

        assertFalse(result)
    }

    @Test
    fun `hasSelectableTracks is true as soon as a second audio track exists`() {
        val result = hasSelectableTracks(
            audioTracks = listOf(
                audioTrack(id = "aud-fr", isSelected = true),
                audioTrack(id = "aud-en", isSelected = false),
            ),
            subtitleTracks = emptyList(),
        )

        assertTrue(result)
    }

    @Test
    fun `hasSelectableTracks is true for a single subtitle track since off is the alternative`() {
        val result = hasSelectableTracks(
            audioTracks = listOf(audioTrack(id = "aud-fr", isSelected = true)),
            subtitleTracks = listOf(subtitleTrack(id = "sub-fr", isSelected = false)),
        )

        assertTrue(result)
    }

    @Test
    fun `hasSelectableTracks is true for subtitles alone with no audio track reported`() {
        val result = hasSelectableTracks(
            audioTracks = emptyList(),
            subtitleTracks = listOf(subtitleTrack(id = "sub-fr", isSelected = true)),
        )

        assertTrue(result)
    }

    // ── areSubtitlesDisabled ────────────────────────────────────────────────

    @Test
    fun `areSubtitlesDisabled is true when the stream has no subtitle track`() {
        val result = areSubtitlesDisabled(emptyList())

        assertTrue(result)
    }

    @Test
    fun `areSubtitlesDisabled is true when subtitle tracks exist but none is applied`() {
        val result = areSubtitlesDisabled(
            listOf(
                subtitleTrack(id = "sub-fr", isSelected = false),
                subtitleTrack(id = "sub-en", isSelected = false),
            ),
        )

        assertTrue(result)
    }

    @Test
    fun `areSubtitlesDisabled is false as soon as one subtitle track is applied`() {
        val result = areSubtitlesDisabled(
            listOf(
                subtitleTrack(id = "sub-fr", isSelected = true),
                subtitleTrack(id = "sub-en", isSelected = false),
            ),
        )

        assertFalse(result)
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private fun audioTrack(id: String, isSelected: Boolean) = PlayerTrack(
        id = id,
        label = id,
        languageCode = null,
        isSelected = isSelected,
        type = PlayerTrackType.AUDIO,
    )

    private fun subtitleTrack(id: String, isSelected: Boolean) = PlayerTrack(
        id = id,
        label = id,
        languageCode = null,
        isSelected = isSelected,
        type = PlayerTrackType.SUBTITLE,
    )
}

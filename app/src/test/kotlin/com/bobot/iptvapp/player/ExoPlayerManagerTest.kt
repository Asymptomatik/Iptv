package com.bobot.iptvapp.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ExoPlayerManager]'s track exposure/selection logic (Task 2).
 *
 * ## Testing strategy — plain JVM, no Robolectric
 * `androidx.media3.common.Format`/`TrackGroup`/`Tracks`/`Tracks.Group` are plain, publicly
 * constructible value classes in `media3-common` — verified directly against this project's own
 * `media3-common-1.4.1` artifact (via `javap`) while writing this test — so real instances are
 * built here rather than mocked, mirroring Media3's own upstream unit-testing convention. `Player`
 * itself is still mocked with mockk (relaxed for anything not stubbed), exactly like
 * `PlayerViewModelTest` does for `androidx.media3.common.Player` — no Robolectric shadow is
 * needed since mockk never executes the real interface's method bodies.
 *
 * Because [ExoPlayerManager] lazily creates its own `ExoPlayer` internally (`requirePlayer()`,
 * backed by a real Android `Context` and `IptvMediaSourceFactory`, neither of which run outside
 * an Android runtime) rather than receiving it via constructor injection, [injectPlayer] reaches
 * past that private `exoPlayer` field via reflection to install the mockk `ExoPlayer` before each
 * test — [context]/[mediaSourceFactory] are relaxed mocks that are never actually exercised
 * (`prepare()`/`createPlayer()` are outside this task's scope and are not called by any test
 * here).
 *
 * ## Constructing real `Format`s: `android.text.TextUtils`/`android.util.Log`
 * `Format.Builder().setLanguage(nonNullCode)` and `TrackGroup`'s multi-format language-consistency
 * check both reach into Android framework stubs (`TextUtils.isEmpty`, `Log.e`/`getStackTraceString`)
 * that throw `"... not mocked"` by default on the plain unit-test classpath. This is why
 * `app/build.gradle.kts`'s `android.testOptions.unitTests.isReturnDefaultValues` was set to
 * `true` alongside this test (see that file's comment) — verified empirically, against this
 * project's actual `media3-common-1.4.1` jar and `compileSdk` `android.jar`, using a real JDK 21
 * (JetBrains Runtime) while implementing this fix.
 *
 * ## `Format.language` normalization — a load-bearing discovery for these fixtures
 * `Format`'s constructor normalizes ISO 639-2 (three-letter) language codes to their ISO 639-1
 * (two-letter) equivalent **itself**, internally, for common languages — e.g. a `Format` built
 * with `setLanguage("fra")` reports `format.language == "fr"`, not `"fra"` — also verified
 * empirically against the real jar. `"und"` ("undetermined") is passed through unchanged. Fixture
 * language codes and assertions below account for this: a `"fra"` input is asserted as `"fr"`
 * `languageCode` output, and the `"und"` fixtures for the Fix-2 regression rely on `"und"` staying
 * literally `"und"` all the way through to [ExoPlayerManager]'s label chain.
 */
class ExoPlayerManagerTest {

    private lateinit var context: Context
    private lateinit var mediaSourceFactory: IptvMediaSourceFactory
    private lateinit var player: ExoPlayer
    private lateinit var manager: ExoPlayerManager

    /** Backing state for the stubbed [ExoPlayer.getTrackSelectionParameters]/setter pair below. */
    private var currentParameters: TrackSelectionParameters = TrackSelectionParameters.DEFAULT

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        mediaSourceFactory = mockk(relaxed = true)
        player = mockk(relaxed = true)
        currentParameters = TrackSelectionParameters.DEFAULT

        // Stateful getter/setter pair so sequential calls (e.g. disableSubtitles() then
        // selectSubtitleTrack()) correctly build upon the params the previous call produced,
        // exactly like the real `Player.trackSelectionParameters` var property does.
        every { player.trackSelectionParameters } answers { currentParameters }
        every { player.trackSelectionParameters = any() } answers { currentParameters = firstArg() }

        manager = ExoPlayerManager(context, mediaSourceFactory)
        injectPlayer(manager, player)
    }

    /** See the class KDoc's "Testing strategy" section for why this reflection seam exists. */
    private fun injectPlayer(target: ExoPlayerManager, player: ExoPlayer) {
        val field = ExoPlayerManager::class.java.getDeclaredField("exoPlayer")
        field.isAccessible = true
        field.set(target, player)
    }

    // ── fixture builders ─────────────────────────────────────────────────────

    private fun audioFormat(id: String? = null, language: String? = null, label: String? = null): Format =
        Format.Builder()
            .setId(id)
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setLanguage(language)
            .setLabel(label)
            .build()

    private fun textFormat(id: String? = null, language: String? = null, label: String? = null): Format =
        Format.Builder()
            .setId(id)
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .setLanguage(language)
            .setLabel(label)
            .build()

    /** A single-format [Tracks.Group] wrapping [format], selected iff [selected]. */
    private fun singleTrackGroup(format: Format, selected: Boolean = false): Tracks.Group =
        Tracks.Group(
            TrackGroup(format),
            /* adaptiveSupported= */ false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(selected),
        )

    /** A multi-format [Tracks.Group] (e.g. adaptive bitrate renditions of the same track). */
    private fun multiTrackGroup(vararg formats: Format): Tracks.Group =
        Tracks.Group(
            TrackGroup(*formats),
            /* adaptiveSupported= */ true,
            IntArray(formats.size) { C.FORMAT_HANDLED },
            BooleanArray(formats.size) { false },
        )

    private fun setCurrentTracks(vararg groups: Tracks.Group) {
        every { player.currentTracks } returns Tracks(groups.toList())
    }

    // ── 1. list content + isSelected ─────────────────────────────────────────

    @Test
    fun `getAudioTracks maps container label, language, and isSelected from the current snapshot`() {
        val frenchFormat = audioFormat(id = "aud-fr", language = "fra", label = "French 5.1")
        val englishFormat = audioFormat(id = "aud-en", language = "eng", label = "English Stereo")
        setCurrentTracks(
            singleTrackGroup(frenchFormat, selected = true),
            singleTrackGroup(englishFormat, selected = false),
        )

        val tracks = manager.getAudioTracks()

        assertEquals(2, tracks.size)
        assertEquals("aud-fr", tracks[0].id)
        assertEquals("French 5.1", tracks[0].label)
        // "fra" -> "fr": Format's own constructor normalizes ISO 639-2 to ISO 639-1 — see class KDoc.
        assertEquals("fr", tracks[0].languageCode)
        assertTrue(tracks[0].isSelected)
        assertEquals(PlayerTrackType.AUDIO, tracks[0].type)

        assertEquals("aud-en", tracks[1].id)
        assertEquals("English Stereo", tracks[1].label)
        assertEquals("en", tracks[1].languageCode)
        assertFalse(tracks[1].isSelected)
    }

    @Test
    fun `getSubtitleTracks only returns text-type groups, ignoring audio groups in the same snapshot`() {
        val audio = audioFormat(id = "aud-fr", language = "fra")
        val subtitle = textFormat(id = "sub-fr", language = "fra", label = "Francais")
        setCurrentTracks(
            singleTrackGroup(audio, selected = true),
            singleTrackGroup(subtitle, selected = true),
        )

        val subtitles = manager.getSubtitleTracks()

        assertEquals(1, subtitles.size)
        assertEquals("sub-fr", subtitles[0].id)
        assertEquals("Francais", subtitles[0].label)
        assertTrue(subtitles[0].isSelected)
        assertEquals(PlayerTrackType.SUBTITLE, subtitles[0].type)
    }

    // ── 2. select with a matching id → TrackSelectionOverride applied ───────

    @Test
    fun `selectAudioTrack applies a TrackSelectionOverride for the matching TrackGroup and index`() {
        val frenchGroup = singleTrackGroup(audioFormat(id = "aud-fr", language = "fra"))
        val englishGroup = singleTrackGroup(audioFormat(id = "aud-en", language = "eng"))
        setCurrentTracks(frenchGroup, englishGroup)

        manager.selectAudioTrack("aud-en")

        assertEquals(
            TrackSelectionOverride(englishGroup.mediaTrackGroup, 0),
            currentParameters.overrides[englishGroup.mediaTrackGroup],
        )
        assertNull(currentParameters.overrides[frenchGroup.mediaTrackGroup])
    }

    @Test
    fun `selectSubtitleTrack applies a TrackSelectionOverride for the matching TrackGroup and index`() {
        val subtitleGroup = singleTrackGroup(textFormat(id = "sub-fr", language = "fra"))
        setCurrentTracks(subtitleGroup)

        manager.selectSubtitleTrack("sub-fr")

        assertEquals(
            TrackSelectionOverride(subtitleGroup.mediaTrackGroup, 0),
            currentParameters.overrides[subtitleGroup.mediaTrackGroup],
        )
        assertFalse(currentParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
    }

    // ── 3. select with a stale/non-matching id → safe no-op ──────────────────

    @Test
    fun `selectAudioTrack with an unknown id is a safe no-op — the setter is never invoked`() {
        setCurrentTracks(singleTrackGroup(audioFormat(id = "aud-fr", language = "fra")))

        manager.selectAudioTrack("stale-id-from-a-previous-snapshot")

        verify(exactly = 0) { player.trackSelectionParameters = any() }
    }

    @Test
    fun `selectSubtitleTrack with an unknown id is a safe no-op`() {
        setCurrentTracks(singleTrackGroup(textFormat(id = "sub-fr", language = "fra")))

        manager.selectSubtitleTrack("does-not-exist")

        verify(exactly = 0) { player.trackSelectionParameters = any() }
    }

    // ── 4. disableSubtitles() then selectSubtitleTrack(id) → re-enable path ──

    @Test
    fun `selectSubtitleTrack re-enables subtitles after a prior disableSubtitles call`() {
        val subtitleGroup = singleTrackGroup(textFormat(id = "sub-fr", language = "fra"))
        setCurrentTracks(subtitleGroup)

        manager.disableSubtitles()
        assertTrue(
            "disableSubtitles should disable TRACK_TYPE_TEXT",
            currentParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT),
        )

        manager.selectSubtitleTrack("sub-fr")

        assertFalse(
            "selectSubtitleTrack must re-enable TRACK_TYPE_TEXT (setTrackTypeDisabled(TEXT, false))",
            currentParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT),
        )
        assertEquals(
            TrackSelectionOverride(subtitleGroup.mediaTrackGroup, 0),
            currentParameters.overrides[subtitleGroup.mediaTrackGroup],
        )
    }

    // ── 5. empty Tracks / no groups of the requested type → empty list, no exception ─

    @Test
    fun `getAudioTracks and getSubtitleTracks return empty lists for an empty Tracks snapshot`() {
        every { player.currentTracks } returns Tracks.EMPTY

        assertEquals(emptyList<PlayerTrack>(), manager.getAudioTracks())
        assertEquals(emptyList<PlayerTrack>(), manager.getSubtitleTracks())
    }

    @Test
    fun `getSubtitleTracks returns an empty list when the snapshot has audio groups but no text groups`() {
        setCurrentTracks(singleTrackGroup(audioFormat(id = "aud-fr", language = "fra")))

        assertEquals(emptyList<PlayerTrack>(), manager.getSubtitleTracks())
    }

    @Test
    fun `selectAudioTrack against an empty Tracks snapshot does not throw and is a no-op`() {
        every { player.currentTracks } returns Tracks.EMPTY

        manager.selectAudioTrack("anything")

        verify(exactly = 0) { player.trackSelectionParameters = any() }
    }

    // ── 6. Format.id present vs. absent → correct id scheme ──────────────────

    @Test
    fun `getAudioTracks uses the stable Format id when the container provides one`() {
        setCurrentTracks(singleTrackGroup(audioFormat(id = "stable-hls-id", language = "fra")))

        val tracks = manager.getAudioTracks()

        assertEquals("stable-hls-id", tracks.single().id)
    }

    @Test
    fun `getAudioTracks falls back to a positional id derived from group and track index when Format id is absent`() {
        // Group 0 has two formats (e.g. two adaptive bitrate renditions of the same French
        // track) — exercises both the groupIndex and trackIndex components of the
        // "audio-<groupIndex>-<trackIndex>" fallback; group 1 (Spanish) exercises groupIndex
        // incrementing across groups.
        setCurrentTracks(
            multiTrackGroup(
                audioFormat(id = null, language = "fra"),
                audioFormat(id = null, language = "fra"),
            ),
            singleTrackGroup(audioFormat(id = null, language = "spa")),
        )

        val ids = manager.getAudioTracks().map { it.id }

        assertEquals(listOf("audio-0-0", "audio-0-1", "audio-1-0"), ids)
    }

    // ── Fix 2 regression: "und" + no container label → generic fallback, not the raw "und" ──

    @Test
    fun `a track tagged und with no container label gets the generic positional label, not the raw code`() {
        setCurrentTracks(
            multiTrackGroup(
                audioFormat(id = "a1", language = "und", label = null),
                audioFormat(id = "a2", language = "und", label = null),
            ),
        )

        val labels = manager.getAudioTracks().map { it.label }

        assertEquals(listOf("Audio 1", "Audio 2"), labels)
        assertTrue(labels.none { it.equals("und", ignoreCase = true) })
    }

    @Test
    fun `a subtitle track tagged und with no container label gets the generic positional label`() {
        setCurrentTracks(singleTrackGroup(textFormat(id = "s1", language = "und", label = null)))

        val label = manager.getSubtitleTracks().single().label

        assertEquals("Subtitle 1", label)
        assertFalse(label.equals("und", ignoreCase = true))
    }
}

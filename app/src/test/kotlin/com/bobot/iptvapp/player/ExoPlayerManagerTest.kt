package com.bobot.iptvapp.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.bobot.iptvapp.domain.model.ExternalSubtitle
import com.bobot.iptvapp.domain.util.LanguageLabel
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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
        Dispatchers.setMain(StandardTestDispatcher())
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

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── ActivePlaybackStopper (review finding H2) ────────────────────────────

    @Test
    fun `stopActivePlayback releases the player`() = runTest {
        manager.stopActivePlayback()

        verify { player.release() }
    }

    // ── Side-loaded subtitle failures ────────────────────────────────────────

    private val reported = mutableListOf<String>()

    /** Prepares [MOVIE_URL] and returns the failure callback it handed the factory, if any. */
    private fun prepareCapturingCallback(): ((String) -> Unit)? {
        val callbacks = mutableListOf<((String) -> Unit)?>()
        every { mediaSourceFactory.create(any(), any(), captureNullable(callbacks)) } returns mockk(relaxed = true)
        manager.prepare(MOVIE_URL, 0L, emptyList())
        return callbacks.single()
    }

    @Test
    fun `a failure of the current media is reported to the listener`() {
        manager.setSideLoadedSubtitleErrorListener { reported += it }

        prepareCapturingCallback()!!.invoke("online-subtitle-1")

        assertEquals(listOf("online-subtitle-1"), reported)
    }

    @Test
    fun `without a listener the factory gets no callback, so failures keep failing playback`() {
        assertNull(prepareCapturingCallback())
    }

    @Test
    fun `a failure of a replaced media is not reported`() {
        manager.setSideLoadedSubtitleErrorListener { reported += it }
        val first = prepareCapturingCallback()!!
        prepareCapturingCallback()

        first("online-subtitle-1")

        assertTrue(reported.isEmpty())
    }

    @Test
    fun `release forgets the listener and silences the released media`() {
        manager.setSideLoadedSubtitleErrorListener { reported += it }
        val callback = prepareCapturingCallback()!!

        manager.release()
        callback("online-subtitle-1")

        assertTrue(reported.isEmpty())
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

    // ── unique fallback labels: one group per track (side-loaded, most embedded) ──

    @Test
    fun `subtitle tracks without language or label, each in its own group, get distinct numbers`() {
        // Every side-loaded subtitle is its own MergingMediaSource child, hence its own group,
        // at track index 0 — so is each embedded text track of a typical MKV.
        setCurrentTracks(
            singleTrackGroup(textFormat()),
            singleTrackGroup(textFormat(language = "und")),
            singleTrackGroup(textFormat(id = "online-1")),
        )

        val tracks = manager.getSubtitleTracks()

        assertEquals(listOf("Subtitle 1", "Subtitle 2", "Subtitle 3"), tracks.map { it.label })
        assertEquals(listOf("subtitle-0-0", "subtitle-1-0", "online-1"), tracks.map { it.id })
    }

    @Test
    fun `a known language keeps its name and an unknown track is numbered by its place in the list`() {
        val french = LanguageLabel.forCode("fr")!!
        setCurrentTracks(
            singleTrackGroup(textFormat(language = "fr")),
            singleTrackGroup(textFormat()),
            singleTrackGroup(textFormat(language = "und")),
        )

        val labels = manager.getSubtitleTracks().map { it.label }

        assertEquals(listOf(french, "Subtitle 2", "Subtitle 3"), labels)
    }

    @Test
    fun `audio tracks in separate groups without language get distinct numbers too`() {
        setCurrentTracks(
            singleTrackGroup(audioFormat(id = "a1")),
            singleTrackGroup(audioFormat(id = "a2")),
        )

        assertEquals(listOf("Audio 1", "Audio 2"), manager.getAudioTracks().map { it.label })
    }

    // ── 7. re-prepare keeps the audio track the user chose ───────────────────
    //
    // A `TrackSelectionOverride` names a `TrackGroup`, and a re-prepare (online subtitle pick,
    // "Réessayer") builds new ones. Once a side-loaded subtitle is merged in, Media3's
    // `MergingMediaPeriod` also renames every group and format id to `"<child>:<id>"` — verified
    // against media3-exoplayer-1.4.1 — so the old override matches nothing and Media3 silently
    // falls back to its default audio track.

    private val listenerSlot = slot<Player.Listener>()

    private fun captureListener() {
        every { player.addListener(capture(listenerSlot)) } just Runs
    }

    /** [group] as the primary child of a `MergingMediaSource` reports it. */
    private fun merged(group: Tracks.Group): Tracks.Group {
        val source = group.mediaTrackGroup
        val formats = Array(source.length) { i ->
            val format = source.getFormat(i)
            format.buildUpon().setId("0:${format.id.orEmpty()}").build()
        }
        return Tracks.Group(
            TrackGroup("0:${source.id}", *formats),
            /* adaptiveSupported= */ false,
            IntArray(source.length) { C.FORMAT_HANDLED },
            BooleanArray(source.length) { false },
        )
    }

    private fun tracksChanged(vararg groups: Tracks.Group) {
        val tracks = Tracks(groups.toList())
        every { player.currentTracks } returns tracks
        // Nothing to notify when prepare registered no listener.
        if (listenerSlot.isCaptured) listenerSlot.captured.onTracksChanged(tracks)
    }

    private fun prepareAgain() {
        manager.prepare(MOVIE_URL, 47_000L, listOf(ExternalSubtitle("file:/data/1001.fr.srt", "fr")))
    }

    @Test
    fun `a re-prepare that merges in a subtitle switches the chosen audio track back on`() {
        captureListener()
        val french = singleTrackGroup(audioFormat(id = "aud-fr", language = "fra"), selected = true)
        val english = singleTrackGroup(audioFormat(id = "aud-en", language = "eng"))
        setCurrentTracks(french, english)
        manager.selectAudioTrack("aud-en")

        prepareAgain()
        val newEnglish = merged(english)
        tracksChanged(merged(french), newEnglish)

        assertEquals(
            TrackSelectionOverride(newEnglish.mediaTrackGroup, 0),
            currentParameters.overrides[newEnglish.mediaTrackGroup],
        )
    }

    @Test
    fun `tracks without a container id are matched on what they carry, not on their position id`() {
        captureListener()
        val first = singleTrackGroup(audioFormat(language = "fra", label = "VF"), selected = true)
        val second = singleTrackGroup(audioFormat(language = "eng", label = "VO"))
        setCurrentTracks(first, second)
        manager.selectAudioTrack("audio-1-0")

        prepareAgain()
        val newSecond = merged(second)
        tracksChanged(merged(first), newSecond)

        assertEquals(
            TrackSelectionOverride(newSecond.mediaTrackGroup, 0),
            currentParameters.overrides[newSecond.mediaTrackGroup],
        )
    }

    @Test
    fun `the replaced media's tracks, or none yet, never consume the choice`() {
        captureListener()
        val french = singleTrackGroup(audioFormat(id = "aud-fr", language = "fra"), selected = true)
        val english = singleTrackGroup(audioFormat(id = "aud-en", language = "eng"))
        setCurrentTracks(french, english)
        manager.selectAudioTrack("aud-en")

        prepareAgain()
        tracksChanged()
        tracksChanged(french, english)
        val newEnglish = merged(english)
        tracksChanged(merged(french), newEnglish)

        assertEquals(
            TrackSelectionOverride(newEnglish.mediaTrackGroup, 0),
            currentParameters.overrides[newEnglish.mediaTrackGroup],
        )
    }

    @Test
    fun `a retry on the very same tracks leaves the chosen override as it is`() {
        captureListener()
        val french = singleTrackGroup(audioFormat(id = "aud-fr", language = "fra"), selected = true)
        val english = singleTrackGroup(audioFormat(id = "aud-en", language = "eng"))
        setCurrentTracks(french, english)
        manager.selectAudioTrack("aud-en")
        val chosen = currentParameters

        manager.prepare(MOVIE_URL, 47_000L, emptyList())
        tracksChanged(french, english)

        assertEquals(chosen, currentParameters)
    }

    @Test
    fun `without an audio choice a re-prepare selects nothing by hand`() {
        captureListener()
        val french = singleTrackGroup(audioFormat(id = "aud-fr", language = "fra"), selected = true)
        val english = singleTrackGroup(audioFormat(id = "aud-en", language = "eng"))
        setCurrentTracks(french, english)

        prepareAgain()
        tracksChanged(merged(french), merged(english))

        verify(exactly = 0) { player.trackSelectionParameters = any() }
    }

    @Test
    fun `a choice made by hand after the re-prepare is never overridden by the old one`() {
        captureListener()
        val french = singleTrackGroup(audioFormat(id = "aud-fr", language = "fra"), selected = true)
        val english = singleTrackGroup(audioFormat(id = "aud-en", language = "eng"))
        setCurrentTracks(french, english)
        manager.selectAudioTrack("aud-en")

        prepareAgain()
        val newFrench = merged(french)
        val newEnglish = merged(english)
        setCurrentTracks(newFrench, newEnglish)
        manager.selectAudioTrack("0:aud-fr")
        tracksChanged(newFrench, newEnglish)

        assertEquals(
            TrackSelectionOverride(newFrench.mediaTrackGroup, 0),
            currentParameters.overrides[newFrench.mediaTrackGroup],
        )
        assertNull(currentParameters.overrides[newEnglish.mediaTrackGroup])
    }

    // ── 8. side-loaded subtitle ids survive the merge ────────────────────────
    //
    // `MergingMediaPeriod` (media3-exoplayer-1.4.1) renames every format to
    // `"<child index>:" + (format.id ?: "")` and every group to `"<child index>:" + group.id`.
    // The fixtures below are built exactly that way — never with the bare requested id.

    /** A text group as `MergingMediaPeriod` reports child [child]'s single track of id [childFormatId]. */
    private fun mergedTextGroup(child: Int, childFormatId: String?, language: String? = null): Tracks.Group =
        Tracks.Group(
            TrackGroup("$child:0", textFormat(id = "$child:${childFormatId.orEmpty()}", language = language)),
            /* adaptiveSupported= */ false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(false),
        )

    private fun prepareWithOnline(onlineTrackId: String) {
        manager.prepare(
            MOVIE_URL,
            47_000L,
            listOf(
                ExternalSubtitle("http://example.com/subs/1.srt", "en"),
                ExternalSubtitle("file:/data/1001.fr.srt", "fr", trackId = onlineTrackId),
            ),
        )
    }

    @Test
    fun `a side-loaded subtitle is listed under the id prepare asked for, not the merged one`() {
        prepareWithOnline("online-subtitle-1")
        setCurrentTracks(
            merged(singleTrackGroup(textFormat(id = "3", language = "eng"))),
            mergedTextGroup(child = 1, childFormatId = null, language = "en"),
            mergedTextGroup(child = 2, childFormatId = "online-subtitle-1", language = "fr"),
        )

        val ids = manager.getSubtitleTracks().map { it.id }

        // Embedded and Xtream tracks keep the id they had before this fix.
        assertEquals(listOf("0:3", "1:", "online-subtitle-1"), ids)
    }

    @Test
    fun `selecting a side-loaded subtitle by its requested id overrides its merged group`() {
        prepareWithOnline("online-subtitle-1")
        val embedded = merged(singleTrackGroup(textFormat(id = "3", language = "eng")))
        val online = mergedTextGroup(child = 2, childFormatId = "online-subtitle-1", language = "fr")
        setCurrentTracks(embedded, mergedTextGroup(child = 1, childFormatId = null), online)

        manager.selectSubtitleTrack("online-subtitle-1")

        assertEquals(
            TrackSelectionOverride(online.mediaTrackGroup, 0),
            currentParameters.overrides[online.mediaTrackGroup],
        )
        assertNull(currentParameters.overrides[embedded.mediaTrackGroup])
    }

    @Test
    fun `an embedded track sharing the requested id keeps its merged id`() {
        prepareWithOnline("online-subtitle-1")
        val embedded = merged(singleTrackGroup(textFormat(id = "online-subtitle-1", language = "eng")))
        val online = mergedTextGroup(child = 2, childFormatId = "online-subtitle-1", language = "fr")
        setCurrentTracks(embedded, online)

        assertEquals(listOf("0:online-subtitle-1", "online-subtitle-1"), manager.getSubtitleTracks().map { it.id })
        manager.selectSubtitleTrack("online-subtitle-1")
        assertNull(currentParameters.overrides[embedded.mediaTrackGroup])
        assertEquals(
            TrackSelectionOverride(online.mediaTrackGroup, 0),
            currentParameters.overrides[online.mediaTrackGroup],
        )
    }

    @Test
    fun `a merged id prepare did not ask for is left as Media3 reports it`() {
        prepareWithOnline("online-subtitle-2")
        // The previous pick's track, still reported by the replaced media.
        setCurrentTracks(mergedTextGroup(child = 2, childFormatId = "online-subtitle-1"))

        assertEquals(listOf("2:online-subtitle-1"), manager.getSubtitleTracks().map { it.id })
        manager.selectSubtitleTrack("online-subtitle-1")
        verify(exactly = 0) { player.trackSelectionParameters = any() }
    }

    @Test
    fun `release forgets the requested side-loaded ids`() {
        prepareWithOnline("online-subtitle-1")
        manager.release()
        injectPlayer(manager, player)
        setCurrentTracks(mergedTextGroup(child = 2, childFormatId = "online-subtitle-1"))

        assertEquals(listOf("2:online-subtitle-1"), manager.getSubtitleTracks().map { it.id })
    }

    private companion object {
        const val MOVIE_URL = "http://example.com:8080/movie/u/p/42.mp4"
    }
}

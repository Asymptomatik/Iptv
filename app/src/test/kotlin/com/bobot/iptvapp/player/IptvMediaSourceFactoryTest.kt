package com.bobot.iptvapp.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleExtractor
import com.bobot.iptvapp.domain.model.ExternalSubtitle
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI

/**
 * Unit tests for [IptvMediaSourceFactory]'s external subtitle side-loading (Task 3).
 *
 * ## `Uri.parse` on plain JVM — why this class statically mocks [Uri]
 * [IptvMediaSourceFactory.create] builds its *video* [androidx.media3.common.MediaItem] via
 * `MediaItem.Builder().setUri(streamUrl)` — the `String` overload, which internally calls
 * `Uri.parse(streamUrl)` (verified directly against the `media3-common-1.4.1` jar's
 * `MediaItem$Builder.setUri(String)` bytecode). `android.net.Uri.parse` is an unstubbed
 * `android.jar` call: on this module's plain-JVM unit test classpath it either throws
 * `"... not mocked"` (default AGP mockable-jar behavior) or returns `null`
 * (`android.testOptions.unitTests.isReturnDefaultValues = true`, set in `app/build.gradle.kts`
 * for [ExoPlayerManagerTest]'s needs — see that class's KDoc), depending on how the test JVM is
 * launched. Either way, `mediaItem.localConfiguration` ends up unusable and
 * `HlsMediaSource.Factory.createMediaSource`/`ProgressiveMediaSource.Factory.createMediaSource`
 * (both start with `checkNotNull(mediaItem.localConfiguration)`) blow up — for *every* call to
 * [IptvMediaSourceFactory.create], not just the subtitle-specific path, since the *video*
 * `MediaItem` goes through the exact same `Uri.parse` call.
 *
 * [Uri.class] is therefore statically mocked here (`mockkStatic`/`unmockkStatic`, scoped to
 * `@Before`/`@After` so it never leaks into sibling test classes sharing this JVM — e.g.
 * [ExoPlayerManagerTest], `PlayerViewModelTest`, `MovieMapperTest`): `Uri.parse(any())` is
 * stubbed to return a relaxed [Uri] mock whose `scheme` is `"http"` for every input, **except**
 * [BLANK_SCHEME_SUBTITLE_URL], which resolves to a blank-scheme [Uri] so the "malformed/schemeless
 * subtitle URL" guard in [IptvMediaSourceFactory.toSubtitleMediaSourceOrNull] can still be
 * exercised deterministically. This unblocks the video [androidx.media3.common.MediaItem] for
 * every test (so `create()` can return a real [MediaSource] instead of NPE-ing before any
 * subtitle logic runs) and, as a side effect, also makes the subtitle side-loading path itself
 * buildable, which lets this class assert the positive [MergingMediaSource] path directly instead
 * of deferring it to instrumentation.
 *
 * Genuinely blank subtitle URLs (`""`, `" "`) are unaffected by the mock: [IptvMediaSourceFactory]
 * checks `url.isBlank()` *before* ever calling `Uri.parse`, so those entries are skipped without
 * the stub being consulted at all.
 *
 * ## The "usable subtitle" tests below still depend on `isReturnDefaultValues = true`
 * Once a subtitle URL passes the blank/scheme guards, building its subtitle [ProgressiveMediaSource]
 * constructs a real [androidx.media3.common.Format], whose constructor normalizes the language
 * code via `Util.normalizeLanguageCode` → `TextUtils.isEmpty` (another unstubbed `android.jar`
 * call) — the exact same environment dependency [ExoPlayerManagerTest]'s KDoc documents for
 * `Format.Builder().setLanguage(...)`. The "usable subtitle" tests below therefore rely on the
 * module's `isReturnDefaultValues = true` flag exactly like [ExoPlayerManagerTest] already does;
 * they are not self-contained the way the [Uri] mock above makes the blank/schemeless-URL tests.
 */
class IptvMediaSourceFactoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var factory: IptvMediaSourceFactory
    private lateinit var subtitleDirectory: File

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            val input = firstArg<String>()
            mockk(relaxed = true) {
                when {
                    input == BLANK_SCHEME_SUBTITLE_URL -> every { scheme } returns ""
                    // A file: Uri carries its real path — FileDataSource opens exactly uri.path.
                    input.startsWith("file:") -> {
                        every { scheme } returns "file"
                        every { path } returns File(URI(input)).path
                    }
                    else -> every { scheme } returns "http"
                }
            }
        }

        subtitleDirectory = tmp.newFolder("online_subtitles")
        factory = IptvMediaSourceFactory(
            mockk<OkHttpClient>(relaxed = true),
            mockk<Cache>(relaxed = true),
            subtitleDirectory,
        )
    }

    @After
    fun tearDown() {
        // Scoped cleanup: an un-cleared static mock on android.net.Uri would otherwise leak into
        // every other test class run in the same JVM (ExoPlayerManagerTest, PlayerViewModelTest,
        // MovieMapperTest, ...), silently corrupting any of their own Uri usages.
        unmockkStatic(Uri::class)
        unmockkStatic(Log::class)
    }

    // ── empty / all-unusable external subtitles → plain video source, no wrapping ──────

    @Test
    fun `create with an empty external subtitles list returns the plain video source, not a MergingMediaSource`() {
        val source = factory.create("http://example.com:8080/movie/u/p/1.mp4")

        assertFalse(
            "externalSubtitles=emptyList() (the default) must stay byte-for-byte identical to " +
                "pre-Task-3 behavior — no MergingMediaSource wrapping.",
            source is MergingMediaSource,
        )
        assertTrue(source is ProgressiveMediaSource)
    }

    @Test
    fun `create with only a blank-url external subtitle falls back to the plain video source`() {
        val source = factory.create(
            streamUrl = "http://example.com:8080/movie/u/p/1.mp4",
            externalSubtitles = listOf(ExternalSubtitle(url = "", language = "en")),
        )

        assertFalse(
            "A blank subtitle URL is skipped best-effort, never wrapped into a MergingMediaSource",
            source is MergingMediaSource,
        )
    }

    @Test
    fun `create with only blank-url external subtitles across several entries still falls back cleanly`() {
        val source = factory.create(
            streamUrl = "http://example.com:8080/live/u/p/2.ts",
            externalSubtitles = listOf(
                ExternalSubtitle(url = "", language = "en"),
                ExternalSubtitle(url = " ", language = null),
            ),
        )

        assertFalse(source is MergingMediaSource)
    }

    @Test
    fun `create with only a schemeless subtitle url falls back to the plain video source`() {
        val source = factory.create(
            streamUrl = "http://example.com:8080/movie/u/p/1.mp4",
            externalSubtitles = listOf(ExternalSubtitle(url = BLANK_SCHEME_SUBTITLE_URL, language = "en")),
        )

        assertFalse(
            "A subtitle URL that fails the Uri scheme guard is skipped, never wrapped",
            source is MergingMediaSource,
        )
    }

    // ── usable external subtitle(s) → MergingMediaSource wrapping ───────────────────────

    @Test
    fun `create with one usable external subtitle returns a MergingMediaSource wrapping the video source`() {
        val source = factory.create(
            streamUrl = "http://example.com:8080/movie/u/p/1.mp4",
            externalSubtitles = listOf(ExternalSubtitle(url = "http://example.com/subs/1.srt", language = "en")),
        )

        assertTrue("A usable subtitle must be side-loaded via MergingMediaSource", source is MergingMediaSource)
        val children = mergingMediaSourceChildren(source as MergingMediaSource)
        assertEquals(
            "Exactly one video source + one subtitle source expected",
            2,
            children.size,
        )
        assertTrue("First child must remain the video source", children[0] is ProgressiveMediaSource)
    }

    @Test
    fun `create preserves the HLS video source type when wrapping it with a usable subtitle`() {
        val source = factory.create(
            streamUrl = "http://example.com:8080/live/u/p/1.m3u8",
            externalSubtitles = listOf(ExternalSubtitle(url = "http://example.com/subs/1.vtt", language = "en")),
        )

        assertTrue(source is MergingMediaSource)
        val children = mergingMediaSourceChildren(source as MergingMediaSource)
        assertTrue(
            "The wrapped video source must stay an HlsMediaSource for a .m3u8 stream",
            children[0] is HlsMediaSource,
        )
    }

    @Test
    fun `create with a mixed usable and unusable subtitle list only side-loads the usable entry`() {
        val source = factory.create(
            streamUrl = "http://example.com:8080/movie/u/p/1.mp4",
            externalSubtitles = listOf(
                ExternalSubtitle(url = "http://example.com/subs/1.srt", language = "en"),
                ExternalSubtitle(url = "", language = "fr"),
                ExternalSubtitle(url = BLANK_SCHEME_SUBTITLE_URL, language = "es"),
            ),
        )

        assertTrue(
            "At least one usable subtitle must still produce a MergingMediaSource",
            source is MergingMediaSource,
        )
        val children = mergingMediaSourceChildren(source as MergingMediaSource)
        assertEquals(
            "Only the video source + the single usable subtitle source should be merged — " +
                "the blank and schemeless entries must not contribute extra children",
            2,
            children.size,
        )
    }

    // ── side-loaded subtitles reach the TextRenderer as cues ────────────────────────────
    //
    // Media3 1.4.1's TextRenderer refuses anything but `application/x-media3-cues` (and CEA-608/708)
    // unless legacy decoding is enabled: a raw SubRip sample fails its `checkState` as soon as the
    // track is selected. The subtitle has to be parsed while it is extracted, as
    // DefaultMediaSourceFactory does by default — ProgressiveMediaSource + SubtitleExtractor.

    @Test
    fun `an online subtitle is extracted into cues carrying the id, language and flags it was asked for`() {
        val file = File(subtitleDirectory, "222.fr.srt").apply { writeText("1\n") }

        val source = factory.create(
            streamUrl = "http://example.com:8080/movie/u/p/1.mp4",
            externalSubtitles = listOf(
                ExternalSubtitle(url = file.toURI().toString(), language = "fr", trackId = "online-subtitle-1"),
            ),
        )

        val format = extractedSubtitleFormat(mergingMediaSourceChildren(source as MergingMediaSource)[1])
        assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, format.sampleMimeType)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, format.codecs)
        assertEquals("online-subtitle-1", format.id)
        assertEquals("fr", format.language)
        assertEquals(C.ROLE_FLAG_SUBTITLE, format.roleFlags)
        assertEquals("never auto-selected", 0, format.selectionFlags)
    }

    @Test
    fun `an Xtream subtitle is extracted into cues too, of the type its URL names`() {
        val source = factory.create(
            streamUrl = "http://example.com:8080/movie/u/p/1.mp4",
            externalSubtitles = listOf(ExternalSubtitle(url = "http://example.com/subs/1.vtt", language = "en")),
        )

        val format = extractedSubtitleFormat(mergingMediaSourceChildren(source as MergingMediaSource)[1])
        assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, format.sampleMimeType)
        assertEquals(MimeTypes.TEXT_VTT, format.codecs)
        assertNull(format.id)
        assertEquals(0, format.selectionFlags)
    }

    // ── downloaded (file:) subtitles → read from the online subtitle directory only ─────

    @Test
    fun `a downloaded subtitle is actually read from disk through the factory's data source`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nBonjour\n".toByteArray()
        val file = File(subtitleDirectory, "222.fr.srt").apply { writeBytes(srt) }
        val uri = Uri.parse(file.toURI().toString())

        val dataSourceFactory = factory.subtitleDataSourceFactory(uri)

        assertTrue(dataSourceFactory is FileDataSource.Factory)
        val dataSource = dataSourceFactory!!.createDataSource()
        val length = dataSource.open(DataSpec(uri))
        val read = ByteArray(length.toInt())
        var offset = 0
        while (offset < read.size) offset += dataSource.read(read, offset, read.size - offset)
        dataSource.close()
        assertEquals(srt.size.toLong(), length)
        assertEquals(String(srt), String(read))
    }

    @Test
    fun `a downloaded subtitle is side-loaded next to the video source`() {
        val file = File(subtitleDirectory, "222.fr.srt").apply { writeText("1\n") }

        val source = factory.create(
            streamUrl = "http://example.com:8080/movie/u/p/1.mp4",
            externalSubtitles = listOf(ExternalSubtitle(url = file.toURI().toString(), language = "fr")),
        )

        assertTrue(source is MergingMediaSource)
        assertEquals(2, mergingMediaSourceChildren(source as MergingMediaSource).size)
    }

    @Test
    fun `a file subtitle outside the online subtitle directory is refused, and playback falls back`() {
        val outside = tmp.newFile("secret.srt").apply { writeText("1\n") }
        val escaping = subtitleDirectory.toURI().toString() + "../secret.srt"

        assertNull(factory.subtitleDataSourceFactory(Uri.parse(outside.toURI().toString())))
        assertNull(factory.subtitleDataSourceFactory(Uri.parse(escaping)))
        val source = factory.create(
            streamUrl = "http://example.com:8080/movie/u/p/1.mp4",
            externalSubtitles = listOf(ExternalSubtitle(url = outside.toURI().toString(), language = "fr")),
        )
        assertFalse(source is MergingMediaSource)
    }

    @Test(expected = FileDataSource.FileDataSourceException::class)
    fun `a downloaded subtitle purged before playback fails to open rather than reading anything`() {
        val uri = Uri.parse(File(subtitleDirectory, "222.fr.srt").toURI().toString())

        factory.subtitleDataSourceFactory(uri)!!.createDataSource().open(DataSpec(uri))
    }

    // ── a skipped subtitle never logs its URL ───────────────────────────────────────────

    @Test
    fun `a subtitle that fails to build is skipped with a warning that reveals neither its URL nor the error text`() {
        mockkStatic(Log::class)
        val logged = mutableListOf<String>()
        every { Log.w(any(), any<String>()) } answers { logged += secondArg<String>(); 0 }
        every { Log.w(any(), any<String>(), any()) } answers {
            logged += secondArg<String>() + " " + thirdArg<Throwable>().stackTraceToString(); 0
        }
        every { Log.w(any(), any<Throwable>()) } answers { logged += secondArg<Throwable>().stackTraceToString(); 0 }
        every { Uri.parse(SENTINEL_SUBTITLE_URL) } throws IllegalArgumentException("unparseable $SENTINEL_SUBTITLE_URL")

        val source = factory.create(
            streamUrl = "http://example.com:8080/movie/u/p/1.mp4",
            externalSubtitles = listOf(ExternalSubtitle(url = SENTINEL_SUBTITLE_URL, language = "fr")),
        )

        assertFalse(source is MergingMediaSource)
        assertEquals(1, logged.size)
        listOf("sentinel-host", "SENTINEL_USER", "SENTINEL_PASS", "unparseable").forEach { secret ->
            assertFalse("log must not contain $secret: ${logged.single()}", logged.single().contains(secret))
        }
    }

    /**
     * Reaches into [MergingMediaSource]'s private `mediaSources` field via reflection — the class
     * has no public accessor for its children — to assert the exact number/order of sources it
     * was constructed with. See this class's KDoc for why the type itself (rather than a public
     * API) is the only way to verify this on plain JVM.
     */
    private fun mergingMediaSourceChildren(source: MergingMediaSource): List<MediaSource> {
        val field = MergingMediaSource::class.java.getDeclaredField("mediaSources")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(source) as Array<MediaSource>).toList()
    }

    /**
     * The [Format] the extractor of side-loaded [source] hands to the player's `TrackOutput` —
     * reached by reflection (media3-exoplayer/extractor 1.4.1 field names) since neither
     * [ProgressiveMediaSource] nor its extractor adapter expose them. Fails unless [source] is a
     * [ProgressiveMediaSource] whose single extractor is a [SubtitleExtractor].
     */
    private fun extractedSubtitleFormat(source: MediaSource): Format {
        assertTrue("expected a ProgressiveMediaSource, got ${source.javaClass.simpleName}", source is ProgressiveMediaSource)
        val adapter = (privateField(source, "progressiveMediaExtractorFactory") as ProgressiveMediaExtractor.Factory)
            .createProgressiveMediaExtractor(PlayerId.UNSET)
        val extractor = (privateField(adapter, "extractorsFactory") as ExtractorsFactory).createExtractors().single()
        assertTrue("expected a SubtitleExtractor, got ${extractor.javaClass.simpleName}", extractor is SubtitleExtractor)
        return privateField(extractor, "format") as Format
    }

    private fun privateField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private companion object {
        /** A URL for which the [Uri.parse] stub in [setUp] deliberately returns a blank scheme. */
        const val BLANK_SCHEME_SUBTITLE_URL = "schemeless-subtitle.srt"

        /** Shaped like an Xtream subtitle URL, whose path carries the account's credentials. */
        const val SENTINEL_SUBTITLE_URL = "http://sentinel-host:8080/movie/SENTINEL_USER/SENTINEL_PASS/1.srt"
    }
}

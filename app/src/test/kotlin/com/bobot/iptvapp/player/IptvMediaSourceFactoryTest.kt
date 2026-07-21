package com.bobot.iptvapp.player

import android.net.Uri
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.bobot.iptvapp.domain.model.ExternalSubtitle
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
 * Once a subtitle URL passes the blank/scheme guards, building its [SingleSampleMediaSource]
 * constructs a real [androidx.media3.common.Format], whose constructor normalizes the language
 * code via `Util.normalizeLanguageCode` → `TextUtils.isEmpty` (another unstubbed `android.jar`
 * call) — the exact same environment dependency [ExoPlayerManagerTest]'s KDoc documents for
 * `Format.Builder().setLanguage(...)`. The "usable subtitle" tests below therefore rely on the
 * module's `isReturnDefaultValues = true` flag exactly like [ExoPlayerManagerTest] already does;
 * they are not self-contained the way the [Uri] mock above makes the blank/schemeless-URL tests.
 */
class IptvMediaSourceFactoryTest {

    private lateinit var factory: IptvMediaSourceFactory

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            val input = firstArg<String>()
            mockk(relaxed = true) {
                every { scheme } returns if (input == BLANK_SCHEME_SUBTITLE_URL) "" else "http"
            }
        }

        factory = IptvMediaSourceFactory(mockk<OkHttpClient>(relaxed = true), mockk<Cache>(relaxed = true))
    }

    @After
    fun tearDown() {
        // Scoped cleanup: an un-cleared static mock on android.net.Uri would otherwise leak into
        // every other test class run in the same JVM (ExoPlayerManagerTest, PlayerViewModelTest,
        // MovieMapperTest, ...), silently corrupting any of their own Uri usages.
        unmockkStatic(Uri::class)
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

    private companion object {
        /** A URL for which the [Uri.parse] stub in [setUp] deliberately returns a blank scheme. */
        const val BLANK_SCHEME_SUBTITLE_URL = "schemeless-subtitle.srt"
    }
}

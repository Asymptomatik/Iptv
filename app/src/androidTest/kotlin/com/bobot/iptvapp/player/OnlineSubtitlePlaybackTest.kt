package com.bobot.iptvapp.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bobot.iptvapp.di.OnlineSubtitlesModule
import com.bobot.iptvapp.domain.model.ExternalSubtitle
import com.bobot.iptvapp.domain.util.LanguageLabel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Plays a real media through the real [IptvMediaSourceFactory] and [ExoPlayerManager] to prove an
 * online subtitle downloaded into [OnlineSubtitlesModule.provideOnlineSubtitleDirectory] renders
 * cues: `file:` through `FileDataSource`, `SubtitleExtractor` parsing, and the merged
 * `"<child>:<id>"` track selected back under its logical id.
 *
 * ## Why no Hilt
 * The app has no Hilt test runner; the two classes are built with their production constructors
 * instead. Only the collaborators differ: a plain [OkHttpClient] (no interceptor matters to a
 * loopback request) and a throwaway [SimpleCache] in its own directory, so the user's download
 * cache and its database are never opened. The subtitle directory is the production one.
 *
 * ## Why a loopback server
 * The video goes through the factory's network path (`CacheDataSource` over `OkHttpDataSource`),
 * which cannot read `file:` — exactly like a real Xtream stream, the 6 s fixture MP4 is therefore
 * served over `http://127.0.0.1`. No request leaves the device.
 *
 * ## Running
 * ```
 * adb.exe shell am instrument -w \
 *   -e class com.bobot.iptvapp.player.OnlineSubtitlePlaybackTest \
 *   com.bobot.iptvapp.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class OnlineSubtitlePlaybackTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    private lateinit var server: LoopbackFileServer
    private lateinit var cacheDir: File
    private lateinit var cache: SimpleCache
    private lateinit var manager: ExoPlayerManager
    private lateinit var subtitleFile: File

    private val cueTexts = CopyOnWriteArrayList<String>()
    private val subtitleErrors = CopyOnWriteArrayList<String>()
    private val cueListener = object : Player.Listener {
        override fun onCues(cueGroup: CueGroup) {
            cueGroup.cues.mapNotNullTo(cueTexts) { it.text?.toString() }
        }
    }

    @Before
    fun setUp() {
        val video = instrumentation.context.assets.open(VIDEO_ASSET).use { it.readBytes() }
        server = LoopbackFileServer(video)

        cacheDir = File(context.cacheDir, "media3-proof-${UUID.randomUUID()}")
        @Suppress("DEPRECATION") // The database-backed constructor would open the app's own database.
        cache = SimpleCache(cacheDir, NoOpCacheEvictor())

        val subtitleDirectory = OnlineSubtitlesModule.provideOnlineSubtitleDirectory(context)
        subtitleDirectory.mkdirs()
        subtitleFile = File(subtitleDirectory, "media3-proof-${UUID.randomUUID()}.srt")

        val factory = IptvMediaSourceFactory(OkHttpClient(), cache, subtitleDirectory)
        manager = ExoPlayerManager(context, factory)
        onMain {
            manager.setSideLoadedSubtitleErrorListener { subtitleErrors += it }
            manager.player.addListener(cueListener)
        }
    }

    @After
    fun tearDown() {
        onMain { manager.release() }
        cache.release()
        cacheDir.deleteRecursively()
        subtitleFile.delete()
        server.close()
    }

    @Test
    fun aDownloadedSrtRendersItsCuesOnceItsLogicalIdIsSelected() {
        subtitleFile.writeText(VALID_SRT)

        onMain { manager.prepare(server.url, 0L, listOf(onlineSubtitle())) }

        awaitTrue("the online track to be listed") {
            manager.getSubtitleTracks().any { it.id == ONLINE_TRACK_ID }
        }
        // Media3 renamed the side-loaded format; the manager lists it under the requested id.
        val mergedIds = onMain {
            manager.player.currentTracks.groups
                .filter { it.type == C.TRACK_TYPE_TEXT }
                .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it).id } }
        }
        assertEquals(listOf("1:$ONLINE_TRACK_ID"), mergedIds)

        onMain { manager.selectSubtitleTrack(ONLINE_TRACK_ID) }

        // Not cue A: Media3 1.4.1 may drop a cue already showing when the track is enabled if it
        // is the file's last one (4 runs out of 6 with a single 0-5.5 s cue on R3GL600FNBT).
        awaitTrue("the last cue of the downloaded file") { "$CUE_TEXT D" in cueTexts }
        assertEquals(listOf("$CUE_TEXT B", "$CUE_TEXT C", "$CUE_TEXT D"), cueTexts.filterNot { it.endsWith(" A") })
        assertTrue(onMain { manager.getSubtitleTracks().single { it.id == ONLINE_TRACK_ID }.isSelected })
        assertNull(onMain { manager.player.playerError })
        assertEquals(emptyList<String>(), subtitleErrors.toList())
    }

    @Test
    fun aMissingSrtIsReportedAndTheVideoPlaysOnOnceReprepareDropsIt() {
        // Never written: the file a purge or the system reclaimed.
        onMain { manager.prepare(server.url, 0L, listOf(onlineSubtitle())) }

        awaitTrue("the failure to be reported") { ONLINE_TRACK_ID in subtitleErrors }
        assertNull(onMain { manager.player.playerError })

        reprepareWithoutSubtitlesAndAwaitPlayback()
    }

    @Test
    fun aCorruptSrtShowsNoCueAndNeverFailsTheVideo() {
        subtitleFile.writeBytes(ByteArray(4_096) { (it * 31 + 7).toByte() })

        onMain { manager.prepare(server.url, 0L, listOf(onlineSubtitle())) }

        // Garbage parses to no cue rather than failing: the track stays listed and selectable.
        awaitTrue("the online track to be listed") {
            manager.getSubtitleTracks().any { it.id == ONLINE_TRACK_ID }
        }
        onMain { manager.selectSubtitleTrack(ONLINE_TRACK_ID) }

        awaitTrue("the video to play past 4 s") { manager.player.currentPosition > 4_000L }
        assertNull(onMain { manager.player.playerError })
        assertEquals(emptyList<String>(), subtitleErrors.toList())
        assertEquals(emptyList<String>(), cueTexts.toList())
    }

    /**
     * The episode path: playback resumed mid-video, then a pick re-prepares where it had reached,
     * and the online track is switched on by the first snapshot listing it — as
     * [com.bobot.iptvapp.ui.screen.player.PlayerViewModel]'s pending track does, from a listener
     * registered before the first [ExoPlayerManager.prepare].
     */
    @Test
    fun anOnlineTrackPickedAfterAResumeIsSelectedByTheFirstSnapshotListingIt() {
        subtitleFile.writeText(VALID_SRT)
        var pendingTrackId: String? = null
        val ready = CountDownLatch(1)
        val selected = CountDownLatch(1)
        val viewModelListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) ready.countDown()
            }

            override fun onTracksChanged(tracks: Tracks) {
                val pending = pendingTrackId
                if (pending != null && manager.getSubtitleTracks().any { it.id == pending }) {
                    pendingTrackId = null
                    manager.selectSubtitleTrack(pending)
                }
                if (manager.getSubtitleTracks().any { it.id == ONLINE_TRACK_ID && it.isSelected }) selected.countDown()
            }
        }
        onMain {
            manager.player.addListener(viewModelListener)
            manager.prepare(server.url, RESUME_POSITION_MS, emptyList())
        }
        assertTrue("the resumed video to be ready", ready.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))

        onMain {
            pendingTrackId = ONLINE_TRACK_ID
            manager.prepare(server.url, manager.player.currentPosition, listOf(onlineSubtitle()))
        }

        val listed = selected.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        assertTrue(
            "the online track to be selected (tracks=${onMain { manager.getSubtitleTracks() }}, " +
                "pending=${onMain { pendingTrackId }}, position=${onMain { manager.player.currentPosition }})",
            listed,
        )
        assertNull(onMain { manager.player.playerError })
        assertEquals(emptyList<String>(), subtitleErrors.toList())
    }

    /**
     * Side-loaded subtitles each sit alone in their own merged group: a known language reaches the
     * label through `SubtitleExtractor`, and the ones without any get distinct numbers.
     */
    @Test
    fun sideLoadedSubtitlesShowTheirLanguageOrADistinctNumber() {
        subtitleFile.writeText(VALID_SRT)
        val unlabelled = ExternalSubtitle(url = Uri.fromFile(subtitleFile).toString(), language = null)

        onMain { manager.prepare(server.url, 0L, listOf(onlineSubtitle(), unlabelled, unlabelled.copy(language = "und"))) }

        awaitTrue("the three subtitles to be listed") { manager.getSubtitleTracks().size == 3 }
        val tracks = onMain { manager.getSubtitleTracks() }
        assertEquals(listOf(LanguageLabel.forCode("fr"), "Subtitle 2", "Subtitle 3"), tracks.map { it.label })
        assertEquals("fr", tracks.first().languageCode)
        assertEquals(ONLINE_TRACK_ID, tracks.first().id)
        assertEquals(3, tracks.map { it.id }.distinct().size)
    }

    /** What [com.bobot.iptvapp.ui.screen.player.PlayerViewModel] does once the track failed. */
    private fun reprepareWithoutSubtitlesAndAwaitPlayback() {
        onMain { manager.prepare(server.url, manager.player.currentPosition, emptyList()) }
        awaitTrue("the video to play past 2 s") { manager.player.currentPosition > 2_000L }
        assertNull(onMain { manager.player.playerError })
    }

    private fun onlineSubtitle() =
        ExternalSubtitle(url = Uri.fromFile(subtitleFile).toString(), language = "fr", trackId = ONLINE_TRACK_ID)

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!onMain(condition)) {
            check(System.currentTimeMillis() < deadline) {
                "Timed out after $TIMEOUT_MS ms waiting for $what (state=${onMain { manager.player.playbackState }}, " +
                    "position=${onMain { manager.player.currentPosition }}, errors=$subtitleErrors, cues=$cueTexts)"
            }
            Thread.sleep(POLL_MS)
        }
    }

    /**
     * Serves [body] as `video/mp4` on `127.0.0.1`, honouring a single `Range: bytes=a-[b]` —
     * enough for `OkHttpDataSource`, one connection per request.
     */
    private class LoopbackFileServer(private val body: ByteArray) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}/movie/proof/1.mp4"

        private val acceptor = thread(isDaemon = true, name = "loopback-mp4") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { client.use { serve(it) } }
            }
        }

        private fun serve(client: Socket) {
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
            reader.readLine() ?: return
            var range: LongRange? = null
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    val (start, end) = line.substringAfter("bytes=").trim().split('-')
                    range = start.toLong()..(end.toLongOrNull() ?: body.lastIndex.toLong())
                }
            }
            val slice = range ?: 0L..body.lastIndex.toLong()
            val status = if (range == null) "200 OK" else "206 Partial Content"
            val headers = buildString {
                append("HTTP/1.1 $status\r\n")
                append("Content-Type: video/mp4\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: ${slice.last - slice.first + 1}\r\n")
                if (range != null) append("Content-Range: bytes ${slice.first}-${slice.last}/${body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out = client.getOutputStream()
            out.write(headers.toByteArray(Charsets.ISO_8859_1))
            runCatching { out.write(body, slice.first.toInt(), (slice.last - slice.first + 1).toInt()) }
            out.flush()
        }

        override fun close() {
            socket.close()
            acceptor.join(1_000)
        }
    }

    private companion object {
        const val VIDEO_ASSET = "media3/test_video_6s.mp4"
        const val ONLINE_TRACK_ID = "online-proof-42"
        const val CUE_TEXT = "Bonjour depuis le cache privé"
        const val TIMEOUT_MS = 15_000L
        const val RESUME_POSITION_MS = 2_000L
        const val POLL_MS = 50L

        val VALID_SRT = """
            1
            00:00:00,000 --> 00:00:01,000
            $CUE_TEXT A

            2
            00:00:01,500 --> 00:00:02,500
            $CUE_TEXT B

            3
            00:00:03,000 --> 00:00:04,000
            $CUE_TEXT C

            4
            00:00:04,500 --> 00:00:05,500
            $CUE_TEXT D

        """.trimIndent()
    }
}

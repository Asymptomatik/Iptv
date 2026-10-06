package com.bobot.iptvapp.player

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bobot.iptvapp.di.OnlineSubtitlesModule
import okhttp3.OkHttpClient
import org.junit.After
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * What a live `.ts` channel does in the real [IptvMediaSourceFactory] and [ExoPlayerManager] when
 * the provider closes the connection: a clean end of input, not an error — the boundary
 * `PlayerViewModel` relies on to offer Réessayer on [Player.STATE_ENDED] for a live channel.
 *
 * ## What this does and does not reproduce
 * The stream is a synthetic 2 s MPEG-TS fixture (`ffmpeg` test pattern and sine tone, no metadata)
 * served from `127.0.0.1` the way an Xtream live channel is: a `/live/…/<id>.ts` path, no
 * `Content-Length`, the body cut by closing the connection. It proves how Media3 reacts to such a
 * close; it does not prove that the provider closed the connection in the freeze seen on the phone.
 *
 * ## The cache
 * Like `OnlineSubtitlePlaybackTest`, a throwaway [SimpleCache] stands in for the download cache the
 * factory streams through. The second test checks that preparing the ended channel again reaches
 * the provider anew — not bytes the cache kept from the first connection.
 *
 * ## Running
 * ```
 * adb.exe shell am instrument -w \
 *   -e class com.bobot.iptvapp.player.LiveStreamEndPlaybackTest \
 *   com.bobot.iptvapp.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LiveStreamEndPlaybackTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    private lateinit var server: ClosingLiveServer
    private lateinit var cacheDir: File
    private lateinit var cache: SimpleCache
    private lateinit var manager: ExoPlayerManager

    @Before
    fun setUp() {
        val stream = instrumentation.context.assets.open(LIVE_ASSET).use { it.readBytes() }
        server = ClosingLiveServer(stream)

        cacheDir = File(context.cacheDir, "media3-live-proof-${UUID.randomUUID()}")
        @Suppress("DEPRECATION") // The database-backed constructor would open the app's own database.
        cache = SimpleCache(cacheDir, NoOpCacheEvictor())

        val factory = IptvMediaSourceFactory(
            OkHttpClient(),
            cache,
            OnlineSubtitlesModule.provideOnlineSubtitleDirectory(context),
        )
        manager = ExoPlayerManager(context, factory)
    }

    @After
    fun tearDown() {
        onMain { manager.release() }
        cache.release()
        cacheDir.deleteRecursively()
        server.close()
    }

    @Test
    fun aLiveTsWhoseConnectionClosesEndsWithoutAnyPlayerError() {
        onMain { manager.prepare(server.url, 0L, emptyList()) }

        awaitTrue("the channel to end") { manager.player.playbackState == Player.STATE_ENDED }
        assertNull(onMain { manager.player.playerError })
        assertTrue(onMain { !manager.player.isPlaying })
    }

    /** What `PlayerViewModel.retry` does for an ended live channel: the same URL, from 0. */
    @Test
    fun preparingTheEndedChannelAgainFromZeroReopensItAndPlays() {
        onMain { manager.prepare(server.url, 0L, emptyList()) }
        awaitTrue("the channel to end") { manager.player.playbackState == Player.STATE_ENDED }
        val requestsBeforeRetry = server.requests.get()

        onMain { manager.prepare(server.url, 0L, emptyList()) }

        awaitTrue("the reopened channel to play") { manager.player.isPlaying }
        assertTrue(
            "the retry must reach the provider again (requests: $requestsBeforeRetry then ${server.requests.get()})",
            server.requests.get() > requestsBeforeRetry,
        )
        assertNull(onMain { manager.player.playerError })
    }

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
                    "error=${onMain { manager.player.playerError?.errorCodeName }}, requests=${server.requests.get()})"
            }
            Thread.sleep(POLL_MS)
        }
    }

    /**
     * Serves [body] as `video/mp2t` on `127.0.0.1` like a live channel: whatever range is asked,
     * no `Content-Length`, and the connection closed once [body] is written. Counts the requests.
     */
    private class ClosingLiveServer(private val body: ByteArray) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}/live/proof/proof/1.ts"
        val requests = AtomicInteger()

        private val acceptor = thread(isDaemon = true, name = "loopback-live") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    client.use {
                        val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.ISO_8859_1))
                        reader.readLine() ?: return@use
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        requests.incrementAndGet()
                        val out = it.getOutputStream()
                        out.write(
                            "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n"
                                .toByteArray(Charsets.ISO_8859_1),
                        )
                        runCatching { out.write(body) }
                        out.flush()
                    }
                }
            }
        }

        override fun close() {
            socket.close()
            acceptor.join(1_000)
        }
    }

    private companion object {
        const val LIVE_ASSET = "media3/test_live_2s.ts"
        const val TIMEOUT_MS = 15_000L
        const val POLL_MS = 50L
    }
}

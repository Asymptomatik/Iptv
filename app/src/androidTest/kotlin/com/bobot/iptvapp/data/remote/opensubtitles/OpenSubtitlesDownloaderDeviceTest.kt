package com.bobot.iptvapp.data.remote.opensubtitles

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bobot.iptvapp.data.logout.SessionWriteGate
import com.bobot.iptvapp.data.preferences.OpenSubtitlesApiKeyStore
import com.bobot.iptvapp.domain.model.OnlineSubtitle
import com.bobot.iptvapp.domain.model.OnlineSubtitleDownloadResult
import com.bobot.iptvapp.domain.model.OnlineSubtitleDownloadResult.Reason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.net.URI

/**
 * [OpenSubtitlesDownloader] with the markup rendered by Android itself ([AndroidCueHtml]), which
 * the JVM tests can only stand in for. Every call is answered in-process: no network, no real key.
 *
 * ## Running
 * ```
 * adb.exe shell am instrument -w \
 *   -e class com.bobot.iptvapp.data.remote.opensubtitles.OpenSubtitlesDownloaderDeviceTest \
 *   com.bobot.iptvapp.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class OpenSubtitlesDownloaderDeviceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val storeDirectory by lazy { File(tmp.root, "online_subtitles") }
    private var file: ByteArray = ByteArray(0)
    private val subtitle = OnlineSubtitle(fileId = 222, language = "fr", fileName = "diner.fr.srt")

    private fun downloader() = OpenSubtitlesDownloader(
        httpClient = OkHttpClient.Builder().addInterceptor(CannedServer()).followRedirects(false).build(),
        json = Json { ignoreUnknownKeys = true; coerceInputValues = true },
        apiKeyStore = KeyStore,
        userAgent = "IptvApp v1.0",
        fileStore = OnlineSubtitleFileStore(storeDirectory, Dispatchers.Unconfined, OpenGate),
        ioDispatcher = Dispatchers.Unconfined,
        cueHtml = AndroidCueHtml,
    )

    private suspend fun download(srt: String): OnlineSubtitleDownloadResult {
        file = srt.toByteArray(Charsets.UTF_8)
        return downloader().download(subtitle, 0, OnlineSubtitleVisit())
    }

    @Test
    fun text_with_a_timecode_but_no_cue_the_player_would_show_is_refused_and_nothing_is_stored() = runTest {
        listOf(
            "cue with tags only" to "{\\an8}<i></i>",
            "unclosed angle bracket" to "<3",
            "> inside a double-quoted attribute" to "<a href=\">\"></a>",
            "> inside a single-quoted attribute" to "<font color='>'></font>",
            "nbsp numeric entity, zero-padded" to "&#0160;",
            "en space entity" to "&ensp;",
            "em and thin space entities" to "&emsp;&thinsp;",
            "image only" to "<img src=\"x\">",
        ).forEach { (case, text) ->
            assertEquals(case, OnlineSubtitleDownloadResult.Failed(Reason.UNSUPPORTED_FORMAT), download(cue(text)))
        }
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun cues_whose_markup_leaves_text_are_kept_byte_for_byte() = runTest {
        listOf(
            cue("<a href=\">\">Lien</a>"),
            cue("&ensp;Bonsoir&ensp;"),
            cue("<b><font color=\"#ff0000\">Rouge</font></b>"),
            cue("&amp;"),
            "1\n" + "x".repeat(1_100) + "\n\n2\n00:00:01,000 --> 00:00:02,000\nBonsoir\n",
        ).forEach { srt ->
            val result = download(srt)

            assertTrue(srt, result is OnlineSubtitleDownloadResult.Downloaded)
            assertEquals(srt, File(URI((result as OnlineSubtitleDownloadResult.Downloaded).subtitle.url)).readText())
        }
    }

    private fun cue(text: String) = "1\n00:00:01,000 --> 00:00:02,000\n$text\n"

    private fun storedFiles(): List<String> = storeDirectory.list()?.toList().orEmpty()

    /** The `/download` answer, then [file] behind its link; anything else is a 404. */
    private inner class CannedServer : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val (code, body) = when (request.url.toString()) {
                DOWNLOAD_URL -> 200 to """{"link":"$LINK","remaining":97}""".toByteArray()
                LINK -> 200 to file
                else -> 404 to ByteArray(0)
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("canned")
                .body(body.toResponseBody(null))
                .build()
        }
    }

    private object KeyStore : OpenSubtitlesApiKeyStore {
        override fun observeIsConfigured(): Flow<Boolean> = flowOf(true)
        override suspend fun getApiKey(): String = "test-consumer-key"
        override suspend fun setApiKey(apiKey: String) = Unit
        override suspend fun clearApiKey() = Unit
    }

    private object OpenGate : SessionWriteGate {
        override suspend fun <T : Any> runInSession(generation: Int, block: suspend () -> T): T? = block()
    }

    private companion object {
        const val DOWNLOAD_URL = "https://api.opensubtitles.com/api/v1/download"
        const val LINK = "https://www.opensubtitles.com/download/A184A5EA6302F2CA/subfile/diner.fr.srt"
    }
}

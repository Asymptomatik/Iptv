package com.bobot.iptvapp.data.remote.opensubtitles

import com.bobot.iptvapp.data.logout.FakeSessionWriteGate
import com.bobot.iptvapp.data.preferences.FakeOpenSubtitlesApiKeyStore
import com.bobot.iptvapp.domain.model.OnlineSubtitle
import com.bobot.iptvapp.domain.model.OnlineSubtitleDownloadResult
import com.bobot.iptvapp.domain.model.OnlineSubtitleDownloadResult.Reason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI

/**
 * Behaviour of [OpenSubtitlesDownloader] against canned answers: a [FakeServer] interceptor
 * answers every call in-process, so nothing reaches the network and no real key is involved.
 *
 * The contract under test is the official one (Stoplight "Download", checked 2026-09-23):
 * `POST https://api.opensubtitles.com/api/v1/download` with `{"file_id": …}` answers a temporary
 * `link` on `www.opensubtitles.com`, whose file is SRT in UTF-8 by default.
 */
class OpenSubtitlesDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val apiKeyStore = FakeOpenSubtitlesApiKeyStore("test-consumer-key")
    private val server = FakeServer()
    private val storeDirectory by lazy { File(tmp.root, "online_subtitles") }
    private val sessionGate = FakeSessionWriteGate()
    private val visit = OnlineSubtitleVisit()

    private fun downloader(cueHtml: CueHtml = TagStrippingCueHtml) = OpenSubtitlesDownloader(
        httpClient = OkHttpClient.Builder().addInterceptor(server).followRedirects(false).build(),
        json = Json { ignoreUnknownKeys = true; coerceInputValues = true },
        apiKeyStore = apiKeyStore,
        userAgent = "IptvApp v1.0",
        fileStore = OnlineSubtitleFileStore(storeDirectory, Dispatchers.Unconfined, sessionGate),
        ioDispatcher = Dispatchers.Unconfined,
        cueHtml = cueHtml,
    )

    private val subtitle = OnlineSubtitle(fileId = 222, language = "fr", fileName = "diner.fr.srt")

    private fun storedFiles(): List<String> = storeDirectory.list()?.toList().orEmpty()

    private fun answerDownload(link: String = LINK, remaining: Int = 97) {
        server.on(DOWNLOAD_URL, 200, downloadBody(link, remaining))
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    fun `a selected subtitle becomes a private local srt file with its language`() = runTest {
        answerDownload()
        server.on(LINK, 200, SRT.toByteArray())

        val result = downloader().download(subtitle, sessionGate.generation, visit)

        result as OnlineSubtitleDownloadResult.Downloaded
        assertEquals("fr", result.subtitle.language)
        assertEquals(97, result.remainingDownloads)
        val file = File(URI(result.subtitle.url))
        assertEquals("file", URI(result.subtitle.url).scheme)
        assertTrue(result.subtitle.url.endsWith(".srt"))
        assertEquals(storeDirectory.canonicalFile, file.parentFile!!.canonicalFile)
        assertEquals(SRT, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `the download request is the documented POST, carrying the file id and nothing else`() = runTest {
        answerDownload()
        server.on(LINK, 200, SRT.toByteArray())

        downloader().download(subtitle, sessionGate.generation, visit)

        val post = server.requests.first()
        assertEquals("POST", post.method)
        assertEquals(DOWNLOAD_URL, post.url.toString())
        assertEquals("test-consumer-key", post.header("Api-Key"))
        assertEquals("IptvApp v1.0", post.header("User-Agent"))
        assertEquals("*/*", post.header("Accept"))
        assertNull("no user login in this lot", post.header("Authorization"))
        assertEquals("application/json; charset=utf-8", post.body!!.contentType().toString())
        assertEquals("""{"file_id":222}""", bodyOf(post))
    }

    @Test
    fun `the file itself is fetched from the link without the key`() = runTest {
        answerDownload()
        server.on(LINK, 200, SRT.toByteArray())

        downloader().download(subtitle, sessionGate.generation, visit)

        val get = server.requests[1]
        assertEquals("GET", get.method)
        assertEquals(LINK, get.url.toString())
        assertNull(get.header("Api-Key"))
        assertNull(get.header("Authorization"))
        assertEquals("IptvApp v1.0", get.header("User-Agent"))
    }

    @Test
    fun `a download whose session ended meanwhile is reported as such and leaves no file`() = runTest {
        answerDownload()
        server.on(LINK, 200, SRT.toByteArray())
        val pickedIn = sessionGate.generation
        sessionGate.generation++ // a logout purge completed while the file was downloading

        assertEquals(
            OnlineSubtitleDownloadResult.Failed(Reason.SESSION_ENDED),
            downloader().download(subtitle, pickedIn, visit),
        )
        assertEquals(emptyList<String>(), storedFiles())
    }

    @Test
    fun `a downloaded file belongs to the visit it was picked in and goes when that visit closes`() = runTest {
        answerDownload()
        server.on(LINK, 200, SRT.toByteArray())
        val result = downloader().download(subtitle, sessionGate.generation, visit) as OnlineSubtitleDownloadResult.Downloaded
        assertTrue(File(URI(result.subtitle.url)).exists())

        visit.close()

        assertEquals(emptyList<String>(), storedFiles())
    }

    // ── nothing sent ──────────────────────────────────────────────────────────

    @Test
    fun `without a configured key nothing is sent`() = runTest {
        apiKeyStore.key.value = null

        assertEquals(OnlineSubtitleDownloadResult.Failed(Reason.MISSING_API_KEY), downloader().download(subtitle, sessionGate.generation, visit))
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `a file id outside the documented int32 range is refused before anything is sent`() = runTest {
        listOf(0L, -5L, Int.MAX_VALUE.toLong() + 1).forEach { id ->
            assertEquals(
                OnlineSubtitleDownloadResult.Failed(Reason.INVALID_FILE_ID),
                downloader().download(subtitle.copy(fileId = id), sessionGate.generation, visit),
            )
        }
        assertTrue(server.requests.isEmpty())
    }

    // ── /download failures ────────────────────────────────────────────────────

    @Test
    fun `406 Invalid file_id is INVALID_FILE_ID`() = runTest {
        server.on(DOWNLOAD_URL, 406, """{"message":"Invalid file_id","status":406}""")

        assertEquals(
            OnlineSubtitleDownloadResult.Failed(Reason.INVALID_FILE_ID, httpCode = 406),
            downloader().download(subtitle, sessionGate.generation, visit),
        )
    }

    @Test
    fun `406 with the quota shape is QUOTA_EXCEEDED`() = runTest {
        server.on(
            DOWNLOAD_URL,
            406,
            """{"requests":6,"remaining":-1,"message":"You have downloaded your allowed 5 subtitles for 24h.",""" +
                """"reset_time":"23 hours and 57 minutes","reset_time_utc":"2022-01-30T06:00:53.000Z"}""",
        )

        assertEquals(
            OnlineSubtitleDownloadResult.Failed(Reason.QUOTA_EXCEEDED, httpCode = 406),
            downloader().download(subtitle, sessionGate.generation, visit),
        )
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `a bare 406 is UNEXPECTED_HTTP`() = runTest {
        server.on(DOWNLOAD_URL, 406, "")

        assertEquals(
            OnlineSubtitleDownloadResult.Failed(Reason.UNEXPECTED_HTTP, httpCode = 406),
            downloader().download(subtitle, sessionGate.generation, visit),
        )
    }

    @Test
    fun `401, 403, 429 and 5xx map like the search does`() = runTest {
        server.on(DOWNLOAD_URL, 401, "")
        assertEquals(OnlineSubtitleDownloadResult.Failed(Reason.UNAUTHORIZED, httpCode = 401), downloader().download(subtitle, sessionGate.generation, visit))

        server.on(DOWNLOAD_URL, 403, "")
        assertEquals(OnlineSubtitleDownloadResult.Failed(Reason.FORBIDDEN, httpCode = 403), downloader().download(subtitle, sessionGate.generation, visit))

        server.on(DOWNLOAD_URL, 429, "", "ratelimit-reset" to "2")
        assertEquals(
            OnlineSubtitleDownloadResult.Failed(Reason.RATE_LIMITED, retryAfterSeconds = 2, httpCode = 429),
            downloader().download(subtitle, sessionGate.generation, visit),
        )

        server.on(DOWNLOAD_URL, 502, "")
        assertEquals(OnlineSubtitleDownloadResult.Failed(Reason.SERVER, httpCode = 502), downloader().download(subtitle, sessionGate.generation, visit))
    }

    @Test
    fun `a redirect on the download call is never followed`() = runTest {
        server.on(DOWNLOAD_URL, 307, "", "Location" to "https://collector.evil.example/download")

        assertEquals(
            OnlineSubtitleDownloadResult.Failed(Reason.REDIRECT_REFUSED, httpCode = 307),
            downloader().download(subtitle, sessionGate.generation, visit),
        )
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `an unreadable or linkless answer is INVALID_RESPONSE`() = runTest {
        server.on(DOWNLOAD_URL, 200, "<html>maintenance</html>")
        assertEquals(OnlineSubtitleDownloadResult.Failed(Reason.INVALID_RESPONSE), downloader().download(subtitle, sessionGate.generation, visit))

        server.on(DOWNLOAD_URL, 200, """{"file_name":"a.srt","remaining":3}""")
        assertEquals(OnlineSubtitleDownloadResult.Failed(Reason.INVALID_RESPONSE), downloader().download(subtitle, sessionGate.generation, visit))
    }

    @Test
    fun `an oversized download answer is not read`() = runTest {
        server.on(DOWNLOAD_URL, 200, downloadBody(LINK + "x".repeat(70 * 1024), 3))

        assertEquals(OnlineSubtitleDownloadResult.Failed(Reason.INVALID_RESPONSE), downloader().download(subtitle, sessionGate.generation, visit))
        assertEquals(1, server.requests.size)
    }

    // ── the link ──────────────────────────────────────────────────────────────

    @Test
    fun `a link off the documented host is never fetched`() = runTest {
        listOf(
            "http://www.opensubtitles.com/download/ABC/subfile/a.srt",
            "https://collector.evil.example/download/ABC/subfile/a.srt",
            "https://127.0.0.1/download/ABC/subfile/a.srt",
            "https://192.168.0.4/download/ABC/subfile/a.srt",
            "https://user:pw@www.opensubtitles.com/download/ABC/subfile/a.srt",
            "file:///data/data/com.bobot.iptvapp/shared_prefs/x.xml",
        ).forEach { link ->
            server.requests.clear()
            answerDownload(link = link)

            assertEquals(
                "for $link",
                OnlineSubtitleDownloadResult.Failed(Reason.UNTRUSTED_LINK),
                downloader().download(subtitle, sessionGate.generation, visit),
            )
            assertEquals("for $link", 1, server.requests.size)
        }
    }

    @Test
    fun `a link redirecting to another host is refused and that host is never contacted`() = runTest {
        answerDownload()
        server.on(LINK, 302, "", "Location" to "https://cdn.evil.example/a.srt")

        assertEquals(
            OnlineSubtitleDownloadResult.Failed(Reason.REDIRECT_REFUSED, httpCode = 302),
            downloader().download(subtitle, sessionGate.generation, visit),
        )
        assertTrue(server.requests.none { it.url.host == "cdn.evil.example" })
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `a link redirecting within the documented host is followed, still without the key`() = runTest {
        val moved = "https://www.opensubtitles.com/download/OTHER/subfile/diner.fr.srt"
        answerDownload()
        server.on(LINK, 302, "", "Location" to moved)
        server.on(moved, 200, SRT.toByteArray())

        val result = downloader().download(subtitle, sessionGate.generation, visit)

        assertTrue(result is OnlineSubtitleDownloadResult.Downloaded)
        assertNull(server.requests.last().header("Api-Key"))
    }

    @Test
    fun `an expired link is LINK_EXPIRED`() = runTest {
        answerDownload()
        server.on(LINK, 410, "Error 410 - Invalid or expired link")

        assertEquals(
            OnlineSubtitleDownloadResult.Failed(Reason.LINK_EXPIRED, httpCode = 410),
            downloader().download(subtitle, sessionGate.generation, visit),
        )
    }

    // ── the file ──────────────────────────────────────────────────────────────

    @Test
    fun `an announced oversized file is refused without reading it`() = runTest {
        answerDownload()
        server.on(LINK, 200, LyingLengthBody(SRT.toByteArray(), announced = 50L * 1024 * 1024))

        assertEquals(OnlineSubtitleDownloadResult.Failed(Reason.TOO_LARGE), downloader().download(subtitle, sessionGate.generation, visit))
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `a file that streams past the limit is refused and nothing is stored`() = runTest {
        answerDownload()
        val huge = ("1\n00:00:01,000 --> 00:00:02,000\n" + "a".repeat(3 * 1024 * 1024)).toByteArray()
        server.on(LINK, 200, LyingLengthBody(huge, announced = -1L))

        assertEquals(OnlineSubtitleDownloadResult.Failed(Reason.TOO_LARGE), downloader().download(subtitle, sessionGate.generation, visit))
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `archives are refused rather than unpacked`() = runTest {
        val gzip = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00) + SRT.toByteArray()
        val zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + SRT.toByteArray()
        listOf(gzip, zip).forEach { bytes ->
            answerDownload()
            server.on(LINK, 200, bytes)

            assertEquals(OnlineSubtitleDownloadResult.Failed(Reason.UNSUPPORTED_FORMAT), downloader().download(subtitle, sessionGate.generation, visit))
        }
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `something that is not a subtitle is refused`() = runTest {
        answerDownload()
        server.on(LINK, 200, "<html><body>Please log in</body></html>".toByteArray())

        assertEquals(OnlineSubtitleDownloadResult.Failed(Reason.UNSUPPORTED_FORMAT), downloader().download(subtitle, sessionGate.generation, visit))
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `text with a timecode but no cue the player would show is refused and nothing is stored`() = runTest {
        listOf(
            "lone timecode" to "00:00:01,000 --> 00:00:02,000".toByteArray(),
            "cue without text" to "1\n00:00:01,000 --> 00:00:02,000\n\n".toByteArray(),
            "cue with tags only" to "1\n00:00:01,000 --> 00:00:02,000\n{\\an8}<i></i>\n".toByteArray(),
            "cue ending before it starts" to "1\n00:00:05,000 --> 00:00:02,000\nTrop tard\n".toByteArray(),
            "dotted milliseconds" to "1\n00:00:01.000 --> 00:00:02.000\nBonsoir\n".toByteArray(),
            "html page quoting a timecode" to "<html><pre>00:00:01,000 --> 00:00:02,000</pre></html>".toByteArray(),
            // High bytes only: no line break, so the timecode never stands on a line of its own.
            "binary around a timecode" to
                ByteArray(2_048) { (0x80 + (it * 31 + 7) % 0x80).toByte() } + "00:00:01,000 --> 00:00:02,000".toByteArray() +
                ByteArray(2_048) { (0x80 + (it * 17 + 3) % 0x80).toByte() },
        ).forEach { (case, bytes) ->
            answerDownload()
            server.on(LINK, 200, bytes)

            assertEquals(case, OnlineSubtitleDownloadResult.Failed(Reason.UNSUPPORTED_FORMAT), downloader().download(subtitle, sessionGate.generation, visit))
        }
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `a cue rendering to spacing or an image placeholder only is refused`() = runTest {
        // What Html.fromHtml makes of &#0160;, &ensp; and <img>: the device test runs the real markup.
        val rendered = mapOf("&#0160;" to "\u00A0", "&ensp;" to "\u2002", "<img src=\"x\">" to "\uFFFC", "&ensp;<img>" to " \u2002\uFFFC ")
        rendered.keys.forEach { markup ->
            answerDownload()
            server.on(LINK, 200, "1\n00:00:01,000 --> 00:00:02,000\n$markup\n".toByteArray())

            val result = downloader(cueHtml = { html -> rendered[html] ?: html }).download(subtitle, sessionGate.generation, visit)

            assertEquals(markup, OnlineSubtitleDownloadResult.Failed(Reason.UNSUPPORTED_FORMAT), result)
        }
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `a cue of invisible format characters only is refused and nothing is stored`() = runTest {
        // Zero-width space (&#8203; decodes to it), joiners, bidi marks and controls, variation selectors, a mid-line BOM, a tag character.
        listOf(
            "zero-width space" to "​",
            "zero-width space entity" to "&#8203;",
            "joiners" to "‌‍⁠",
            "bidi marks and controls" to "‎‏‪‬⁦⁩؜",
            "variation selectors" to "️󠄀",
            "soft hyphen, grapheme joiner, mid-line BOM" to "­͏﻿",
            "tag character" to "󠁡",
            "invisibles around a line break" to "​<br>‍",
        ).forEach { (case, text) ->
            answerDownload()
            server.on(LINK, 200, "1\n00:00:01,000 --> 00:00:02,000\n$text\n".toByteArray())

            val result = downloader(cueHtml = { html -> html.replace("&#8203;", "​").replace("<br>", "\n") })
                .download(subtitle, sessionGate.generation, visit)

            assertEquals(case, OnlineSubtitleDownloadResult.Failed(Reason.UNSUPPORTED_FORMAT), result)
        }
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `real glyphs carrying format characters are kept`() = runTest {
        listOf(
            "1\n00:00:01,000 --> 00:00:02,000\nété\n",
            "1\n00:00:01,000 --> 00:00:02,000\n字幕\n",
            "1\n00:00:01,000 --> 00:00:02,000\n‏שלום\n",
            "1\n00:00:01,000 --> 00:00:02,000\n❤️\n",
            "1\n00:00:01,000 --> 00:00:02,000\n​👨‍👩‍👧\n",
        ).forEach { srt ->
            answerDownload()
            server.on(LINK, 200, srt.toByteArray())

            assertTrue(srt, downloader().download(subtitle, sessionGate.generation, visit) is OnlineSubtitleDownloadResult.Downloaded)
        }
    }

    @Test
    fun `an overlong timing line the regex could match refuses the file, a safety limit`() = runTest {
        // Never fed to the regex. Media3 would skip this one and play cue 2: a refusal accepted as the price of the bound.
        val srt = "1\n" + "-->".repeat(400) + "\n\n2\n00:00:01,000 --> 00:00:02,000\nBonsoir\n"
        answerDownload()
        server.on(LINK, 200, srt.toByteArray())

        assertEquals(OnlineSubtitleDownloadResult.Failed(Reason.UNSUPPORTED_FORMAT), downloader().download(subtitle, sessionGate.generation, visit))
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `a timecode the player would choke on anywhere in the file is refused`() = runTest {
        // Media3 1.4.1 parses every timecode with Long.parseLong, uncaught: the whole track would fail.
        val overflowing = SRT + "\n2\n99999999999999999999:00:00,000 --> 99999999999999999999:00:01,000\nJamais\n"
        answerDownload()
        server.on(LINK, 200, overflowing.toByteArray())

        assertEquals(OnlineSubtitleDownloadResult.Failed(Reason.UNSUPPORTED_FORMAT), downloader().download(subtitle, sessionGate.generation, visit))
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `legitimate SRT variants are kept byte for byte`() = runTest {
        listOf(
            "1\r\n00:00:01,000 --> 00:00:02,000\r\nBonsoir\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nÀ demain\r\n",
            "1\r00:00:01,000 --> 00:00:02,000\rBonsoir\r",
            "\n\n1\n01:02,500 --> 01:04,000\nSans heures\n",
            "1\n100:00:01 --> 100:00:02,5\nCent heures, sans millisecondes\n",
            "1\n  00:00:01,000-->00:00:02,000\t\n  Espaces autour  \n",
            "1\nnot a timing\n\n2\n00:00:01,000 --> 00:00:02,000\n{\\an8}<i>Seconde cue valide</i>",
            // An overlong line the timing regex cannot match is skipped, as Media3 skips it.
            "1\n" + "x".repeat(1_100) + "\n\n2\n00:00:01,000 --> 00:00:02,000\nBonsoir\n",
            "1\n" + "0".repeat(1_100) + "\n\n2\n00:00:01,000 --> 00:00:02,000\nBonsoir\n",
        ).forEach { srt ->
            answerDownload()
            server.on(LINK, 200, srt.toByteArray())

            val result = downloader().download(subtitle, sessionGate.generation, visit)

            assertTrue(srt, result is OnlineSubtitleDownloadResult.Downloaded)
            assertEquals(srt, File(URI((result as OnlineSubtitleDownloadResult.Downloaded).subtitle.url)).readText())
        }
    }

    @Test
    fun `a CP1252 file is stored as UTF-8`() = runTest {
        answerDownload()
        val cp1252 = "1\n00:00:01,000 --> 00:00:02,000\nDéjà vu … €\n"
        server.on(LINK, 200, cp1252.toByteArray(charset("windows-1252")))

        val result = downloader().download(subtitle, sessionGate.generation, visit) as OnlineSubtitleDownloadResult.Downloaded

        assertArrayEquals(cp1252.toByteArray(Charsets.UTF_8), File(URI(result.subtitle.url)).readBytes())
    }

    @Test
    fun `a UTF-8 byte order mark is dropped`() = runTest {
        answerDownload()
        server.on(LINK, 200, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + SRT.toByteArray())

        val result = downloader().download(subtitle, sessionGate.generation, visit) as OnlineSubtitleDownloadResult.Downloaded

        assertArrayEquals(SRT.toByteArray(Charsets.UTF_8), File(URI(result.subtitle.url)).readBytes())
    }

    @Test
    fun `UTF-16 files with a byte order mark, either endian, are stored as UTF-8`() = runTest {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nDéjà vu … € 字幕 😀\n"
        val littleEndian = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val bigEndian = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        listOf(littleEndian, bigEndian).forEach { bytes ->
            answerDownload()
            server.on(LINK, 200, bytes)

            val result = downloader().download(subtitle, sessionGate.generation, visit) as OnlineSubtitleDownloadResult.Downloaded

            assertArrayEquals(text.toByteArray(Charsets.UTF_8), File(URI(result.subtitle.url)).readBytes())
        }
    }

    @Test
    fun `malformed or truncated UTF-16 is refused, never patched up`() = runTest {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val utf16 = SRT.toByteArray(Charsets.UTF_16LE)
        val loneSurrogate = bom + utf16 + byteArrayOf(0x00, 0xD8.toByte(), 0x41, 0x00)
        val truncated = bom + utf16 + byteArrayOf(0x41)
        val withNul = bom + utf16 + byteArrayOf(0x00, 0x00)
        // FF FE 00 00 is UTF-32LE's mark, not a UTF-16 file.
        val utf32 = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00) + SRT.toByteArray(charset("UTF-32LE"))
        listOf(loneSurrogate, truncated, withNul, utf32).forEach { bytes ->
            answerDownload()
            server.on(LINK, 200, bytes)

            assertEquals(OnlineSubtitleDownloadResult.Failed(Reason.UNSUPPORTED_FORMAT), downloader().download(subtitle, sessionGate.generation, visit))
        }
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `NUL bytes are still refused without a UTF-16 byte order mark`() = runTest {
        answerDownload()
        server.on(LINK, 200, SRT.toByteArray(Charsets.UTF_16LE))

        assertEquals(OnlineSubtitleDownloadResult.Failed(Reason.UNSUPPORTED_FORMAT), downloader().download(subtitle, sessionGate.generation, visit))
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `a UTF-16 file is bounded before and after conversion`() = runTest {
        // Up to the read limit, the densest UTF-16 → UTF-8 growth (×1.5) still fits what is stored.
        val cue = "1\n00:00:01,000 --> 00:00:02,000\n"
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val fillerChars = (2 * 1024 * 1024 - bom.size) / 2 - cue.length
        val atLimit = bom + (cue + "字".repeat(fillerChars)).toByteArray(Charsets.UTF_16LE)
        answerDownload()
        server.on(LINK, 200, LyingLengthBody(atLimit, announced = -1L))

        val result = downloader().download(subtitle, sessionGate.generation, visit) as OnlineSubtitleDownloadResult.Downloaded
        val stored = File(URI(result.subtitle.url)).readBytes()
        assertTrue(stored.size <= 3 * 1024 * 1024)
        assertEquals(cue.length + fillerChars * 3, stored.size)

        // One character more is past the read limit.
        answerDownload()
        server.on(LINK, 200, LyingLengthBody(atLimit + "字".toByteArray(Charsets.UTF_16LE), announced = -1L))
        assertEquals(OnlineSubtitleDownloadResult.Failed(Reason.TOO_LARGE), downloader().download(subtitle, sessionGate.generation, visit))
    }

    @Test
    fun `transport failures on the file are TIMEOUT or NETWORK and store nothing`() = runTest {
        answerDownload()
        server.failOn(LINK, SocketTimeoutException("timeout"))
        assertEquals(OnlineSubtitleDownloadResult.Failed(Reason.TIMEOUT), downloader().download(subtitle, sessionGate.generation, visit))

        server.failOn(LINK, IOException("connection reset"))
        assertEquals(OnlineSubtitleDownloadResult.Failed(Reason.NETWORK), downloader().download(subtitle, sessionGate.generation, visit))

        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `a store that cannot be written is STORAGE`() = runTest {
        storeDirectory.writeText("in the way")
        answerDownload()
        server.on(LINK, 200, SRT.toByteArray())

        assertEquals(OnlineSubtitleDownloadResult.Failed(Reason.STORAGE), downloader().download(subtitle, sessionGate.generation, visit))
    }

    // ── fakes ─────────────────────────────────────────────────────────────────

    private fun bodyOf(request: Request): String = Buffer().also { request.body!!.writeTo(it) }.readUtf8()

    /** A body whose announced length is whatever the test says, to exercise the pre-read check. */
    private class LyingLengthBody(private val bytes: ByteArray, private val announced: Long) : ResponseBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = announced
        override fun source(): BufferedSource = Buffer().write(bytes)
    }

    /** Answers by exact URL; anything unknown is a 404, so an unexpected call cannot pass silently. */
    private class FakeServer : Interceptor {
        val requests = mutableListOf<Request>()
        private val answers = mutableMapOf<String, (Request) -> Response>()

        // String and ByteArray answers get a fresh body per request: a body is single-read, so a
        // shared one answers the second call with nothing.
        fun on(url: String, code: Int, body: String, vararg headers: Pair<String, String>) =
            on(url, code, { body.toResponseBody("application/json".toMediaType()) }, *headers)

        fun on(url: String, code: Int, body: ByteArray, vararg headers: Pair<String, String>) =
            on(url, code, { body.toResponseBody(null) }, *headers)

        fun on(url: String, code: Int, body: ResponseBody, vararg headers: Pair<String, String>) =
            on(url, code, { body }, *headers)

        private fun on(url: String, code: Int, body: () -> ResponseBody, vararg headers: Pair<String, String>) {
            answers[url] = { request ->
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("fake")
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .body(body())
                    .build()
            }
        }

        fun failOn(url: String, failure: IOException) {
            answers[url] = { throw failure }
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            val answer = answers[request.url.toString()]
                ?: return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("unknown")
                    .body("".toResponseBody(null))
                    .build()
            return answer(request)
        }
    }

    private companion object {
        const val DOWNLOAD_URL = "https://api.opensubtitles.com/api/v1/download"
        const val LINK = "https://www.opensubtitles.com/download/A184A5EA6302F2CA/subfile/diner.fr.srt"
        const val SRT = "1\n00:00:01,000 --> 00:00:02,000\nBonsoir, vous êtes ?\n"

        fun downloadBody(link: String, remaining: Int) =
            """{"link":"$link","file_name":"diner.fr.srt","requests":3,"remaining":$remaining,""" +
                """"message":"Your quota will be renewed in 07 hours","reset_time":"07 hours",""" +
                """"reset_time_utc":"2022-04-08T13:03:16.000Z"}"""
    }
}

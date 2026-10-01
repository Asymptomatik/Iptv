package com.bobot.iptvapp.data.remote.opensubtitles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which URLs the OpenSubtitles code may talk to. Only the hosts the official documentation
 * actually shows are accepted: the API server for calls carrying the key, and the host of the
 * documented `/download` link for the file itself.
 */
class OpenSubtitlesUrlPolicyTest {

    @Test
    fun `the documented API host over HTTPS is accepted for API calls`() {
        assertTrue(OpenSubtitlesUrlPolicy.isAllowedApiUrl("https://api.opensubtitles.com/api/v1/subtitles?query=x"))
        assertTrue(OpenSubtitlesUrlPolicy.isAllowedApiUrl("https://API.OpenSubtitles.com/api/v1/download"))
    }

    @Test
    fun `the documented download link host over HTTPS is accepted for the file`() {
        assertTrue(
            OpenSubtitlesUrlPolicy.isAllowedLinkUrl(
                "https://www.opensubtitles.com/download/A184A5EA6302/subfile/castle.rock.s01e03.srt",
            ),
        )
    }

    @Test
    fun `the API host and the link host are not interchangeable`() {
        assertFalse(OpenSubtitlesUrlPolicy.isAllowedApiUrl("https://www.opensubtitles.com/api/v1/subtitles"))
        assertFalse(OpenSubtitlesUrlPolicy.isAllowedLinkUrl("https://api.opensubtitles.com/api/v1/download"))
    }

    @Test
    fun `anything else is refused, for both kinds of URL`() {
        val refused = listOf(
            // plain HTTP would carry the key or the file in clear
            "http://api.opensubtitles.com/api/v1/subtitles",
            "http://www.opensubtitles.com/download/x/subfile/a.srt",
            // userinfo
            "https://user:pass@api.opensubtitles.com/api/v1/subtitles",
            "https://user@www.opensubtitles.com/download/x/subfile/a.srt",
            // non-default port
            "https://api.opensubtitles.com:8443/api/v1/subtitles",
            "https://www.opensubtitles.com:8443/download/x/subfile/a.srt",
            // look-alike and sibling hosts
            "https://api.opensubtitles.com.evil.example/api/v1/subtitles",
            "https://evil-opensubtitles.com/download/x/subfile/a.srt",
            "https://opensubtitles.com/download/x/subfile/a.srt",
            "https://dl.opensubtitles.org/download/x/subfile/a.srt",
            "https://vip-api.opensubtitles.com/api/v1/subtitles",
            // local and private addresses
            "https://localhost/download/x/subfile/a.srt",
            "https://127.0.0.1/download/x/subfile/a.srt",
            "https://10.0.0.2/download/x/subfile/a.srt",
            "https://192.168.1.10/download/x/subfile/a.srt",
            "https://[::1]/download/x/subfile/a.srt",
            // not a URL at all
            "file:///data/data/com.bobot.iptvapp/secret",
            "javascript:alert(1)",
            "",
            "not a url",
        )
        refused.forEach { url ->
            assertFalse("API must refuse $url", OpenSubtitlesUrlPolicy.isAllowedApiUrl(url))
            assertFalse("link must refuse $url", OpenSubtitlesUrlPolicy.isAllowedLinkUrl(url))
        }
    }
}

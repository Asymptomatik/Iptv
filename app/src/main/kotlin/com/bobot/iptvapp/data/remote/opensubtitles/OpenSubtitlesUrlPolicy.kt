package com.bobot.iptvapp.data.remote.opensubtitles

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The only URLs OpenSubtitles code may contact, for the API calls and for the downloaded file.
 *
 * The hosts are exactly the ones the official documentation shows (Stoplight, checked
 * 2026-09-23): `api.opensubtitles.com` is the default API server, and the `/download` example
 * answers a link on `www.opensubtitles.com`. Nothing else is documented, so nothing else is
 * trusted. The VIP server is left out because this app has no VIP login.
 *
 * Matching the host exactly is what rules out look-alikes, `localhost` and IP literals (private or
 * not) at the same time. A URL is also refused when it is not HTTPS, when it carries userinfo, or
 * when it uses a port other than 443.
 */
object OpenSubtitlesUrlPolicy {

    const val API_HOST = "api.opensubtitles.com"
    const val DOWNLOAD_LINK_HOST = "www.opensubtitles.com"

    /** Whether a call carrying the `Api-Key` header may go to [url]. */
    fun isAllowedApiUrl(url: String): Boolean = isAllowed(url.toHttpUrlOrNull(), API_HOST)

    fun isAllowedApiUrl(url: HttpUrl): Boolean = isAllowed(url, API_HOST)

    /** Whether the subtitle file may be fetched from [url]. */
    fun isAllowedLinkUrl(url: String): Boolean = isAllowed(url.toHttpUrlOrNull(), DOWNLOAD_LINK_HOST)

    fun isAllowedLinkUrl(url: HttpUrl): Boolean = isAllowed(url, DOWNLOAD_LINK_HOST)

    // HttpUrl lower-cases the host and parses IP literals, so a plain comparison is exact.
    private fun isAllowed(url: HttpUrl?, host: String): Boolean =
        url != null &&
            url.isHttps &&
            url.host == host &&
            url.port == 443 &&
            url.username.isEmpty() &&
            url.password.isEmpty()
}

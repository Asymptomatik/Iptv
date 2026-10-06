package com.bobot.iptvapp.data.remote.opensubtitles

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** What [executeFollowingAllowedRedirects] ended with. */
internal sealed interface GuardedResponse {

    /** A non-redirect answer. The caller owns — and must close — [response]. */
    class Answered(val response: Response) : GuardedResponse

    /** A redirect that was not followed; [httpCode] is its status. */
    data class RedirectRefused(val httpCode: Int) : GuardedResponse
}

/**
 * Executes [request], following redirects by hand — and only to URLs [isAllowed] accepts.
 *
 * The OpenSubtitles [OkHttpClient] never follows redirects itself: OkHttp strips `Authorization`
 * on a cross-host hop but carries every other header, `Api-Key` included, to whatever HTTPS host a
 * `Location` names. Here a redirect is followed only when its target passes [isAllowed], with the
 * same method and headers, at most [maxRedirects] times; anything else — no `Location`, a
 * disallowed target, one hop too many — is refused without contacting the target.
 *
 * Only for idempotent GETs when [maxRedirects] > 0: a redirected POST is not re-sent.
 */
internal fun OkHttpClient.executeFollowingAllowedRedirects(
    request: Request,
    maxRedirects: Int,
    isAllowed: (HttpUrl) -> Boolean,
): GuardedResponse {
    var current = request
    var followed = 0
    while (true) {
        val response = newCall(current).execute()
        if (!response.isRedirect) return GuardedResponse.Answered(response)

        val code = response.code
        val target = response.header("Location")?.let { current.url.resolve(it) }
        response.close()
        if (target == null || followed >= maxRedirects || !isAllowed(target)) {
            return GuardedResponse.RedirectRefused(code)
        }
        followed++
        current = current.newBuilder().url(target).build()
    }
}

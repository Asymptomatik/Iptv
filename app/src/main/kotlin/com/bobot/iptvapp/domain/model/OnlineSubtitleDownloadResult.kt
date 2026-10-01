package com.bobot.iptvapp.domain.model

/** Outcome of downloading one [OnlineSubtitle] the user picked. */
sealed interface OnlineSubtitleDownloadResult {

    /**
     * The file is on disk, app-private, as UTF-8 SRT.
     *
     * @property subtitle           Ready for the player: a `file:` URI to the `.srt` and its language.
     * @property remainingDownloads What the provider says is left of the download quota, when it said so.
     */
    data class Downloaded(
        val subtitle: ExternalSubtitle,
        val remainingDownloads: Int? = null,
    ) : OnlineSubtitleDownloadResult

    /**
     * Nothing usable was produced, and nothing was left on disk.
     *
     * @property retryAfterSeconds For [Reason.RATE_LIMITED], how long the provider asked to wait,
     *                             when it said so.
     * @property httpCode          The HTTP status behind an HTTP-level failure, `null` otherwise.
     */
    data class Failed(
        val reason: Reason,
        val retryAfterSeconds: Int? = null,
        val httpCode: Int? = null,
    ) : OnlineSubtitleDownloadResult

    enum class Reason {
        /** No consumer API key is configured in Réglages; nothing was sent. */
        MISSING_API_KEY,

        /** The file id is outside the documented range, or the provider answered 406 "Invalid file_id". */
        INVALID_FILE_ID,

        /**
         * HTTP 406 with the quota body: without a user login the key allows 5 downloads per IP per
         * 24 h (100 while the consumer is "Under Development"); the counter resets at midnight UTC.
         */
        QUOTA_EXCEEDED,

        /** HTTP 401. */
        UNAUTHORIZED,

        /** HTTP 403 — the API key or the User-Agent was refused. */
        FORBIDDEN,

        /** HTTP 429 — request rate limit (5 requests per second per IP), not the daily quota. */
        RATE_LIMITED,

        /** HTTP 410 — the temporary link expired (it lives 3 hours); a new download call makes a new one. */
        LINK_EXPIRED,

        /** A redirect was answered where none is allowed, or towards a host outside the allowlist. */
        REDIRECT_REFUSED,

        /** The link the provider handed back is not HTTPS on the documented download host. */
        UNTRUSTED_LINK,

        /** The file is larger than a subtitle can reasonably be. */
        TOO_LARGE,

        /** The file is an archive, binary, or otherwise not an SRT subtitle. */
        UNSUPPORTED_FORMAT,

        /** A 2xx whose body could not be read as a download response. */
        INVALID_RESPONSE,

        /** A request timed out. */
        TIMEOUT,

        /** Any other I/O failure (no connectivity, DNS, TLS…). */
        NETWORK,

        /** HTTP 5xx. */
        SERVER,

        /** Any other HTTP status. */
        UNEXPECTED_HTTP,

        /** The file could not be written to app-private storage. */
        STORAGE,

        /**
         * A logout purge ran, or is owed, since the pick: the file belonged to an account that is
         * no longer signed in, so nothing was written.
         */
        SESSION_ENDED,
    }
}

package com.bobot.iptvapp.domain.model

/**
 * One downloadable subtitle file found by an online search (OpenSubtitles today).
 *
 * Only search metadata: nothing here has been downloaded, and [fileId] is the one value a later
 * download step needs to hand back to the provider.
 *
 * @property fileId            Provider file id — what the download endpoint takes.
 * @property language          Lower-case language code as the provider reports it (`"fr"`, `"en"`, `"pt-br"`…).
 * @property release           Release name the subtitle was synced against, when known.
 * @property fileName          Name of the subtitle file, when known.
 * @property downloadCount     Provider download counter — a rough popularity signal.
 * @property isHearingImpaired Whether the file includes hearing-impaired cues.
 * @property isMachineTranslated Whether the file was machine translated (lowest quality).
 * @property isAiTranslated    Whether the file was AI translated.
 * @property isFromTrusted     Whether the uploader is a trusted source.
 * @property featureTitle      Title of the movie or episode the provider matched, when known.
 * @property featureYear       Year of that feature, when known.
 * @property seasonNumber      Season the provider matched, for episodes.
 * @property episodeNumber     Episode the provider matched, for episodes.
 * @property featureMatch      Whether the matched feature was checked against the searched movie.
 */
data class OnlineSubtitle(
    val fileId: Long,
    val language: String,
    val release: String? = null,
    val fileName: String? = null,
    val downloadCount: Int = 0,
    val isHearingImpaired: Boolean = false,
    val isMachineTranslated: Boolean = false,
    val isAiTranslated: Boolean = false,
    val isFromTrusted: Boolean = false,
    val featureTitle: String? = null,
    val featureYear: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val featureMatch: FeatureMatch = FeatureMatch.NOT_CHECKED,
) {
    /** How far the provider's `feature_details` confirm this file belongs to the searched movie. */
    enum class FeatureMatch {
        /** Same title (accents and punctuation aside), and no contradicting year. */
        CONFIRMED,

        /**
         * Not contradicted, not proven: the provider gave no title; or a longer official title of a
         * close year (`"Dune: Part One"` for `"Dune"`); or another one that may be a localised alias
         * (`"The Dinner Game"` for `"Le Dîner de Cons"`) — the latter only when its release or file
         * name opens on the searched title. Offered, but flagged.
         */
        UNCONFIRMED,

        /** No movie check applies — episode searches keep the provider's answer as it is. */
        NOT_CHECKED,
    }
}

/** Outcome of one online subtitle search. */
sealed interface OnlineSubtitleSearchResult {

    /** At least one downloadable file, preferred languages first. Never empty. */
    data class Found(val subtitles: List<OnlineSubtitle>) : OnlineSubtitleSearchResult

    /** The provider answered and had nothing downloadable for this content. */
    data object NoResults : OnlineSubtitleSearchResult

    /**
     * The search did not produce an answer.
     *
     * @property retryAfterSeconds For [Reason.RATE_LIMITED], how long the provider asked to wait,
     *                             when it said so.
     * @property httpCode          The HTTP status behind an HTTP-level failure, `null` otherwise.
     */
    data class Failed(
        val reason: Reason,
        val retryAfterSeconds: Int? = null,
        val httpCode: Int? = null,
    ) : OnlineSubtitleSearchResult

    enum class Reason {
        /** No consumer API key is configured in Réglages; nothing was sent. */
        MISSING_API_KEY,

        /** HTTP 401. */
        UNAUTHORIZED,

        /** HTTP 403 — the API key or the User-Agent was refused. */
        FORBIDDEN,

        /** HTTP 429 — rate limit (5 requests per second per IP). */
        RATE_LIMITED,

        /** The request timed out. */
        TIMEOUT,

        /** Any other I/O failure (no connectivity, DNS, TLS…). */
        NETWORK,

        /** HTTP 5xx — the provider asks for a retry after a second. */
        SERVER,

        /** A 2xx whose body could not be read as a search response. */
        INVALID_RESPONSE,

        /**
         * A redirect left the API host, dropped to plain HTTP, had no target, or looped: it was not
         * followed, so the key went nowhere else.
         */
        REDIRECT_REFUSED,

        /** Any other HTTP status. */
        UNEXPECTED_HTTP,
    }
}

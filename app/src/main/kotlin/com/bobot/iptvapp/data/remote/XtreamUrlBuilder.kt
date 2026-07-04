package com.bobot.iptvapp.data.remote

/**
 * Constructs direct-play stream URLs for Xtream Codes content types.
 *
 * Xtream Codes servers expose three URL patterns for playback, all following the
 * structure `{baseUrl}/{type}/{username}/{password}/{id}.{extension}`.
 *
 * URL formats:
 * - **Live**:    `{baseUrl}/live/{user}/{pass}/{streamId}.{ext}`    (.ts or .m3u8)
 * - **VOD**:     `{baseUrl}/movie/{user}/{pass}/{streamId}.{ext}`   (.mkv, .mp4, …)
 * - **Series**:  `{baseUrl}/series/{user}/{pass}/{episodeId}.{ext}` (.mkv, .mp4, …)
 *
 * All methods normalise [baseUrl] by appending a trailing `/` when absent.
 *
 * Example:
 * ```kotlin
 * val url = XtreamUrlBuilder.buildLiveUrl(
 *     baseUrl  = "http://example.com:8080",
 *     username = "alice",
 *     password = "secret",
 *     streamId = "12345",
 * )
 * // → "http://example.com:8080/live/alice/secret/12345.ts"
 * ```
 */
object XtreamUrlBuilder {

    /**
     * Default container extension for live streams.
     * Some players prefer `.m3u8` (HLS adaptive); pass explicitly to override.
     */
    const val LIVE_EXTENSION_TS = "ts"
    const val LIVE_EXTENSION_HLS = "m3u8"

    /**
     * Builds a playable URL for a **live** stream.
     *
     * @param baseUrl   Root URL of the Xtream server (e.g. "http://example.com:8080").
     * @param username  Xtream Codes account username.
     * @param password  Xtream Codes account password.
     * @param streamId  The [com.bobot.iptvapp.domain.model.Channel.id] value.
     * @param extension Container extension; defaults to [LIVE_EXTENSION_TS].
     *                  Use [LIVE_EXTENSION_HLS] for HLS adaptive streams.
     */
    fun buildLiveUrl(
        baseUrl: String,
        username: String,
        password: String,
        streamId: String,
        extension: String = LIVE_EXTENSION_TS,
    ): String = "${baseUrl.withTrailingSlash()}live/$username/$password/$streamId.$extension"

    /**
     * Builds a playable URL for a **VOD movie**.
     *
     * @param baseUrl            Root URL of the Xtream server.
     * @param username           Xtream Codes account username.
     * @param password           Xtream Codes account password.
     * @param streamId           The [com.bobot.iptvapp.domain.model.Movie.id] value.
     * @param containerExtension File container extension from [com.bobot.iptvapp.domain.model.Movie.containerExtension]
     *                           (e.g. "mkv", "mp4"). Must not be blank.
     */
    fun buildMovieUrl(
        baseUrl: String,
        username: String,
        password: String,
        streamId: String,
        containerExtension: String,
    ): String = "${baseUrl.withTrailingSlash()}movie/$username/$password/$streamId.$containerExtension"

    /**
     * Builds a playable URL for a **series episode**.
     *
     * @param baseUrl            Root URL of the Xtream server.
     * @param username           Xtream Codes account username.
     * @param password           Xtream Codes account password.
     * @param episodeId          The [com.bobot.iptvapp.domain.model.Episode.id] value.
     * @param containerExtension File container extension from [com.bobot.iptvapp.domain.model.Episode.containerExtension]
     *                           (e.g. "mkv", "mp4"). Must not be blank.
     */
    fun buildEpisodeUrl(
        baseUrl: String,
        username: String,
        password: String,
        episodeId: String,
        containerExtension: String,
    ): String = "${baseUrl.withTrailingSlash()}series/$username/$password/$episodeId.$containerExtension"

    // ─────────────────────────────────────────────────────────────────────────

    /** Ensures [this] ends with exactly one `/`, stripping any surplus trailing slashes. */
    private fun String.withTrailingSlash(): String = trimEnd('/') + "/"
}

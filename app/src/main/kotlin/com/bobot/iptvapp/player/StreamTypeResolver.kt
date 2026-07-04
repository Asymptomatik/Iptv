package com.bobot.iptvapp.player

/**
 * Resolves a [StreamMediaType] from a playable stream URL.
 *
 * Pure/framework-free on purpose (no Media3 import) so it can be unit-tested on the JVM
 * without an Android runtime, mirroring the testing approach already used for
 * [com.bobot.iptvapp.data.remote.XtreamUrlBuilder].
 *
 * Detection is extension-based, matching the URL shapes produced by
 * [com.bobot.iptvapp.data.remote.XtreamUrlBuilder]:
 * - Live: `.../live/{user}/{pass}/{id}.ts` or `.m3u8`
 * - VOD:  `.../movie/{user}/{pass}/{id}.{ext}` (commonly `.mp4`, but also `.mkv`, `.avi`, …)
 * - Series: `.../series/{user}/{pass}/{id}.{ext}` (same extension variability as VOD)
 */
object StreamTypeResolver {

    private const val EXTENSION_M3U8 = "m3u8"
    private const val EXTENSION_TS = "ts"
    private const val EXTENSION_MP4 = "mp4"

    /**
     * Resolves the [StreamMediaType] of [streamUrl] by inspecting its file extension.
     *
     * Any query string (`?token=...`) is stripped before extracting the extension.
     * Returns [StreamMediaType.OTHER] when no extension is present or it does not match
     * a known type — callers should fall back to content-type sniffing in that case.
     */
    fun resolve(streamUrl: String): StreamMediaType {
        val extension = extractExtension(streamUrl) ?: return StreamMediaType.OTHER
        return when (extension.lowercase()) {
            EXTENSION_M3U8 -> StreamMediaType.HLS
            EXTENSION_TS -> StreamMediaType.MPEG_TS
            EXTENSION_MP4 -> StreamMediaType.MP4
            else -> StreamMediaType.OTHER
        }
    }

    private fun extractExtension(streamUrl: String): String? {
        val withoutQuery = streamUrl.substringBefore('?').substringBefore('#')
        val lastSegment = withoutQuery.substringAfterLast('/')
        if ('.' !in lastSegment) return null
        return lastSegment.substringAfterLast('.').takeIf { it.isNotBlank() }
    }
}

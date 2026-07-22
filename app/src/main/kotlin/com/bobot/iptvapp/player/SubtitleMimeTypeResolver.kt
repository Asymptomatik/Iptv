package com.bobot.iptvapp.player

import androidx.media3.common.MimeTypes

/**
 * Infers a Media3 subtitle MIME type from an [ExternalSubtitle][com.bobot.iptvapp.domain.model.ExternalSubtitle]
 * URL's file extension, for [IptvMediaSourceFactory]'s best-effort side-loading (Task 3).
 *
 * Extension detection mirrors [StreamTypeResolver.resolve]'s approach exactly (query
 * string/fragment stripped before extracting the last path segment's extension), for the same
 * reason: Xtream `get_vod_info` subtitle URLs may carry a signed/token query string after the
 * file extension (e.g. `.../subs/movie.srt?token=abc`).
 *
 * Only [MimeTypes.APPLICATION_SUBRIP] ([resolve]'s default fallback for an unknown or missing
 * extension), [MimeTypes.TEXT_VTT], and [MimeTypes.TEXT_SSA] are distinguished — the only
 * subtitle formats the approved brief calls out (`.srt`, with `.vtt`/`.ass`/`.ssa` handled since
 * Media3's `SubtitleParser` machinery already supports them and servers occasionally advertise
 * them instead).
 */
object SubtitleMimeTypeResolver {

    private const val EXTENSION_VTT = "vtt"
    private const val EXTENSION_ASS = "ass"
    private const val EXTENSION_SSA = "ssa"

    /**
     * Resolves the Media3 MIME type hint for [subtitleUrl], defaulting to
     * [MimeTypes.APPLICATION_SUBRIP] (SubRip, `.srt` — the brief's primary target format) when
     * the extension is absent or unrecognised, rather than returning `null`: unlike
     * [StreamTypeResolver] (where `null`/[com.bobot.iptvapp.player.StreamMediaType.OTHER] defers
     * to Media3's own content-type sniffing), [androidx.media3.exoplayer.source.SingleSampleMediaSource]
     * requires an explicit, non-null MIME type to pick a [androidx.media3.extractor.text.SubtitleParser]
     * — there is no sniffing fallback for a side-loaded single-sample text track.
     */
    fun resolve(subtitleUrl: String): String =
        when (extractExtension(subtitleUrl)?.lowercase()) {
            EXTENSION_VTT -> MimeTypes.TEXT_VTT
            EXTENSION_ASS, EXTENSION_SSA -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }

    /** Identical extraction logic to [StreamTypeResolver]'s private helper of the same name. */
    private fun extractExtension(url: String): String? {
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        val lastSegment = withoutQuery.substringAfterLast('/')
        if ('.' !in lastSegment) return null
        return lastSegment.substringAfterLast('.').takeIf { it.isNotBlank() }
    }
}

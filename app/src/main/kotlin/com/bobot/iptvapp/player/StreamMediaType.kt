package com.bobot.iptvapp.player

/**
 * Playback container/protocol families this app's player layer must support, per the
 * approved brief's "VLC-like formats" requirement: HLS (live adaptive streaming),
 * MPEG-TS (Xtream Codes live direct-play), and MP4 (VOD direct-play).
 *
 * This enum exists so [IptvMediaSourceFactory] can pick the correct Media3
 * `MediaSource.Factory` explicitly instead of relying purely on extension/content-type
 * sniffing — Xtream Codes URLs are predictable (see
 * [com.bobot.iptvapp.data.remote.XtreamUrlBuilder]) but not guaranteed to always carry
 * a recognisable extension (e.g. a live channel served without `.ts`/`.m3u8`).
 */
enum class StreamMediaType {

    /** Adaptive HTTP Live Streaming — `.m3u8` playlists, used for live channels. */
    HLS,

    /** MPEG transport stream — `.ts`, the default Xtream Codes live direct-play container. */
    MPEG_TS,

    /** Progressive MP4 — used for VOD (movies/episodes) direct-play. */
    MP4,

    /**
     * Unrecognised extension/URL shape. Falls back to Media3's own content-type sniffing
     * (`DefaultMediaSourceFactory`) rather than failing outright — Xtream servers and VOD
     * containers vary (`.mkv`, `.avi`, no extension at all, etc.).
     */
    OTHER,
}

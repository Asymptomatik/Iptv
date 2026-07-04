package com.bobot.iptvapp.domain.model

/**
 * Classifies a piece of content by its delivery or playback type.
 *
 * Used by:
 *  - [Category.type]          — to declare what kind of content a category holds.
 *  - [PlaybackProgress.contentType] — to qualify which content entity a progress
 *    record refers to, enabling routing to the correct detail screen.
 *
 * Sourced from the Xtream Codes API concept of "stream types":
 *  - `live`   → [LIVE]
 *  - `movie`  → [MOVIE]
 *  - `series` → [SERIES]
 *
 * The string representation (name) is used as the navigation argument for
 * [com.bobot.iptvapp.navigation.Detail.contentType] when navigating to a
 * detail screen.
 */
enum class ContentType {

    /**
     * A live broadcast stream (linear TV, radio, sports, news).
     * Streams of this type are backed by [Channel] domain models.
     */
    LIVE,

    /**
     * A video-on-demand movie (single playable file).
     * Streams of this type are backed by [Movie] domain models.
     */
    MOVIE,

    /**
     * A multi-season series with discrete episodes.
     * Content of this type is backed by [Series] → [Season] → [Episode] domain models.
     */
    SERIES,
}

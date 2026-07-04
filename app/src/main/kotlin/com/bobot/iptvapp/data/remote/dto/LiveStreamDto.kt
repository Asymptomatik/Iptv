package com.bobot.iptvapp.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO for a single live stream returned by `get_live_streams`.
 *
 * Sample JSON:
 * ```json
 * {
 *   "num": 1,
 *   "name": "CNN",
 *   "stream_type": "live",
 *   "stream_id": 12345,
 *   "stream_icon": "http://example.com/icon.png",
 *   "epg_channel_id": "cnn.us",
 *   "added": "1620000000",
 *   "category_id": "7",
 *   "tv_archive": 0,
 *   "tv_archive_duration": 0
 * }
 * ```
 *
 * [streamId] and [categoryId] use [FlexibleStringSerializer] because some Xtream
 * servers return them as bare integers rather than quoted strings.
 * [added] is epoch SECONDS as a quoted string; mappers convert to millis.
 * [streamIcon] and [epgChannelId] use [NullableFlexibleStringSerializer] to handle
 * blank strings returned by some servers (treated as absent).
 */
@Serializable
data class LiveStreamDto(
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("stream_id") val streamId: String,
    @SerialName("name") val name: String,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("stream_icon") val streamIcon: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    /** Epoch seconds as string, e.g. "1620000000". Null when absent. */
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("added") val added: String? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("category_id") val categoryId: String,
    /** 1 = time-shift archive enabled, 0 = disabled. */
    @SerialName("tv_archive") val tvArchive: Int? = null,
    @SerialName("tv_archive_duration") val tvArchiveDuration: Int? = null,
)

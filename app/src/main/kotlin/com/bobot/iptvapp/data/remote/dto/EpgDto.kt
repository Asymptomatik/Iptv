package com.bobot.iptvapp.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level wrapper for the `get_short_epg` response.
 *
 * Sample JSON:
 * ```json
 * {
 *   "epg_listings": [
 *     {
 *       "id": "1",
 *       "title": "VGhlIE5ld3M=",
 *       "lang": "en",
 *       "start": "2024-01-15 14:00:00",
 *       "end": "2024-01-15 15:00:00",
 *       "description": "VG9kYXkncyBoZWFkbGluZXM=",
 *       "channel_id": "cnn.us",
 *       "start_timestamp": "1705327200",
 *       "stop_timestamp": "1705330800"
 *     }
 *   ]
 * }
 * ```
 */
@Serializable
data class EpgListingDto(
    @SerialName("epg_listings") val epgListings: List<EpgProgramDto> = emptyList(),
)

/**
 * A single EPG programme entry inside [EpgListingDto.epgListings].
 *
 * **Base64 encoding**: The Xtream Codes `get_short_epg` endpoint encodes [title] and
 * [description] in Base64. Mappers are responsible for decoding them via
 * [com.bobot.iptvapp.data.remote.mapper.decodeBase64OrSelf].
 *
 * **Timestamps**: Prefer [startTimestamp] and [stopTimestamp] (epoch SECONDS as string)
 * over [start] and [end] (human-readable datetime strings). Mappers convert to millis.
 * The [start]/[end] strings serve as fallback if the timestamp fields are absent.
 *
 * [channelId] matches [com.bobot.iptvapp.domain.model.Channel.epgChannelId] for
 * correlating programme records with their live channel.
 */
@Serializable
data class EpgProgramDto(
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("id") val id: String,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("epg_id") val epgId: String? = null,
    /** Base64-encoded programme title. Decode with [android.util.Base64] in mappers. */
    @SerialName("title") val title: String,
    @SerialName("lang") val lang: String? = null,
    /** Human-readable start datetime, e.g. "2024-01-15 14:00:00" (UTC). Fallback only. */
    @SerialName("start") val start: String? = null,
    /** Human-readable end datetime, e.g. "2024-01-15 15:00:00" (UTC). Fallback only. */
    @SerialName("end") val end: String? = null,
    /** Base64-encoded programme description. Decode in mappers. Null when absent. */
    @SerialName("description") val description: String? = null,
    /** Matches [com.bobot.iptvapp.domain.model.Channel.epgChannelId]. */
    @SerialName("channel_id") val channelId: String,
    /** Epoch SECONDS as quoted string, e.g. "1705327200". Preferred over [start]. */
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("start_timestamp") val startTimestamp: String? = null,
    /** Epoch SECONDS as quoted string, e.g. "1705330800". Preferred over [end]. */
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("stop_timestamp") val stopTimestamp: String? = null,
)

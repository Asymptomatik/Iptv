package com.bobot.iptvapp.data.remote.mapper

import com.bobot.iptvapp.data.remote.dto.LiveStreamDto
import com.bobot.iptvapp.domain.model.Channel

/**
 * Maps a [LiveStreamDto] (network DTO from `get_live_streams`) to a [Channel] domain model.
 *
 * Mapping rules:
 * - [LiveStreamDto.streamId] → [Channel.id] (already String via [FlexibleStringSerializer])
 * - [LiveStreamDto.streamIcon] → [Channel.logoUrl]: null when blank or absent
 * - [LiveStreamDto.epgChannelId] → [Channel.epgChannelId]: null when blank or absent
 * - [LiveStreamDto.categoryId] → [Channel.categoryId]
 */
fun LiveStreamDto.toDomain(): Channel = Channel(
    id = streamId,
    name = name,
    logoUrl = streamIcon?.takeIf { it.isNotBlank() },
    categoryId = categoryId,
    epgChannelId = epgChannelId?.takeIf { it.isNotBlank() },
)

/**
 * Convenience extension to map a list of [LiveStreamDto]s.
 */
fun List<LiveStreamDto>.toDomain(): List<Channel> = map { it.toDomain() }

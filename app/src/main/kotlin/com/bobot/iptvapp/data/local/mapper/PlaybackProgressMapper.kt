package com.bobot.iptvapp.data.local.mapper

import com.bobot.iptvapp.data.local.entity.PlaybackProgressEntity
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.PlaybackProgress

/**
 * Mapping functions between [PlaybackProgressEntity] (Room) and [PlaybackProgress] (domain).
 *
 * [PlaybackProgressEntity.contentType] is stored as the [ContentType] enum name (a plain
 * String, e.g. `"MOVIE"`). These mappers handle the String ↔ enum conversion.
 */

/** Maps a [PlaybackProgressEntity] to the domain [PlaybackProgress]. */
fun PlaybackProgressEntity.toDomain(): PlaybackProgress = PlaybackProgress(
    contentId = contentId,
    contentType = ContentType.valueOf(contentType),
    positionMillis = positionMillis,
    durationMillis = durationMillis,
    lastUpdatedMillis = lastUpdatedMillis,
    profileId = profileId,
)

/** Maps a domain [PlaybackProgress] to the [PlaybackProgressEntity] for Room persistence. */
fun PlaybackProgress.toEntity(): PlaybackProgressEntity = PlaybackProgressEntity(
    profileId = profileId,
    contentId = contentId,
    contentType = contentType.name,
    positionMillis = positionMillis,
    durationMillis = durationMillis,
    lastUpdatedMillis = lastUpdatedMillis,
)

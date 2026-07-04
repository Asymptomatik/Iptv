package com.bobot.iptvapp.data.local.mapper

import com.bobot.iptvapp.data.local.entity.ProfileEntity
import com.bobot.iptvapp.domain.model.Profile

/**
 * Mapping functions between [ProfileEntity] (Room) and [Profile] (domain).
 *
 * These are pure, stateless transformations — no database I/O is performed here.
 * The repository layer calls these before returning domain objects or before writing to the DAO.
 */

/** Maps a [ProfileEntity] to the domain [Profile]. */
fun ProfileEntity.toDomain(): Profile = Profile(
    id = id,
    name = name,
    avatarUrl = avatarUrl,
)

/** Maps a domain [Profile] to the [ProfileEntity] for Room persistence. */
fun Profile.toEntity(): ProfileEntity = ProfileEntity(
    id = id,
    name = name,
    avatarUrl = avatarUrl,
)

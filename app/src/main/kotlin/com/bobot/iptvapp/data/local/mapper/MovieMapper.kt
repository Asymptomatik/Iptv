package com.bobot.iptvapp.data.local.mapper

import com.bobot.iptvapp.data.local.entity.MovieEntity
import com.bobot.iptvapp.domain.model.Movie

/**
 * Mapping functions between [MovieEntity] (Room cache) and [Movie] (domain).
 *
 * All nullable fields are forwarded directly — no transformation is applied.
 * These mappers are used by the offline-first catalog cache integration layer.
 * See [com.bobot.iptvapp.data.local.dao.CatalogCacheDao] for the underlying DAO.
 */

/** Maps a [MovieEntity] to the domain [Movie]. */
fun MovieEntity.toDomain(): Movie = Movie(
    id = id,
    title = title,
    posterUrl = posterUrl,
    plot = plot,
    categoryId = categoryId,
    rating = rating,
    year = year,
    addedMillis = addedMillis,
    durationMillis = durationMillis,
    containerExtension = containerExtension,
)

/**
 * Maps a domain [Movie] to the [MovieEntity] for Room cache persistence.
 *
 * @param accountKey The owning account's cache partition key (see
 *   [com.bobot.iptvapp.domain.util.accountKeyOf]), part of the entity's composite
 *   primary key.
 */
fun Movie.toEntity(accountKey: String): MovieEntity = MovieEntity(
    accountKey = accountKey,
    id = id,
    title = title,
    posterUrl = posterUrl,
    plot = plot,
    categoryId = categoryId,
    rating = rating,
    year = year,
    addedMillis = addedMillis,
    durationMillis = durationMillis,
    containerExtension = containerExtension,
)

/** Convenience extension to map a list of [MovieEntity]s. */
fun List<MovieEntity>.toDomain(): List<Movie> = map { it.toDomain() }

/** Convenience extension to map a list of [Movie]s. */
fun List<Movie>.toEntity(accountKey: String): List<MovieEntity> = map { it.toEntity(accountKey) }

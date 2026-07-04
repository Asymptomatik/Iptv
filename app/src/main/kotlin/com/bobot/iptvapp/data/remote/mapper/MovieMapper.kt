package com.bobot.iptvapp.data.remote.mapper

import com.bobot.iptvapp.data.remote.dto.VodInfoDto
import com.bobot.iptvapp.data.remote.dto.VodStreamDto
import com.bobot.iptvapp.domain.model.Movie

/**
 * Maps a [VodStreamDto] (list-endpoint payload from `get_vod_streams`) to a basic
 * [Movie] domain model.
 *
 * The list endpoint does not include plot, cover art, or duration. Those fields are
 * populated only after a `get_vod_info` call is mapped via [VodInfoDto.toDomain].
 *
 * Timestamp conversion: [VodStreamDto.added] is epoch SECONDS as a string.
 * The mapper converts to epoch MILLIS by multiplying by 1000.
 */
fun VodStreamDto.toDomain(): Movie = Movie(
    id = streamId,
    title = name,
    posterUrl = streamIcon?.takeIf { it.isNotBlank() },
    plot = plot?.takeIf { it.isNotBlank() },
    categoryId = categoryId,
    rating = rating?.takeIf { it.isNotBlank() },
    year = null,
    addedMillis = added?.toLongOrNull()?.let { it * 1_000L },
    durationMillis = null,
    containerExtension = containerExtension?.takeIf { it.isNotBlank() },
)

/**
 * Maps a [VodInfoDto] (detail-endpoint payload from `get_vod_info`) to an enriched
 * [Movie] domain model.
 *
 * [fallbackStreamId] and [fallbackCategoryId] are required because the `get_vod_info`
 * payload may lack these fields when [VodInfoDto.movieData] is absent. Pass the
 * `vod_id` query param value and the category from the list payload as fallbacks.
 *
 * Mapping decisions:
 * - Stream ID: prefer `movie_data.stream_id`, fall back to [fallbackStreamId].
 * - Category: prefer `movie_data.category_id`, then `info.category_id`, then fallback.
 * - Poster URL: prefer `info.cover_big`, fall back to `info.movie_image`.
 * - Plot: prefer `info.plot`, fall back to `info.description`.
 * - Year: parsed from `info.year` as Int; returns null when absent or non-numeric.
 * - Duration: `info.duration_secs` × 1000 → millis.
 * - Added: epoch SECONDS string × 1000 → millis.
 */
fun VodInfoDto.toDomain(
    fallbackStreamId: String,
    fallbackCategoryId: String,
): Movie {
    val resolvedStreamId = movieData?.streamId?.takeIf { it.isNotBlank() } ?: fallbackStreamId
    val resolvedCategoryId = movieData?.categoryId
        ?: info.categoryId
        ?: fallbackCategoryId
    val resolvedAdded = movieData?.added ?: info.added

    return Movie(
        id = resolvedStreamId,
        title = (info.title ?: info.name)?.takeIf { it.isNotBlank() } ?: fallbackStreamId,
        posterUrl = (info.coverBig?.takeIf { it.isNotBlank() }
            ?: info.movieImage?.takeIf { it.isNotBlank() }),
        plot = (info.plot?.takeIf { it.isNotBlank() }
            ?: info.description?.takeIf { it.isNotBlank() }),
        categoryId = resolvedCategoryId,
        rating = info.rating?.takeIf { it.isNotBlank() },
        year = info.year?.toIntOrNull()
            ?: info.releaseDate?.take(4)?.toIntOrNull(),
        addedMillis = resolvedAdded?.toLongOrNull()?.let { it * 1_000L },
        durationMillis = info.durationSecs?.let { it.toLong() * 1_000L },
        containerExtension = (movieData?.containerExtension?.takeIf { it.isNotBlank() }
            ?: info.containerExtension?.takeIf { it.isNotBlank() }),
    )
}

/**
 * Convenience extension to map a list of [VodStreamDto]s.
 */
fun List<VodStreamDto>.toDomain(): List<Movie> = map { it.toDomain() }

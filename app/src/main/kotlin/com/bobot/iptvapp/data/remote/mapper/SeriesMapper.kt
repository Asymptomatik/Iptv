package com.bobot.iptvapp.data.remote.mapper

import com.bobot.iptvapp.data.remote.dto.EpisodeDto
import com.bobot.iptvapp.data.remote.dto.SeriesDto
import com.bobot.iptvapp.data.remote.dto.SeriesInfoDto
import com.bobot.iptvapp.domain.model.Episode
import com.bobot.iptvapp.domain.model.Season
import com.bobot.iptvapp.domain.model.Series

/**
 * Maps a [SeriesDto] (list-endpoint payload from `get_series`) to a [Series] domain
 * model without seasons.
 *
 * [Series.seasons] is always empty here. The season/episode tree is populated only
 * after a `get_series_info` call is mapped via [SeriesInfoDto.toDomain].
 *
 * Year extraction: [SeriesDto.releaseDate] may be a bare year string ("2008") or a
 * full date ("2008-01-20"). The mapper takes the first 4 characters.
 */
fun SeriesDto.toDomain(): Series = Series(
    id = seriesId,
    title = name,
    coverUrl = cover?.takeIf { it.isNotBlank() },
    plot = plot?.takeIf { it.isNotBlank() },
    categoryId = categoryId ?: "",
    rating = rating?.takeIf { it.isNotBlank() },
    year = releaseDate?.take(4)?.toIntOrNull(),
    seasons = emptyList(),
)

/**
 * Maps a [SeriesInfoDto] (detail-endpoint payload from `get_series_info`) to a full
 * [Series] domain model including seasons and episodes.
 *
 * @param seriesId The `series_id` query parameter value used to make the request.
 *   This is the authoritative ID because some Xtream servers do not echo it in
 *   [SeriesInfoDto.info].
 *
 * Mapping rules for the season/episode tree:
 * - Seasons are sorted ascending by [SeasonDto.seasonNumber].
 * - Episodes for each season are retrieved from [SeriesInfoDto.episodes] using the
 *   season number as a string key (e.g. `"1"`, `"2"`, …).
 * - Episodes within a season are sorted by their numeric [EpisodeDto.episodeNum] value.
 * - If a season has no entry in the episodes map, its [Season.episodes] is empty.
 */
fun SeriesInfoDto.toDomain(seriesId: String): Series {
    val detail = info

    val mappedSeasons = seasons
        .sortedBy { it.seasonNumber }
        .map { seasonDto ->
            val seasonEpisodes = episodes[seasonDto.seasonNumber.toString()]
                ?.sortedBy { it.episodeNum.toIntOrNull() ?: 0 }
                ?.map { episodeDto -> episodeDto.toDomain(seasonNumber = seasonDto.seasonNumber) }
                ?: emptyList()

            Season(
                seasonNumber = seasonDto.seasonNumber,
                name = seasonDto.name?.takeIf { it.isNotBlank() },
                coverUrl = (seasonDto.coverBig?.takeIf { it.isNotBlank() }
                    ?: seasonDto.cover?.takeIf { it.isNotBlank() }),
                episodes = seasonEpisodes,
            )
        }

    return Series(
        id = detail.seriesId?.takeIf { it.isNotBlank() } ?: seriesId,
        title = (detail.title ?: detail.name)?.takeIf { it.isNotBlank() } ?: seriesId,
        coverUrl = detail.cover?.takeIf { it.isNotBlank() },
        plot = detail.plot?.takeIf { it.isNotBlank() },
        categoryId = detail.categoryId ?: "",
        rating = detail.rating?.takeIf { it.isNotBlank() },
        year = (detail.year ?: detail.releaseDate)?.take(4)?.toIntOrNull(),
        seasons = mappedSeasons,
    )
}

/**
 * Maps an [EpisodeDto] to an [Episode] domain model.
 *
 * @param seasonNumber The parent season number, used as fallback when the episode's
 *   own [EpisodeDto.season] field is absent.
 *
 * Mapping rules:
 * - [EpisodeDto.episodeNum] is parsed to Int; defaults to 0 when non-numeric.
 * - [EpisodeDto.season] takes precedence over [seasonNumber] for [Episode.seasonNumber].
 * - Duration: [EpisodeInfoDto.durationSecs] × 1000 → millis.
 * - Title: falls back to "Episode {num}" when absent.
 */
fun EpisodeDto.toDomain(seasonNumber: Int): Episode = Episode(
    id = id,
    title = title?.takeIf { it.isNotBlank() } ?: "Episode $episodeNum",
    episodeNumber = episodeNum.toIntOrNull() ?: 0,
    seasonNumber = season ?: seasonNumber,
    plot = info?.plot?.takeIf { it.isNotBlank() },
    durationMillis = info?.durationSecs?.let { it.toLong() * 1_000L },
    containerExtension = containerExtension?.takeIf { it.isNotBlank() },
    coverUrl = info?.movieImage?.takeIf { it.isNotBlank() },
)

/**
 * Convenience extension to map a list of [SeriesDto]s.
 */
fun List<SeriesDto>.toDomain(): List<Series> = map { it.toDomain() }

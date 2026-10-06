package com.bobot.iptvapp.domain.util

import com.bobot.iptvapp.domain.model.Movie

/** The two orders offered by the "Nouveautés" view of the Films tab. */
enum class MovieSortMode {
    /** Title year descending, then added date descending within a year. */
    RECENT_RELEASE,

    /** Added date descending, whatever the release year. */
    RECENTLY_ADDED,
}

/**
 * What [MovieSort] orders a [Movie] by. [titleYear] comes from [MovieReleaseYear] and is kept apart
 * from [Movie.year] on purpose: the latter is the official year from the detail call, filled in
 * lazily, and letting it leak in would reorder the list after the user opens a film.
 */
data class MovieSortKey(val movieId: String, val titleYear: Int?, val addedMillis: Long?)

/** Sort projection of this movie; an unknown year stays `null`, never derived from [Movie.addedMillis]. */
fun Movie.sortKey(latestPlausibleYear: Int): MovieSortKey =
    MovieSortKey(
        movieId = id,
        titleYear = MovieReleaseYear.fromTitle(title, latestPlausibleYear),
        addedMillis = addedMillis,
    )

/**
 * Orders movies for the "Nouveautés" view. Unknown values sort last, and full ties are broken by
 * [Movie.id] so the result does not depend on the input order (categories load one at a time).
 */
object MovieSort {

    private val BY_ADDED_THEN_ID: Comparator<MovieSortKey> =
        compareBy<MovieSortKey, Long?>(nullsLast(reverseOrder())) { it.addedMillis }
            .thenBy { it.movieId }

    private val BY_YEAR_THEN_ADDED: Comparator<MovieSortKey> =
        compareBy<MovieSortKey, Int?>(nullsLast(reverseOrder())) { it.titleYear }
            .then(BY_ADDED_THEN_ID)

    /** The order [mode] puts [MovieSortKey]s in — also what incremental merges must agree with. */
    fun comparator(mode: MovieSortMode): Comparator<MovieSortKey> =
        when (mode) {
            MovieSortMode.RECENT_RELEASE -> BY_YEAR_THEN_ADDED
            MovieSortMode.RECENTLY_ADDED -> BY_ADDED_THEN_ID
        }

    /**
     * [movies] in [mode] order. The same instances are returned, so ids and categories survive for
     * navigation. Each title is parsed once, not once per comparison.
     */
    fun sort(movies: List<Movie>, mode: MovieSortMode, latestPlausibleYear: Int): List<Movie> {
        val comparator = comparator(mode)
        return movies
            .map { it to it.sortKey(latestPlausibleYear) }
            .sortedWith(compareBy(comparator) { it.second })
            .map { it.first }
    }
}

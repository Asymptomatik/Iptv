package com.bobot.iptvapp.ui.screen.home

import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.util.MovieSort
import com.bobot.iptvapp.domain.util.MovieSortKey
import com.bobot.iptvapp.domain.util.MovieSortMode
import com.bobot.iptvapp.domain.util.sortKey
import java.util.IdentityHashMap
import java.util.PriorityQueue

/**
 * The index behind the Films tab's "Nouveautés" view: every film of the selected categories, in
 * [MovieSortMode] order, one page at a time.
 *
 * A catalogue can hold 154 000 films and grows one category per emission of the accumulated list,
 * so nothing here is ever done over the whole catalogue twice:
 *  - [ingest] only reads the films appended since its previous call, filing each under its
 *    category's slice. The accumulator only ever grows within one load; a list that does not
 *    extend the previous one (a reload) starts the index over.
 *  - A slice builds its [MovieSortKey]s — one title parse per film — and its sorted order per mode
 *    lazily, the first time a page needs them, and keeps both until the slice grows. Changing the
 *    language only changes which slices are merged; changing the mode sorts each slice once for it.
 *  - [page] is a k-way merge of the already-sorted slices that stops after `limit` distinct films,
 *    so it reads roughly `limit` entries whatever the catalogue size.
 *  - Only the returned page is turned into [HomeCardItem]s, reusing the previous page's cards; the
 *    category rows' own cards are left alone, so the two views never double the card count.
 *
 * Slices hold references to the [Movie]s already in the accumulated list, plus one key and one
 * `Int` per film and mode — no copies.
 *
 * Not thread-safe: [HomeViewModel] drives one instance from a single collector, and replaces it
 * with a fresh one for every load rather than mutating an old one.
 */
internal class MovieDiscoveryIndex(
    private val latestPlausibleYear: Int,
    private val toCard: (Movie) -> HomeCardItem,
) {

    /** One page of "Nouveautés": its cards, and whether at least one more distinct film follows. */
    data class Page(val items: List<HomeCardItem>, val hasMore: Boolean)

    /** Titles parsed into sort keys so far — each film counts once per load. */
    var keysBuilt = 0
        private set

    /** Slice sorts performed so far — once per slice and mode until the slice grows. */
    var slicesSorted = 0
        private set

    /** Cards built so far; only the films of the pages asked for ever get one. */
    var cardsBuilt = 0
        private set

    private val slices = HashMap<String, Slice>()
    private var ingestedCount = 0
    private var lastIngested: Movie? = null
    private var pageCards = IdentityHashMap<Movie, HomeCardItem>()

    private inner class Slice {
        val movies = ArrayList<Movie>()
        val keys = ArrayList<MovieSortKey>()
        private val orders = arrayOfNulls<IntArray>(MovieSortMode.entries.size)

        fun add(movie: Movie) {
            movies += movie
            orders.fill(null)
        }

        /** Positions into [movies], in [mode] order. */
        fun order(mode: MovieSortMode): IntArray {
            orders[mode.ordinal]?.let { return it }
            for (i in keys.size until movies.size) {
                keys += movies[i].sortKey(latestPlausibleYear)
                keysBuilt++
            }
            val comparator = MovieSort.comparator(mode)
            val sorted = (0 until movies.size).sortedWith { a, b -> comparator.compare(keys[a], keys[b]) }
                .toIntArray()
            slicesSorted++
            orders[mode.ordinal] = sorted
            return sorted
        }
    }

    /** Takes in whatever [movies] adds to the list last seen — see the class KDoc. */
    fun ingest(movies: List<Movie>) {
        val extendsPrevious = movies.size >= ingestedCount &&
            (ingestedCount == 0 || movies[ingestedCount - 1] === lastIngested)
        if (!extendsPrevious) {
            slices.clear()
            pageCards = IdentityHashMap()
            ingestedCount = 0
        }
        for (i in ingestedCount until movies.size) {
            val movie = movies[i]
            slices.getOrPut(movie.categoryId) { Slice() }.add(movie)
        }
        ingestedCount = movies.size
        lastIngested = movies.lastOrNull()
    }

    /**
     * The first [limit] distinct films (by [Movie.id]) of [categoryIds] in [mode] order. When the
     * same film sits in several categories, its first occurrence in that order wins.
     */
    fun page(categoryIds: Collection<String>, mode: MovieSortMode, limit: Int): Page {
        val comparator = MovieSort.comparator(mode)
        val cursors = PriorityQueue<Cursor>(maxOf(1, categoryIds.size)) { a, b ->
            comparator.compare(a.key, b.key)
        }
        categoryIds.toSet().forEach { categoryId ->
            val slice = slices[categoryId] ?: return@forEach
            if (slice.movies.isNotEmpty()) cursors += Cursor(slice, slice.order(mode))
        }

        val seenIds = HashSet<String>()
        val pageMovies = ArrayList<Movie>(minOf(limit, 256))
        var hasMore = false
        while (cursors.isNotEmpty()) {
            val cursor = cursors.poll()!!
            val movie = cursor.movie
            if (seenIds.add(movie.id)) {
                if (pageMovies.size == limit) {
                    hasMore = true
                    break
                }
                pageMovies += movie
            }
            if (cursor.advance()) cursors += cursor
        }

        val cards = IdentityHashMap<Movie, HomeCardItem>(pageMovies.size)
        val items = pageMovies.map { movie ->
            val card = pageCards[movie] ?: toCard(movie).also { cardsBuilt++ }
            cards[movie] = card
            card
        }
        pageCards = cards
        return Page(items, hasMore)
    }

    private inner class Cursor(private val slice: Slice, private val order: IntArray) {
        private var position = 0
        val movie: Movie get() = slice.movies[order[position]]
        val key: MovieSortKey get() = slice.keys[order[position]]

        fun advance(): Boolean = ++position < order.size
    }
}

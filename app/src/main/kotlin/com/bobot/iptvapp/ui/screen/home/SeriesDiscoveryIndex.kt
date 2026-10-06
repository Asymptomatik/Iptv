package com.bobot.iptvapp.ui.screen.home

import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.util.SeriesSort
import com.bobot.iptvapp.domain.util.SeriesSortKey
import com.bobot.iptvapp.domain.util.sortKey
import java.util.IdentityHashMap
import java.util.PriorityQueue

/**
 * The index behind the Series tab's "Nouveautés" view: every series of the selected categories, in
 * [SeriesSort] order, one page at a time.
 *
 * Same shape as [MovieDiscoveryIndex], with a single order:
 *  - [ingest] only reads the series appended since its previous call; a list that does not extend
 *    the previous one (a reload) starts the index over.
 *  - A slice builds its [SeriesSortKey]s and sorted order lazily, the first time a page needs them,
 *    and keeps both until the slice grows.
 *  - [page] is a k-way merge of the sorted slices that stops after `limit` distinct series.
 *  - Only the returned page is turned into [HomeCardItem]s, reusing the previous page's cards.
 *
 * Not thread-safe: [HomeViewModel] drives one instance from a single collector, and replaces it
 * with a fresh one for every load.
 */
internal class SeriesDiscoveryIndex(
    private val latestPlausibleYear: Int,
    private val toCard: (Series) -> HomeCardItem,
) {

    /** One page of "Nouveautés": its cards, and whether at least one more distinct series follows. */
    data class Page(val items: List<HomeCardItem>, val hasMore: Boolean)

    /** Titles parsed into sort keys so far — each series counts once per load. */
    var keysBuilt = 0
        private set

    /** Slice sorts performed so far — once per slice until the slice grows. */
    var slicesSorted = 0
        private set

    /** Cards built so far; only the series of the pages asked for ever get one. */
    var cardsBuilt = 0
        private set

    private val slices = HashMap<String, Slice>()
    private var ingestedCount = 0
    private var lastIngested: Series? = null
    private var pageCards = IdentityHashMap<Series, HomeCardItem>()

    private inner class Slice {
        val series = ArrayList<Series>()
        val keys = ArrayList<SeriesSortKey>()
        private var order: IntArray? = null

        fun add(entry: Series) {
            series += entry
            order = null
        }

        /** Positions into [series], in [SeriesSort] order. */
        fun order(): IntArray {
            order?.let { return it }
            for (i in keys.size until series.size) {
                keys += series[i].sortKey(latestPlausibleYear)
                keysBuilt++
            }
            val comparator = SeriesSort.comparator
            val sorted = (0 until series.size).sortedWith { a, b -> comparator.compare(keys[a], keys[b]) }
                .toIntArray()
            slicesSorted++
            order = sorted
            return sorted
        }
    }

    /** Takes in whatever [series] adds to the list last seen — see the class KDoc. */
    fun ingest(series: List<Series>) {
        val extendsPrevious = series.size >= ingestedCount &&
            (ingestedCount == 0 || series[ingestedCount - 1] === lastIngested)
        if (!extendsPrevious) {
            slices.clear()
            pageCards = IdentityHashMap()
            ingestedCount = 0
        }
        for (i in ingestedCount until series.size) {
            val entry = series[i]
            slices.getOrPut(entry.categoryId) { Slice() }.add(entry)
        }
        ingestedCount = series.size
        lastIngested = series.lastOrNull()
    }

    /**
     * The first [limit] distinct series (by [Series.id]) of [categoryIds] in [SeriesSort] order.
     * When the same series sits in several categories, its first occurrence in that order wins.
     */
    fun page(categoryIds: Collection<String>, limit: Int): Page {
        val comparator = SeriesSort.comparator
        val cursors = PriorityQueue<Cursor>(maxOf(1, categoryIds.size)) { a, b ->
            comparator.compare(a.key, b.key)
        }
        categoryIds.toSet().forEach { categoryId ->
            val slice = slices[categoryId] ?: return@forEach
            if (slice.series.isNotEmpty()) cursors += Cursor(slice, slice.order())
        }

        val seenIds = HashSet<String>()
        val pageSeries = ArrayList<Series>(minOf(limit, 256))
        var hasMore = false
        while (cursors.isNotEmpty()) {
            val cursor = cursors.poll()!!
            val entry = cursor.series
            if (seenIds.add(entry.id)) {
                if (pageSeries.size == limit) {
                    hasMore = true
                    break
                }
                pageSeries += entry
            }
            if (cursor.advance()) cursors += cursor
        }

        val cards = IdentityHashMap<Series, HomeCardItem>(pageSeries.size)
        val items = pageSeries.map { entry ->
            val card = pageCards[entry] ?: toCard(entry).also { cardsBuilt++ }
            cards[entry] = card
            card
        }
        pageCards = cards
        return Page(items, hasMore)
    }

    private inner class Cursor(private val slice: Slice, private val order: IntArray) {
        private var position = 0
        val series: Series get() = slice.series[order[position]]
        val key: SeriesSortKey get() = slice.keys[order[position]]

        fun advance(): Boolean = ++position < order.size
    }
}

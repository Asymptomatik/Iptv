package com.bobot.iptvapp.ui.screen.home

import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.util.MovieSortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeScreenSelectionTest {

    private val firstMovie = HomeCardItem(
        id = "movie-1",
        title = "Premier film",
        imageUrl = null,
        contentType = ContentType.MOVIE,
    )
    private val secondMovie = HomeCardItem(
        id = "movie-2",
        title = "Second film",
        imageUrl = null,
        contentType = ContentType.MOVIE,
    )
    private val liveHero = HomeCardItem(
        id = "live-hero",
        title = "Hero live",
        imageUrl = null,
        contentType = ContentType.LIVE,
    )

    private val actionRow = HomeRow(
        categoryId = "action",
        title = "Action",
        items = listOf(firstMovie),
    )
    private val dramaRow = HomeRow(
        categoryId = "drama",
        title = "Drame",
        items = listOf(secondMovie),
    )

    @Test
    fun `normalizedCategorySelectionFor keeps the explicit non-first category when it still exists`() {
        val uiState = HomeUiState(movieRows = listOf(actionRow, dramaRow))

        val selection = uiState.normalizedCategorySelectionFor(
            tab = HomeTab.MOVIES,
            selectedCategoryId = "drama",
        )

        assertEquals("drama", selection)
    }

    @Test
    fun `normalizedCategorySelectionFor falls back to the first available category when selection is stale`() {
        val uiState = HomeUiState(seriesRows = listOf(actionRow, dramaRow))

        val selection = uiState.normalizedCategorySelectionFor(
            tab = HomeTab.SERIES,
            selectedCategoryId = "unknown",
        )

        assertEquals("action", selection)
    }

    @Test
    fun `initialFocusItemFor targets the selected category instead of the first category`() {
        val uiState = HomeUiState(movieRows = listOf(actionRow, dramaRow))

        val focusItem = uiState.initialFocusItemFor(
            tab = HomeTab.MOVIES,
            selectedCategoryId = "drama",
        )

        assertEquals(secondMovie, focusItem)
    }

    @Test
    fun `initialFocusItemFor returns null on home when hero is present`() {
        val uiState = HomeUiState(
            liveRows = listOf(HomeRow(categoryId = "live", title = "Live", items = listOf(liveHero))),
        )

        val focusItem = uiState.initialFocusItemFor(
            tab = HomeTab.HOME,
            selectedCategoryId = null,
        )

        assertNull(focusItem)
    }

    // --- Films "Nouveautés" ---

    private val newReleaseCard = HomeCardItem(
        id = "movie-new",
        title = "Nouveau film (2025)",
        imageUrl = null,
        contentType = ContentType.MOVIE,
    )

    private fun filmsState(
        selectedSortMode: MovieSortMode = MovieSortMode.RECENT_RELEASE,
        itemsSortMode: MovieSortMode = MovieSortMode.RECENT_RELEASE,
        items: List<HomeCardItem> = listOf(newReleaseCard, firstMovie),
    ) = HomeUiState(
        movieRows = listOf(actionRow, dramaRow),
        selectedMovieSortMode = selectedSortMode,
        newReleases = NewReleasesState(sortMode = itemsSortMode, items = items, hasMore = true),
    )

    @Test
    fun `Films defaults to Nouveautes even when categories exist`() {
        assertEquals(
            NEW_RELEASES_CATEGORY_ID,
            filmsState().normalizedCategorySelectionFor(tab = HomeTab.MOVIES, selectedCategoryId = null),
        )
    }

    @Test
    fun `Films falls back to Nouveautes, not the first category, when the selection is stale`() {
        assertEquals(
            NEW_RELEASES_CATEGORY_ID,
            filmsState().normalizedCategorySelectionFor(tab = HomeTab.MOVIES, selectedCategoryId = "unknown"),
        )
    }

    @Test
    fun `Films keeps Nouveautes selected before any category has loaded`() {
        val uiState = HomeUiState()

        assertEquals(
            NEW_RELEASES_CATEGORY_ID,
            uiState.normalizedCategorySelectionFor(tab = HomeTab.MOVIES, selectedCategoryId = NEW_RELEASES_CATEGORY_ID),
        )
    }

    @Test
    fun `other catalog tabs never get a Nouveautes selection`() {
        val uiState = HomeUiState(liveRows = listOf(actionRow), seriesRows = listOf(dramaRow))

        assertEquals("action", uiState.normalizedCategorySelectionFor(HomeTab.LIVE, NEW_RELEASES_CATEGORY_ID))
        assertEquals("drama", uiState.normalizedCategorySelectionFor(HomeTab.SERIES, null))
    }

    @Test
    fun `the Nouveautes grid row shows the published page only`() {
        val row = filmsState().selectedCategoryRowFor(HomeTab.MOVIES, NEW_RELEASES_CATEGORY_ID)

        assertEquals(NEW_RELEASES_CATEGORY_ID, row?.categoryId)
        assertEquals(listOf(newReleaseCard, firstMovie), row?.items)
    }

    @Test
    fun `initialFocusItemFor targets the first Nouveautes card`() {
        assertEquals(newReleaseCard, filmsState().initialFocusItemFor(HomeTab.MOVIES, NEW_RELEASES_CATEGORY_ID))
    }

    @Test
    fun `cards sorted for another order are hidden while the picked order is on its way`() {
        val uiState = filmsState(selectedSortMode = MovieSortMode.RECENTLY_ADDED)

        val row = uiState.selectedCategoryRowFor(HomeTab.MOVIES, NEW_RELEASES_CATEGORY_ID)

        assertTrue(uiState.isNewReleasesPending)
        assertEquals(emptyList<HomeCardItem>(), row?.items)
        // No stale card to steal focus either.
        assertNull(uiState.initialFocusItemFor(HomeTab.MOVIES, NEW_RELEASES_CATEGORY_ID))
    }

    @Test
    fun `an ordinary Films category grows its local grid page and never asks for Nouveautes`() {
        var gridPages = 1
        var newReleasesRequests = 0

        val loadMore = movieGridLoadMore(
            showsNewReleases = false,
            onLoadMoreNewReleases = { newReleasesRequests++ },
            onNextGridPage = { gridPages++ },
        )
        loadMore()

        assertEquals(2, gridPages)
        assertEquals(0, newReleasesRequests)
    }

    @Test
    fun `Nouveautes asks the ViewModel for its next page, not the local grid page`() {
        var gridPages = 1
        var newReleasesRequests = 0

        val loadMore = movieGridLoadMore(
            showsNewReleases = true,
            onLoadMoreNewReleases = { newReleasesRequests++ },
            onNextGridPage = { gridPages++ },
        )
        loadMore()

        assertEquals(1, gridPages)
        assertEquals(1, newReleasesRequests)
    }

    @Test
    fun `Nouveautes waits while Films rows are being indexed`() {
        val uiState = filmsState(items = emptyList()).copy(
            catalogTabLoadStates = mapOf(ContentType.MOVIE to CatalogTabLoadState.LOADED),
        )

        assertTrue(uiState.isNewReleasesWaiting())
    }

    @Test
    fun `Nouveautes stops waiting once the Films reload failed with the previous rows kept`() {
        val uiState = HomeUiState(
            movieRows = listOf(actionRow, dramaRow),
            errorMessage = "Panne films",
            catalogTabLoadStates = mapOf(ContentType.MOVIE to CatalogTabLoadState.LOADED),
            newReleases = NewReleasesState(isFailed = true),
        )

        assertFalse(uiState.isNewReleasesWaiting())
        // The chips stay: the previous categories and Nouveautés are still selectable.
        assertEquals(NEW_RELEASES_CATEGORY_ID, uiState.normalizedCategorySelectionFor(HomeTab.MOVIES, null))
        assertEquals("drama", uiState.normalizedCategorySelectionFor(HomeTab.MOVIES, "drama"))
    }
}

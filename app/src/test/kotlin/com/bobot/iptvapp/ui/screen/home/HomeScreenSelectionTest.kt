package com.bobot.iptvapp.ui.screen.home

import com.bobot.iptvapp.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val uiState = HomeUiState(movieRows = listOf(actionRow, dramaRow))

        val selection = uiState.normalizedCategorySelectionFor(
            tab = HomeTab.MOVIES,
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
}

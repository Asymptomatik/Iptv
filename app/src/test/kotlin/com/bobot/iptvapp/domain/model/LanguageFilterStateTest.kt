package com.bobot.iptvapp.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageFilterStateTest {

    @Test
    fun `default state has no available languages and no selection`() {
        val state = LanguageFilterState()

        assertTrue(state.available.isEmpty())
        assertNull(state.selected)
    }

    @Test
    fun `withSelection changes selected while leaving available untouched`() {
        val state = LanguageFilterState(available = listOf("FR", "EN"), selected = null)

        val result = state.withSelection("FR")

        assertEquals("FR", result.selected)
        assertEquals(listOf("FR", "EN"), result.available)
    }

    @Test
    fun `withSelection to null clears the selection`() {
        val state = LanguageFilterState(available = listOf("FR", "EN"), selected = "FR")

        val result = state.withSelection(null)

        assertNull(result.selected)
        assertEquals(listOf("FR", "EN"), result.available)
    }
}

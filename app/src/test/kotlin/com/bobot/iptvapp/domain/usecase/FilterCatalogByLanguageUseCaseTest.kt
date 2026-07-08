package com.bobot.iptvapp.domain.usecase

import com.bobot.iptvapp.domain.model.Category
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.LanguageFilterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterCatalogByLanguageUseCaseTest {

    private val useCase = FilterCatalogByLanguageUseCase()

    private fun category(id: String, name: String, type: ContentType = ContentType.LIVE) =
        Category(id = id, name = name, type = type)

    // ── availableLanguages ─────────────────────────────────────────────────

    @Test
    fun `availableLanguages returns distinct tags in first-appearance order`() {
        val categories = listOf(
            category("1", "FR | Sport"),
            category("2", "EN | News"),
            category("3", "FR | Documentaires"),
            category("4", "EN | Movies"),
        )

        val result = useCase.availableLanguages(categories)

        assertEquals(listOf("FR", "EN"), result)
    }

    @Test
    fun `availableLanguages excludes null tags`() {
        val categories = listOf(
            category("1", "FR | Sport"),
            category("2", "Documentaires"),
            category("3", "EN | News"),
        )

        val result = useCase.availableLanguages(categories)

        assertEquals(listOf("FR", "EN"), result)
    }

    @Test
    fun `availableLanguages returns empty list when no category has a detectable tag`() {
        val categories = listOf(
            category("1", "Documentaires"),
            category("2", "4K | Sport"),
        )

        val result = useCase.availableLanguages(categories)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `availableLanguages returns empty list for empty input`() {
        val result = useCase.availableLanguages(emptyList())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `availableLanguages supports union of multiple content types' categories`() {
        val liveCategories = listOf(category("1", "FR | Sport"))
        val vodCategories = listOf(category("2", "EN | Movies"))
        val seriesCategories = listOf(category("3", "FR | Series"), category("4", "DE | Series"))

        val result = useCase.availableLanguages(liveCategories + vodCategories + seriesCategories)

        assertEquals(listOf("FR", "EN", "DE"), result)
    }

    // ── matches ─────────────────────────────────────────────────────────────

    @Test
    fun `matches returns true when selectedLanguage is null regardless of category`() {
        assertTrue(useCase.matches(category("1", "FR | Sport"), selectedLanguage = null))
        assertTrue(useCase.matches(null, selectedLanguage = null))
    }

    @Test
    fun `matches returns true when category tag equals selectedLanguage`() {
        val result = useCase.matches(category("1", "FR | Sport"), selectedLanguage = "FR")

        assertTrue(result)
    }

    @Test
    fun `matches returns false when category tag differs from selectedLanguage`() {
        val result = useCase.matches(category("1", "EN | News"), selectedLanguage = "FR")

        assertFalse(result)
    }

    @Test
    fun `matches returns false when category is null and selectedLanguage is not null`() {
        val result = useCase.matches(null, selectedLanguage = "FR")

        assertFalse(result)
    }

    @Test
    fun `matches returns false when category has no detectable tag and selectedLanguage is not null`() {
        val result = useCase.matches(category("1", "Documentaires"), selectedLanguage = "FR")

        assertFalse(result)
    }

    // ── filterCategories ───────────────────────────────────────────────────

    @Test
    fun `filterCategories keeps every category when selectedLanguage is null`() {
        val categories = listOf(
            category("1", "FR | Sport"),
            category("2", "Documentaires"),
            category("3", "EN | News"),
        )

        val result = useCase.filterCategories(categories, selectedLanguage = null)

        assertEquals(categories, result)
    }

    @Test
    fun `filterCategories keeps only categories matching selectedLanguage`() {
        val fr = category("1", "FR | Sport")
        val en = category("2", "EN | News")
        val untagged = category("3", "Documentaires")

        val result = useCase.filterCategories(listOf(fr, en, untagged), selectedLanguage = "FR")

        assertEquals(listOf(fr), result)
    }

    @Test
    fun `filterCategories excludes untagged categories when a filter is active`() {
        val untagged = category("1", "Documentaires")

        val result = useCase.filterCategories(listOf(untagged), selectedLanguage = "FR")

        assertTrue(result.isEmpty())
    }

    // ── deriveAvailableLanguages ────────────────────────────────────────────

    @Test
    fun `deriveAvailableLanguages recomputes available from categories`() {
        val categories = listOf(category("1", "FR | Sport"), category("2", "EN | News"))
        val state = LanguageFilterState(available = emptyList(), selected = null)

        val result = useCase.deriveAvailableLanguages(categories, state)

        assertEquals(listOf("FR", "EN"), result.available)
    }

    @Test
    fun `deriveAvailableLanguages replaces stale available list`() {
        val categories = listOf(category("1", "EN | News"))
        val state = LanguageFilterState(available = listOf("FR", "DE"), selected = null)

        val result = useCase.deriveAvailableLanguages(categories, state)

        assertEquals(listOf("EN"), result.available)
    }

    @Test
    fun `deriveAvailableLanguages preserves the current selection even if it is no longer available`() {
        val categories = listOf(category("1", "EN | News"))
        val state = LanguageFilterState(available = listOf("FR"), selected = "FR")

        val result = useCase.deriveAvailableLanguages(categories, state)

        assertEquals("FR", result.selected)
        assertEquals(listOf("EN"), result.available)
    }

    @Test
    fun `deriveAvailableLanguages with no selection keeps selection null`() {
        val categories = listOf(category("1", "FR | Sport"))
        val state = LanguageFilterState()

        val result = useCase.deriveAvailableLanguages(categories, state)

        assertNull(result.selected)
    }
}

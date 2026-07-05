package com.bobot.iptvapp.domain.util

import com.bobot.iptvapp.domain.model.Category
import com.bobot.iptvapp.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryLanguageTest {

    // ── Recognised delimiters ────────────────────────────────────────────────

    @Test
    fun `extractLanguageTag returns tag for pipe delimiter`() {
        val result = CategoryLanguage.extractLanguageTag("FR | Sport")

        assertEquals("FR", result)
    }

    @Test
    fun `extractLanguageTag returns tag for dash delimiter without surrounding spaces`() {
        val result = CategoryLanguage.extractLanguageTag("EN-Movies")

        assertEquals("EN", result)
    }

    @Test
    fun `extractLanguageTag returns tag for dash delimiter with surrounding spaces`() {
        val result = CategoryLanguage.extractLanguageTag("EN - Movies")

        assertEquals("EN", result)
    }

    @Test
    fun `extractLanguageTag returns tag for colon delimiter`() {
        val result = CategoryLanguage.extractLanguageTag("FR: Documentaires")

        assertEquals("FR", result)
    }

    @Test
    fun `extractLanguageTag returns three-letter tag`() {
        val result = CategoryLanguage.extractLanguageTag("FRA | Sport")

        assertEquals("FRA", result)
    }

    // ── Case normalization ───────────────────────────────────────────────────

    @Test
    fun `extractLanguageTag normalizes lowercase input to uppercase tag`() {
        val result = CategoryLanguage.extractLanguageTag("fr | sport")

        assertEquals("FR", result)
    }

    // ── No-tag cases ─────────────────────────────────────────────────────────

    @Test
    fun `extractLanguageTag returns null when no delimiter follows the leading word`() {
        val result = CategoryLanguage.extractLanguageTag("Documentaires")

        assertNull(result)
    }

    @Test
    fun `extractLanguageTag returns null for blank name`() {
        val result = CategoryLanguage.extractLanguageTag("   ")

        assertNull(result)
    }

    @Test
    fun `extractLanguageTag returns null for empty name`() {
        val result = CategoryLanguage.extractLanguageTag("")

        assertNull(result)
    }

    // ── Superficially similar but non-tag prefixes ──────────────────────────
    // "4K" and "18+" both start with a digit, so they never match the leading
    // alphabetic quantifier at all — see CategoryLanguage's KDoc for the reasoning.

    @Test
    fun `extractLanguageTag returns null for numeric quality prefix with delimiter`() {
        val result = CategoryLanguage.extractLanguageTag("4K | Sport")

        assertNull(result)
    }

    @Test
    fun `extractLanguageTag returns null for numeric quality prefix without delimiter`() {
        val result = CategoryLanguage.extractLanguageTag("4K UHD")

        assertNull(result)
    }

    @Test
    fun `extractLanguageTag returns null for age-restriction prefix`() {
        val result = CategoryLanguage.extractLanguageTag("18+ Adult")

        assertNull(result)
    }

    @Test
    fun `extractLanguageTag returns null for a plain word longer than three letters even when followed by a delimiter`() {
        val result = CategoryLanguage.extractLanguageTag("SPORT - Something")

        assertNull(result)
    }

    // ── Category extension ───────────────────────────────────────────────────

    @Test
    fun `languageTag extension delegates to extractLanguageTag using the category name`() {
        val category = Category(id = "1", name = "FR | Sport", type = ContentType.LIVE)

        assertEquals("FR", category.languageTag())
    }

    @Test
    fun `languageTag extension returns null when the category name has no recognised tag`() {
        val category = Category(id = "1", name = "Documentaires", type = ContentType.LIVE)

        assertNull(category.languageTag())
    }
}

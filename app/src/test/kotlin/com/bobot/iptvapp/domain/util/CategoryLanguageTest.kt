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

    @Test
    fun `extractLanguageTag returns nested language tag when provider prefix comes first`() {
        val result = CategoryLanguage.extractLanguageTag("SRS | FR - LATEST SERIES")

        assertEquals("FR", result)
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

    @Test
    fun `displayName extension removes nested provider and language prefixes`() {
        val category = Category(id = "1", name = "SRS | FR - LATEST SERIES", type = ContentType.SERIES)

        assertEquals("LATEST SERIES", category.displayName())
    }

    @Test
    fun `displayName extension removes direct language prefix`() {
        val category = Category(id = "1", name = "FR | Sport", type = ContentType.LIVE)

        assertEquals("Sport", category.displayName())
    }

    // ── Space-separated prefix (whitelist-gated fallback) ───────────────────

    @Test
    fun `extractLanguageTag returns tag for whitelisted space-separated prefix`() {
        val result = CategoryLanguage.extractLanguageTag("FR Sport")

        assertEquals("FR", result)
    }

    @Test
    fun `extractLanguageTag normalizes lowercase space-separated prefix to uppercase tag`() {
        val result = CategoryLanguage.extractLanguageTag("fr sport")

        assertEquals("FR", result)
    }

    @Test
    fun `extractLanguageTag returns six-letter whitelisted space-separated tag`() {
        val result = CategoryLanguage.extractLanguageTag("VOSTFR Films")

        assertEquals("VOSTFR", result)
    }

    @Test
    fun `extractLanguageTag returns tag for another whitelisted space-separated prefix`() {
        val result = CategoryLanguage.extractLanguageTag("EN Movies")

        assertEquals("EN", result)
    }

    @Test
    fun `extractLanguageTag returns null for non-whitelisted space-separated prefix HD`() {
        val result = CategoryLanguage.extractLanguageTag("HD Movies")

        assertNull(result)
    }

    @Test
    fun `extractLanguageTag returns null for non-whitelisted space-separated prefix 4K`() {
        val result = CategoryLanguage.extractLanguageTag("4K Sport")

        assertNull(result)
    }

    @Test
    fun `extractLanguageTag returns null for non-whitelisted space-separated prefix TV`() {
        val result = CategoryLanguage.extractLanguageTag("TV Shows")

        assertNull(result)
    }

    @Test
    fun `extractLanguageTag returns null for country codes behind a plain space`() {
        // Countries are not languages: behind a mere space the signal is too weak to act on.
        // "US Sports" must stay "US Sports", not become "Sports" filed under a pseudo-language.
        listOf(
            "SP Sports",
            "UK Entertainment",
            "US Sports",
            "BR Movies",
            "CA Movies",
            "BE Channels",
            "CH Channels",
        ).forEach { name ->
            assertNull("unexpected tag for \"$name\"", CategoryLanguage.extractLanguageTag(name))
        }
    }

    @Test
    fun `extractDisplayName keeps country codes behind a plain space`() {
        listOf(
            "SP Sports",
            "UK Entertainment",
            "US Sports",
            "BR Movies",
            "CA Movies",
            "BE Channels",
            "CH Channels",
        ).forEach { name ->
            assertEquals(name, CategoryLanguage.extractDisplayName(name))
        }
    }

    @Test
    fun `extractLanguageTag still returns country codes when an explicit delimiter is present`() {
        // The delimiter is a deliberate signal from the provider, so it is still honoured.
        assertEquals("UK", CategoryLanguage.extractLanguageTag("UK | Sports"))
        assertEquals("US", CategoryLanguage.extractLanguageTag("US - Movies"))
        assertEquals("BR", CategoryLanguage.extractLanguageTag("BR: Novelas"))
    }

    @Test
    fun `extractLanguageTag treats IT behind a space as Italian - knowingly retained ambiguity`() {
        // Documents an accepted trade-off: "IT Support" is misread as Italian, Italian being far
        // likelier than an IT-helpdesk category in an IPTV catalogue.
        assertEquals("IT", CategoryLanguage.extractLanguageTag("IT Support"))
    }

    @Test
    fun `extractLanguageTag returns null when whitelisted code is not the leading token`() {
        val result = CategoryLanguage.extractLanguageTag("Sport FR")

        assertNull(result)
    }

    @Test
    fun `extractLanguageTag returns null for whitelisted code alone without a following token`() {
        val result = CategoryLanguage.extractLanguageTag("FR")

        assertNull(result)
    }

    @Test
    fun `extractLanguageTag still returns delimited result unchanged when a space would also match`() {
        val result = CategoryLanguage.extractLanguageTag("FR | Sport")

        assertEquals("FR", result)
    }

    @Test
    fun `extractLanguageTag still returns nested delimited result unchanged when a space would also match`() {
        val result = CategoryLanguage.extractLanguageTag("SRS | FR - LATEST SERIES")

        assertEquals("FR", result)
    }

    @Test
    fun `extractDisplayName removes whitelisted space-separated prefix`() {
        val result = CategoryLanguage.extractDisplayName("FR Sport")

        assertEquals("Sport", result)
    }

    @Test
    fun `extractDisplayName returns name unchanged for non-whitelisted space-separated prefix`() {
        val result = CategoryLanguage.extractDisplayName("HD Movies")

        assertEquals("HD Movies", result)
    }

    // ── Non-breaking spaces ──────────────────────────────────────────────────
    // Regression coverage for a bug observed on a real Xtream catalogue: some providers separate
    // the tag from its delimiter with U+00A0 instead of a plain space. Java's `\s` and
    // Char.isWhitespace() are both ASCII-only and report false for it, so every pattern failed to
    // match — the category lost its tag *and* had its raw prefix shown to the user.

    @Test
    fun `extractLanguageTag returns tag when a non-breaking space precedes the delimiter`() {
        assertEquals("FR", CategoryLanguage.extractLanguageTag("FR\u00A0| Sport"))
        assertEquals("EU", CategoryLanguage.extractLanguageTag("EU\u00A0| FRANCE GENERAL"))
        assertEquals("AF", CategoryLanguage.extractLanguageTag("AF\u00A0| SOMETHING"))
    }

    @Test
    fun `extractDisplayName strips the prefix when a non-breaking space precedes the delimiter`() {
        assertEquals("FRANCE GENERAL", CategoryLanguage.extractDisplayName("EU\u00A0| FRANCE GENERAL"))
        assertEquals("Sport", CategoryLanguage.extractDisplayName("FR\u00A0|\u00A0Sport"))
    }

    @Test
    fun `extractLanguageTag returns nested tag across non-breaking spaces`() {
        assertEquals("FR", CategoryLanguage.extractLanguageTag("SRS\u00A0|\u00A0FR\u00A0-\u00A0LATEST SERIES"))
    }

    @Test
    fun `extractDisplayName removes nested prefixes across non-breaking spaces`() {
        assertEquals(
            "LATEST SERIES",
            CategoryLanguage.extractDisplayName("SRS\u00A0|\u00A0FR\u00A0-\u00A0LATEST SERIES"),
        )
    }

    @Test
    fun `extractLanguageTag returns whitelisted tag separated by a non-breaking space alone`() {
        assertEquals("FR", CategoryLanguage.extractLanguageTag("FR\u00A0Sport"))
    }

    @Test
    fun `extractLanguageTag trims leading and trailing non-breaking spaces`() {
        assertEquals("FR", CategoryLanguage.extractLanguageTag("\u00A0FR | Sport\u00A0"))
    }

    @Test
    fun `extractDisplayName trims leading and trailing non-breaking spaces`() {
        assertEquals("Sport", CategoryLanguage.extractDisplayName("\u00A0FR | Sport\u00A0"))
        assertEquals("Unrecognised name", CategoryLanguage.extractDisplayName("\u00A0Unrecognised name\u00A0"))
    }

    @Test
    fun `extractLanguageTag returns null for a name made only of non-breaking spaces`() {
        assertNull(CategoryLanguage.extractLanguageTag("\u00A0\u00A0"))
    }

    @Test
    fun `extractLanguageTag keeps rejecting non-whitelisted prefixes behind a non-breaking space`() {
        // The whitespace widening must not weaken the false-positive filtering.
        assertNull(CategoryLanguage.extractLanguageTag("HD\u00A0Movies"))
        assertNull(CategoryLanguage.extractLanguageTag("US\u00A0Sports"))
    }
}

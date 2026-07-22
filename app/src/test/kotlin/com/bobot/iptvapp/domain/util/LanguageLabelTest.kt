package com.bobot.iptvapp.domain.util

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageLabelTest {

    // ── Null / blank / undetermined ─────────────────────────────────────────

    @Test
    fun `forCode returns null for null code`() {
        val result = LanguageLabel.forCode(null)

        assertNull(result)
    }

    @Test
    fun `forCode returns null for blank code`() {
        val result = LanguageLabel.forCode("   ")

        assertNull(result)
    }

    @Test
    fun `forCode returns null for empty code`() {
        val result = LanguageLabel.forCode("")

        assertNull(result)
    }

    @Test
    fun `forCode returns null for the ISO undetermined placeholder`() {
        val result = LanguageLabel.forCode("und")

        assertNull(result)
    }

    @Test
    fun `forCode returns null for the undetermined placeholder regardless of case`() {
        val result = LanguageLabel.forCode("UND")

        assertNull(result)
    }

    // ── Resolvable codes ─────────────────────────────────────────────────────

    @Test
    fun `forCode returns readable display name for a known two-letter code`() {
        val result = LanguageLabel.forCode("fr", displayLocale = Locale.ENGLISH)

        assertEquals("French", result)
    }

    @Test
    fun `forCode returns readable display name in the requested display locale`() {
        val result = LanguageLabel.forCode("es", displayLocale = Locale.FRENCH)

        assertEquals("espagnol", result)
    }

    @Test
    fun `forCode is case-insensitive on the input code`() {
        val lower = LanguageLabel.forCode("en", displayLocale = Locale.ENGLISH)
        val upper = LanguageLabel.forCode("EN", displayLocale = Locale.ENGLISH)

        assertEquals(lower, upper)
    }

    @Test
    fun `forCode trims surrounding whitespace before resolving`() {
        val result = LanguageLabel.forCode("  fr  ", displayLocale = Locale.ENGLISH)

        assertEquals("French", result)
    }

    // ── ISO 639-2 (three-letter) codes — realistic IPTV/Xtream input ─────────
    //
    // Empirically verified against a real JDK 21 (JetBrains Runtime, bundled with Android
    // Studio) while implementing this fix: java.util.Locale.getDisplayLanguage() does NOT
    // uniformly resolve three-letter ISO 639-2 codes — e.g. "fra"/"deu"/"zho" echo back the raw
    // code unresolved on that JDK, while "eng"/"ger"/"chi" happen to resolve directly. This is
    // exactly the inconsistency LanguageLabel's ISO_639_2_TO_1 alias map exists to paper over:
    // every code below must resolve to a readable name regardless of which of those two buckets
    // the running JVM's bundled locale data would otherwise put it in.

    @Test
    fun `forCode resolves the ISO 639-2 terminological code for French`() {
        val result = LanguageLabel.forCode("fra", displayLocale = Locale.ENGLISH)

        assertEquals("French", result)
    }

    @Test
    fun `forCode resolves ISO 639-2 codes for English and German`() {
        val english = LanguageLabel.forCode("eng", displayLocale = Locale.ENGLISH)
        val german = LanguageLabel.forCode("deu", displayLocale = Locale.ENGLISH)

        assertEquals("English", english)
        assertEquals("German", german)
    }

    @Test
    fun `forCode resolves the ISO 639-2 bibliographic code for German`() {
        // "ger" (639-2/B) alongside "deu" (639-2/T) — both map to the same display name.
        val result = LanguageLabel.forCode("ger", displayLocale = Locale.ENGLISH)

        assertEquals("German", result)
    }

    @Test
    fun `forCode resolves a composite ISO 639-1 region tag such as pt-BR`() {
        val result = LanguageLabel.forCode("pt-BR", displayLocale = Locale.ENGLISH)

        assertEquals("Portuguese", result)
    }

    @Test
    fun `forCode preserves the region subtag when aliasing a composite three-letter tag`() {
        // "fra-BE" (Belgian French) — the alias must only rewrite the leading language subtag.
        val result = LanguageLabel.forCode("fra-BE", displayLocale = Locale.ENGLISH)

        assertEquals("French", result)
    }

    // ── Unmappable / garbage codes ───────────────────────────────────────────

    @Test
    fun `forCode falls back to the raw trimmed code when it cannot be resolved`() {
        val result = LanguageLabel.forCode("xyz", displayLocale = Locale.ENGLISH)

        assertEquals("xyz", result)
    }

    @Test
    fun `forCode never throws for symbol-only garbage input`() {
        val result = LanguageLabel.forCode("###", displayLocale = Locale.ENGLISH)

        assertEquals("###", result)
    }

    // ── Default display locale ───────────────────────────────────────────────

    @Test
    fun `forCode without an explicit display locale still returns a non-blank label`() {
        val result = LanguageLabel.forCode("de")

        assertFalse(result.isNullOrBlank())
    }
}

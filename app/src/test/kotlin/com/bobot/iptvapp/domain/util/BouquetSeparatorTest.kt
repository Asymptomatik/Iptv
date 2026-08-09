package com.bobot.iptvapp.domain.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BouquetSeparator].
 *
 * Two halves, and the second one matters more: the separator cases document what the rule is
 * meant to catch, the channel cases pin down what it must never catch. A false positive removes
 * a channel the user is paying for, and does so silently — no error, the entry simply is not in
 * the list any more — so every plausible decorated channel name seen in the wild gets a case.
 */
class BouquetSeparatorTest {

    @Test
    fun `hash-framed separators are recognised`() {
        assertTrue(BouquetSeparator.matches("##### FRANCE GENERAL FHD #####"))
        assertTrue(BouquetSeparator.matches("### VOD | FR ###"))
    }

    @Test
    fun `other decoration styles are recognised`() {
        assertTrue(BouquetSeparator.matches("--- SPORT ---"))
        assertTrue(BouquetSeparator.matches("=== BEIN SPORTS ==="))
        assertTrue(BouquetSeparator.matches("▬▬▬ CANAL+ ▬▬▬"))
        assertTrue(BouquetSeparator.matches("***  KIDS  ***"))
    }

    @Test
    fun `a row of pure decoration is a separator`() {
        assertTrue(BouquetSeparator.matches("========================"))
        assertTrue(BouquetSeparator.matches("---"))
        assertTrue(BouquetSeparator.matches("■■■■■■■■"))
    }

    @Test
    fun `a blank name is a separator`() {
        assertTrue(BouquetSeparator.matches(""))
        assertTrue(BouquetSeparator.matches("   "))
    }

    @Test
    fun `ordinary channel names are kept`() {
        assertFalse(BouquetSeparator.matches("TF1"))
        assertFalse(BouquetSeparator.matches("FR | TF1 FHD"))
        assertFalse(BouquetSeparator.matches("beIN SPORTS 1 4K"))
    }

    @Test
    fun `decorated channel names are kept`() {
        // Each of these carries decoration, but never a three-glyph frame on both ends.
        assertFalse("a leading hash is a channel number, not a frame", BouquetSeparator.matches("#1 Music"))
        assertFalse("an internal dash is a separator inside the name", BouquetSeparator.matches("TF1 - HD"))
        assertFalse(BouquetSeparator.matches("M6 +1"))
        assertFalse("two stars either side is not enough", BouquetSeparator.matches("**CANAL+**"))
        assertFalse(BouquetSeparator.matches("RMC Sport 1 |FR|"))
        assertFalse("an internal hashtag is not a frame", BouquetSeparator.matches("CNews #Replay"))
        assertFalse(BouquetSeparator.matches("Eurosport 1 HD ***"))
    }

    @Test
    fun `decoration on one side only is not a frame`() {
        assertFalse(BouquetSeparator.matches("### FRANCE"))
        assertFalse(BouquetSeparator.matches("FRANCE ###"))
    }

    @Test
    fun `the whitespace between frame and label does not count towards the run`() {
        // "-- BEIN 1 --" reaches three characters either side only if the space is counted.
        assertFalse(BouquetSeparator.matches("-- BEIN 1 --"))
    }
}

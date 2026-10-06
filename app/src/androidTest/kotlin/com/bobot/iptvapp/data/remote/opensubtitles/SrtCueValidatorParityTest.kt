package com.bobot.iptvapp.data.remote.opensubtitles

import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.subrip.SubripParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [SrtCueValidator] must say what the player's own parser says. On the JVM, Media3's
 * `SubripParser` cannot run (its `TextUtils`/`Html` calls are stubs there), so the replica is held
 * to the real 1.4.1 parser here, on Android, where both share the same `java.util.regex`,
 * `Integer`/`Long` parsing and `Html.fromHtml` ([AndroidCueHtml]) the player uses.
 *
 * Two lists: [CORPUS], where the verdicts must be equal, and [ACCEPTED_LIMITS], the known cases
 * where the validator refuses a file Media3 would play — never the other way round.
 *
 * ## Running
 * ```
 * adb.exe shell am instrument -w \
 *   -e class com.bobot.iptvapp.data.remote.opensubtitles.SrtCueValidatorParityTest \
 *   com.bobot.iptvapp.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class SrtCueValidatorParityTest {

    /** What the player would do with [bytes]: show at least one cue, or nothing / fail the track. */
    private fun media3Plays(bytes: ByteArray): Boolean = try {
        var plays = false
        SubripParser().parse(bytes, SubtitleParser.OutputOptions.allCues()) { item ->
            if (item.durationUs > 0 && item.cues.any { !it.text.isNullOrBlank() }) plays = true
        }
        plays
    } catch (e: RuntimeException) {
        false
    }

    private val validator = SrtCueValidator(AndroidCueHtml)

    @Test
    fun the_validator_agrees_with_media3_on_every_sample() {
        val disagreements = CORPUS.mapNotNull { (case, text) ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            val expected = media3Plays(bytes)
            val actual = validator.hasPlayableCue(bytes)
            if (expected != actual) "$case: media3=$expected validator=$actual" else null
        }
        assertEquals(disagreements.joinToString("\n"), 0, disagreements.size)
    }

    @Test
    fun the_accepted_limits_only_ever_refuse_what_media3_plays() {
        // Should Media3 stop playing one of these, it belongs in CORPUS instead.
        val unexpected = ACCEPTED_LIMITS.mapNotNull { (case, text) ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            val media3 = media3Plays(bytes)
            val actual = validator.hasPlayableCue(bytes)
            if (!media3 || actual) "$case: media3=$media3 validator=$actual" else null
        }
        assertEquals(unexpected.joinToString("\n"), 0, unexpected.size)
    }

    @Test
    fun the_corpus_holds_both_verdicts() {
        // A corpus Media3 accepts (or refuses) wholesale would prove nothing.
        val verdicts = CORPUS.map { (_, text) -> media3Plays(text.toByteArray(Charsets.UTF_8)) }
        assertTrue(true in verdicts)
        assertTrue(false in verdicts)
    }

    private companion object {
        const val CUE = "1\n00:00:01,000 --> 00:00:02,000\nBonsoir\n"

        val CORPUS: List<Pair<String, String>> = listOf(
            // Refused by the downloader's unit tests.
            "lone timecode" to "00:00:01,000 --> 00:00:02,000",
            "cue without text" to "1\n00:00:01,000 --> 00:00:02,000\n\n",
            "cue with tags only" to "1\n00:00:01,000 --> 00:00:02,000\n{\\an8}<i></i>\n",
            "cue ending before it starts" to "1\n00:00:05,000 --> 00:00:02,000\nTrop tard\n",
            "zero-length cue" to "1\n00:00:02,000 --> 00:00:02,000\nInstantané\n",
            "dotted milliseconds" to "1\n00:00:01.000 --> 00:00:02.000\nBonsoir\n",
            "html page quoting a timecode" to "<html><pre>00:00:01,000 --> 00:00:02,000</pre></html>",
            "binary as CP1252 around a timecode" to
                String(ByteArray(512) { (0x80 + (it * 31 + 7) % 0x80).toByte() }, charset("windows-1252")) +
                "00:00:01,000 --> 00:00:02,000",
            "overflow after a valid cue" to
                "$CUE\n2\n99999999999999999999:00:00,000 --> 99999999999999999999:00:01,000\nJamais\n",
            "overflowing milliseconds" to "1\n00:00:01,99999999999999999999 --> 00:00:02,000\nBonsoir\n",
            // Kept by the downloader's unit tests.
            "plain" to CUE,
            "CRLF" to "1\r\n00:00:01,000 --> 00:00:02,000\r\nBonsoir\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nÀ demain\r\n",
            "CR only" to "1\r00:00:01,000 --> 00:00:02,000\rBonsoir\r",
            "no hours" to "\n\n1\n01:02,500 --> 01:04,000\nSans heures\n",
            "long hours, no millis" to "1\n100:00:01 --> 100:00:02,5\nCent heures, sans millisecondes\n",
            "spaces around" to "1\n  00:00:01,000-->00:00:02,000\t\n  Espaces autour  \n",
            "valid cue after a malformed one" to
                "1\nnot a timing\n\n2\n00:00:01,000 --> 00:00:02,000\n{\\an8}<i>Seconde cue valide</i>",
            // Edges of the replica.
            "LF CR is two breaks" to "1\n\r00:00:01,000 --> 00:00:02,000\nBonsoir\n",
            "BOM heading an index line" to "﻿1\n00:00:01,000 --> 00:00:02,000\nBonsoir\n",
            "BOM mid-file" to "1\nnot a timing\n\n﻿2\n00:00:01,000 --> 00:00:02,000\nBonsoir\n",
            "signed index" to "+1\n00:00:01,000 --> 00:00:02,000\nBonsoir\n",
            "negative index" to "-1\n00:00:01,000 --> 00:00:02,000\nBonsoir\n",
            "index with a space" to " 1\n00:00:01,000 --> 00:00:02,000\nBonsoir\n",
            "index then EOF" to "1",
            "timing then EOF" to "1\n00:00:01,000 --> 00:00:02,000",
            "whitespace line then text" to "1\n00:00:01,000 --> 00:00:02,000\n   \nBonsoir\n",
            "text after an empty line is not the cue's" to "1\n00:00:01,000 --> 00:00:02,000\n\nBonsoir\n",
            "silent multiplication overflow" to "1\n9999999999999:00:00,000 --> 9999999999999:00:01,000\nBonsoir\n",
            "four-digit millis" to "1\n00:00:01,0000 --> 00:00:01,9999\nBonsoir\n",
            "br only" to "1\n00:00:01,000 --> 00:00:02,000\n<br>\n",
            "nbsp entity only" to "1\n00:00:01,000 --> 00:00:02,000\n&nbsp;\n",
            "amp entity" to "1\n00:00:01,000 --> 00:00:02,000\n&amp;\n",
            "empty font tag" to "1\n00:00:01,000 --> 00:00:02,000\n<font color=\"#ff0000\"></font>\n",
            "styled text" to "1\n00:00:01,000 --> 00:00:02,000\n<b><font color=\"#ff0000\">Rouge</font></b>\n",
            "unclosed angle bracket" to "1\n00:00:01,000 --> 00:00:02,000\n<3\n",
            "> inside a double-quoted attribute" to "1\n00:00:01,000 --> 00:00:02,000\n<a href=\">\"></a>\n",
            "> inside a single-quoted attribute" to "1\n00:00:01,000 --> 00:00:02,000\n<font color='>'></font>\n",
            "text after a quoted >" to "1\n00:00:01,000 --> 00:00:02,000\n<a href=\">\">Lien</a>\n",
            "nbsp numeric entity, zero-padded" to "1\n00:00:01,000 --> 00:00:02,000\n&#0160;\n",
            "en space entity" to "1\n00:00:01,000 --> 00:00:02,000\n&ensp;\n",
            "em and thin space entities" to "1\n00:00:01,000 --> 00:00:02,000\n&emsp;&thinsp;\n",
            "space entities around text" to "1\n00:00:01,000 --> 00:00:02,000\n&ensp;Bonsoir&ensp;\n",
            "brace without backslash" to "1\n00:00:01,000 --> 00:00:02,000\n{b}\n",
            "unclosed override tag" to "1\n00:00:01,000 --> 00:00:02,000\n{\\an8\n",
            "two override tags" to "1\n00:00:01,000 --> 00:00:02,000\n{\\an8}{\\b1}\n",
            "override tags around text" to "1\n00:00:01,000 --> 00:00:02,000\n{\\an8}Haut{\\b0}\n",
            "tags-only line then text line" to "1\n00:00:01,000 --> 00:00:02,000\n<i></i>\nDeuxième ligne\n",
            // Real glyphs carrying format characters or combining sequences.
            "decomposed accents" to "1\n00:00:01,000 --> 00:00:02,000\nété\n",
            "CJK" to "1\n00:00:01,000 --> 00:00:02,000\n字幕\n",
            "RTL mark before Hebrew" to "1\n00:00:01,000 --> 00:00:02,000\n‏שלום\n",
            "emoji with a variation selector" to "1\n00:00:01,000 --> 00:00:02,000\n❤️\n",
            "ZWJ emoji after a zero-width space" to
                "1\n00:00:01,000 --> 00:00:02,000\n&#8203;👨‍👩‍👧\n",
            "unicode digits" to "١\n٠٠:٠٠:٠١,٠٠٠ --> ٠٠:٠٠:٠٢,٠٠٠\nأهلا\n",
            "nothing" to "",
            "blank lines only" to "\n\r\n\r",
            // Overlong timing lines the regex cannot match: skipped, as Media3 skips them.
            "overlong text line before a valid cue" to "1\n" + "x".repeat(1_100) + "\n\n2\n00:00:01,000 --> 00:00:02,000\nBonsoir\n",
            "overlong digit run before a valid cue" to "1\n" + "0".repeat(1_100) + "\n\n2\n00:00:01,000 --> 00:00:02,000\nBonsoir\n",
        )

        /** Refused by the validator, played by Media3 — on purpose. */
        val ACCEPTED_LIMITS: List<Pair<String, String>> = listOf(
            // Media3 counts <img>'s U+FFFC as text; on screen it is a placeholder glyph, no subtitle.
            "image only" to "1\n00:00:01,000 --> 00:00:02,000\n<img src=\"x\">\n",
            // Not blank to Media3, yet nothing drawn: controls, format characters, variation selectors.
            "zero-width space entity only" to "1\n00:00:01,000 --> 00:00:02,000\n&#8203;\n",
            "zero-width space only" to "1\n00:00:01,000 --> 00:00:02,000\n​\n",
            "joiners only" to "1\n00:00:01,000 --> 00:00:02,000\n‌‍⁠\n",
            "bidi marks and controls only" to "1\n00:00:01,000 --> 00:00:02,000\n‎‏‪‬⁦⁩؜\n",
            "variation selectors only" to "1\n00:00:01,000 --> 00:00:02,000\n️󠄀\n",
            "soft hyphen, grapheme joiner, mid-line BOM" to "1\n00:00:01,000 --> 00:00:02,000\n­͏﻿\n",
            "invisibles around a line break" to "1\n00:00:01,000 --> 00:00:02,000\n&#8203;<br>&#x200D;\n",
            // Safety bound: an overlong line the timing regex could match never reaches it.
            "overlong timing-like line before a valid cue" to
                "1\n" + "-->".repeat(400) + "\n\n2\n00:00:01,000 --> 00:00:02,000\nBonsoir\n",
        )
    }
}

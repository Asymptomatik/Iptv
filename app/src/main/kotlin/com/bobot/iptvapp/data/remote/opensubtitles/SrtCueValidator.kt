package com.bobot.iptvapp.data.remote.opensubtitles

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Tells whether the player would show anything from an SRT file, before it is stored.
 *
 * ## Why not Media3's `SubripParser` itself
 * The player parses the file with Media3 1.4.1 `SubripParser` (through `SubtitleExtractor`), and
 * that parser leans on `android.text.TextUtils` and `android.text.Html`. On the JVM unit-test
 * runtime (`isReturnDefaultValues`, no Robolectric) those answer `false`/`null`, so the parser
 * never ends a cue and crashes on the file's end. This is therefore a line-for-line replica of its
 * `parse` loop (read from the 1.4.1 bytecode), checked against the real parser on a device by
 * `SrtCueValidatorParityTest`. The markup is not replicated: [cueHtml] renders it, and on a device
 * that is [AndroidCueHtml], the player's own `Html.fromHtml` call.
 *
 * ## The replica
 * - lines end at `\n`, `\r` or `\r\n`; a BOM heading a line is skipped (`ParsableByteArray.readLine`);
 * - a cue is an index line (`Integer.parseInt`), a timing line matching [TIMING] in full, then text
 *   lines up to an empty one; anything else is skipped, not fatal;
 * - every timecode goes through `Long.parseLong`, uncaught in Media3: one out of range anywhere
 *   makes the whole track fail, so the whole file is read, never just up to the first good cue;
 * - `{\…}` tags are dropped from each trimmed text line, the lines are joined with `<br>` and the
 *   result goes through [cueHtml].
 *
 * The file is playable when at least one cue lasts a positive time and renders non-blank text.
 * One deliberate difference: Media3 counts anything non-blank as text, but an `<img>`'s U+FFFC
 * shows only as a placeholder glyph, and spaces of any kind (no-break ones included), controls,
 * format characters (U+200B, joiners, bidi marks) and variation selectors show nothing — none of them is counted here, so a file of those only is
 * refused.
 *
 * ## Bounded
 * The input is already capped by the downloader. Line splitting and tag stripping are linear in it;
 * once one cue shows, no further cue is rendered. A timing line longer than [MAX_TIMING_LINE] never
 * feeds the regex: if it holds a character [TIMING] cannot match, or no `-->`, it is skipped as
 * Media3 would; otherwise the file is refused — no genuine SRT has such a line.
 */
internal class SrtCueValidator(private val cueHtml: CueHtml) {

    /** @param utf8 the normalised UTF-8 bytes about to be stored. */
    fun hasPlayableCue(utf8: ByteArray): Boolean {
        val lines = LineReader(String(utf8, Charsets.UTF_8))
        var playable = false
        while (true) {
            val indexLine = lines.next() ?: break
            if (indexLine.isEmpty() || !isInt(indexLine)) continue
            val timingLine = lines.next() ?: break
            if (timingLine.length > MAX_TIMING_LINE) {
                if (couldMatchTiming(timingLine)) return false
                continue
            }
            val matcher = TIMING.matcher(timingLine)
            if (!matcher.matches()) continue
            val startUs = timecodeUs(matcher, 1) ?: return false
            val endUs = timecodeUs(matcher, 6) ?: return false

            val text = StringBuilder()
            var textLine = lines.next()
            while (!textLine.isNullOrEmpty()) {
                if (text.isNotEmpty()) text.append("<br>")
                text.append(stripOverrideTags(textLine.trim { it <= ' ' }))
                textLine = lines.next()
            }
            if (!playable && endUs - startUs > 0 && isVisible(text)) playable = true
        }
        return playable
    }

    private fun isInt(line: String): Boolean = try {
        Integer.parseInt(line)
        true
    } catch (e: NumberFormatException) {
        false
    }

    /** `SubripParser.parseTimecode`, overflow included; `null` where `Long.parseLong` would throw. */
    private fun timecodeUs(matcher: Matcher, groupOffset: Int): Long? = try {
        var ms = matcher.group(groupOffset + 1)?.let { java.lang.Long.parseLong(it) * 60 * 60 * 1000 } ?: 0L
        ms += java.lang.Long.parseLong(matcher.group(groupOffset + 2)) * 60 * 1000
        ms += java.lang.Long.parseLong(matcher.group(groupOffset + 3)) * 1000
        matcher.group(groupOffset + 4)?.let { ms += java.lang.Long.parseLong(it) }
        ms * 1000
    } catch (e: NumberFormatException) {
        null
    }

    /**
     * Whether the timing regex could match [line], judged in one pass: [TIMING] needs a literal
     * `-->` and nothing but digits, spaces, `:` and `,` around it. Digits and spaces are taken in
     * their Unicode sense, as Android's ICU-backed `\d` and `\s` may be — erring towards "could".
     */
    private fun couldMatchTiming(line: String): Boolean =
        line.contains("-->") && line.all { it.isDigit() || it.isWhitespace() || it == '\u0085' || it in ":,->" }

    /**
     * Whether the cue's joined [text] shows something once rendered: not blank — Media3's own
     * test — and not just the U+FFFC an `<img>` leaves, nor characters that draw nothing on their
     * own (controls, format characters such as U+200B or bidi marks, variation selectors). Those
     * only count alongside a real glyph, which is enough to keep the cue.
     */
    private fun isVisible(text: CharSequence): Boolean =
        cueHtml.render(text.toString()).codePoints().anyMatch { isGlyph(it) }

    /** `isSpaceChar` as well: `isWhitespace` leaves out the no-break spaces (U+00A0, U+2007, U+202F). */
    private fun isGlyph(codePoint: Int): Boolean {
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == OBJECT_REPLACEMENT.code) return false
        return when (Character.getType(codePoint).toByte()) {
            Character.CONTROL, Character.FORMAT -> false
            else -> codePoint != COMBINING_GRAPHEME_JOINER && codePoint !in VARIATION_SELECTORS &&
                codePoint !in VARIATION_SELECTORS_SUPPLEMENT
        }
    }

    /**
     * [line] without the non-overlapping `\{\\.*?\}` matches `SubripParser.processLine` removes.
     * `.` stops at Java's line terminators, so a tag never spans one.
     */
    private fun stripOverrideTags(line: String): String {
        val out = StringBuilder(line.length)
        // Past this index, no '{\' can close before a terminator: known from one earlier scan.
        var noCloseUntil = -1
        var i = 0
        while (i < line.length) {
            if (line[i] == '{' && i + 1 < line.length && line[i + 1] == '\\' && i >= noCloseUntil) {
                var j = i + 2
                while (j < line.length && line[j] != '}' && !isDotTerminator(line[j])) j++
                if (j < line.length && line[j] == '}') {
                    i = j + 1
                    continue
                }
                noCloseUntil = j
            }
            out.append(line[i])
            i++
        }
        return out.toString()
    }

    private fun isDotTerminator(c: Char) =
        c == '\n' || c == '\r' || c == '\u0085' || c == ' ' || c == ' '

    /** `ParsableByteArray.readLine(UTF_8)` over already-decoded text. */
    private class LineReader(private val text: String) {
        private var position = 0

        fun next(): String? {
            if (position >= text.length) return null
            if (text[position] == '﻿') position++
            var end = position
            while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
            val line = text.substring(position, end)
            position = end
            if (position < text.length && text[position++] == '\r' && position < text.length && text[position] == '\n') {
                position++
            }
            return line
        }
    }

    private companion object {
        /** Media3 1.4.1 `SubripParser.SUBRIP_TIMING_LINE`, verbatim. */
        val TIMING: Pattern =
            Pattern.compile("""\s*((?:(\d+):)?(\d+):(\d+)(?:,(\d+))?)\s*-->\s*((?:(\d+):)?(\d+):(\d+)(?:,(\d+))?)\s*""")

        const val OBJECT_REPLACEMENT = '\uFFFC'

        /** Invisible combining marks, where [Character.getType] says NON_SPACING_MARK. */
        const val COMBINING_GRAPHEME_JOINER = 0x034F
        val VARIATION_SELECTORS = 0xFE00..0xFE0F
        val VARIATION_SELECTORS_SUPPLEMENT = 0xE0100..0xE01EF

        const val MAX_TIMING_LINE = 1024
    }
}

package com.bobot.iptvapp.domain.util

/**
 * Recognises the fake "channels" IPTV providers slip into a bouquet purely to draw a visual
 * divider in a set-top box's flat channel list — `##### FRANCE GENERAL FHD #####`,
 * `--- SPORT ---`, `======================`, and friends.
 *
 * They come back from `get_live_streams` looking exactly like a real entry: a `stream_id`, a
 * `category_id`, sometimes even an icon. Nothing in the payload marks them, so the only signal
 * available is the name itself. Left alone they are rendered as tappable cards that open a player
 * on a dead stream, and — since the Accueil hero is simply the first item of the first row — one
 * of them regularly ends up as the full-screen hero on Android TV (QA finding Y2).
 *
 * ## Detection, and why it is deliberately narrow
 * A separator is a name that is *framed* by decoration: at least [MIN_DECORATION_RUN] consecutive
 * decoration characters on both ends, or nothing but decoration and whitespace from end to end.
 * Requiring a run on *both* sides, and a run of three rather than one, is what keeps real channels
 * out of the net: `#1 Music`, `TF1 - HD`, `M6 +1` and `**CANAL+**` all survive, because none of
 * them is framed by a three-character run at both ends.
 *
 * The cost of the two error directions is not symmetric — a missed separator is a junk card the
 * user can scroll past, a false positive is a channel they paid for and can no longer reach — so
 * the rule errs towards keeping things.
 *
 * Pure and framework-free so it is unit-testable on the JVM, like [CategoryLanguage] and
 * [LanguageLabel]. Placed in `domain/util` because both the network mapper
 * ([com.bobot.iptvapp.data.remote.mapper.toDomain]) and the Room cache mapper
 * ([com.bobot.iptvapp.data.local.mapper.toDomain]) need it: the former stops separators entering
 * the catalog, the latter stops the ones already sitting in a cache written before this rule
 * existed from coming back out.
 */
object BouquetSeparator {

    /**
     * Characters providers decorate separator rows with. Intentionally limited to punctuation and
     * box-drawing glyphs that carry no meaning inside a channel name.
     */
    private const val DECORATION_CHARS = "#=-_*~+.:|<>/\\·•▬═■□▪●◄►─━"

    /**
     * How many decoration characters in a row make a frame. Three is the shortest run that never
     * shows up incidentally at the edge of a real channel name.
     */
    private const val MIN_DECORATION_RUN = 3

    /**
     * Whether [name] is a bouquet separator rather than a playable channel.
     *
     * Blank names count: an entry with no name at all is never something a user can choose, and
     * some providers ship separators as a row of spaces.
     */
    fun matches(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return true

        val leading = trimmed.takeWhile { it.isDecoration() }
        // Decoration all the way through — a bare rule, with no label inside it.
        if (leading.length == trimmed.length) return true

        val trailing = trimmed.takeLastWhile { it.isDecoration() }
        return leading.glyphCount() >= MIN_DECORATION_RUN && trailing.glyphCount() >= MIN_DECORATION_RUN
    }

    /**
     * A run may contain the space between the decoration and the label (`"### "`), but only the
     * glyphs count towards [MIN_DECORATION_RUN] — otherwise a plausible channel name such as
     * `-- BEIN 1 --` would reach three on two dashes plus a space.
     */
    private fun String.glyphCount(): Int = count { !it.isWhitespace() }

    private fun Char.isDecoration(): Boolean = this in DECORATION_CHARS || isWhitespace()
}

package com.bobot.iptvapp.data.remote.opensubtitles

import android.text.Html

/**
 * What a cue's joined text renders to, markup applied and entities decoded — the one piece of
 * [SrtCueValidator] that needs Android. A seam, so the JVM tests can run the validator with a
 * stand-in; on a device, [AndroidCueHtml] is the very call the player makes.
 */
fun interface CueHtml {
    fun render(html: String): CharSequence
}

/** Media3 1.4.1 `SubripParser`'s own call: `Html.fromHtml(String)`, the legacy one-argument form. */
object AndroidCueHtml : CueHtml {
    @Suppress("DEPRECATION")
    override fun render(html: String): CharSequence = Html.fromHtml(html)
}

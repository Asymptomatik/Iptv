package com.bobot.iptvapp.data.remote.opensubtitles

/**
 * JVM stand-in for [AndroidCueHtml], whose `Html.fromHtml` is a stub off Android: drops `<…>`
 * tags and decodes nothing. Not a rendering to rely on for markup edge cases — Android's own is
 * held on a device by `SrtCueValidatorParityTest` and `OpenSubtitlesDownloaderDeviceTest`.
 */
object TagStrippingCueHtml : CueHtml {
    private val TAG = Regex("<[^>]*>")

    override fun render(html: String): CharSequence = html.replace(TAG, "")
}

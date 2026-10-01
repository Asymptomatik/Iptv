package com.bobot.iptvapp.ui.screen.player

import com.bobot.iptvapp.domain.model.OnlineSubtitle
import com.bobot.iptvapp.domain.model.SubtitleSearchContext
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineSubtitlePresentationTest {

    // ── describeSearchContext ───────────────────────────────────────────────

    @Test
    fun `a movie reads as its title and year`() {
        val context = SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, "Inception", year = 2010)

        assertEquals("Inception (2010)", describeSearchContext(context))
    }

    @Test
    fun `a movie without year reads as its title alone`() {
        val context = SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, "Inception")

        assertEquals("Inception", describeSearchContext(context))
    }

    @Test
    fun `an episode reads as its series and episode code`() {
        val context = SubtitleSearchContext(
            kind = SubtitleSearchContext.Kind.EPISODE,
            title = "Le chat",
            seriesTitle = "Breaking Bad",
            seasonNumber = 1,
            episodeNumber = 2,
        )

        assertEquals("Breaking Bad · S01E02", describeSearchContext(context))
    }

    @Test
    fun `an episode without series or numbers falls back to its own title`() {
        val context = SubtitleSearchContext(SubtitleSearchContext.Kind.EPISODE, "Pilote")

        assertEquals("Pilote", describeSearchContext(context))
    }

    // ── onlineSubtitleTitle ─────────────────────────────────────────────────

    @Test
    fun `a result is named after its release first`() {
        val subtitle = OnlineSubtitle(1L, "fr", release = "Inception.2010.1080p", fileName = "a.srt")

        assertEquals("Inception.2010.1080p", onlineSubtitleTitle(subtitle))
    }

    @Test
    fun `a result without release falls back to file name then feature title then id`() {
        assertEquals("a.srt", onlineSubtitleTitle(OnlineSubtitle(1L, "fr", release = " ", fileName = "a.srt")))
        assertEquals("Inception", onlineSubtitleTitle(OnlineSubtitle(1L, "fr", featureTitle = "Inception")))
        assertEquals("Sous-titre n° 42", onlineSubtitleTitle(OnlineSubtitle(42L, "fr")))
    }

    // ── onlineSubtitleHints ─────────────────────────────────────────────────

    @Test
    fun `hints show language year episode and downloads in French`() {
        val subtitle = OnlineSubtitle(
            fileId = 1L,
            language = "fr",
            downloadCount = 1_234,
            featureYear = 2008,
            seasonNumber = 1,
            episodeNumber = 2,
        )

        assertEquals("Français · 2008 · S01E02 · 1234 téléchargements", onlineSubtitleHints(subtitle))
    }

    @Test
    fun `hints flag a movie result that is not confirmed, and only that one`() {
        val subtitle = OnlineSubtitle(fileId = 1L, language = "fr", featureMatch = OnlineSubtitle.FeatureMatch.UNCONFIRMED)

        assertEquals("Français · Film non confirmé · 0 téléchargements", onlineSubtitleHints(subtitle))
        assertEquals(
            "Français · 0 téléchargements",
            onlineSubtitleHints(subtitle.copy(featureMatch = OnlineSubtitle.FeatureMatch.CONFIRMED)),
        )
    }

    @Test
    fun `hints skip what the provider left out and flag quality`() {
        val subtitle = OnlineSubtitle(
            fileId = 1L,
            language = "fr",
            downloadCount = 1,
            isHearingImpaired = true,
            isMachineTranslated = true,
            isFromTrusted = true,
        )

        assertEquals(
            "Français · 1 téléchargement · Malentendants · Traduction automatique · Source fiable",
            onlineSubtitleHints(subtitle),
        )
    }

    @Test
    fun `an unknown language code is shown upper-cased`() {
        assertEquals("ZZZ", languageDisplayName("zzz"))
    }

    // ── searchFocusTarget ───────────────────────────────────────────────────

    @Test
    fun `focus waits on the back row while searching`() {
        assertEquals(SearchFocusTarget.BACK, searchFocusTarget(OnlineSubtitleSearchState.Loading))
        assertEquals(SearchFocusTarget.BACK, searchFocusTarget(OnlineSubtitleSearchState.Idle))
    }

    @Test
    fun `focus lands on the first result`() {
        val results = OnlineSubtitleSearchState.Results(listOf(OnlineSubtitle(1L, "fr")))

        assertEquals(SearchFocusTarget.FIRST_RESULT, searchFocusTarget(results))
    }

    @Test
    fun `focus lands on retry when nothing was found or the failure can be retried`() {
        assertEquals(SearchFocusTarget.RETRY, searchFocusTarget(OnlineSubtitleSearchState.Empty))
        assertEquals(
            SearchFocusTarget.RETRY,
            searchFocusTarget(OnlineSubtitleSearchState.Failed(NETWORK_MESSAGE, canRetry = true)),
        )
    }

    @Test
    fun `focus falls back to the back row when retrying cannot help`() {
        val failed = OnlineSubtitleSearchState.Failed(MISSING_KEY_MESSAGE, canRetry = false)

        assertEquals(SearchFocusTarget.BACK, searchFocusTarget(failed))
    }
}

package com.bobot.iptvapp.domain.util

import com.bobot.iptvapp.domain.model.DownloadContentType
import com.bobot.iptvapp.domain.model.DownloadState
import com.bobot.iptvapp.domain.model.Episode
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.OfflineDownload
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.model.SubtitleSearchContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the [SubtitleSearchContext] builders in `SubtitleSearchContexts.kt`. */
class SubtitleSearchContextsTest {

    private val movie = Movie(
        id = "m1",
        title = "FR - Bangkok Dangerous",
        posterUrl = null,
        plot = null,
        categoryId = "10",
        rating = null,
        year = 2008,
        addedMillis = null,
        durationMillis = null,
        containerExtension = "mkv",
    )

    private val series = Series(
        id = "s1",
        title = "VOSTFR - Breaking Bad",
        coverUrl = null,
        plot = null,
        categoryId = "3",
        rating = null,
        year = 2008,
    )

    private val episode = Episode(
        id = "e7",
        title = "FR - Pilote",
        episodeNumber = 1,
        seasonNumber = 2,
        plot = null,
        durationMillis = null,
        containerExtension = null,
        coverUrl = null,
    )

    // ── Movie ────────────────────────────────────────────────────────────────

    @Test
    fun `movie context carries the prefix-stripped title and the year`() {
        assertEquals(
            SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "Bangkok Dangerous", year = 2008),
            movie.subtitleSearchContext(),
        )
    }

    @Test
    fun `movie context leaves the year null when the server omits it`() {
        assertEquals(
            SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "Bangkok Dangerous"),
            movie.copy(year = null).subtitleSearchContext(),
        )
        assertNull(movie.copy(year = 0).subtitleSearchContext()?.year)
    }

    @Test
    fun `movie context searches the bare title, without the provider's tags and year suffix`() {
        assertEquals(
            SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "Avatar", year = 2009),
            movie.copy(title = "FR - Avatar [MULTI-SUB] - 2009", year = null).subtitleSearchContext(),
        )
    }

    @Test
    fun `the catalogue year wins over the one in the title`() {
        assertEquals(2010, movie.copy(title = "Avatar - 2009", year = 2010).subtitleSearchContext()?.year)
    }

    @Test
    fun `a number that belongs to the title is not taken for a year`() {
        val context = movie.copy(title = "Blade Runner 2049", year = null).subtitleSearchContext()
        assertEquals(SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "Blade Runner 2049"), context)
        assertEquals("Spider-Man", movie.copy(title = "Spider-Man (2002)").subtitleSearchContext()?.title)
    }

    @Test
    fun `a future number after a delimiter belongs to the title, not a year`() {
        // Second review: "Blade Runner: 2049" was read as "Blade Runner" released in 2049.
        assertEquals(
            SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "Blade Runner: 2049"),
            movie.copy(title = "Blade Runner: 2049", year = null).subtitleSearchContext(),
        )
        assertEquals(
            SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "Blade Runner - 2049", year = 2017),
            movie.copy(title = "Blade Runner - 2049", year = 2017).subtitleSearchContext(),
        )
        assertEquals(
            SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "Blade Runner: 2049"),
            downloadOf(DownloadContentType.MOVIE, "FR - Blade Runner: 2049").subtitleSearchContext(),
        )
    }

    @Test
    fun `brackets that belong to the film's own title are kept`() {
        assertEquals(
            SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "[REC] 2", year = 2009),
            movie.copy(title = "[REC] 2", year = 2009).subtitleSearchContext(),
        )
        assertEquals(
            SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "[REC]", year = 2007),
            movie.copy(title = "FR - [REC] (2007)", year = null).subtitleSearchContext(),
        )
        assertEquals(
            SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "[REC] 3 Génesis", year = 2012),
            movie.copy(title = "[REC] 3 Génesis [MULTI-SUB] [1080p] - 2012", year = null).subtitleSearchContext(),
        )
    }

    @Test
    fun `a year in brackets is read as the year, not dropped as a tag`() {
        assertEquals(
            SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "Avatar", year = 2009),
            movie.copy(title = "Avatar [VOSTFR] [2009]", year = null).subtitleSearchContext(),
        )
    }

    @Test
    fun `a title made only of tags is searched as it is shown`() {
        assertEquals("[MULTI-SUB]", movie.copy(title = "[MULTI-SUB]").subtitleSearchContext()?.title)
    }

    @Test
    fun `movie without a visible title has no context rather than an invented one`() {
        assertNull(movie.copy(title = "  ").subtitleSearchContext())
    }

    // ── Episode ──────────────────────────────────────────────────────────────

    @Test
    fun `episode context carries episode title, series title, season and episode numbers`() {
        assertEquals(
            SubtitleSearchContext(
                kind = SubtitleSearchContext.Kind.EPISODE,
                title = "Pilote",
                seriesTitle = "Breaking Bad",
                seasonNumber = 2,
                episodeNumber = 1,
            ),
            episode.subtitleSearchContext(series),
        )
    }

    @Test
    fun `episode numbers Xtream reports as 0 are treated as unknown`() {
        val context = episode.copy(seasonNumber = 0, episodeNumber = 0).subtitleSearchContext(series)

        assertNull(context?.seasonNumber)
        assertNull(context?.episodeNumber)
        assertEquals("Breaking Bad", context?.seriesTitle)
    }

    @Test
    fun `episode without a known series keeps its own title and numbers`() {
        assertEquals(
            SubtitleSearchContext(
                kind = SubtitleSearchContext.Kind.EPISODE,
                title = "Pilote",
                seasonNumber = 2,
                episodeNumber = 1,
            ),
            episode.subtitleSearchContext(series = null),
        )
    }

    @Test
    fun `episode with a blank title falls back to the series title`() {
        assertEquals("Breaking Bad", episode.copy(title = "").subtitleSearchContext(series)?.title)
        assertNull(episode.copy(title = "").subtitleSearchContext(series = null))
    }

    // ── Offline download ─────────────────────────────────────────────────────

    @Test
    fun `movie download context only knows the stored title`() {
        assertEquals(
            SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "Bangkok Dangerous"),
            downloadOf(DownloadContentType.MOVIE, "Bangkok Dangerous").subtitleSearchContext(),
        )
    }

    @Test
    fun `episode download context only knows the episode title`() {
        // Documented gap: an offline episode stores neither its series title nor its numbers.
        assertEquals(
            SubtitleSearchContext(SubtitleSearchContext.Kind.EPISODE, title = "Pilote"),
            downloadOf(DownloadContentType.EPISODE, "FR - Pilote").subtitleSearchContext(),
        )
    }

    @Test
    fun `movie download context reads the year its title spells out`() {
        assertEquals(
            SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "Bangkok Dangerous", year = 2008),
            downloadOf(DownloadContentType.MOVIE, "FR - Bangkok Dangerous (2008)").subtitleSearchContext(),
        )
    }

    @Test
    fun `movie download context drops only the provider's tags, never the film's own brackets`() {
        assertEquals(
            SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "[REC] 2"),
            downloadOf(DownloadContentType.MOVIE, "[REC] 2").subtitleSearchContext(),
        )
        assertEquals(
            SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "[REC]", year = 2007),
            downloadOf(DownloadContentType.MOVIE, "[REC] (2007)").subtitleSearchContext(),
        )
        assertEquals(
            SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "Avatar", year = 2009),
            downloadOf(DownloadContentType.MOVIE, "FR - Avatar [MULTI-SUB] (2009)").subtitleSearchContext(),
        )
    }

    @Test
    fun `download with a blank title has no context`() {
        assertNull(downloadOf(DownloadContentType.MOVIE, " ").subtitleSearchContext())
    }

    private fun downloadOf(type: DownloadContentType, title: String) = OfflineDownload(
        downloadId = "$type:x",
        contentType = type,
        contentId = "x",
        title = title,
        artworkUrl = null,
        streamUrl = "file:///x.mkv",
        state = DownloadState.COMPLETED,
        bytesDownloaded = 1L,
        contentLength = 1L,
        updatedAtMillis = 0L,
    )
}

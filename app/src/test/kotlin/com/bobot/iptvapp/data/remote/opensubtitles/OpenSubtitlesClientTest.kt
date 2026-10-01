package com.bobot.iptvapp.data.remote.opensubtitles

import com.bobot.iptvapp.data.preferences.FakeOpenSubtitlesApiKeyStore
import com.bobot.iptvapp.domain.model.OnlineSubtitle
import com.bobot.iptvapp.domain.model.OnlineSubtitleSearchResult
import com.bobot.iptvapp.domain.model.OnlineSubtitleSearchResult.Reason
import com.bobot.iptvapp.domain.model.SubtitleSearchContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Behaviour of [OpenSubtitlesClient] against a canned server: a [FakeServer] interceptor answers
 * every call in-process, so nothing ever reaches the network and no real key is involved.
 */
class OpenSubtitlesClientTest {

    private val apiKeyStore = FakeOpenSubtitlesApiKeyStore("test-consumer-key")
    private val server = FakeServer()

    private fun client() = OpenSubtitlesClient(
        httpClient = OkHttpClient.Builder().addInterceptor(server).build(),
        json = Json { ignoreUnknownKeys = true; coerceInputValues = true },
        apiKeyStore = apiKeyStore,
        userAgent = "IptvApp v1.0",
        ioDispatcher = Dispatchers.Unconfined,
    )

    private val movie = SubtitleSearchContext(
        kind = SubtitleSearchContext.Kind.MOVIE,
        title = "Le Dîner de Cons",
        year = 1998,
    )

    private val episode = SubtitleSearchContext(
        kind = SubtitleSearchContext.Kind.EPISODE,
        title = "Pilot",
        seriesTitle = "Breaking Bad",
        seasonNumber = 1,
        episodeNumber = 2,
    )

    // ── request shape ─────────────────────────────────────────────────────────

    @Test
    fun `a movie is searched by lower-case title and year, parameters sorted, French then English`() = runTest {
        client().search(movie)

        val url = server.requests.first().url
        assertEquals("https", url.scheme)
        assertEquals("api.opensubtitles.com", url.host)
        assertEquals("/api/v1/subtitles", url.encodedPath)
        assertEquals(
            listOf(
                "languages=fr&query=le+d%C3%AEner+de+cons&type=movie&year=1998",
                "languages=en&query=le+d%C3%AEner+de+cons&type=movie&year=1998",
            ),
            server.requests.map { it.url.encodedQuery },
        )
    }

    @Test
    fun `a movie without a year is searched by title alone`() = runTest {
        client().search(movie.copy(year = null))

        assertEquals(
            "languages=fr&query=le+d%C3%AEner+de+cons&type=movie",
            server.requests.first().url.encodedQuery,
        )
    }

    @Test
    fun `an episode is searched by series title, season and episode`() = runTest {
        client().search(episode)

        assertEquals(
            "episode_number=2&languages=fr&query=breaking+bad&season_number=1&type=episode",
            server.requests.first().url.encodedQuery,
        )
    }

    @Test
    fun `an episode with no known series falls back to its own title and omits unknown numbers`() = runTest {
        client().search(episode.copy(seriesTitle = null, seasonNumber = null, episodeNumber = null))

        assertEquals(
            "languages=fr&query=pilot&type=episode",
            server.requests.first().url.encodedQuery,
        )
    }

    @Test
    fun `the key travels in the Api-Key header, never in the URL, with a named User-Agent`() = runTest {
        client().search(movie)

        assertEquals(2, server.requests.size)
        server.requests.forEach { request ->
            assertEquals("test-consumer-key", request.header("Api-Key"))
            assertEquals("IptvApp v1.0", request.header("User-Agent"))
            assertEquals("*/*", request.header("Accept"))
            assertFalse(request.url.toString().contains("test-consumer-key"))
            assertNull(request.header("Authorization"))
        }
    }

    @Test
    fun `without a configured key nothing is sent`() = runTest {
        apiKeyStore.key.value = null

        val result = client().search(movie)

        assertEquals(OnlineSubtitleSearchResult.Failed(Reason.MISSING_API_KEY), result)
        assertTrue(server.requests.isEmpty())
    }

    // ── response mapping ──────────────────────────────────────────────────────

    @Test
    fun `results are mapped, French before English, server order kept within a language`() = runTest {
        server.respond(200, SEARCH_BODY)

        val result = client().search(movie)

        assertEquals(
            OnlineSubtitleSearchResult.Found(
                listOf(
                    OnlineSubtitle(
                        fileId = 222,
                        language = "fr",
                        release = "Le.Diner.De.Cons.1998.1080p",
                        fileName = "diner.fr.srt",
                        downloadCount = 900,
                        isHearingImpaired = true,
                        isFromTrusted = true,
                        featureTitle = "Le Dîner de Cons",
                        featureYear = 1998,
                        featureMatch = OnlineSubtitle.FeatureMatch.CONFIRMED,
                    ),
                    OnlineSubtitle(
                        fileId = 333,
                        language = "fr",
                        release = "Le.Diner.De.Cons.1998.DVDRip",
                        fileName = null,
                        isMachineTranslated = true,
                        isAiTranslated = true,
                        featureMatch = OnlineSubtitle.FeatureMatch.UNCONFIRMED,
                    ),
                    OnlineSubtitle(
                        fileId = 111,
                        language = "en",
                        release = "Le.Diner.De.Cons.1998.720p.WEB",
                        fileName = "dinner.en.srt",
                        downloadCount = 5000,
                        featureMatch = OnlineSubtitle.FeatureMatch.UNCONFIRMED,
                    ),
                ),
            ),
            result,
        )
    }

    // ── one search per language ───────────────────────────────────────────────
    //
    // The API answers one page at a time (60 results, `total_pages` for the rest) across every
    // language asked for: with `languages=en,fr`, a popular film's English subtitles can fill the
    // whole first page, and the French ones on later pages are never seen. Each language is
    // therefore searched on its own, French first.

    @Test
    fun `French is found even when English fills the first page of a mixed search`() = runTest {
        val englishPage = languagePage("en", fileIds = 1L..60L, totalPages = 3)
        server.respondTo("en,fr", 200, englishPage)
        server.respondTo("en", 200, englishPage)
        server.respondTo("fr", 200, languagePage("fr", fileIds = 901L..902L, totalPages = 1))

        val found = client().search(movie) as OnlineSubtitleSearchResult.Found

        assertEquals(listOf(901L, 902L), found.subtitles.take(2).map { it.fileId })
        assertEquals(62, found.subtitles.size)
        assertTrue(found.subtitles.drop(2).all { it.language == "en" })
    }

    @Test
    fun `a result in another language than the one searched is not offered twice or out of place`() = runTest {
        // A server ignoring the filter: the French search also returns the English subtitle.
        server.respondTo("fr", 200, mixedPage("en" to 111L, "fr" to 222L))
        server.respondTo("en", 200, mixedPage("en" to 111L))

        val found = client().search(movie) as OnlineSubtitleSearchResult.Found

        assertEquals(listOf(222L to "fr", 111L to "en"), found.subtitles.map { it.fileId to it.language })
    }

    @Test
    fun `no French result falls back on English`() = runTest {
        server.respondTo("fr", 200, """{"total_pages":0,"total_count":0,"per_page":60,"page":1,"data":[]}""")
        server.respondTo("en", 200, mixedPage("en" to 111L))

        val found = client().search(movie) as OnlineSubtitleSearchResult.Found

        assertEquals(listOf(111L), found.subtitles.map { it.fileId })
    }

    @Test
    fun `a failed French search is reported without asking for English`() = runTest {
        server.respondTo("fr", 429, "")
        server.respondTo("en", 200, mixedPage("en" to 111L))

        val result = client().search(movie)

        assertEquals(OnlineSubtitleSearchResult.Failed(Reason.RATE_LIMITED, httpCode = 429), result)
        assertEquals(listOf("fr"), server.requests.map { it.url.queryParameter("languages") })
    }

    @Test
    fun `French results survive a failed English search`() = runTest {
        server.respondTo("fr", 200, mixedPage("fr" to 222L))
        server.respondTo("en", 503, "")

        val found = client().search(movie) as OnlineSubtitleSearchResult.Found

        assertEquals(listOf(222L), found.subtitles.map { it.fileId })
    }

    @Test
    fun `an English failure after no French result is reported`() = runTest {
        server.respondTo("fr", 200, """{"data":[]}""")
        server.respondTo("en", 503, "")

        assertEquals(OnlineSubtitleSearchResult.Failed(Reason.SERVER, httpCode = 503), client().search(movie))
    }

    /** One page of [language] results for the searched movie, as the API paginates them. */
    private fun languagePage(language: String, fileIds: LongRange, totalPages: Int): String =
        fileIds.joinToString(
            separator = ",",
            prefix = """{"total_pages": $totalPages, "total_count": ${totalPages * 60}, "per_page": 60, "page": 1, "data": [""",
            postfix = "]}",
        ) { id ->
            """{"id": "$id", "attributes": {"language": "$language",
              "feature_details": {"title": "Le Dîner de Cons", "year": 1998},
              "files": [{"file_id": $id}]}}"""
        }

    private fun mixedPage(vararg entries: Pair<String, Long>): String =
        entries.joinToString(separator = ",", prefix = """{"data": [""", postfix = "]}") { (language, id) ->
            """{"id": "$id", "attributes": {"language": "$language",
              "feature_details": {"title": "Le Dîner de Cons", "year": 1998},
              "files": [{"file_id": $id}]}}"""
        }

    // ── movie relevance ───────────────────────────────────────────────────────

    private val avatar = SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "Avatar", year = 2009)

    @Test
    fun `a movie result whose feature is a sequel from another year is not offered`() = runTest {
        server.respond(200, movieBody(fileId = 7, title = "Avatar: The Way of Water", year = 2022))

        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(avatar))
    }

    @Test
    fun `a sequel is not offered either when one of the years is unknown`() = runTest {
        server.respond(200, movieBody(fileId = 7, title = "Avatar: The Way of Water", year = null))
        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(avatar))

        server.respond(200, movieBody(fileId = 7, title = "Avatar: The Way of Water", year = 2022))
        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(avatar.copy(year = null)))
    }

    @Test
    fun `the same title, accents and punctuation aside, within a year is confirmed`() = runTest {
        server.respond(200, movieBody(fileId = 7, title = "Le Diner de cons", year = 1999))
        assertEquals(OnlineSubtitle.FeatureMatch.CONFIRMED, singleMatch(movie))

        server.respond(200, movieBody(fileId = 7, title = "Mission: Impossible", year = null))
        assertEquals(
            OnlineSubtitle.FeatureMatch.CONFIRMED,
            singleMatch(avatar.copy(title = "Mission - Impossible", year = null)),
        )
    }

    @Test
    fun `the same title from a year too far is a namesake and not offered`() = runTest {
        server.respond(200, movieBody(fileId = 7, title = "Le Dîner de Cons", year = 2010))

        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(movie))
    }

    @Test
    fun `a result without feature details is offered as unconfirmed`() = runTest {
        server.respond(200, movieBody(fileId = 7, title = null, year = null))

        assertEquals(OnlineSubtitle.FeatureMatch.UNCONFIRMED, singleMatch(avatar))
    }

    @Test
    fun `a localised alias is offered as unconfirmed when its release names the searched title`() = runTest {
        server.respond(200, movieBody(fileId = 7, title = "The Dinner Game", year = 1998, release = "Le.Diner.De.Cons.1998.1080p"))
        assertEquals(OnlineSubtitle.FeatureMatch.UNCONFIRMED, singleMatch(movie))

        // Year unknown on our side: the release still points at the searched title.
        assertEquals(OnlineSubtitle.FeatureMatch.UNCONFIRMED, singleMatch(movie.copy(year = null)))
    }

    @Test
    fun `a localised alias is offered when a file name names the searched title, group tags aside`() = runTest {
        server.respond(
            200,
            movieBody(fileId = 7, title = "The Dinner Game", year = 1998, fileName = "[YTS] Le Dîner de cons (1998).srt"),
        )

        assertEquals(OnlineSubtitle.FeatureMatch.UNCONFIRMED, singleMatch(movie))
    }

    @Test
    fun `another title without evidence is not offered, even of the same year`() = runTest {
        // The reported defect: searching Avatar (2009) listed an unrelated film of 2009.
        server.respond(200, movieBody(fileId = 7, title = "Completely Different Film", year = 2009))
        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(avatar))

        // Same for an alias whose release only carries the alias: nothing in the payload proves it.
        server.respond(200, movieBody(fileId = 7, title = "The Dinner Game", year = 1998, release = "The.Dinner.Game.1998"))
        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(movie))
    }

    @Test
    fun `another title is not offered when a year is unknown and nothing names the searched title`() = runTest {
        server.respond(200, movieBody(fileId = 7, title = "Completely Different Film", year = null))
        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(avatar))

        server.respond(200, movieBody(fileId = 7, title = "Completely Different Film", year = 2009))
        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(avatar.copy(year = null)))
    }

    @Test
    fun `a release naming a sequel, a longer title or a mere substring is no evidence`() = runTest {
        for (release in listOf("Avatar.2.2022.1080p", "Avatar.The.Way.Of.Water.2022", "Avatars.2009", "Avatar.1995.DVDRip")) {
            server.respond(200, movieBody(fileId = 7, title = "Completely Different Film", year = 2009, release = release))
            assertEquals(release, OnlineSubtitleSearchResult.NoResults, client().search(avatar))
        }
    }

    @Test
    fun `a release naming another title after the searched one and its year is no evidence`() = runTest {
        // Second review: only the word after the title was checked, so this sequel passed.
        for (release in listOf(
            "Avatar.2009.The.Way.Of.Water.2022",
            "Avatar.2009.1080p.The.Way.Of.Water",
            "Avatar.1080p.BluRay.2.2022",
            "Avatar.2009.2022.1080p",
        )) {
            server.respond(200, movieBody(fileId = 7, title = "Completely Different Film", year = 2009, release = release))
            assertEquals(release, OnlineSubtitleSearchResult.NoResults, client().search(avatar))
        }
    }

    @Test
    fun `a release made of the searched title and technical tags only, group and extension included, is evidence`() = runTest {
        for (release in listOf(
            "Avatar.2009.1080p.BluRay.x264-GROUP",
            "Avatar.2009.2160p.WEB-DL.DDP5.1.Atmos.HDR.HEVC-GROUP",
            "Avatar (2009) [1080p] [YTS.MX]",
            "Avatar.2009.srt",
        )) {
            server.respond(200, movieBody(fileId = 7, title = "Completely Different Film", year = 2009, release = release))
            assertEquals(release, OnlineSubtitle.FeatureMatch.UNCONFIRMED, singleMatch(avatar))
        }
    }

    @Test
    fun `without a feature title, a label naming another film is not offered`() = runTest {
        server.respond(200, movieBody(fileId = 7, title = null, year = null, release = "Completely.Different.Film.2009"))
        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(avatar))

        server.respond(200, movieBody(fileId = 7, title = null, year = null, fileName = "Completely.Different.Film.2009.1080p.srt"))
        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(avatar))

        server.respond(200, movieBody(fileId = 7, title = null, year = 2009, release = "Avatar.2009.The.Way.Of.Water.2022"))
        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(avatar))
    }

    @Test
    fun `without a feature title, a label naming the searched film or nothing readable stays unconfirmed`() = runTest {
        server.respond(200, movieBody(fileId = 7, title = null, year = null, release = "Avatar.2009.1080p.BluRay.x264-GROUP"))
        assertEquals(OnlineSubtitle.FeatureMatch.UNCONFIRMED, singleMatch(avatar))

        // No title part, no year or tag: nothing tells which film it is.
        server.respond(200, movieBody(fileId = 7, title = null, year = null, release = "1080p.BluRay", fileName = "subtitle.srt"))
        assertEquals(OnlineSubtitle.FeatureMatch.UNCONFIRMED, singleMatch(avatar))

        // A language code is no release marker: this is a file name, not a film called "Movie".
        server.respond(200, movieBody(fileId = 7, title = null, year = null, fileName = "movie.fr.srt"))
        assertEquals(OnlineSubtitle.FeatureMatch.UNCONFIRMED, singleMatch(avatar))
    }

    @Test
    fun `release and file name contradicting each other are not offered`() = runTest {
        server.respond(
            200,
            movieBody(fileId = 7, title = null, year = null, release = "Avatar.2009.1080p", fileName = "Completely.Different.Film.2009.srt"),
        )
        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(avatar))

        server.respond(
            200,
            movieBody(
                fileId = 7, title = "The Dinner Game", year = 1998,
                release = "Le.Diner.De.Cons.1998.1080p", fileName = "Completely.Different.Film.1998.srt",
            ),
        )
        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(movie))
    }

    @Test
    fun `a longer official title of the very same year is not taken for a sequel`() = runTest {
        server.respond(200, movieBody(fileId = 7, title = "Dune: Part One", year = 2021))

        assertEquals(OnlineSubtitle.FeatureMatch.UNCONFIRMED, singleMatch(avatar.copy(title = "Dune", year = 2021)))
    }

    @Test
    fun `a longer official title within the year tolerance is offered as unconfirmed`() = runTest {
        server.respond(200, movieBody(fileId = 7, title = "Dune: Part One", year = 2021))

        assertEquals(OnlineSubtitle.FeatureMatch.UNCONFIRMED, singleMatch(avatar.copy(title = "Dune", year = 2022)))
    }

    @Test
    fun `a longer title with a sequel sign is not offered, even within the year tolerance`() = runTest {
        for (title in listOf("Avatar 2", "Avatar II", "Avatar: Part Two", "Avatar Returns")) {
            server.respond(200, movieBody(fileId = 7, title = title, year = 2010))
            assertEquals(title, OnlineSubtitleSearchResult.NoResults, client().search(avatar))
        }
    }

    @Test
    fun `a label naming another film hides even an equal feature title`() = runTest {
        // Third review: the equal title returned CONFIRMED before the labels were looked at.
        server.respond(200, movieBody(fileId = 7, title = "Avatar", year = 2009, release = "Avatar.The.Way.Of.Water.2022"))
        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(avatar))

        server.respond(200, movieBody(fileId = 7, title = "Avatar", year = 2009, fileName = "Completely.Different.Film.2009.srt"))
        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(avatar))

        server.respond(200, movieBody(fileId = 7, title = "Avatar", year = 2009, release = "Avatar.2009.1080p.BluRay"))
        assertEquals(OnlineSubtitle.FeatureMatch.CONFIRMED, singleMatch(avatar))
    }

    @Test
    fun `a label naming another film hides a longer feature title too`() = runTest {
        val dune = avatar.copy(title = "Dune", year = 2021)
        for (release in listOf("Dune.Part.Two.2021.1080p", "Completely.Different.Film.2021")) {
            server.respond(200, movieBody(fileId = 7, title = "Dune: Part One", year = 2021, release = release))
            assertEquals(release, OnlineSubtitleSearchResult.NoResults, client().search(dune))
        }
    }

    @Test
    fun `a label naming the longer feature title itself, or the shorter one, contradicts nothing`() = runTest {
        server.respond(200, movieBody(fileId = 7, title = "Dune: Part One", year = 2021, release = "Dune.Part.One.2021.1080p"))
        assertEquals(OnlineSubtitle.FeatureMatch.UNCONFIRMED, singleMatch(avatar.copy(title = "Dune", year = 2021)))

        server.respond(200, movieBody(fileId = 7, title = "Dune", year = 2021, release = "Dune.2021.2160p.WEB-DL"))
        assertEquals(OnlineSubtitle.FeatureMatch.UNCONFIRMED, singleMatch(avatar.copy(title = "Dune: Part One", year = 2021)))
    }

    @Test
    fun `a generic name before the year is no film title`() = runTest {
        for (release in listOf("subtitle.1998.1080p", "Subs.1998.BluRay", "file.1998.srt")) {
            server.respond(
                200,
                movieBody(fileId = 7, title = null, year = null, release = release, fileName = "Le.Diner.De.Cons.1998.srt"),
            )
            assertEquals(release, OnlineSubtitle.FeatureMatch.UNCONFIRMED, singleMatch(movie))
        }
    }

    @Test
    fun `a generic name hides neither another title nor a year too far`() = runTest {
        for (release in listOf("Completely.Different.Film.2009", "Subtitle.Different.Film.2009", "subtitle.1995.1080p")) {
            server.respond(200, movieBody(fileId = 7, title = null, year = null, release = release))
            assertEquals(release, OnlineSubtitleSearchResult.NoResults, client().search(avatar))
        }
    }

    @Test
    fun `labels naming another film without year or tag hide even an equal feature title`() = runTest {
        // Fifth review: both labels name another film, yet neither carries a release marker.
        server.respond(
            200,
            movieBody(
                fileId = 7, title = "Avatar", year = 2009,
                release = "Completely Different Film", fileName = "Completely.Different.Film.srt",
            ),
        )
        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(avatar))
    }

    @Test
    fun `a release alone or a file name alone naming another film without year or tag is no match`() = runTest {
        for (release in listOf("Completely Different Film", "Completely.Different.Film", "Completely_Different_Film.VF")) {
            server.respond(200, movieBody(fileId = 7, title = "Avatar", year = 2009, release = release))
            assertEquals(release, OnlineSubtitleSearchResult.NoResults, client().search(avatar))

            server.respond(200, movieBody(fileId = 7, title = null, year = null, release = release))
            assertEquals(release, OnlineSubtitleSearchResult.NoResults, client().search(avatar))
        }
        for (fileName in listOf("Completely.Different.Film.srt", "Completely Different Film.fr.srt")) {
            server.respond(200, movieBody(fileId = 7, title = "Avatar", year = 2009, fileName = fileName))
            assertEquals(fileName, OnlineSubtitleSearchResult.NoResults, client().search(avatar))

            server.respond(200, movieBody(fileId = 7, title = null, year = null, fileName = fileName))
            assertEquals(fileName, OnlineSubtitleSearchResult.NoResults, client().search(avatar))
        }
    }

    @Test
    fun `generic subtitle labels without year or tag name no film`() = runTest {
        for (label in listOf(
            "subtitle.srt", "French Full.srt", "Sous-titres français", "Sous-titres en français.srt",
            "English SDH.srt", "Subtitles for the hearing impaired.srt", "movie.fr.srt", "dinner.en.srt", "Avatar",
        )) {
            server.respond(200, movieBody(fileId = 7, title = "Avatar", year = 2009, release = label, fileName = label))
            assertEquals(label, OnlineSubtitle.FeatureMatch.CONFIRMED, singleMatch(avatar))
        }
        // The searched title anywhere in the label is no contradiction either.
        server.respond(200, movieBody(fileId = 7, title = "Avatar", year = 2009, fileName = "www.site.com - Avatar.srt"))
        assertEquals(OnlineSubtitle.FeatureMatch.CONFIRMED, singleMatch(avatar))
    }

    @Test
    fun `the searched title after an unknown title part is a contradiction`() = runTest {
        // Sixth review: an unrecognised prefix is another title, not a decoration.
        for (fileName in listOf("Completely Different Film Avatar.srt", "Different Avatar.srt", "Avatar Completely Different.srt")) {
            server.respond(200, movieBody(fileId = 7, title = "Avatar", year = 2009, fileName = fileName))
            assertEquals(fileName, OnlineSubtitleSearchResult.NoResults, client().search(avatar))
        }
        server.respond(
            200,
            movieBody(
                fileId = 7, title = "The Dinner Game", year = 1998,
                release = "Le.Diner.De.Cons.1998.1080p", fileName = "Completely Different The Dinner Game.srt",
            ),
        )
        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(movie))
    }

    @Test
    fun `recognised decorations around the searched title contradict nothing`() = runTest {
        for (fileName in listOf(
            "www.site.com - Avatar.srt", "site.com - Avatar.srt", "http://www.site.net | Avatar.srt",
            "French Avatar.srt", "[YTS] Avatar.srt",
        )) {
            server.respond(200, movieBody(fileId = 7, title = "Avatar", year = 2009, fileName = fileName))
            assertEquals(fileName, OnlineSubtitle.FeatureMatch.CONFIRMED, singleMatch(avatar))
        }
    }

    @Test
    fun `a bracketed tag naming another film is a contradiction, at either end`() = runTest {
        // Final review: `[Titanic 1997].srt` was stripped whole and read as a CONFIRMED Avatar.
        for (fileName in listOf(
            "[Titanic 1997].srt", "[Titanic 1997] Avatar.srt", "Avatar [Titanic 1997].srt",
            "Avatar.2009 [Titanic.1997.1080p].srt", "[YTS] [Titanic 1997] Avatar.srt", "Avatar [1995].srt",
            "[Avatar 2 2022] Avatar.srt",
        )) {
            server.respond(200, movieBody(fileId = 7, title = "Avatar", year = 2009, fileName = fileName))
            assertEquals(fileName, OnlineSubtitleSearchResult.NoResults, client().search(avatar))
        }
        // The release name is read the same way.
        server.respond(200, movieBody(fileId = 7, title = "Avatar", year = 2009, release = "[Titanic 1997]"))
        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(avatar))
    }

    @Test
    fun `a marker-less bracketed tag holding a multi-word title is a contradiction`() = runTest {
        // Review: `[Completely Different Film].srt` was set aside as a group tag and read as a CONFIRMED Avatar.
        for (fileName in listOf(
            "[Completely Different Film].srt", "Avatar [Completely Different Film].srt",
            "[Completely Different Film] Avatar.srt", "[Completely.Different.Film] Avatar.srt",
            "[YTS] [Completely Different Film] Avatar.srt", "[Titanic Returns] Avatar.srt",
        )) {
            server.respond(200, movieBody(fileId = 7, title = "Avatar", year = 2009, fileName = fileName))
            assertEquals(fileName, OnlineSubtitleSearchResult.NoResults, client().search(avatar))
        }
        server.respond(200, movieBody(fileId = 7, title = "Avatar", year = 2009, release = "[Completely Different Film]"))
        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(avatar))
    }

    @Test
    fun `group, site and descriptive bracketed tags without marker contradict nothing`() = runTest {
        for (fileName in listOf(
            "[YTS.AM] Avatar.srt", "[yts.mx] Avatar.srt", "[www.site.com] Avatar.srt", "[opensubtitles.org] Avatar.srt",
            "[Erai-raws] Avatar.srt", "[RARBG] Avatar.srt", "[Avatar] Avatar.srt", "[Avatar Extended] Avatar.srt",
            "[French Subs] Avatar.srt", "Avatar [Sous-titres Francais].srt", "Avatar [English SDH].srt",
            "[French Avatar].srt",
        )) {
            server.respond(200, movieBody(fileId = 7, title = "Avatar", year = 2009, fileName = fileName))
            assertEquals(fileName, OnlineSubtitle.FeatureMatch.CONFIRMED, singleMatch(avatar))
        }
        // A tag naming the feature's own alias title is no other film either.
        server.respond(
            200,
            movieBody(fileId = 7, title = "The Dinner Game", year = 1998, fileName = "[The Dinner Game] Le Dîner de cons.srt"),
        )
        assertEquals(OnlineSubtitle.FeatureMatch.UNCONFIRMED, singleMatch(movie))
    }

    @Test
    fun `technical, decorative or searched-film bracketed tags contradict nothing`() = runTest {
        for (fileName in listOf(
            "[YTS] Avatar.srt", "[FR] Avatar.srt", "[YTS.MX] Avatar.srt", "[1080p] [FR] Avatar.srt",
            "Avatar (2009) [1080p] [YTS.MX].srt", "[2009] Avatar.srt", "Avatar [French 2009].srt",
            "[Avatar 2009].srt", "[Avatar.2009.1080p.BluRay] Avatar.srt", "[] Avatar.srt",
        )) {
            server.respond(200, movieBody(fileId = 7, title = "Avatar", year = 2009, fileName = fileName))
            assertEquals(fileName, OnlineSubtitle.FeatureMatch.CONFIRMED, singleMatch(avatar))
        }
    }

    @Test
    fun `descriptive words before a release marker name no film`() = runTest {
        for (label in listOf("French.Full.1998.1080p.srt", "Sous-titres.Francais.1998.srt", "English.SDH.1080p.srt")) {
            server.respond(200, movieBody(fileId = 7, title = "Le Dîner de Cons", year = 1998, fileName = label))
            assertEquals(label, OnlineSubtitle.FeatureMatch.CONFIRMED, singleMatch(movie))
        }
        // Still a real other film when a title word hides among them, or the year is too far.
        for (label in listOf("French.Titanic.1998.1080p.srt", "Movie.43.1998.srt", "French.Full.2005.1080p.srt")) {
            server.respond(200, movieBody(fileId = 7, title = "Le Dîner de Cons", year = 1998, fileName = label))
            assertEquals(label, OnlineSubtitleSearchResult.NoResults, client().search(movie))
        }
    }

    @Test
    fun `a file name carrying the alias feature title itself contradicts nothing`() = runTest {
        server.respond(
            200,
            movieBody(
                fileId = 7, title = "The Dinner Game", year = 1998,
                release = "Le.Diner.De.Cons.1998.1080p", fileName = "The Dinner Game.srt",
            ),
        )
        assertEquals(OnlineSubtitle.FeatureMatch.UNCONFIRMED, singleMatch(movie))
    }

    @Test
    fun `confirmed files come first within a language, unrelated ones are dropped`() = runTest {
        server.respond(
            200,
            """
            {"data": [
              {"id": "1", "attributes": {"language": "fr", "files": [{"file_id": 1}]}},
              {"id": "2", "attributes": {"language": "fr", "feature_details": {"title": "Avatar 2", "year": 2022},
                "files": [{"file_id": 2}]}},
              {"id": "3", "attributes": {"language": "fr", "feature_details": {"title": "Avatar", "year": 2009},
                "files": [{"file_id": 3}]}}
            ]}
            """.trimIndent(),
        )

        val found = client().search(avatar) as OnlineSubtitleSearchResult.Found
        assertEquals(listOf(3L, 1L), found.subtitles.map { it.fileId })
    }

    @Test
    fun `episode results are not checked against a movie title`() = runTest {
        server.respond(200, movieBody(fileId = 7, title = "Some Other Show", year = 1990))

        assertEquals(OnlineSubtitle.FeatureMatch.NOT_CHECKED, singleMatch(episode))
    }

    private suspend fun singleMatch(context: SubtitleSearchContext): OnlineSubtitle.FeatureMatch =
        (client().search(context) as OnlineSubtitleSearchResult.Found).subtitles.single().featureMatch

    @Test
    fun `a subtitle without any file is not offered`() = runTest {
        server.respond(
            200,
            """{"total_count":1,"data":[{"id":"9","attributes":{"language":"fr","files":[]}}]}""",
        )

        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(movie))
    }

    @Test
    fun `an empty answer is NoResults`() = runTest {
        server.respond(200, """{"total_pages":0,"total_count":0,"per_page":60,"page":1,"data":[]}""")

        assertEquals(OnlineSubtitleSearchResult.NoResults, client().search(movie))
    }

    @Test
    fun `an unreadable 200 is INVALID_RESPONSE`() = runTest {
        server.respond(200, "<html>maintenance</html>")

        assertEquals(
            OnlineSubtitleSearchResult.Failed(Reason.INVALID_RESPONSE),
            client().search(movie),
        )
    }

    // ── failures ──────────────────────────────────────────────────────────────

    @Test
    fun `401 is UNAUTHORIZED`() = runTest {
        server.respond(401, """{"message":"Error, invalid username/password","status":401}""")

        assertEquals(OnlineSubtitleSearchResult.Failed(Reason.UNAUTHORIZED, httpCode = 401), client().search(movie))
    }

    @Test
    fun `403 is FORBIDDEN`() = runTest {
        server.respond(403, """{"message":"You cannot consume this service"}""")

        assertEquals(OnlineSubtitleSearchResult.Failed(Reason.FORBIDDEN, httpCode = 403), client().search(movie))
    }

    @Test
    fun `429 is RATE_LIMITED and carries the advertised reset delay`() = runTest {
        server.respond(429, """{"message":"Throttle limit reached. Retry later.","status":429}""", "ratelimit-reset" to "3")

        assertEquals(
            OnlineSubtitleSearchResult.Failed(Reason.RATE_LIMITED, retryAfterSeconds = 3, httpCode = 429),
            client().search(movie),
        )
    }

    @Test
    fun `429 without a reset header is still RATE_LIMITED`() = runTest {
        server.respond(429, "")

        assertEquals(
            OnlineSubtitleSearchResult.Failed(Reason.RATE_LIMITED, httpCode = 429),
            client().search(movie),
        )
    }

    @Test
    fun `5xx is SERVER`() = runTest {
        server.respond(503, "")

        assertEquals(OnlineSubtitleSearchResult.Failed(Reason.SERVER, httpCode = 503), client().search(movie))
    }

    @Test
    fun `another status is UNEXPECTED_HTTP`() = runTest {
        server.respond(406, "")

        assertEquals(OnlineSubtitleSearchResult.Failed(Reason.UNEXPECTED_HTTP, httpCode = 406), client().search(movie))
    }

    @Test
    fun `a timeout is TIMEOUT`() = runTest {
        server.fail(SocketTimeoutException("timeout"))

        assertEquals(OnlineSubtitleSearchResult.Failed(Reason.TIMEOUT), client().search(movie))
    }

    @Test
    fun `any other IO failure is NETWORK`() = runTest {
        server.fail(IOException("Unable to resolve host"))

        assertEquals(OnlineSubtitleSearchResult.Failed(Reason.NETWORK), client().search(movie))
    }

    // ── redirects ─────────────────────────────────────────────────────────────
    //
    // The API redirects non-canonical queries, and the key rides in a custom header OkHttp would
    // happily carry to whatever host a Location names — it only strips `Authorization`. So the
    // client follows redirects itself, and only to the API host.

    @Test
    fun `a redirect to the API host itself is followed, key included`() = runTest {
        server.enqueue(301, "", "Location" to "https://api.opensubtitles.com/api/v1/subtitles?languages=en,fr&query=x")
        server.enqueue(200, SEARCH_BODY)

        val result = client().search(movie)

        assertTrue(result is OnlineSubtitleSearchResult.Found)
        assertEquals(3, server.requests.size)
        val followed = server.requests[1]
        assertEquals("api.opensubtitles.com", followed.url.host)
        assertEquals("languages=en,fr&query=x", followed.url.encodedQuery)
        assertEquals("test-consumer-key", followed.header("Api-Key"))
        assertEquals("IptvApp v1.0", followed.header("User-Agent"))
    }

    @Test
    fun `a relative redirect resolves against the API host and is followed`() = runTest {
        server.enqueue(302, "", "Location" to "/api/v1/subtitles?query=x")
        server.enqueue(200, SEARCH_BODY)

        client().search(movie)

        assertEquals("https://api.opensubtitles.com/api/v1/subtitles?query=x", server.requests[1].url.toString())
    }

    @Test
    fun `a redirect to any other HTTPS host is refused and the key never goes there`() = runTest {
        server.enqueue(302, "", "Location" to "https://collector.evil.example/steal")

        val result = client().search(movie)

        assertEquals(OnlineSubtitleSearchResult.Failed(Reason.REDIRECT_REFUSED, httpCode = 302), result)
        assertEquals(1, server.requests.size)
        assertTrue(server.requests.none { it.url.host == "collector.evil.example" })
    }

    @Test
    fun `a redirect down to plain HTTP on the API host is refused`() = runTest {
        server.enqueue(301, "", "Location" to "http://api.opensubtitles.com/api/v1/subtitles?query=x")

        val result = client().search(movie)

        assertEquals(OnlineSubtitleSearchResult.Failed(Reason.REDIRECT_REFUSED, httpCode = 301), result)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `a redirect without a Location is refused`() = runTest {
        server.enqueue(307, "")

        assertEquals(
            OnlineSubtitleSearchResult.Failed(Reason.REDIRECT_REFUSED, httpCode = 307),
            client().search(movie),
        )
    }

    @Test
    fun `a redirect loop on the API host stops after a few hops`() = runTest {
        repeat(10) {
            server.enqueue(308, "", "Location" to "https://api.opensubtitles.com/api/v1/subtitles?hop=$it")
        }

        val result = client().search(movie)

        assertEquals(OnlineSubtitleSearchResult.Failed(Reason.REDIRECT_REFUSED, httpCode = 308), result)
        assertTrue("hops must be bounded, got ${server.requests.size}", server.requests.size <= 4)
    }

    // ── body size bound ───────────────────────────────────────────────────────
    //
    // A real HTTP server this time: the bound must hold on the bytes actually streamed — a chunked
    // answer announces no length, and a gzip one only grows once OkHttp decompresses it.

    @Test
    fun `a small answer over real HTTP is read and mapped`() = runTest {
        MockWebServer().use { http ->
            // One answer per language searched.
            repeat(2) { http.enqueue(MockResponse().setChunkedBody(SEARCH_BODY, 64)) }

            val result = client(http).search(movie)

            assertTrue(result is OnlineSubtitleSearchResult.Found)
            assertEquals(3, (result as OnlineSubtitleSearchResult.Found).subtitles.size)
        }
    }

    @Test
    fun `an oversized chunked answer is INVALID_RESPONSE, carrying neither key nor URL`() = runTest {
        MockWebServer().use { http ->
            http.enqueue(MockResponse().setChunkedBody(oversizedSearchBody(), 16 * 1024))

            val result = client(http).search(movie)

            assertEquals(OnlineSubtitleSearchResult.Failed(Reason.INVALID_RESPONSE), result)
            assertFalse(result.toString().contains("test-consumer-key"))
            assertFalse(result.toString().contains(http.hostName))
        }
    }

    @Test
    fun `a small gzip answer that decompresses past the bound is INVALID_RESPONSE`() = runTest {
        val compressed = Buffer().apply { GzipSink(this).buffer().use { it.writeUtf8(oversizedSearchBody()) } }
        assertTrue("the wire bytes alone must stay under the bound", compressed.size < 64 * 1024)
        MockWebServer().use { http ->
            http.enqueue(
                MockResponse()
                    .setHeader("Content-Encoding", "gzip")
                    .setChunkedBody(compressed, 4 * 1024),
            )

            val result = client(http).search(movie)

            assertEquals(OnlineSubtitleSearchResult.Failed(Reason.INVALID_RESPONSE), result)
        }
    }

    private fun client(http: MockWebServer) = OpenSubtitlesClient(
        httpClient = OkHttpClient(),
        json = Json { ignoreUnknownKeys = true; coerceInputValues = true },
        apiKeyStore = apiKeyStore,
        userAgent = "IptvApp v1.0",
        ioDispatcher = Dispatchers.Unconfined,
        baseUrl = http.url("/api/v1/"),
    )

    /** Valid JSON that would parse to results — only its size (~4 MiB of padding) makes it unacceptable. */
    private fun oversizedSearchBody(): String =
        SEARCH_BODY.replaceFirst("{", """{"padding":"${"x".repeat(4 * 1024 * 1024)}",""")

    /** One French movie result; `feature_details` is left out entirely when both fields are null. */
    private fun movieBody(
        fileId: Long,
        title: String?,
        year: Int?,
        release: String? = null,
        fileName: String? = null,
    ): String {
        val fields = listOfNotNull(
            title?.let { "\"title\": \"$it\"" },
            year?.let { "\"year\": $it" },
        )
        val details = if (fields.isEmpty()) "" else """"feature_details": {${fields.joinToString()}},"""
        val releaseField = release?.let { """"release": "$it",""" }.orEmpty()
        val fileNameField = fileName?.let { """, "file_name": "$it"""" }.orEmpty()
        return """
            {"data": [{"id": "$fileId", "attributes": {"language": "fr", $details $releaseField
              "files": [{"file_id": $fileId$fileNameField}]}}]}
        """.trimIndent()
    }

    // ── fakes ─────────────────────────────────────────────────────────────────

    private class FakeServer : Interceptor {
        val requests = mutableListOf<Request>()
        private var code = 200
        private var body = """{"data":[]}"""
        private var headers: Array<out Pair<String, String>> = emptyArray()
        private var failure: IOException? = null
        private val queued = ArrayDeque<Triple<Int, String, Array<out Pair<String, String>>>>()

        fun respond(code: Int, body: String, vararg headers: Pair<String, String>) {
            this.code = code
            this.body = body
            this.headers = headers
        }

        /** Queued answers are served first, in order; [respond]'s answer serves once they run out. */
        fun enqueue(code: Int, body: String, vararg headers: Pair<String, String>) {
            queued.addLast(Triple(code, body, headers))
        }

        fun fail(failure: IOException) {
            this.failure = failure
        }

        private val routes = mutableMapOf<String, Pair<Int, String>>()

        /** Answers requests whose `languages` parameter is exactly [languages], ahead of [respond]. */
        fun respondTo(languages: String, code: Int, body: String) {
            routes[languages] = code to body
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            requests += chain.request()
            failure?.let { throw it }
            val routed = routes[chain.request().url.queryParameter("languages")]
                ?.let { (code, body) -> Triple(code, body, emptyArray<Pair<String, String>>()) }
            val (code, body, headers) = queued.removeFirstOrNull() ?: routed ?: Triple(code, body, headers)
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fake")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private companion object {
        // English first on the wire, as a server ordering by popularity would return it.
        val SEARCH_BODY = """
            {
              "total_pages": 1, "total_count": 3, "per_page": 60, "page": 1,
              "data": [
                {"id": "1", "type": "subtitle", "attributes": {
                  "subtitle_id": "1", "language": "en", "download_count": 5000,
                  "release": "Le.Diner.De.Cons.1998.720p.WEB",
                  "files": [{"file_id": 111, "cd_number": 1, "file_name": "dinner.en.srt"}]
                }},
                {"id": "2", "type": "subtitle", "attributes": {
                  "subtitle_id": "2", "language": "fr", "download_count": 900,
                  "hearing_impaired": true, "from_trusted": true, "unknown_field": 42,
                  "release": "Le.Diner.De.Cons.1998.1080p",
                  "feature_details": {"feature_type": "Movie", "title": "Le Dîner de Cons", "year": 1998},
                  "files": [{"file_id": 222, "cd_number": 1, "file_name": "diner.fr.srt"}]
                }},
                {"id": "3", "type": "subtitle", "attributes": {
                  "subtitle_id": "3", "language": "fr", "download_count": 0,
                  "machine_translated": true, "ai_translated": true,
                  "release": "Le.Diner.De.Cons.1998.DVDRip",
                  "files": [{"file_id": 333}]
                }}
              ]
            }
        """.trimIndent()
    }
}

package com.bobot.iptvapp.navigation

import com.bobot.iptvapp.domain.model.SubtitleSearchContext
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for how the [Player] route carries a [SubtitleSearchContext]. */
class PlayerRouteTest {

    private val url = "http://example.com:8080/movie/u/p/42.mkv"

    @Test
    fun `a movie context survives the route`() {
        val context = SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "Bangkok Dangerous", year = 2008)

        assertEquals(context, Player(url, "42", context).subtitleSearchContext())
    }

    @Test
    fun `an episode context survives the route`() {
        val context = SubtitleSearchContext(
            kind = SubtitleSearchContext.Kind.EPISODE,
            title = "Pilote",
            seriesTitle = "Breaking Bad",
            seasonNumber = 2,
            episodeNumber = 1,
        )

        assertEquals(context, Player(url, "e7", context).subtitleSearchContext())
    }

    @Test
    fun `the context also survives route serialization`() {
        val context = SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "Up", year = 2009)
        val route = Player(url, "42", context)

        val decoded = Json.decodeFromString(Player.serializer(), Json.encodeToString(Player.serializer(), route))

        assertEquals(context, decoded.subtitleSearchContext())
    }

    @Test
    fun `a route without subtitle metadata has no context`() {
        assertNull(Player(streamUrl = url, streamId = "42").subtitleSearchContext())
        assertNull(Player(url, "42", subtitleSearchContext = null).subtitleSearchContext())
    }

    @Test
    fun `a legacy route payload without the new fields still decodes`() {
        val decoded = Json.decodeFromString(Player.serializer(), """{"streamUrl":"$url","streamId":"42"}""")

        assertEquals(Player(streamUrl = url, streamId = "42"), decoded)
        assertNull(decoded.subtitleSearchContext())
    }

    @Test
    fun `unknown kind or blank title yields no context instead of a crash`() {
        assertNull(Player(url, "42", subtitleKind = "DOCUMENTARY", subtitleTitle = "X").subtitleSearchContext())
        assertNull(Player(url, "42", subtitleKind = "MOVIE", subtitleTitle = " ").subtitleSearchContext())
    }
}

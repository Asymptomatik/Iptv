package com.bobot.iptvapp.data.source.fake

import com.bobot.iptvapp.data.source.CatalogDataSource
import com.bobot.iptvapp.data.source.CatalogException
import com.bobot.iptvapp.domain.model.Category
import com.bobot.iptvapp.domain.model.Channel
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.Episode
import com.bobot.iptvapp.domain.model.EpgProgram
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.Season
import com.bobot.iptvapp.domain.model.Series
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory fake [CatalogDataSource] for development and demo purposes.
 *
 * Returns a realistic Netflix-style catalog that exercises every domain model and
 * edge case defined in the approved brief:
 *
 *  - **Live** — 4 categories, 10 channels:
 *    - channels with and without logos (Sky Sports Main — no logo)
 *    - channels with and without EPG (Netflix Channel — no EPgChannelId)
 *  - **VOD** — 4 categories, 10 movies:
 *    - one movie without a poster (Forrest Gump) to exercise placeholder handling
 *  - **Series** — 3 categories, 4 series:
 *    - single season (Squid Game)
 *    - two seasons (Breaking Bad)
 *    - three seasons (Game of Thrones)
 *    - two seasons with varied episode durations (The Mandalorian)
 *  - **EPG** — 4 time slots per channel (previous, current, next, next+1), anchored
 *    to the top of the current hour so "now playing" is always visible in the UI.
 *
 * ## Simulated delay
 * Every call waits [SIMULATED_DELAY_MS] milliseconds before returning. This mimics
 * network round-trip time so loading states and shimmer placeholders are exercised
 * during development. Set [SIMULATED_DELAY_MS] to `0L` to disable.
 *
 * ## Data determinism
 * All titles, descriptions, IDs, and metadata are fixed constants. EPG start/end
 * timestamps are anchored to the current wall-clock hour (not fixed epoch values)
 * so the "now" programme is always the second slot, regardless of when the app runs.
 */
@Singleton
class FakeXtreamSource @Inject constructor() : CatalogDataSource {

    // ── CatalogDataSource implementation ──────────────────────────────────────

    override suspend fun authenticate(): Result<Unit> {
        delay(SIMULATED_DELAY_MS)
        return Result.success(Unit)
    }

    override suspend fun getLiveCategories(): List<Category> {
        delay(SIMULATED_DELAY_MS)
        return LIVE_CATEGORIES
    }

    override suspend fun getVodCategories(): List<Category> {
        delay(SIMULATED_DELAY_MS)
        return VOD_CATEGORIES
    }

    override suspend fun getSeriesCategories(): List<Category> {
        delay(SIMULATED_DELAY_MS)
        return SERIES_CATEGORIES
    }

    override suspend fun getLiveChannels(categoryId: String?): List<Channel> {
        delay(SIMULATED_DELAY_MS)
        return if (categoryId == null) ALL_CHANNELS
        else ALL_CHANNELS.filter { it.categoryId == categoryId }
    }

    override suspend fun getMovies(categoryId: String?): List<Movie> {
        delay(SIMULATED_DELAY_MS)
        return if (categoryId == null) ALL_MOVIES
        else ALL_MOVIES.filter { it.categoryId == categoryId }
    }

    override suspend fun getSeriesList(categoryId: String?): List<Series> {
        delay(SIMULATED_DELAY_MS)
        // Return stubs without seasons — matches the real Xtream list endpoint behaviour.
        val stubs = SERIES_CATALOG.map { it.copy(seasons = emptyList()) }
        return if (categoryId == null) stubs
        else stubs.filter { it.categoryId == categoryId }
    }

    override suspend fun getMovieInfo(movieId: String): Movie {
        delay(SIMULATED_DELAY_MS)
        return ALL_MOVIES.find { it.id == movieId }
            ?: throw CatalogException.NotFound(movieId)
    }

    override suspend fun getSeriesInfo(seriesId: String): Series {
        delay(SIMULATED_DELAY_MS)
        return SERIES_CATALOG.find { it.id == seriesId }
            ?: throw CatalogException.NotFound(seriesId)
    }

    override suspend fun getShortEpg(channelId: String, limit: Int?): List<EpgProgram> {
        delay(SIMULATED_DELAY_MS)
        val programs = buildEpgPrograms(channelId)
        return if (limit != null) programs.take(limit) else programs
    }

    // ── EPG generation ────────────────────────────────────────────────────────

    /**
     * Builds 4 consecutive EPG slots anchored to the top of the current hour:
     *  - Slot 0 (index 0): previous program — [hourStart - 1h, hourStart)
     *  - Slot 1 (index 1): now playing     — [hourStart, hourStart + 1h)
     *  - Slot 2 (index 2): next            — [hourStart + 1h, hourStart + 2h)
     *  - Slot 3 (index 3): next+1          — [hourStart + 2h, hourStart + 3h)
     *
     * [channelId] is a [Channel.id] (numeric `stream_id`), like the real endpoint takes —
     * see [CatalogDataSource.getShortEpg]. [EPG_DATA] stays keyed by XMLTV id because that is
     * what reads naturally next to the programme titles, so the channel is looked up first to
     * translate one into the other. A channel with no [Channel.epgChannelId] resolves to no
     * entries, which is the "channel the provider has no schedule for" case this fake models.
     */
    private fun buildEpgPrograms(channelId: String): List<EpgProgram> {
        val epgChannelId = ALL_CHANNELS.find { it.id == channelId }?.epgChannelId
        val entries = EPG_DATA[epgChannelId] ?: return emptyList()
        val now = System.currentTimeMillis()
        val slotMs = HOUR_MS
        val hourStart = now - (now % slotMs)
        return entries.mapIndexed { index, (title, description) ->
            EpgProgram(
                channelId = channelId,
                title = title,
                description = description,
                startMillis = hourStart + (index - 1) * slotMs,
                endMillis = hourStart + index * slotMs,
            )
        }
    }

    // ── Static catalog data ───────────────────────────────────────────────────

    companion object {

        /** Simulated network round-trip delay. Set to 0L to disable. */
        private const val SIMULATED_DELAY_MS = 50L

        private const val HOUR_MS = 60 * 60 * 1000L

        // Fixed addedMillis timestamp for all VOD entries (2023-11-15 00:00:00 UTC).
        private const val ADDED_TS = 1_700_006_400_000L

        // ── Live categories ──────────────────────────────────────────────────

        private val LIVE_CATEGORIES = listOf(
            Category(id = "1", name = "News & Current Affairs", type = ContentType.LIVE),
            Category(id = "2", name = "Sport", type = ContentType.LIVE),
            Category(id = "3", name = "Entertainment", type = ContentType.LIVE),
            Category(id = "4", name = "Documentary", type = ContentType.LIVE),
        )

        // ── VOD categories ───────────────────────────────────────────────────

        private val VOD_CATEGORIES = listOf(
            Category(id = "1001", name = "Action", type = ContentType.MOVIE),
            Category(id = "1002", name = "Drama", type = ContentType.MOVIE),
            Category(id = "1003", name = "Comedy", type = ContentType.MOVIE),
            Category(id = "1004", name = "Science Fiction", type = ContentType.MOVIE),
        )

        // ── Series categories ────────────────────────────────────────────────

        private val SERIES_CATEGORIES = listOf(
            Category(id = "2001", name = "Action & Adventure", type = ContentType.SERIES),
            Category(id = "2002", name = "Drama", type = ContentType.SERIES),
            Category(id = "2003", name = "Sci-Fi & Fantasy", type = ContentType.SERIES),
        )

        // ── Live channels ────────────────────────────────────────────────────
        //
        // Edge cases exercised:
        //   - logoUrl = null  : Sky Sports Main Event (no logo from server)
        //   - epgChannelId = null : Netflix Channel (no EPG mapping)

        private val ALL_CHANNELS = listOf(
            // News & Current Affairs — categoryId = "1"
            Channel(
                id = "101",
                name = "BBC World News",
                logoUrl = "https://placehold.co/120x68/1a1a2e/ffffff?text=BBC",
                categoryId = "1",
                epgChannelId = "bbc.world",
            ),
            Channel(
                id = "102",
                name = "CNN International",
                logoUrl = "https://placehold.co/120x68/cc0000/ffffff?text=CNN",
                categoryId = "1",
                epgChannelId = "cnn.int",
            ),
            Channel(
                id = "103",
                name = "Al Jazeera English",
                logoUrl = "https://placehold.co/120x68/003366/ffffff?text=AJE",
                categoryId = "1",
                epgChannelId = "aljazeera.english",
            ),
            // Sport — categoryId = "2"
            Channel(
                id = "201",
                name = "ESPN",
                logoUrl = "https://placehold.co/120x68/cc0000/ffffff?text=ESPN",
                categoryId = "2",
                epgChannelId = "espn",
            ),
            Channel(
                id = "202",
                name = "beIN Sports 1",
                logoUrl = "https://placehold.co/120x68/8b0000/ffffff?text=beIN",
                categoryId = "2",
                epgChannelId = "bein.sports.1",
            ),
            Channel(
                id = "203",
                name = "Sky Sports Main Event",
                logoUrl = null, // edge case: no logo
                categoryId = "2",
                epgChannelId = "sky.sports.main",
            ),
            // Entertainment — categoryId = "3"
            Channel(
                id = "301",
                name = "HBO",
                logoUrl = "https://placehold.co/120x68/000000/ffffff?text=HBO",
                categoryId = "3",
                epgChannelId = "hbo",
            ),
            Channel(
                id = "302",
                name = "Netflix Channel",
                logoUrl = "https://placehold.co/120x68/e50914/ffffff?text=N",
                categoryId = "3",
                epgChannelId = null, // edge case: no EPG
            ),
            // Documentary — categoryId = "4"
            Channel(
                id = "401",
                name = "National Geographic",
                logoUrl = "https://placehold.co/120x68/ffcc00/000000?text=NatGeo",
                categoryId = "4",
                epgChannelId = "natgeo",
            ),
            Channel(
                id = "402",
                name = "Discovery Channel",
                logoUrl = "https://placehold.co/120x68/003366/ffffff?text=Discovery",
                categoryId = "4",
                epgChannelId = "discovery",
            ),
        )

        // ── Movies ───────────────────────────────────────────────────────────
        //
        // Edge cases exercised:
        //   - posterUrl = null : Forrest Gump (exercises placeholder image handling)

        private val ALL_MOVIES = listOf(
            // Action — categoryId = "1001"
            Movie(
                id = "m101",
                title = "John Wick",
                categoryId = "1001",
                posterUrl = "https://picsum.photos/seed/johnwick/300/450",
                plot = "An ex-hitman comes out of retirement to track down the gangsters" +
                    " that killed his dog and stole his car.",
                rating = "7.4",
                year = 2014,
                addedMillis = ADDED_TS,
                durationMillis = 6_240_000L, // 104 min
                containerExtension = "mkv",
            ),
            Movie(
                id = "m102",
                title = "Mad Max: Fury Road",
                categoryId = "1001",
                posterUrl = "https://picsum.photos/seed/madmax/300/450",
                plot = "In a post-apocalyptic wasteland, Max teams up with Furiosa to" +
                    " flee from a warlord and his army.",
                rating = "8.1",
                year = 2015,
                addedMillis = ADDED_TS,
                durationMillis = 7_200_000L, // 120 min
                containerExtension = "mkv",
            ),
            Movie(
                id = "m103",
                title = "Top Gun: Maverick",
                categoryId = "1001",
                posterUrl = "https://picsum.photos/seed/topgunmaverick/300/450",
                plot = "After more than thirty years of service, Pete Mitchell pushes the" +
                    " envelope as a top naval aviator while grappling with the ghosts of his past.",
                rating = "8.3",
                year = 2022,
                addedMillis = ADDED_TS,
                durationMillis = 8_220_000L, // 137 min
                containerExtension = "mp4",
            ),
            // Drama — categoryId = "1002"
            Movie(
                id = "m201",
                title = "The Shawshank Redemption",
                categoryId = "1002",
                posterUrl = "https://picsum.photos/seed/shawshank/300/450",
                plot = "Two imprisoned men bond over a number of years, finding solace" +
                    " and eventual redemption through acts of common decency.",
                rating = "9.3",
                year = 1994,
                addedMillis = ADDED_TS,
                durationMillis = 8_520_000L, // 142 min
                containerExtension = "mkv",
            ),
            Movie(
                id = "m202",
                title = "Forrest Gump",
                categoryId = "1002",
                posterUrl = null, // edge case: no poster — UI must show a placeholder
                plot = "The story of an unlikely hero whose simple outlook on life takes" +
                    " him from Alabama to the White House.",
                rating = "8.8",
                year = 1994,
                addedMillis = ADDED_TS,
                durationMillis = 8_520_000L, // 142 min
                containerExtension = "mp4",
            ),
            // Comedy — categoryId = "1003"
            Movie(
                id = "m301",
                title = "The Grand Budapest Hotel",
                categoryId = "1003",
                posterUrl = "https://picsum.photos/seed/grandbudapest/300/450",
                plot = "A writer encounters the owner of an aging European hotel between" +
                    " the wars, who recounts his adventures as the hotel's concierge.",
                rating = "8.1",
                year = 2014,
                addedMillis = ADDED_TS,
                durationMillis = 5_940_000L, // 99 min
                containerExtension = "mkv",
            ),
            Movie(
                id = "m302",
                title = "Superbad",
                categoryId = "1003",
                posterUrl = "https://picsum.photos/seed/superbad/300/450",
                plot = "Two co-dependent high school seniors deal with separation anxiety" +
                    " when their plan to provide alcohol for a house party goes awry.",
                rating = "7.6",
                year = 2007,
                addedMillis = ADDED_TS,
                durationMillis = 6_900_000L, // 115 min
                containerExtension = "mp4",
            ),
            // Science Fiction — categoryId = "1004"
            Movie(
                id = "m401",
                title = "Dune: Part One",
                categoryId = "1004",
                posterUrl = "https://picsum.photos/seed/dune2021/300/450",
                plot = "A noble family becomes embroiled in a war for control over the" +
                    " galaxy's most valuable asset while its heir is troubled by dark visions.",
                rating = "8.0",
                year = 2021,
                addedMillis = ADDED_TS,
                durationMillis = 9_300_000L, // 155 min
                containerExtension = "mkv",
            ),
            Movie(
                id = "m402",
                title = "Interstellar",
                categoryId = "1004",
                posterUrl = "https://picsum.photos/seed/interstellar/300/450",
                plot = "A team of explorers travel through a wormhole in space in an" +
                    " attempt to ensure humanity's survival.",
                rating = "8.7",
                year = 2014,
                addedMillis = ADDED_TS,
                durationMillis = 10_140_000L, // 169 min
                containerExtension = "mkv",
            ),
            Movie(
                id = "m403",
                title = "Arrival",
                categoryId = "1004",
                posterUrl = "https://picsum.photos/seed/arrival2016/300/450",
                plot = "A linguist works with the military to communicate with alien" +
                    " lifeforms after twelve mysterious spacecraft appear around the world.",
                rating = "7.9",
                year = 2016,
                addedMillis = ADDED_TS,
                durationMillis = 6_900_000L, // 116 min
                containerExtension = "mp4",
            ),
        )

        // ── Series catalog ───────────────────────────────────────────────────
        //
        // Full series with populated seasons and episodes. [getSeriesList] maps each
        // entry to a stub (seasons = emptyList()) before returning it.
        //
        // Series exercised:
        //   - Squid Game      : 1 season
        //   - Breaking Bad    : 2 seasons
        //   - Game of Thrones : 3 seasons
        //   - The Mandalorian : 2 seasons (shorter episodes — episodic streaming format)

        private val SERIES_CATALOG: List<Series> = listOf(

            // ── Squid Game — 1 season, 9 episodes ───────────────────────────
            Series(
                id = "s301",
                title = "Squid Game",
                categoryId = "2001",
                coverUrl = "https://picsum.photos/seed/squidgame/300/450",
                plot = "Hundreds of cash-strapped players accept a strange invitation to" +
                    " compete in children's games — with deadly high stakes and a tempting cash prize.",
                rating = "8.0",
                year = 2021,
                seasons = listOf(
                    Season(
                        seasonNumber = 1,
                        name = "Season 1",
                        coverUrl = "https://picsum.photos/seed/squidgame1/300/450",
                        episodes = listOf(
                            ep("sg-s1e1", "Red Light, Green Light", 1, 1, 60,
                                "Seong Gi-hun, a divorced and indebted chauffeur, is invited to" +
                                    " play a series of children's games for a chance at a large cash prize."),
                            ep("sg-s1e2", "Hell", 2, 1, 61,
                                "Gi-hun and the other players grapple with the violence they witnessed" +
                                    " and must decide whether to continue the games."),
                            ep("sg-s1e3", "The Man with the Umbrella", 3, 1, 64,
                                "A VIP observes the games. Gi-hun's team gathers new members for the" +
                                    " tug-of-war challenge."),
                            ep("sg-s1e4", "Stick to the Team", 4, 1, 56,
                                "The players scramble to form teams of ten for the tug-of-war. Gi-hun" +
                                    " turns to an unlikely ally."),
                            ep("sg-s1e5", "A Fair World", 5, 1, 53,
                                "A marble game pits players against their closest allies. Gi-hun tries" +
                                    " to outsmart his elderly opponent."),
                            ep("sg-s1e6", "Gganbu", 6, 1, 60,
                                "Gi-hun faces a heartbreaking challenge against his childhood friend." +
                                    " Alliances are tested to their limits."),
                            ep("sg-s1e7", "VIPS", 7, 1, 60,
                                "Four VIPs arrive to watch the competition. Gi-hun and allies try to" +
                                    " infiltrate the staff to find answers."),
                            ep("sg-s1e8", "Front Man", 8, 1, 56,
                                "In the glass bridge challenge, players must survive by choosing the" +
                                    " right panels. Tension reaches its peak."),
                            ep("sg-s1e9", "One Lucky Day", 9, 1, 66,
                                "The final game begins. The sole survivor discovers the truth behind the" +
                                    " games and their mysterious host."),
                        ),
                    ),
                ),
            ),

            // ── Breaking Bad — 2 seasons ─────────────────────────────────────
            Series(
                id = "s101",
                title = "Breaking Bad",
                categoryId = "2002",
                coverUrl = "https://picsum.photos/seed/breakingbad/300/450",
                plot = "A high school chemistry teacher diagnosed with inoperable lung cancer" +
                    " turns to manufacturing methamphetamine to secure his family's future.",
                rating = "9.5",
                year = 2008,
                seasons = listOf(
                    Season(
                        seasonNumber = 1,
                        name = "Season 1",
                        coverUrl = "https://picsum.photos/seed/breakingbad1/300/450",
                        episodes = listOf(
                            ep("bb-s1e1", "Pilot", 1, 1, 58,
                                "Mild-mannered chemistry teacher Walter White discovers he has terminal" +
                                    " cancer and partners with former student Jesse Pinkman to cook crystal meth."),
                            ep("bb-s1e2", "Cat's in the Bag", 2, 1, 48,
                                "Walt and Jesse must dispose of the bodies of their victims while" +
                                    " dealing with the trauma of their first kills."),
                            ep("bb-s1e3", "...And the Bag's in the River", 3, 1, 48,
                                "Walt tries to decide the fate of a captive while Jesse struggles" +
                                    " with his past."),
                            ep("bb-s1e4", "Cancer Man", 4, 1, 48,
                                "Walt reveals his cancer diagnosis to his family. Jesse reconnects" +
                                    " with his parents."),
                            ep("bb-s1e5", "Gray Matter", 5, 1, 48,
                                "Walt struggles with pride when his former business partners offer to" +
                                    " pay for his cancer treatment."),
                            ep("bb-s1e6", "Crazy Handful of Nothin'", 6, 1, 48,
                                "Walt goes through his first round of chemotherapy while Jesse tries" +
                                    " to move their product to a new dealer named Tuco."),
                            ep("bb-s1e7", "A No-Rough-Stuff-Type Deal", 7, 1, 48,
                                "Walt and Jesse seek the ingredients to make a new batch, and Walter" +
                                    " makes a dangerous deal with Tuco."),
                        ),
                    ),
                    Season(
                        seasonNumber = 2,
                        name = "Season 2",
                        coverUrl = "https://picsum.photos/seed/breakingbad2/300/450",
                        episodes = listOf(
                            ep("bb-s2e1", "Seven Thirty-Seven", 1, 2, 47,
                                "In the aftermath of a terrifying encounter with Tuco, Walt and Jesse" +
                                    " plan their next move."),
                            ep("bb-s2e2", "Down", 2, 2, 47,
                                "Jesse hits rock bottom while Walt struggles to hide his secret life" +
                                    " from his family."),
                            ep("bb-s2e3", "Bit by a Dead Bee", 3, 2, 47,
                                "Walt concocts a desperate story to explain his disappearance; Jesse" +
                                    " goes to rehab."),
                            ep("bb-s2e4", "Down", 4, 2, 47,
                                "Walt works to keep his two worlds from colliding while Jesse's" +
                                    " situation grows desperate."),
                            ep("bb-s2e5", "Breakage", 5, 2, 47,
                                "Walt and Jesse set up their own distribution network, recruiting" +
                                    " small-time dealers."),
                            ep("bb-s2e6", "Peekaboo", 6, 2, 47,
                                "Jesse visits junkies who ripped off one of their dealers; Walt" +
                                    " confronts his former colleague."),
                            ep("bb-s2e7", "Negro y Azul", 7, 2, 47,
                                "A narcocorrido about Heisenberg spreads fear through the cartel." +
                                    " Walt and Jesse recruit Badger and Skinny Pete."),
                            ep("bb-s2e8", "Better Call Saul", 8, 2, 47,
                                "A crisis leads Walt and Jesse to criminal lawyer Saul Goodman, who" +
                                    " opens unexpected possibilities."),
                            ep("bb-s2e9", "4 Days Out", 9, 2, 47,
                                "Walt and Jesse spend four days in the desert cooking a large batch" +
                                    " of meth."),
                            ep("bb-s2e10", "Over", 10, 2, 47,
                                "Walt receives life-changing news about his cancer as his domestic" +
                                    " life quietly unravels."),
                            ep("bb-s2e11", "Mandala", 11, 2, 47,
                                "Walt and Jesse have an opportunity to make a major business deal" +
                                    " that could change everything."),
                            ep("bb-s2e12", "Phoenix", 12, 2, 47,
                                "Walt faces a profound moral dilemma as Jesse spirals out of control" +
                                    " with his girlfriend Jane."),
                            ep("bb-s2e13", "ABQ", 13, 2, 47,
                                "In the aftermath of a tragedy, Walt must make peace with his secrets" +
                                    " as his family faces an unexpected crisis."),
                        ),
                    ),
                ),
            ),

            // ── Game of Thrones — 3 seasons ──────────────────────────────────
            Series(
                id = "s102",
                title = "Game of Thrones",
                categoryId = "2002",
                coverUrl = "https://picsum.photos/seed/gameofthrones/300/450",
                plot = "Nine noble families fight for control of the mythical land of Westeros." +
                    " Political intrigue, betrayal, and epic battles define every season.",
                rating = "9.2",
                year = 2011,
                seasons = listOf(
                    Season(
                        seasonNumber = 1,
                        name = "Season 1",
                        coverUrl = "https://picsum.photos/seed/got1/300/450",
                        episodes = listOf(
                            ep("got-s1e1", "Winter Is Coming", 1, 1, 62,
                                "Lord Eddard Stark is asked by the King to serve as the Hand after the" +
                                    " unexpected death of Jon Arryn."),
                            ep("got-s1e2", "The Kingsroad", 2, 1, 56,
                                "The Starks say their farewells as Ned and his daughters head south" +
                                    " for King's Landing."),
                            ep("got-s1e3", "Lord Snow", 3, 1, 58,
                                "Ned arrives at King's Landing and learns the troubling state of the" +
                                    " kingdom's finances."),
                            ep("got-s1e4", "Cripples, Bastards and Broken Things", 4, 1, 56,
                                "Ned investigates the death of his predecessor while Daenerys embraces" +
                                    " her new role as Khaleesi."),
                            ep("got-s1e5", "The Wolf and the Lion", 5, 1, 55,
                                "Catelyn captures Tyrion and brings him to the Eyrie. Ned discovers" +
                                    " a disturbing truth about the Lannisters."),
                            ep("got-s1e6", "A Golden Crown", 6, 1, 52,
                                "Viserys reaches his breaking point. Daenerys proves herself a true" +
                                    " Khaleesi in a ceremony before the khalasar."),
                            ep("got-s1e7", "You Win or You Die", 7, 1, 58,
                                "Ned confronts Cersei about what he knows. Drogo promises to cross" +
                                    " the Narrow Sea and conquer Westeros."),
                            ep("got-s1e8", "The Pointy End", 8, 1, 58,
                                "After Ned's arrest, his bannermen mobilise for war. Jon has his first" +
                                    " real taste of battle at the Wall."),
                            ep("got-s1e9", "Baelor", 9, 1, 56,
                                "Robb takes a calculated risk in sending his most seasoned commander" +
                                    " on a suicide mission."),
                            ep("got-s1e10", "Fire and Blood", 10, 1, 53,
                                "After a public execution shocks the realm, Robb is proclaimed King in" +
                                    " the North and marches to war."),
                        ),
                    ),
                    Season(
                        seasonNumber = 2,
                        name = "Season 2",
                        coverUrl = "https://picsum.photos/seed/got2/300/450",
                        episodes = listOf(
                            ep("got-s2e1", "The North Remembers", 1, 2, 53,
                                "Tyrion arrives at King's Landing to take up his post as the new" +
                                    " Hand of the King."),
                            ep("got-s2e2", "The Night Lands", 2, 2, 54,
                                "Arya makes friends on the road to the Wall. Theon Greyjoy arrives" +
                                    " home on the Iron Islands."),
                            ep("got-s2e3", "What Is Dead May Never Die", 3, 2, 53,
                                "Catelyn arrives at Renly's camp. Tyrion tests the loyalties of those" +
                                    " closest to King Joffrey."),
                            ep("got-s2e4", "Garden of Bones", 4, 2, 50,
                                "Joffrey punishes Sansa for Robb's victories. Daenerys and her" +
                                    " khalasar finally arrive at the city of Qarth."),
                            ep("got-s2e5", "The Ghost of Harrenhal", 5, 2, 56,
                                "Tyrion discovers Cersei's secret weapon. Arya is granted a favour" +
                                    " by the mysterious Jaqen H'ghar."),
                            ep("got-s2e6", "The Old Gods and the New", 6, 2, 54,
                                "Theon seizes Winterfell. Joffrey is mobbed by hungry citizens in" +
                                    " a dangerous riot in King's Landing."),
                            ep("got-s2e7", "A Man Without Honor", 7, 2, 56,
                                "Theon hunts for Bran and Rickon as Jaime makes a desperate" +
                                    " escape attempt from his captors."),
                            ep("got-s2e8", "The Prince of Winterfell", 8, 2, 55,
                                "Stannis sails toward King's Landing. Cersei believes she has" +
                                    " found Tyrion's weakness."),
                            ep("got-s2e9", "Blackwater", 9, 2, 55,
                                "Stannis' fleet and army converge on King's Landing. Tyrion" +
                                    " establishes a last-ditch plan to defend the city."),
                            ep("got-s2e10", "Valar Morghulis", 10, 2, 66,
                                "Tyrion awakens after the Battle of the Blackwater to find his" +
                                    " world has fundamentally changed."),
                        ),
                    ),
                    Season(
                        seasonNumber = 3,
                        name = "Season 3",
                        coverUrl = "https://picsum.photos/seed/got3/300/450",
                        episodes = listOf(
                            ep("got-s3e1", "Valar Dohaeris", 1, 3, 57,
                                "Jon is brought before Mance Rayder, the King Beyond the Wall." +
                                    " Daenerys purchases the Unsullied army."),
                            ep("got-s3e2", "Dark Wings, Dark Words", 2, 3, 55,
                                "Bran and company meet Jojen and Meera Reed. Arya encounters" +
                                    " the Brotherhood Without Banners."),
                            ep("got-s3e3", "Walk of Punishment", 3, 3, 57,
                                "Robb arrives at Riverrun for Lord Tully's funeral. Jaime forms" +
                                    " an uneasy bond with Brienne."),
                            ep("got-s3e4", "And Now His Watch Is Ended", 4, 3, 57,
                                "The Night's Watch mutinies at Craster's Keep. Daenerys frees" +
                                    " the Unsullied and takes command."),
                            ep("got-s3e5", "Kissed by Fire", 5, 3, 57,
                                "Jon breaks his vow with the wildlings. The Lannisters face a" +
                                    " formidable new political challenge."),
                            ep("got-s3e6", "The Climb", 6, 3, 55,
                                "Jon and the wildlings scale the Wall. Littlefinger reveals his" +
                                    " plans for the Stark girls."),
                            ep("got-s3e7", "The Bear and the Maiden Fair", 7, 3, 55,
                                "Daenerys arrives at Yunkai. Tywin meets with Joffrey to discuss" +
                                    " the political situation."),
                            ep("got-s3e8", "Second Sons", 8, 3, 57,
                                "Daenerys meets the leaders of the Second Sons mercenary company." +
                                    " Tyrion and Sansa are wed."),
                            ep("got-s3e9", "The Rains of Castamere", 9, 3, 53,
                                "Robb and Catelyn arrive at the Twins for the wedding of Edmure" +
                                    " Tully — the Red Wedding unfolds."),
                            ep("got-s3e10", "Mhysa", 10, 3, 66,
                                "Joffrey challenges Tywin's authority. The smallfolk of King's" +
                                    " Landing greet Daenerys as a liberator."),
                        ),
                    ),
                ),
            ),

            // ── The Mandalorian — 2 seasons ───────────────────────────────────
            Series(
                id = "s201",
                title = "The Mandalorian",
                categoryId = "2003",
                coverUrl = "https://picsum.photos/seed/mandalorian/300/450",
                plot = "The travels of a lone bounty hunter in the outer reaches of the galaxy," +
                    " far from the authority of the New Republic.",
                rating = "8.7",
                year = 2019,
                seasons = listOf(
                    Season(
                        seasonNumber = 1,
                        name = "Season 1",
                        coverUrl = "https://picsum.photos/seed/mandalorian1/300/450",
                        episodes = listOf(
                            ep("mando-s1e1", "Chapter 1: The Mandalorian", 1, 1, 39,
                                "A Mandalorian bounty hunter tracks a target for a well-paying" +
                                    " mysterious client on a remote outer planet."),
                            ep("mando-s1e2", "Chapter 2: The Child", 2, 1, 31,
                                "The bounty hunter must go back for his quarry with help from an" +
                                    " unexpected rival hunter."),
                            ep("mando-s1e3", "Chapter 3: The Sin", 3, 1, 38,
                                "The Mandalorian delivers the asset but second thoughts lead to a" +
                                    " violent confrontation with the Guild."),
                            ep("mando-s1e4", "Chapter 4: Sanctuary", 4, 1, 37,
                                "The Mandalorian and the Child hide on a remote farming planet. A" +
                                    " familiar face arrives unexpectedly."),
                            ep("mando-s1e5", "Chapter 5: The Gunslinger", 5, 1, 34,
                                "Forced to land on Tatooine for repairs, the Mandalorian teams up" +
                                    " with a young bounty hunter in training."),
                            ep("mando-s1e6", "Chapter 6: The Prisoner", 6, 1, 41,
                                "A mercenary crew needs the Mandalorian for a dangerous prison break." +
                                    " Old acquaintances prove treacherous."),
                            ep("mando-s1e7", "Chapter 7: The Reckoning", 7, 1, 42,
                                "The Mandalorian returns to Nevarro as old alliances are put to" +
                                    " the test in a desperate gambit."),
                            ep("mando-s1e8", "Chapter 8: Redemption", 8, 1, 49,
                                "The Mandalorian and his allies make a desperate last stand on" +
                                    " Nevarro against overwhelming Imperial forces."),
                        ),
                    ),
                    Season(
                        seasonNumber = 2,
                        name = "Season 2",
                        coverUrl = "https://picsum.photos/seed/mandalorian2/300/450",
                        episodes = listOf(
                            ep("mando-s2e1", "Chapter 9: The Marshal", 1, 2, 55,
                                "The Mandalorian heads to Tatooine seeking others of his kind and" +
                                    " encounters a legendary warrior."),
                            ep("mando-s2e2", "Chapter 10: The Passenger", 2, 2, 36,
                                "The Mandalorian agrees to transport a passenger and her clutch of" +
                                    " eggs to a distant planet."),
                            ep("mando-s2e3", "Chapter 11: The Heiress", 3, 2, 35,
                                "On the ocean planet of Trask, the Mandalorian searches for others" +
                                    " who walk the Way."),
                            ep("mando-s2e4", "Chapter 12: The Siege", 4, 2, 37,
                                "The Mandalorian revisits old allies on Nevarro for what should be" +
                                    " a straightforward clean-up operation."),
                            ep("mando-s2e5", "Chapter 13: The Jedi", 5, 2, 35,
                                "The Mandalorian travels to the forest planet Corvus and encounters" +
                                    " a mysterious Jedi."),
                            ep("mando-s2e6", "Chapter 14: The Tragedy", 6, 2, 32,
                                "The Mandalorian keeps a hard-won promise — but an unexpected" +
                                    " encounter has dire and lasting consequences."),
                            ep("mando-s2e7", "Chapter 15: The Believer", 7, 2, 34,
                                "To rescue Grogu, the Mandalorian requires the help of a man with" +
                                    " the right Imperial access codes."),
                            ep("mando-s2e8", "Chapter 16: The Rescue", 8, 2, 47,
                                "The Mandalorian and his allies attempt an audacious rescue mission" +
                                    " against overwhelming odds."),
                        ),
                    ),
                ),
            ),
        )

        // ── EPG data ─────────────────────────────────────────────────────────
        //
        // Keyed by epgChannelId. Each list contains exactly 4 pairs (title, description):
        //   index 0 — previous program (ended at top of current hour)
        //   index 1 — now playing     (started at top of current hour)
        //   index 2 — next program
        //   index 3 — program after next
        //
        // Channels without an entry (e.g. "netflix.channel") return an empty list.

        private val EPG_DATA: Map<String, List<Pair<String, String?>>> = mapOf(
            "bbc.world" to listOf(
                "World Business Report" to
                    "The latest news from financial markets and business around the world.",
                "The News at Ten" to
                    "Top stories and in-depth reports from the BBC's global correspondents.",
                "Outside Source" to
                    "BBC World Service journalists explain the stories everyone is talking about.",
                "HARDtalk" to
                    "In-depth interviews with world figures from politics, business, and culture.",
            ),
            "cnn.int" to listOf(
                "Quest Means Business" to
                    "Richard Quest brings you the latest news from the business world.",
                "Connect the World" to
                    "Becky Anderson reports live from Abu Dhabi on the day's top stories.",
                "The Situation Room" to
                    "Breaking news and political analysis with Wolf Blitzer.",
                "Anderson Cooper 360" to
                    "Anderson Cooper goes beyond the headlines with in-depth reporting.",
            ),
            "aljazeera.english" to listOf(
                "Inside Story" to
                    "An in-depth look at the stories and issues driving the global news agenda.",
                "The Stream" to
                    "Community-powered stories, conversation, and engagement about global events.",
                "News Hour" to
                    "The latest news and breaking stories from around the world.",
                "The Listening Post" to
                    "A critical look at the world's media and how the news is reported.",
            ),
            "espn" to listOf(
                "SportsCenter" to
                    "The latest scores, highlights, and analysis from the world of sports.",
                "NFL Live" to
                    "Expert analysis and breaking news from across the NFL.",
                "NBA Today" to
                    "Everything you need to know about the latest in basketball.",
                "First Take" to
                    "Stephen A. Smith and guests debate the biggest topics in sports.",
            ),
            "bein.sports.1" to listOf(
                "Serie A Review" to
                    "Highlights and analysis from the Italian top flight.",
                "Premier League Highlights" to
                    "The best goals and moments from the English Premier League weekend.",
                "UEFA Champions League" to
                    "Live coverage from Europe's premier club football competition.",
                "La Liga Match Day" to
                    "Build-up, live action, and post-match reaction from La Liga.",
            ),
            "sky.sports.main" to listOf(
                "Goals on Sunday" to
                    "Ben Shepherd and Chris Kamara review the weekend's Premier League action.",
                "Soccer Saturday" to
                    "Jeff Stelling anchors this classic live football results show.",
                "Super Sunday" to
                    "Live Premier League football with expert studio panel discussion.",
                "Monday Night Football" to
                    "Extended coverage of two top-of-the-table Premier League encounters.",
            ),
            "hbo" to listOf(
                "The Sopranos" to
                    "New Jersey mob boss Tony Soprano deals with personal and professional troubles.",
                "The Wire" to
                    "A complex portrait of the city of Baltimore through crime and law enforcement.",
                "Succession" to
                    "The Roy family's brutal power struggles over their global media empire.",
                "Game of Thrones" to
                    "Nine noble families fight for control over the mythical land of Westeros.",
            ),
            "natgeo" to listOf(
                "Blue Planet II" to
                    "David Attenborough explores the hidden world beneath the ocean's surface.",
                "Planet Earth" to
                    "A jaw-dropping portrait of life on our planet narrated by David Attenborough.",
                "Our Planet" to
                    "The story of the diversity of habitats and wildlife across the globe.",
                "Yellowstone" to
                    "An inside look at one of the world's most iconic national parks.",
            ),
            "discovery" to listOf(
                "Deadliest Catch" to
                    "Crab fishermen battle the harsh and unforgiving waters of the Bering Sea.",
                "MythBusters" to
                    "Adam and Jamie put popular myths and movie scenes to the ultimate scientific test.",
                "Dirty Jobs" to
                    "Mike Rowe tackles some of the messiest, most unpleasant jobs in America.",
                "Alaska: The Last Frontier" to
                    "The Kilcher family prepares to survive another brutal Alaskan winter.",
            ),
        )

        // ── Helper ───────────────────────────────────────────────────────────

        /**
         * Builds an [Episode] with [durationMinutes] converted to milliseconds.
         * The [coverUrl] uses the episode ID as a picsum seed, giving each episode a
         * unique but deterministic thumbnail image (480x270 widescreen aspect ratio).
         */
        private fun ep(
            id: String,
            title: String,
            episodeNumber: Int,
            seasonNumber: Int,
            durationMinutes: Int,
            plot: String,
        ) = Episode(
            id = id,
            title = title,
            episodeNumber = episodeNumber,
            seasonNumber = seasonNumber,
            plot = plot,
            durationMillis = durationMinutes * 60 * 1000L,
            containerExtension = "mkv",
            coverUrl = "https://picsum.photos/seed/$id/480/270",
        )
    }
}

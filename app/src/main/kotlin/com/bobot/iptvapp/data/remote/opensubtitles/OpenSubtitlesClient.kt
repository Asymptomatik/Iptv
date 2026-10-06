package com.bobot.iptvapp.data.remote.opensubtitles

import com.bobot.iptvapp.data.preferences.OpenSubtitlesApiKeyStore
import com.bobot.iptvapp.domain.model.OnlineSubtitle
import com.bobot.iptvapp.domain.model.OnlineSubtitleSearchResult
import com.bobot.iptvapp.domain.model.OnlineSubtitleSearchResult.Reason
import com.bobot.iptvapp.domain.model.SubtitleSearchContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Searches subtitles on the OpenSubtitles REST API v1 (`GET /subtitles`). Search only: no
 * download, no user login — the consumer key alone is enough to search.
 *
 * Kept apart from everything Xtream: its own [OkHttpClient] (see
 * [com.bobot.iptvapp.di.NetworkModule.provideOpenSubtitlesOkHttpClient], which carries no
 * interceptor, so no logger can ever print the `Api-Key` header) and its own base URL.
 *
 * ## Request shape (official "Search for subtitles" and "Best-Practices" pages, checked 2026-09-23)
 * - the key goes in the `Api-Key` header, never in the URL; `User-Agent` is `<App> v<version>`,
 *   or the API answers 403;
 * - movie → `query` = title, `type=movie`, `year` when known;
 *   episode → `query` = series title (the episode title when the series is unknown),
 *   `type=episode`, `season_number` / `episode_number` when known;
 * - one request per language of [preferredLanguages], in that order (see "One search per language");
 * - to avoid the API's redirects: parameters sorted alphabetically, values lower-case, `+` for
 *   spaces, defaults (page, ai/machine translation filters) never sent. The redirects that still
 *   happen are followed by hand, only back to the API host — see [executeFollowingAllowedRedirects].
 *
 * ## One search per language
 * The API answers one page of 60 results at a time (`total_pages` tells how many more) across every
 * language asked for: with `languages=en,fr`, a popular film's English subtitles can fill the whole
 * first page and leave the French ones on pages never read. Each language is therefore searched on
 * its own, French first, and only its own results are kept from its answer. A failure with nothing
 * found yet is reported as is, without asking the next language; once something was found, a later
 * failure only leaves its language out.
 *
 * ## Movie relevance
 * `query` is a full-text match: a movie search also returns sequels and namesakes. Every movie
 * result is checked against the searched title and year (see [movieMatch]); episodes are not.
 *
 * No logging anywhere in here, and [OnlineSubtitleSearchResult.Failed] carries no server text.
 * The answer is read up to [MAX_ANSWER_BYTES] only; a larger one is [Reason.INVALID_RESPONSE].
 */
class OpenSubtitlesClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val apiKeyStore: OpenSubtitlesApiKeyStore,
    private val userAgent: String,
    private val ioDispatcher: CoroutineDispatcher,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
    private val preferredLanguages: List<String> = DEFAULT_LANGUAGES,
) {

    suspend fun search(context: SubtitleSearchContext): OnlineSubtitleSearchResult {
        val apiKey = apiKeyStore.getApiKey() ?: return OnlineSubtitleSearchResult.Failed(Reason.MISSING_API_KEY)
        val found = mutableListOf<OnlineSubtitle>()
        for (language in preferredLanguages.map { it.lowercase(Locale.ROOT) }) {
            when (val result = searchLanguage(context, language, apiKey)) {
                is OnlineSubtitleSearchResult.Found -> found += result.subtitles
                OnlineSubtitleSearchResult.NoResults -> Unit
                is OnlineSubtitleSearchResult.Failed -> if (found.isEmpty()) return result else break
            }
        }
        return if (found.isEmpty()) OnlineSubtitleSearchResult.NoResults else OnlineSubtitleSearchResult.Found(found)
    }

    private suspend fun searchLanguage(
        context: SubtitleSearchContext,
        language: String,
        apiKey: String,
    ): OnlineSubtitleSearchResult {
        val request = Request.Builder()
            .url(searchUrl(context, language))
            .header("Api-Key", apiKey)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .get()
            .build()

        return withContext(ioDispatcher) {
            try {
                // The API redirects non-canonical queries: followed, but only back to the API host.
                val guarded = httpClient.executeFollowingAllowedRedirects(
                    request,
                    MAX_REDIRECTS,
                    OpenSubtitlesUrlPolicy::isAllowedApiUrl,
                )
                when (guarded) {
                    is GuardedResponse.RedirectRefused ->
                        OnlineSubtitleSearchResult.Failed(Reason.REDIRECT_REFUSED, httpCode = guarded.httpCode)
                    is GuardedResponse.Answered -> guarded.response.use { response ->
                        when {
                            response.isSuccessful -> response.body?.readAtMost(MAX_ANSWER_BYTES)
                                ?.let { parse(it, context, language) }
                                ?: OnlineSubtitleSearchResult.Failed(Reason.INVALID_RESPONSE)
                            else -> failureFor(response.code, response.header("ratelimit-reset"))
                        }
                    }
                }
            } catch (e: InterruptedIOException) {
                // SocketTimeoutException and OkHttp's call timeout both land here.
                OnlineSubtitleSearchResult.Failed(Reason.TIMEOUT)
            } catch (e: IOException) {
                OnlineSubtitleSearchResult.Failed(Reason.NETWORK)
            }
        }
    }

    private fun searchUrl(context: SubtitleSearchContext, language: String): HttpUrl {
        val params = sortedMapOf<String, String>()
        params["languages"] = language
        when (context.kind) {
            SubtitleSearchContext.Kind.MOVIE -> {
                params["query"] = context.title
                params["type"] = "movie"
                context.year?.let { params["year"] = it.toString() }
            }
            SubtitleSearchContext.Kind.EPISODE -> {
                params["query"] = context.seriesTitle ?: context.title
                params["type"] = "episode"
                context.seasonNumber?.let { params["season_number"] = it.toString() }
                context.episodeNumber?.let { params["episode_number"] = it.toString() }
            }
        }
        return baseUrl.newBuilder()
            .addPathSegment("subtitles")
            .apply { params.forEach { (name, value) -> addEncodedQueryParameter(name, encode(value)) } }
            .build()
    }

    /** Lower-case, form-encoded (`+` for spaces), commas kept literal as the reference shows them. */
    private fun encode(value: String): String =
        URLEncoder.encode(value.trim().lowercase(Locale.ROOT), Charsets.UTF_8.name()).replace("%2C", ",")

    /** The [language] results of [body] only: a server ignoring the filter must not mislabel the others. */
    private fun parse(body: String, context: SubtitleSearchContext, language: String): OnlineSubtitleSearchResult {
        val dto = try {
            json.decodeFromString(SubtitleSearchResponseDto.serializer(), body)
        } catch (e: SerializationException) {
            return OnlineSubtitleSearchResult.Failed(Reason.INVALID_RESPONSE)
        } catch (e: IllegalArgumentException) {
            return OnlineSubtitleSearchResult.Failed(Reason.INVALID_RESPONSE)
        }
        val subtitles = dto.data.mapNotNull { data ->
            val attributes = data.attributes?.takeIf { it.language.equals(language, ignoreCase = true) }
                ?: return@mapNotNull null
            val match = when (context.kind) {
                SubtitleSearchContext.Kind.MOVIE -> movieMatch(context, attributes) ?: return@mapNotNull null
                SubtitleSearchContext.Kind.EPISODE -> OnlineSubtitle.FeatureMatch.NOT_CHECKED
            }
            attributes.toOnlineSubtitle()?.copy(featureMatch = match)
        }
        if (subtitles.isEmpty()) return OnlineSubtitleSearchResult.NoResults
        // Stable sort: the server's own order survives, confirmed files first.
        return OnlineSubtitleSearchResult.Found(
            subtitles.sortedBy { it.featureMatch != OnlineSubtitle.FeatureMatch.CONFIRMED },
        )
    }

    /**
     * How [attributes] relate to the searched movie, or `null` when they name another one. The
     * API's `query` is a full-text match and happily returns sequels and namesakes, so this is the check.
     *
     * - a year more than [YEAR_TOLERANCE] away from a known one is another film;
     * - a release or file name naming another film (see [readLabel]) hides the result whatever the
     *   feature title says — equal or longer included: user-contributed metadata is often attached
     *   to the wrong feature, and a label contradicting it leaves nothing to trust;
     * - an equal title is [CONFIRMED][OnlineSubtitle.FeatureMatch.CONFIRMED];
     * - one title extending the other word for word (`Dune` / `Dune: Part One`) is the same film
     *   under a longer name only when both years are known and close, and the extra words carry
     *   no sequel sign (see [SEQUEL_WORDS]); otherwise it is a sibling of the franchise
     *   (`Avatar` / `Avatar: The Way of Water`), unknown year included;
     * - another known title may be a localised alias (`The Dinner Game` for `Le Dîner de Cons`),
     *   but also a plain namesake the full-text search dragged in. It is offered only when a
     *   release or file name names the searched title and none names another film (see [readLabel]).
     *   Limitation: an alias whose labels carry that alias only is hidden — the payload holds
     *   nothing else to tell it from another film;
     *   Out of reach too: a release carrying a localised name other than the searched spelling
     *   (feature title absent or not), since a label opening on an unknown title reads as another film —
     *   year or tag or not, as soon as it holds two title words (see [readLabel]);
     * - no title at all is judged on the labels alone: searched title named, or nothing readable,
     *   stays [UNCONFIRMED][OnlineSubtitle.FeatureMatch.UNCONFIRMED].
     *
     * Release and file names contradicting each other (one names the searched film, the other a
     * different one) are not offered: nothing tells which is right.
     */
    private fun movieMatch(context: SubtitleSearchContext, attributes: SubtitleAttributesDto): OnlineSubtitle.FeatureMatch? {
        val details = attributes.featureDetails
        val wantedYear = context.year
        val foundYear = details?.year?.takeIf { it > 0 }
        if (wantedYear != null && foundYear != null && abs(wantedYear - foundYear) > YEAR_TOLERANCE) return null
        val wanted = titleWords(context.title)
        val found = details?.title?.let(::titleWords)?.takeIf { it.isNotEmpty() }
        val (shorter, longer) = when {
            found == null -> emptyList<String>() to emptyList()
            found.size < wanted.size -> found to wanted
            else -> wanted to found
        }
        val extension = found != null && shorter.isNotEmpty() && longer.take(shorter.size) == shorter
        // A label naming the feature's own longer or shorter title (`Dune.Part.One.2021`) is no contradiction.
        val names = listOfNotNull(wanted, found?.takeIf { extension && it != wanted })
        val labels = (listOf(attributes.release) + attributes.files.map { it.fileName })
            .map { readLabel(it, names, wantedYear, found) }
        if (Label.OTHER_FILM in labels) return null
        if (found == null) return OnlineSubtitle.FeatureMatch.UNCONFIRMED
        if (found == wanted) return OnlineSubtitle.FeatureMatch.CONFIRMED
        if (extension) {
            val bothYearsKnown = wantedYear != null && foundYear != null
            val sequel = longer.drop(shorter.size).any(::isSequelSign)
            return if (bothYearsKnown && !sequel) OnlineSubtitle.FeatureMatch.UNCONFIRMED else null
        }
        return if (Label.SEARCHED_FILM in labels) OnlineSubtitle.FeatureMatch.UNCONFIRMED else null
    }

    /** What a release or file name says about the film it belongs to. */
    private enum class Label {
        /** The searched title — or the feature's longer or shorter form of it — then technical decorations only. */
        SEARCHED_FILM,

        /** A title followed by a release marker (see [isReleaseMarker]), or the searched title extended by other words. */
        OTHER_FILM,

        /** Absent, or no title part to read (`subtitle.srt`, `1080p.BluRay`, `subtitle.1998.1080p`, `French Full.srt`). */
        UNREADABLE,
    }

    /**
     * Reads [label] (a release or file name) once its extension, `[TAG]`s at either end and trailing
     * `-GROUP` are set aside — a tag naming another film (see [readTag]: `[Titanic 1997] Avatar.srt`)
     * makes the whole label name it. It names the searched film when it opens on one of the [names] (title
     * words, the searched ones first) and the whole rest is technical (see [isTechnicalSuffix]):
     * `Avatar.2009.1080p.BluRay.x264-GROUP` names `Avatar`; `Avatar.2009.The.Way.Of.Water.2022`,
     * `Avatar.1080p.2`, `Avatar.1995` and `Avatars.2009` name another film. Any other label is
     * unreadable unless a title part precedes a release marker (`Completely.Different.Film.2009`),
     * or — no marker at all — it holds at least [MIN_TITLE_WORDS] words that are neither decoration
     * nor filler (see [isTitleWord]: `Completely Different Film`, `Completely.Different.Film.srt`),
     * unless it holds the searched title or [featureTitle] surrounded by descriptive words only (see
     * [isDescriptiveWord]: `French Avatar.srt`, `The Dinner Game.srt` for that very feature) — any other
     * word around it is another title (`Completely Different Film Avatar.srt`). A leading site name
     * (see [LEADING_SITE]: `www.site.com - Avatar.srt`) is set aside first. A title part made of
     * descriptive words only (`subtitle.1998.1080p`, `French.Full.1998.1080p`) is no title, and such
     * a label is unreadable as long as its rest is technical; so are `subtitle.srt`, `French Full.srt`
     * and `Sous-titres français`.
     * Limitations: a decoration missing from the known lists (an unusual tag before or after the
     * searched title, `CD1`) makes the label read as another film; a film actually titled with
     * descriptive words only (`Subs`, `The Movie`) is read as unnamed; a marker-less label of a
     * single title word (`Titanic.srt`) or of
     * filler words only stays unreadable; a marker-less label carrying a localised alias of the
     * searched film (`The Dinner Game.srt` under the feature title `Le Dîner de Cons`) reads as
     * another film and hides the result — the payload holds nothing to tell an alias from another film.
     */
    private fun readLabel(label: String?, names: List<List<String>>, wantedYear: Int?, featureTitle: List<String>?): Label {
        val wanted = names.first()
        if (label.isNullOrBlank() || wanted.isEmpty()) return Label.UNREADABLE
        val parts = splitLabel(label)
        if (parts.tags.any { readTag(it, names, wantedYear, featureTitle) == Label.OTHER_FILM }) return Label.OTHER_FILM
        val words = titleWords(parts.core)
        val named = names.filter { words.take(it.size) == it }
        if (named.isNotEmpty()) {
            val technical = named.any { isTechnicalSuffix(words.drop(it.size), wantedYear) }
            return if (technical) Label.SEARCHED_FILM else Label.OTHER_FILM
        }
        val titlePart = words.takeWhile { !isReleaseMarker(it) }
        if (titlePart.isEmpty()) return Label.UNREADABLE
        if (titlePart.size == words.size) {
            val known = listOfNotNull(featureTitle) + names
            val decorated = known.any { name ->
                words.windowed(name.size).withIndex().any { (i, window) ->
                    window == name && (words.take(i) + words.drop(i + name.size)).all(::isDescriptiveWord)
                }
            }
            if (decorated) return Label.UNREADABLE
            return if (words.count(::isTitleWord) >= MIN_TITLE_WORDS) Label.OTHER_FILM else Label.UNREADABLE
        }
        val descriptive = titlePart.all(::isDescriptiveWord)
        return if (descriptive && isTechnicalSuffix(words.drop(titlePart.size), wantedYear)) Label.UNREADABLE else Label.OTHER_FILM
    }

    /**
     * What a `[TAG]` set aside at either end of a label says. A release group or site name (see
     * [GROUP_TAG]: `[YTS]`, `[YTS.MX]`, `[Erai-raws]`, `[www.site.com]`) is a decoration; a tag of technical words
     * holds at most one year, close enough to [wantedYear] (`[1080p]`, `[2009]` but not `[1995]`);
     * any other is read as a label of its own (see [readLabel]): `[Titanic 1997]` and, without
     * marker, `[Completely Different Film]` name another film; `[Avatar 2009]`, `[French 2009]`,
     * `[FR]`, `[French Subs]` and `[Avatar Extended]` don't.
     * Limitation: a group name of several words apart (`[Team Kaizoku]`) reads as another film,
     * and a one-token title (`[Spider-Man]`, `[Titanic.II]`) as a group — told apart by shape only.
     */
    private fun readTag(tag: String, names: List<List<String>>, wantedYear: Int?, featureTitle: List<String>?): Label {
        val words = titleWords(splitLabel(tag).core)
        if (words.none(::isReleaseMarker)) {
            return if (GROUP_TAG.matches(tag.trim())) Label.UNREADABLE else readLabel(tag, names, wantedYear, featureTitle)
        }
        if (words.all { YEAR_WORD.matches(it) || isTechnicalWord(it) }) {
            return if (isTechnicalSuffix(words, wantedYear)) Label.UNREADABLE else Label.OTHER_FILM
        }
        return readLabel(tag, names, wantedYear, featureTitle)
    }

    /** A word that may belong to a film title: no decoration, filler, number or single letter. */
    private fun isTitleWord(word: String): Boolean =
        word.length > 1 && !word.all(Char::isDigit) && !isDescriptiveWord(word)

    /** A word describing the subtitle rather than naming a film: technical tag, placeholder or filler (`French`, `Full`). */
    private fun isDescriptiveWord(word: String): Boolean =
        isTechnicalWord(word) || word in GENERIC_NAMES || word in FILLER_WORDS

    /**
     * A word that ends a title in a release name: year, resolution, source or codec. Language and
     * subtitle-format words don't count — `diner.fr.srt` is a file name, not a film called `Diner`.
     */
    private fun isReleaseMarker(word: String): Boolean =
        YEAR_WORD.matches(word) || RESOLUTION_WORD.matches(word) || word in SOURCE_TAGS

    /** A label's [core] and the contents of the `[TAG]`s set aside at either end of it, to be read apart (see [readTag]). */
    private class LabelParts(val core: String, val tags: List<String>)

    /**
     * [label] split into its bracketed tags at either end and the rest, the latter without file
     * extension, audio channel layout (`DDP5.1` → `DDP`) and a trailing `-GROUP` — this one only right
     * after a technical word, so a hyphenated title (`Spider-Man`) stays whole.
     */
    private fun splitLabel(label: String): LabelParts {
        val trimmed = label.trim()
        val leading = LEADING_BRACKET_TAGS.find(trimmed)?.value.orEmpty()
        val rest = trimmed.substring(leading.length)
            .replace(LEADING_SITE, "")
            .replace(FILE_EXTENSION, "")
        val trailing = TRAILING_BRACKET_TAGS.find(rest)?.value.orEmpty()
        val tags = BRACKET_TAG.findAll(leading + trailing).map { it.groupValues[1] }.toList()
        var core = rest.substring(0, rest.length - trailing.length)
            .replace(AUDIO_CHANNELS, "$1")
            .trim()
        val group = RELEASE_GROUP.find(core)
        if (group != null) {
            val before = core.substring(0, group.range.first)
            val lastWord = titleWords(before).lastOrNull()
            if (lastWord != null && (YEAR_WORD.matches(lastWord) || isTechnicalWord(lastWord))) core = before
        }
        return LabelParts(core, tags)
    }

    /**
     * Whether every word after the title is a decoration: technical tags (see [isTechnicalWord]) and
     * at most one year, within [YEAR_TOLERANCE] of [wantedYear] when known. Any other word — a
     * sequel's title, a number — makes it another film, wherever it hides.
     */
    private fun isTechnicalSuffix(suffix: List<String>, wantedYear: Int?): Boolean {
        val years = suffix.filter { YEAR_WORD.matches(it) }
        if (years.size > 1) return false
        val year = years.singleOrNull()?.toInt()
        if (year != null && wantedYear != null && abs(wantedYear - year) > YEAR_TOLERANCE) return false
        return suffix.all { YEAR_WORD.matches(it) || isTechnicalWord(it) }
    }

    private fun isTechnicalWord(word: String): Boolean =
        word in SOURCE_TAGS || word in RELEASE_TAGS ||
            RESOLUTION_WORD.matches(word) || AUDIO_WORD.matches(word) || BIT_DEPTH_WORD.matches(word)

    /** A number past one (`2`, `ii`, `deux`…) or a sequel word, in the extra words of a longer title. */
    private fun isSequelSign(word: String): Boolean =
        (word.all(Char::isDigit) && word.trimStart('0') != "1") ||
            (ROMAN_NUMERAL.matches(word) && word != "i") ||
            word in SEQUEL_WORDS

    /** Lower-case words of [title], accents and punctuation dropped: `"Le Dîner de Cons"` → `[le, diner, de, cons]`. */
    private fun titleWords(title: String): List<String> =
        Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .split(NON_WORD)
            .filter { it.isNotEmpty() }

    /**
     * The body as text, or `null` as soon as it is known to exceed [maxBytes] — announced or
     * streamed. Counts what [ResponseBody.source] yields, so a chunked answer (no length) and a
     * gzip one (decompressed transparently by OkHttp) are bounded on their real size.
     */
    private fun ResponseBody.readAtMost(maxBytes: Long): String? {
        if (contentLength() > maxBytes) return null
        val buffer = Buffer()
        val source = source()
        while (source.read(buffer, READ_CHUNK_BYTES) != -1L) {
            if (buffer.size > maxBytes) return null
        }
        return buffer.readUtf8()
    }

    /** `null` when there is nothing downloadable — a subtitle without a file id is useless here. */
    private fun SubtitleAttributesDto.toOnlineSubtitle(): OnlineSubtitle? {
        val file = files.firstOrNull { it.fileId != null } ?: return null
        return OnlineSubtitle(
            fileId = file.fileId!!,
            language = language.orEmpty().lowercase(Locale.ROOT),
            release = release,
            fileName = file.fileName,
            downloadCount = downloadCount,
            isHearingImpaired = hearingImpaired,
            isMachineTranslated = machineTranslated,
            isAiTranslated = aiTranslated,
            isFromTrusted = fromTrusted,
            featureTitle = featureDetails?.title,
            featureYear = featureDetails?.year,
            seasonNumber = featureDetails?.seasonNumber,
            episodeNumber = featureDetails?.episodeNumber,
        )
    }

    private fun failureFor(code: Int, rateLimitReset: String?): OnlineSubtitleSearchResult.Failed =
        when {
            code == 401 -> OnlineSubtitleSearchResult.Failed(Reason.UNAUTHORIZED, httpCode = code)
            code == 403 -> OnlineSubtitleSearchResult.Failed(Reason.FORBIDDEN, httpCode = code)
            code == 429 -> OnlineSubtitleSearchResult.Failed(
                Reason.RATE_LIMITED,
                retryAfterSeconds = rateLimitReset?.trim()?.toIntOrNull(),
                httpCode = code,
            )
            code in 500..599 -> OnlineSubtitleSearchResult.Failed(Reason.SERVER, httpCode = code)
            else -> OnlineSubtitleSearchResult.Failed(Reason.UNEXPECTED_HTTP, httpCode = code)
        }

    companion object {
        val DEFAULT_BASE_URL: HttpUrl = "https://${OpenSubtitlesUrlPolicy.API_HOST}/api/v1/".toHttpUrl()

        /** A canonicalising redirect takes one hop; a few more than that is a loop. */
        private const val MAX_REDIRECTS = 3

        /** A full 60-result page is a few hundred KiB; beyond 1 MiB the answer is not genuine. */
        private const val MAX_ANSWER_BYTES = 1024L * 1024
        private const val READ_CHUNK_BYTES = 8L * 1024

        /** French first; English as the fallback most catalogues actually have. */
        val DEFAULT_LANGUAGES: List<String> = listOf("fr", "en")

        /** Catalogues date a film by its local release, the provider by its first one: a year apart is common. */
        private const val YEAR_TOLERANCE = 1

        /**
         * Words that, past a shared title, mark another instalment. Not exhaustive: a sequel named
         * only by a subtitle of its own (`Avatar: The Way of Water`) is caught by its year instead.
         */
        private val SEQUEL_WORDS = setOf(
            "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "deux", "trois", "quatre", "cinq", "sept", "huit", "neuf", "dix",
            "second", "third", "deuxieme", "troisieme", "seconde",
            "returns", "reloaded", "revolutions", "resurrection", "revenge", "rises", "retour", "revanche",
            "sequel", "suite",
        )

        /**
         * Technical decorations a release or file name adds to a title besides [SOURCE_TAGS]:
         * dynamic range, language, edition, streaming service, subtitle format. Anything else after the title is read as
         * part of another title.
         */
        private val RELEASE_TAGS = setOf(
            "dl", "hdr", "hdr10", "dv", "uhd", "sdr",
            "multi", "french", "truefrench", "vff", "vfq", "vf", "vostfr", "subfrench", "vost", "fr", "eng",
            "extended", "unrated", "remastered", "proper", "repack", "imax", "internal", "limited",
            "amzn", "nf", "dsnp", "hmax", "atvp",
            "srt", "sub", "subs", "ass", "ssa", "vtt", "hi", "sdh", "cc", "forced",
        )

        /** Placeholder names an uploader gives a file instead of the film's title (`subtitle.1998.1080p`). */
        private val GENERIC_NAMES = setOf("subtitle", "subtitles", "sub", "subs", "file", "soustitre", "soustitres")

        /** Title words a marker-less label must hold to name another film: one alone is too often a placeholder. */
        private const val MIN_TITLE_WORDS = 2

        /**
         * Words a marker-less label uses to describe the subtitle rather than name a film: language,
         * audience, placeholder, article (`Sous-titres en français`, `Subtitles for the hearing impaired`).
         */
        private val FILLER_WORDS = setOf(
            "sous", "titre", "titres", "st", "full", "complete", "complet", "final", "version", "track", "piste",
            "default", "caption", "captions", "text", "texte", "movie", "film", "hearing", "impaired",
            "malentendants", "sourds", "english", "anglais", "francais", "francaise", "spanish", "espanol",
            "german", "deutsch", "italian", "italiano", "portuguese", "en", "fre", "fra", "es", "de", "it", "pt",
            "the", "a", "an", "for", "of", "and", "le", "la", "les", "des", "du", "et", "pour", "un", "une",
        )

        /** Source and codec: with a year or a resolution, what tells a release name from any file name. */
        private val SOURCE_TAGS = setOf(
            "bluray", "bdrip", "brrip", "dvdrip", "webrip", "webdl", "web", "hdtv", "hdrip", "remux",
            "x264", "x265", "h264", "h265", "hevc", "avc",
        )
        private val YEAR_WORD = Regex("(?:19|20)\\d{2}")
        private val RESOLUTION_WORD = Regex("\\d{3,4}[pi]|4k")
        private val AUDIO_WORD = Regex("(?:ddp|dd|eac3|ac3|aac|dts|truehd|atmos|flac|opus|mp3)\\d?")
        private val BIT_DEPTH_WORD = Regex("(?:8|10|12)bits?")
        private val ROMAN_NUMERAL = Regex("x{0,3}(?:ix|iv|v?i{0,3})")
        private val LEADING_BRACKET_TAGS = Regex("^(?:\\s*\\[[^\\]]*])+")
        private const val SITE = "(?:[\\p{L}\\p{N}-]+\\.)+(?:com|net|org|info|to|tv|io|co|cc|me|ws|biz|fr)\\b"

        /** `www.site.com - `, `http://site.net | `, `site.com - `: a bare domain counts only before a separator (`movie.fr` stays). */
        private val LEADING_SITE = Regex(
            "^\\s*(?:(?:https?://)?www\\.$SITE|https?://$SITE|$SITE(?=\\s*[-|:]))\\s*[-|:]*\\s*",
            RegexOption.IGNORE_CASE,
        )
        /**
         * The shape of a release group or site name in a `[TAG]`: one token, hyphens allowed, and at
         * most a two- or three-letter domain (`YTS`, `YTS.MX`, `Erai-raws`), or a full site name (`www.site.com`).
         */
        private val GROUP_TAG = Regex(
            "[\\p{L}\\p{N}]+(?:-[\\p{L}\\p{N}]+)*(?:\\.\\p{L}{2,3})?|(?:https?://)?(?:www\\.)?$SITE",
            RegexOption.IGNORE_CASE,
        )
        private val TRAILING_BRACKET_TAGS = Regex("(?:\\s*\\[[^\\]]*])+$")
        private val BRACKET_TAG = Regex("\\[([^\\]]*)]")
        private val FILE_EXTENSION = Regex("\\.(?:srt|sub|ass|ssa|vtt|smi|txt|mkv|mp4|avi)$", RegexOption.IGNORE_CASE)
        private val AUDIO_CHANNELS = Regex("(?i)(ddp|dd|eac3|ac3|aac|dts|truehd|flac|opus)\\s?[257][.\\s][01](?!\\d)")
        private val RELEASE_GROUP = Regex("-[\\p{L}\\p{N}]+$")

        private val COMBINING_MARKS = Regex("\\p{M}+")
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    }
}

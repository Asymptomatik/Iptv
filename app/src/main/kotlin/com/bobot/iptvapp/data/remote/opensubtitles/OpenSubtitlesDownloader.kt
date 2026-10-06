package com.bobot.iptvapp.data.remote.opensubtitles

import com.bobot.iptvapp.data.preferences.OpenSubtitlesApiKeyStore
import com.bobot.iptvapp.domain.model.ExternalSubtitle
import com.bobot.iptvapp.domain.model.OnlineSubtitle
import com.bobot.iptvapp.domain.model.OnlineSubtitleDownloadResult
import com.bobot.iptvapp.domain.model.OnlineSubtitleDownloadResult.Reason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Downloads one subtitle the user picked from an OpenSubtitles search, into app-private storage.
 *
 * ## Contract (official "Download" page, checked 2026-09-23)
 * `POST /api/v1/download` with `{"file_id": <int32>}` and the `Api-Key` header answers a temporary
 * `link` (valid 3 hours) plus the remaining quota; the file behind the link is SRT, UTF-8 by
 * default. No user login in this lot, so no `Authorization` header: the key alone gives the small
 * per-IP daily quota, and a 406 with the quota body says it is spent.
 *
 * ## What is refused
 * - the `/download` call never follows a redirect: a POST is not re-sent, and the key stays on the API host;
 * - the link must pass [OpenSubtitlesUrlPolicy.isAllowedLinkUrl], and so must every redirect it
 *   takes; it is fetched **without** the key;
 * - both bodies are read up to a bound, never past it; archives and binaries are refused, not unpacked;
 * - the text is re-encoded to UTF-8 (strict UTF-16 when a UTF-16 BOM says so, else strict UTF-8
 *   first, CP1252 otherwise), BOM dropped; NUL bytes outside UTF-16 are refused;
 * - the UTF-8 result must hold a cue the player would show, and no timecode that would make it
 *   fail the whole track ([SrtCueValidator]) — otherwise nothing is stored.
 *
 * `cueHtml` renders a cue's markup for that last check: [AndroidCueHtml], the player's own call,
 * outside tests.
 *
 * Only the file id goes out — nothing from the Xtream account. No logging anywhere in here.
 */
class OpenSubtitlesDownloader(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val apiKeyStore: OpenSubtitlesApiKeyStore,
    private val userAgent: String,
    private val fileStore: OnlineSubtitleFileStore,
    private val ioDispatcher: CoroutineDispatcher,
    cueHtml: CueHtml,
    private val baseUrl: HttpUrl = OpenSubtitlesClient.DEFAULT_BASE_URL,
) {

    private val cueValidator = SrtCueValidator(cueHtml)

    /**
     * @param sessionGeneration [com.bobot.iptvapp.data.logout.LogoutCoordinator.sessionGeneration]
     *   read when the user picked [subtitle]; the file is only written if it still holds — see
     *   [OnlineSubtitleFileStore.save].
     * @param visit the player visit the file belongs to, and goes with.
     */
    suspend fun download(
        subtitle: OnlineSubtitle,
        sessionGeneration: Int,
        visit: OnlineSubtitleVisit,
    ): OnlineSubtitleDownloadResult {
        val apiKey = apiKeyStore.getApiKey() ?: return failed(Reason.MISSING_API_KEY)
        if (subtitle.fileId !in 1..Int.MAX_VALUE.toLong()) return failed(Reason.INVALID_FILE_ID)

        return withContext(ioDispatcher) {
            try {
                val answer = requestLink(apiKey, subtitle.fileId)
                val content = toUtf8Srt(fetchFile(answer.link))
                val file = try {
                    fileStore.save(subtitle.fileId, subtitle.language, content, sessionGeneration, visit)
                } catch (e: IOException) {
                    throw Abort(Reason.STORAGE)
                } ?: throw Abort(Reason.SESSION_ENDED)
                OnlineSubtitleDownloadResult.Downloaded(
                    subtitle = ExternalSubtitle(url = file.toURI().toString(), language = subtitle.language.ifBlank { null }),
                    remainingDownloads = answer.remaining,
                )
            } catch (abort: Abort) {
                abort.failed
            } catch (e: InterruptedIOException) {
                // SocketTimeoutException and OkHttp's call timeout both land here.
                failed(Reason.TIMEOUT)
            } catch (e: IOException) {
                failed(Reason.NETWORK)
            }
        }
    }

    private class LinkAnswer(val link: HttpUrl, val remaining: Int?)

    private fun requestLink(apiKey: String, fileId: Long): LinkAnswer {
        val body = json.encodeToString(DownloadRequestDto.serializer(), DownloadRequestDto(fileId))
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("download").build())
            .header("Api-Key", apiKey)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .post(body.toRequestBody(JSON))
            .build()

        val response = when (val guarded = httpClient.executeFollowingAllowedRedirects(request, 0) { false }) {
            is GuardedResponse.RedirectRefused -> throw Abort(Reason.REDIRECT_REFUSED, httpCode = guarded.httpCode)
            is GuardedResponse.Answered -> guarded.response
        }
        response.use {
            val text = it.body?.readAtMost(MAX_ANSWER_BYTES)?.toString(Charsets.UTF_8)
            if (!it.isSuccessful) throw Abort(downloadCallFailure(it.code, text, it.header("ratelimit-reset")))
            val dto = text?.let(::parseOrNull) ?: throw Abort(Reason.INVALID_RESPONSE)
            val rawLink = dto.link?.takeIf { link -> link.isNotBlank() } ?: throw Abort(Reason.INVALID_RESPONSE)
            val link = rawLink.toHttpUrlOrNull()
                ?.takeIf { url -> OpenSubtitlesUrlPolicy.isAllowedLinkUrl(url) }
                ?: throw Abort(Reason.UNTRUSTED_LINK)
            return LinkAnswer(link, dto.remaining)
        }
    }

    private fun fetchFile(link: HttpUrl): ByteArray {
        // Deliberately no Api-Key: the link is its own credential.
        val request = Request.Builder()
            .url(link)
            .header("User-Agent", userAgent)
            .get()
            .build()

        val guarded = httpClient.executeFollowingAllowedRedirects(
            request,
            MAX_LINK_REDIRECTS,
            OpenSubtitlesUrlPolicy::isAllowedLinkUrl,
        )
        val response = when (guarded) {
            is GuardedResponse.RedirectRefused -> throw Abort(Reason.REDIRECT_REFUSED, httpCode = guarded.httpCode)
            is GuardedResponse.Answered -> guarded.response
        }
        response.use {
            if (it.code == 410) throw Abort(Reason.LINK_EXPIRED, httpCode = 410)
            if (!it.isSuccessful) throw Abort(httpFailure(it.code, it.header("ratelimit-reset")))
            return it.body?.readAtMost(MAX_FILE_BYTES) ?: throw Abort(Reason.TOO_LARGE)
        }
    }

    /** [raw] as UTF-8 SRT bytes, or an [Abort] when it is not a subtitle this app can play. */
    private fun toUtf8Srt(raw: ByteArray): ByteArray {
        if (raw.startsWith(GZIP_MAGIC) || raw.startsWith(ZIP_MAGIC)) throw Abort(Reason.UNSUPPORTED_FORMAT)
        // UTF-16 is full of NUL bytes, so it is told apart by its mark before those are refused.
        val utf16 = when {
            raw.startsWith(UTF16LE_BOM) -> Charsets.UTF_16LE
            raw.startsWith(UTF16BE_BOM) -> Charsets.UTF_16BE
            else -> null
        }
        val text = if (utf16 != null) {
            decodeStrict(raw.copyOfRange(UTF16LE_BOM.size, raw.size), utf16) ?: throw Abort(Reason.UNSUPPORTED_FORMAT)
        } else {
            val bytes = if (raw.startsWith(UTF8_BOM)) raw.copyOfRange(UTF8_BOM.size, raw.size) else raw
            if (bytes.any { it == 0.toByte() }) throw Abort(Reason.UNSUPPORTED_FORMAT)
            decodeStrict(bytes, Charsets.UTF_8) ?: String(bytes, CP1252)
        }
        // A decoded NUL is binary all the same (UTF-32 read as UTF-16, for one).
        if ('\u0000' in text) throw Abort(Reason.UNSUPPORTED_FORMAT)
        // CP1252 → UTF-8 can grow the text up to threefold, UTF-16 by half: bounded again after conversion.
        val utf8 = text.toByteArray(Charsets.UTF_8).also { if (it.size > MAX_STORED_BYTES) throw Abort(Reason.TOO_LARGE) }
        // Checked on the very bytes stored, so the verdict is the player's.
        if (!cueValidator.hasPlayableCue(utf8)) throw Abort(Reason.UNSUPPORTED_FORMAT)
        return utf8
    }

    /** [bytes] in [charset], or `null` at the first malformed or truncated sequence. */
    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun parseOrNull(text: String): DownloadResponseDto? = try {
        json.decodeFromString(DownloadResponseDto.serializer(), text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** 406 means two different things on `/download`; the body tells them apart. */
    private fun downloadCallFailure(code: Int, body: String?, rateLimitReset: String?): OnlineSubtitleDownloadResult.Failed {
        if (code != 406) return httpFailure(code, rateLimitReset)
        val dto = body?.takeIf { it.isNotBlank() }?.let(::parseOrNull)
        return when {
            dto?.message.equals(INVALID_FILE_ID_MESSAGE, ignoreCase = true) -> failed(Reason.INVALID_FILE_ID, httpCode = code)
            dto?.remaining != null -> failed(Reason.QUOTA_EXCEEDED, httpCode = code)
            else -> failed(Reason.UNEXPECTED_HTTP, httpCode = code)
        }
    }

    private fun httpFailure(code: Int, rateLimitReset: String?): OnlineSubtitleDownloadResult.Failed =
        when {
            code == 401 -> failed(Reason.UNAUTHORIZED, httpCode = code)
            code == 403 -> failed(Reason.FORBIDDEN, httpCode = code)
            code == 429 -> OnlineSubtitleDownloadResult.Failed(
                Reason.RATE_LIMITED,
                retryAfterSeconds = rateLimitReset?.trim()?.toIntOrNull(),
                httpCode = code,
            )
            code in 500..599 -> failed(Reason.SERVER, httpCode = code)
            else -> failed(Reason.UNEXPECTED_HTTP, httpCode = code)
        }

    /** The body, or `null` as soon as it is known to exceed [maxBytes] — announced or streamed. */
    private fun ResponseBody.readAtMost(maxBytes: Long): ByteArray? {
        if (contentLength() > maxBytes) return null
        val buffer = Buffer()
        val source = source()
        while (source.read(buffer, READ_CHUNK_BYTES) != -1L) {
            if (buffer.size > maxBytes) return null
        }
        return buffer.readByteArray()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /** Ends the download with [failed]; never escapes this class. */
    private class Abort(val failed: OnlineSubtitleDownloadResult.Failed) : Exception(null, null, false, false) {
        constructor(reason: Reason, httpCode: Int? = null) : this(OnlineSubtitleDownloadResult.Failed(reason, httpCode = httpCode))
    }

    private fun failed(reason: Reason, httpCode: Int? = null) = OnlineSubtitleDownloadResult.Failed(reason, httpCode = httpCode)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val CP1252: Charset = Charset.forName("windows-1252")

        /** The `/download` answer is a few hundred bytes. */
        const val MAX_ANSWER_BYTES = 64L * 1024

        /** A feature-length SRT is ~100 KiB; 2 MiB leaves room for anything genuine. */
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        const val MAX_STORED_BYTES = 3 * 1024 * 1024
        const val READ_CHUNK_BYTES = 8L * 1024

        /** A mirror hop or two on the download host; more is a loop. */
        const val MAX_LINK_REDIRECTS = 3

        const val INVALID_FILE_ID_MESSAGE = "Invalid file_id"

        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        val GZIP_MAGIC = byteArrayOf(0x1f, 0x8b.toByte())
        val ZIP_MAGIC = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
    }
}

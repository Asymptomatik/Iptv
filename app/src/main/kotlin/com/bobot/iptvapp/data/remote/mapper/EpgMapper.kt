package com.bobot.iptvapp.data.remote.mapper

import android.util.Base64
import com.bobot.iptvapp.data.remote.dto.EpgListingDto
import com.bobot.iptvapp.data.remote.dto.EpgProgramDto
import com.bobot.iptvapp.domain.model.EpgProgram
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Maps an [EpgProgramDto] to an [EpgProgram] domain model.
 *
 * **Base64 decoding**: The Xtream Codes `get_short_epg` endpoint Base64-encodes the
 * [title][EpgProgramDto.title] and [description][EpgProgramDto.description] fields.
 * Both are decoded via [decodeBase64OrSelf], which returns the input unchanged if
 * decoding fails (e.g. for servers that return plain text instead).
 *
 * **Timestamp conversion**: [EpgProgramDto.startTimestamp] and [stopTimestamp] are
 * epoch SECONDS as quoted strings. The mapper multiplies by 1000 to produce epoch
 * MILLIS required by [EpgProgram]. When the timestamp fields are absent, the mapper
 * falls back to parsing the [start][EpgProgramDto.start] and [end][EpgProgramDto.end]
 * datetime strings (format "yyyy-MM-dd HH:mm:ss", UTC).
 *
 * **Stable ID strategy**: [EpgProgram] has no `id` field. The domain model uses
 * the composite `(channelId, startMillis)` pair as a stable natural key for Room
 * persistence (Task 10).
 */
fun EpgProgramDto.toDomain(): EpgProgram {
    val startMs = startTimestamp?.toLongOrNull()?.let { it * 1_000L }
        ?: parseEpgDateTime(start)
        ?: 0L
    val endMs = stopTimestamp?.toLongOrNull()?.let { it * 1_000L }
        ?: parseEpgDateTime(end)
        ?: 0L

    return EpgProgram(
        channelId = channelId,
        title = title.decodeBase64OrSelf(),
        description = description?.decodeBase64OrSelf()?.takeIf { it.isNotBlank() },
        startMillis = startMs,
        endMillis = endMs,
    )
}

/**
 * Maps the listings inside an [EpgListingDto] to a list of [EpgProgram] domain models.
 */
fun EpgListingDto.toDomain(): List<EpgProgram> = epgListings.map { it.toDomain() }

// ─────────────────────────────────────────────────────────────────────────────
// Internal helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Attempts to decode [this] string as Base64, returning the decoded UTF-8 content.
 * Returns [this] unchanged when decoding fails or the result is blank.
 *
 * Uses [android.util.Base64] (available on all API levels) rather than
 * [java.util.Base64] (requires API 26+) to maintain minSdk 24 compatibility.
 * [Base64.DEFAULT] handles both standard and URL-safe Base64 with optional padding.
 */
internal fun String.decodeBase64OrSelf(): String {
    if (isBlank()) return this
    return try {
        val decoded = Base64.decode(this, Base64.DEFAULT).decodeToString()
        decoded.takeIf { it.isNotBlank() } ?: this
    } catch (_: Exception) {
        this
    }
}

/**
 * Parses a datetime string in the format `"yyyy-MM-dd HH:mm:ss"` (UTC) to epoch millis.
 * Returns `null` when [dateStr] is null, blank, or cannot be parsed.
 *
 * A new [SimpleDateFormat] is created per call to avoid thread-safety issues.
 */
internal fun parseEpgDateTime(dateStr: String?): Long? {
    if (dateStr.isNullOrBlank()) return null
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(dateStr)
            ?.time
    } catch (_: Exception) {
        null
    }
}

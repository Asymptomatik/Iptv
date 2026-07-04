package com.bobot.iptvapp.domain.model

/**
 * A single Electronic Programme Guide (EPG) entry for a live channel.
 *
 * EPG programs describe what is currently airing or scheduled to air on a
 * [Channel]. They are displayed in the channel detail screen and optionally
 * as an overlay during live playback.
 *
 * [EpgProgram] records are correlated to [Channel]s via [channelId] matching
 * [Channel.epgChannelId]. A channel with a null [Channel.epgChannelId] has no
 * EPG data available.
 *
 * Time fields use epoch-millisecond Long values — see [Movie] for the rationale.
 * The "now playing" program for a channel is the record where:
 *   [startMillis] <= System.currentTimeMillis() < [endMillis]
 *
 * Sourced from: Xtream Codes `get_short_epg` and `get_epg` endpoints.
 * Mapped from network DTOs in Task 6. Persisted as Room entities in Task 10.
 *
 * @property channelId    Xtream `epg_channel_id` — matches [Channel.epgChannelId].
 * @property title        Programme title (e.g. "The News at Ten").
 * @property description  Programme synopsis or description. Null when absent.
 * @property startMillis  Programme start time as epoch milliseconds (UTC).
 * @property endMillis    Programme end time as epoch milliseconds (UTC).
 */
data class EpgProgram(
    val channelId: String,
    val title: String,
    val description: String?,
    val startMillis: Long,
    val endMillis: Long,
)

package com.bobot.iptvapp.domain.model

/**
 * A live broadcast stream (linear TV, radio, or sports channel).
 *
 * Channels are the domain representation of [ContentType.LIVE] content.
 * They appear in category rows on the home screen and link to the fullscreen
 * player. EPG data for a channel is retrieved separately via [EpgProgram]
 * records matched on [epgChannelId].
 *
 * Sourced from: Xtream Codes `get_live_streams` endpoint (filtered by category).
 * Mapped from network DTOs in Task 6. Persisted as Room entities in Task 10.
 *
 * @property id           Domain identifier — the string form of the Xtream Codes
 *                        `stream_id` integer. Used as the `contentId` argument in
 *                        [com.bobot.iptvapp.navigation.Detail] and [com.bobot.iptvapp.navigation.Player].
 * @property name         Display name of the channel.
 * @property logoUrl      Remote URL of the channel logo / icon. Null when the
 *                        Xtream server returns an empty or absent `stream_icon`.
 * @property categoryId   Foreign key to [Category.id] for grouping in home rows.
 * @property epgChannelId Xtream `epg_channel_id` string used to correlate this
 *                        channel with [EpgProgram.channelId] records. Null when
 *                        the server provides no EPG mapping for this stream.
 */
data class Channel(
    val id: String,
    val name: String,
    val logoUrl: String?,
    val categoryId: String,
    val epgChannelId: String?,
)

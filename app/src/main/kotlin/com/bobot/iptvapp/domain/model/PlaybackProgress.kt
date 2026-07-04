package com.bobot.iptvapp.domain.model

/**
 * Persisted playback position for a piece of content, scoped to a [Profile].
 *
 * [PlaybackProgress] records power the "Continue Watching" / resume feature.
 * When the player exits, the current position and total duration are written to
 * a progress record. The home screen reads the most recent records per profile
 * and surfaces them in a "Continue Watching" row.
 *
 * The composite key ([contentId], [contentType], [profileId]) is unique — one
 * progress record per content item per profile.
 *
 * [contentType] determines how [contentId] is interpreted:
 *  - [ContentType.LIVE]   — [contentId] = [Channel.id]  (resume is less meaningful
 *                           for live TV but the record may still be written)
 *  - [ContentType.MOVIE]  — [contentId] = [Movie.id]
 *  - [ContentType.SERIES] — [contentId] = [Episode.id]  (progress is per episode)
 *
 * Time fields use epoch-millisecond Long values — see [Movie] for the rationale.
 *
 * Sourced from: local Room database only (Task 10). Written by the player (Task 13).
 * Read by the home screen repository (Task 11).
 *
 * @property contentId         Identifier of the content item being tracked.
 *                             Interpretation depends on [contentType] — see above.
 * @property contentType       Classifies what [contentId] refers to.
 * @property positionMillis    Last known playback position in milliseconds.
 * @property durationMillis    Total duration of the content in milliseconds. Used to
 *                             compute the completion percentage for the progress bar.
 *                             Zero when duration is unknown.
 * @property lastUpdatedMillis Epoch-millisecond timestamp of when this record was last
 *                             written. Used to sort "Continue Watching" rows by recency.
 * @property profileId         Foreign key to [Profile.id] — scopes this record to a
 *                             specific user profile.
 */
data class PlaybackProgress(
    val contentId: String,
    val contentType: ContentType,
    val positionMillis: Long,
    val durationMillis: Long,
    val lastUpdatedMillis: Long,
    val profileId: String,
)

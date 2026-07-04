package com.bobot.iptvapp.domain.model

/**
 * A lightweight domain model representing a single item on a user's "My List" (favorites).
 *
 * [FavoriteItem] intentionally carries only the key fields needed to identify and sort
 * the item — it does NOT embed full content metadata (title, poster URL, etc.).
 *
 * ## Why no content metadata?
 * [com.bobot.iptvapp.data.local.entity.FavoriteEntity] stores only the composite key and
 * a timestamp. Embedding full metadata would require either:
 *  - duplicating it in the favorites table (stale data risk on catalog refresh), or
 *  - a JOIN query that couples the favorites DAO to catalog entities.
 *
 * Instead, ViewModels rendering the "My List" row combine favorites from
 * [com.bobot.iptvapp.domain.repository.FavoritesRepository] with full content objects
 * from [com.bobot.iptvapp.domain.repository.CatalogRepository], matched on
 * [contentId] + [contentType].
 *
 * @property profileId   The profile this favorite belongs to.
 * @property contentId   Identifier of the favorited content item. Interpretation depends
 *                       on [contentType]: movie stream ID, episode ID, or channel ID.
 * @property contentType Classifies what [contentId] refers to — determines which catalog
 *                       source to query for full metadata.
 * @property addedAt     Epoch-millisecond timestamp when the item was added to "My List".
 *                       Used to order the favorites row by most recently added.
 */
data class FavoriteItem(
    val profileId: String,
    val contentId: String,
    val contentType: ContentType,
    val addedAt: Long,
)

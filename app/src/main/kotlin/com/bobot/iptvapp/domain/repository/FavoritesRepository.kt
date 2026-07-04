package com.bobot.iptvapp.domain.repository

import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.FavoriteItem
import kotlinx.coroutines.flow.Flow

/**
 * Domain-layer contract for managing user favorites ("My List" feature).
 *
 * Favorites are scoped per profile — each [profileId] has its own independent list.
 * The [FavoriteItem] domain model carries only the key + timestamp. Full content metadata
 * (title, poster) is fetched by joining with [CatalogRepository] in the ViewModel.
 *
 * ## Toggle pattern
 * Call [toggleFavorite] on a UI action — the repository checks the current state via
 * [isFavorite] and adds or removes the item accordingly.
 *
 * ## Profile deletion
 * When a profile is deleted, call [clearFavorites] to remove all associated favorite
 * records. This is a responsibility of the caller (typically a ViewModel or use-case),
 * not handled automatically by the repository.
 *
 * ## Implementation
 * @see com.bobot.iptvapp.data.repository.FavoritesRepositoryImpl
 */
interface FavoritesRepository {

    /**
     * Observes all favorites for [profileId], ordered by most recently added first.
     * Emits a new list whenever the favorites table changes for that profile.
     */
    fun observeFavorites(profileId: String): Flow<List<FavoriteItem>>

    /**
     * Toggles the favorite state of a content item for a profile.
     *
     * - If the item is currently a favorite → removes it.
     * - If the item is not a favorite → adds it with the current timestamp.
     *
     * @param profileId   The profile performing the action.
     * @param contentId   Identifier of the content item.
     * @param contentType Classifies the content item (LIVE, MOVIE, or SERIES).
     */
    suspend fun toggleFavorite(profileId: String, contentId: String, contentType: ContentType)

    /**
     * Observes whether a specific content item is in a profile's favorites list.
     * Emits `true` when the item is a favorite, `false` when it is not.
     *
     * Collect this to drive heart/bookmark toggle icons without polling.
     */
    fun isFavorite(profileId: String, contentId: String, contentType: ContentType): Flow<Boolean>

    /**
     * Deletes all favorite records for [profileId].
     *
     * Intended to be called alongside [ProfileRepository.deleteProfile] when a profile
     * is removed, to prevent orphaned favorite rows.
     */
    suspend fun clearFavorites(profileId: String)
}

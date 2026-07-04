package com.bobot.iptvapp.data.repository

import com.bobot.iptvapp.data.local.dao.FavoriteDao
import com.bobot.iptvapp.data.local.entity.FavoriteEntity
import com.bobot.iptvapp.data.local.mapper.toDomain
import com.bobot.iptvapp.di.IoDispatcher
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.FavoriteItem
import com.bobot.iptvapp.domain.repository.FavoritesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Room-backed implementation of [FavoritesRepository].
 *
 * All database operations delegate to [FavoriteDao]. Entity-domain mapping is handled
 * by the extension functions in `data.local.mapper.FavoriteMapper`. All IO is dispatched
 * on [ioDispatcher].
 *
 * ## Toggle implementation
 * [toggleFavorite] checks the current state via [FavoriteDao.isFavorite].first() and
 * either inserts or deletes accordingly. These two operations are NOT wrapped in a
 * database transaction; a concurrent toggle (two UI events in very quick succession)
 * could theoretically produce duplicate inserts, but [FavoriteDao.insert] uses
 * `OnConflictStrategy.REPLACE`, so the result is always a single correct row.
 *
 * ## Hilt binding
 * Bound to [FavoritesRepository] in [com.bobot.iptvapp.di.LocalRepositoryModule].
 */
class FavoritesRepositoryImpl @Inject constructor(
    private val favoriteDao: FavoriteDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : FavoritesRepository {

    override fun observeFavorites(profileId: String): Flow<List<FavoriteItem>> =
        favoriteDao.observeFavorites(profileId)
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(ioDispatcher)

    override suspend fun toggleFavorite(
        profileId: String,
        contentId: String,
        contentType: ContentType,
    ) {
        withContext(ioDispatcher) {
            val isFav = favoriteDao.isFavorite(profileId, contentId, contentType.name).first()
            if (isFav) {
                favoriteDao.deleteByKeys(profileId, contentId, contentType.name)
            } else {
                favoriteDao.insert(
                    FavoriteEntity(
                        profileId = profileId,
                        contentId = contentId,
                        contentType = contentType.name,
                        addedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    override fun isFavorite(
        profileId: String,
        contentId: String,
        contentType: ContentType,
    ): Flow<Boolean> =
        favoriteDao.isFavorite(profileId, contentId, contentType.name)
            .flowOn(ioDispatcher)

    override suspend fun clearFavorites(profileId: String) {
        withContext(ioDispatcher) {
            favoriteDao.clearByProfileId(profileId)
        }
    }
}

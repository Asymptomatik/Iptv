package com.bobot.iptvapp.data.repository

import com.bobot.iptvapp.data.local.dao.PlaybackProgressDao
import com.bobot.iptvapp.data.local.mapper.toDomain
import com.bobot.iptvapp.data.local.mapper.toEntity
import com.bobot.iptvapp.di.IoDispatcher
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.PlaybackProgress
import com.bobot.iptvapp.domain.repository.PlaybackProgressRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Room-backed implementation of [PlaybackProgressRepository].
 *
 * All database operations delegate to [PlaybackProgressDao]. Entity-domain mapping is
 * handled by the extension functions in `data.local.mapper.PlaybackProgressMapper`.
 * All IO is dispatched on [ioDispatcher].
 *
 * [observeContinueWatching] passes [limit] directly to the DAO query so the LIMIT clause
 * is applied in SQLite — the result set is bounded at the database level rather than
 * by in-memory filtering. This was the Task 10 review carry-forward fix.
 *
 * ## Hilt binding
 * Bound to [PlaybackProgressRepository] in [com.bobot.iptvapp.di.LocalRepositoryModule].
 */
class PlaybackProgressRepositoryImpl @Inject constructor(
    private val progressDao: PlaybackProgressDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PlaybackProgressRepository {

    override suspend fun upsertProgress(progress: PlaybackProgress) {
        withContext(ioDispatcher) {
            progressDao.upsert(progress.toEntity())
        }
    }

    override suspend fun getProgress(
        profileId: String,
        contentId: String,
        contentType: ContentType,
    ): PlaybackProgress? =
        withContext(ioDispatcher) {
            progressDao.getProgress(profileId, contentId, contentType.name)?.toDomain()
        }

    override fun observeContinueWatching(
        profileId: String,
        limit: Int,
    ): Flow<List<PlaybackProgress>> =
        progressDao.observeContinueWatching(profileId, limit)
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(ioDispatcher)

    override suspend fun deleteProgress(
        profileId: String,
        contentId: String,
        contentType: ContentType,
    ) {
        withContext(ioDispatcher) {
            progressDao.deleteByKeys(profileId, contentId, contentType.name)
        }
    }

    override suspend fun clearProgress(profileId: String) {
        withContext(ioDispatcher) {
            progressDao.clearByProfileId(profileId)
        }
    }
}

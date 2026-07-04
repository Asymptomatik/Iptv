package com.bobot.iptvapp.data.repository

import com.bobot.iptvapp.data.local.dao.ProfileDao
import com.bobot.iptvapp.data.local.mapper.toDomain
import com.bobot.iptvapp.data.local.mapper.toEntity
import com.bobot.iptvapp.di.IoDispatcher
import com.bobot.iptvapp.domain.model.Profile
import com.bobot.iptvapp.domain.repository.ProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/**
 * Room-backed implementation of [ProfileRepository].
 *
 * All database operations delegate to [ProfileDao]. Entity-domain mapping is handled
 * by the extension functions in `data.local.mapper.ProfileMapper`. All IO is dispatched
 * on [ioDispatcher] — injected for testability.
 *
 * Profile creation generates a new UUID string as the [Profile.id]. The generated ID
 * is returned as part of the created [Profile] so callers can immediately reference it
 * (e.g. to set as the active profile in [com.bobot.iptvapp.data.preferences.AppPreferencesStore]).
 *
 * ## Hilt binding
 * Bound to [ProfileRepository] in [com.bobot.iptvapp.di.LocalRepositoryModule].
 */
class ProfileRepositoryImpl @Inject constructor(
    private val profileDao: ProfileDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ProfileRepository {

    override fun observeProfiles(): Flow<List<Profile>> =
        profileDao.getAll()
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(ioDispatcher)

    override suspend fun getProfile(id: String): Profile? =
        withContext(ioDispatcher) {
            profileDao.getById(id)?.toDomain()
        }

    override suspend fun createProfile(name: String, avatarUrl: String?): Profile =
        withContext(ioDispatcher) {
            val profile = Profile(
                id = UUID.randomUUID().toString(),
                name = name,
                avatarUrl = avatarUrl,
            )
            profileDao.upsert(profile.toEntity())
            profile
        }

    override suspend fun updateProfile(profile: Profile): Profile =
        withContext(ioDispatcher) {
            profileDao.upsert(profile.toEntity())
            profile
        }

    override suspend fun deleteProfile(id: String) {
        withContext(ioDispatcher) {
            profileDao.deleteById(id)
        }
    }
}

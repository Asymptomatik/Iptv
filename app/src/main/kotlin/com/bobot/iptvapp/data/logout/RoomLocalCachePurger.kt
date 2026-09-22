package com.bobot.iptvapp.data.logout

import com.bobot.iptvapp.data.local.dao.CatalogCacheDao
import com.bobot.iptvapp.data.local.dao.DownloadDao
import com.bobot.iptvapp.data.local.dao.EpgDao
import com.bobot.iptvapp.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [LocalCachePurger].
 *
 * Every method hops onto [ioDispatcher] rather than trusting its caller, matching
 * [com.bobot.iptvapp.data.repository.DownloadRepositoryImpl]'s convention: the logout orchestrator
 * is driven from a `viewModelScope` on the main dispatcher, and Room would throw there.
 */
@Singleton
class RoomLocalCachePurger @Inject constructor(
    private val downloadDao: DownloadDao,
    private val catalogCacheDao: CatalogCacheDao,
    private val epgDao: EpgDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : LocalCachePurger {

    override suspend fun purgeDownloadIndex() = withContext(ioDispatcher) {
        downloadDao.clearAll()
    }

    /**
     * Clears the eight catalogue/EPG tables, using the DAOs' unparameterised `clearAll*` methods so
     * *every* account partition goes — including one the user is not currently signed into. The
     * sync markers must go with the rows they describe, or the next session would read a "fresh"
     * marker over an empty table and serve nothing while believing the cache was warm.
     */
    override suspend fun purgeCatalogAndEpgCaches() = withContext(ioDispatcher) {
        catalogCacheDao.clearAllCategories()
        catalogCacheDao.clearChannels()
        catalogCacheDao.clearMovies()
        catalogCacheDao.clearSeries()
        catalogCacheDao.clearAllSeasons()
        catalogCacheDao.clearAllEpisodes()
        catalogCacheDao.clearAllSyncMarkers()
        epgDao.clearAll()
    }

    override suspend fun countDownloadResidue(): Int = withContext(ioDispatcher) {
        downloadDao.countAll()
    }

    override suspend fun countCatalogResidue(): Int = withContext(ioDispatcher) {
        catalogCacheDao.countAllCatalogRows() + epgDao.countAll()
    }
}

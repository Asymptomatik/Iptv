package com.bobot.iptvapp.di

import com.bobot.iptvapp.data.repository.FavoritesRepositoryImpl
import com.bobot.iptvapp.data.repository.DownloadRepositoryImpl
import com.bobot.iptvapp.data.repository.PlaybackProgressRepositoryImpl
import com.bobot.iptvapp.data.repository.ProfileRepositoryImpl
import com.bobot.iptvapp.domain.repository.FavoritesRepository
import com.bobot.iptvapp.domain.repository.DownloadRepository
import com.bobot.iptvapp.domain.repository.PlaybackProgressRepository
import com.bobot.iptvapp.domain.repository.ProfileRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module binding local Room-backed repository interfaces to their implementations.
 *
 * All three repositories are `@Singleton` — one instance shared for the entire application
 * lifetime. They hold no mutable state themselves; the singleton scope avoids redundant
 * allocations and ensures that any caching added in future tasks is shared globally.
 *
 * ## Catalog repository
 * [com.bobot.iptvapp.domain.repository.CatalogRepository] is bound separately in
 * [RepositoryModule] alongside [com.bobot.iptvapp.data.source.CredentialsProvider] to
 * keep the catalog / remote concern separate from the local user-data concern here.
 *
 * ## DAO dependencies
 * All three implementations receive their DAOs via [DatabaseModule] providers, which
 * vend DAO instances from the `@Singleton` [com.bobot.iptvapp.data.local.IptvDatabase].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LocalRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindProfileRepository(impl: ProfileRepositoryImpl): ProfileRepository

    @Binds
    @Singleton
    abstract fun bindFavoritesRepository(impl: FavoritesRepositoryImpl): FavoritesRepository

    @Binds
    @Singleton
    abstract fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository

    @Binds
    @Singleton
    abstract fun bindPlaybackProgressRepository(
        impl: PlaybackProgressRepositoryImpl,
    ): PlaybackProgressRepository
}

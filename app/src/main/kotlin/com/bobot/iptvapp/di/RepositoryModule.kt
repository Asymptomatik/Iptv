package com.bobot.iptvapp.di

import com.bobot.iptvapp.data.preferences.DataStoreCredentialsProvider
import com.bobot.iptvapp.data.repository.CatalogRepositoryImpl
import com.bobot.iptvapp.data.source.CredentialsProvider
import com.bobot.iptvapp.domain.repository.CatalogRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module binding repository and credentials provider interfaces to their implementations.
 *
 * ## CatalogRepository
 * Bound to [CatalogRepositoryImpl], which delegates to the active [com.bobot.iptvapp.data.source.CatalogDataSource]
 * (mock or real, selected by [DataSourceModule]).
 *
 * ## CredentialsProvider
 * Bound to [DataStoreCredentialsProvider] — persists credentials across process restarts
 * via DataStore Preferences (Task 9). Replaces the temporary [com.bobot.iptvapp.data.source.InMemoryCredentialsProvider]
 * that held credentials in memory until Task 9 was implemented.
 * [com.bobot.iptvapp.data.source.InMemoryCredentialsProvider] is retained in the codebase
 * as a convenient test double for unit tests that need a controllable credential source
 * without DataStore I/O.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindCatalogRepository(impl: CatalogRepositoryImpl): CatalogRepository

    @Binds
    @Singleton
    abstract fun bindCredentialsProvider(impl: DataStoreCredentialsProvider): CredentialsProvider
}

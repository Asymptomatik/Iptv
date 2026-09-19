package com.bobot.iptvapp.di

import com.bobot.iptvapp.data.logout.DefaultLogoutPurger
import com.bobot.iptvapp.data.logout.LocalCachePurger
import com.bobot.iptvapp.data.logout.LogoutFinalizer
import com.bobot.iptvapp.data.logout.SessionCacheInvalidator
import com.bobot.iptvapp.data.logout.RoomLocalCachePurger
import com.bobot.iptvapp.data.preferences.DataStoreLogoutFinalizer
import com.bobot.iptvapp.data.preferences.DataStoreLogoutPurgeMarkerStore
import com.bobot.iptvapp.data.preferences.LogoutPurgeMarkerStore
import com.bobot.iptvapp.data.repository.CatalogRepositoryImpl
import com.bobot.iptvapp.domain.logout.LogoutPurger
import com.bobot.iptvapp.download.DownloadCommander
import com.bobot.iptvapp.download.IptvDownloadService
import com.bobot.iptvapp.download.purge.DownloadStoragePurger
import com.bobot.iptvapp.download.purge.LiveMedia3CacheGateway
import com.bobot.iptvapp.download.purge.LiveMedia3DownloadIndexGateway
import com.bobot.iptvapp.download.purge.Media3CacheGateway
import com.bobot.iptvapp.download.purge.Media3DownloadIndexGateway
import com.bobot.iptvapp.download.purge.Media3DownloadStoragePurger
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the logout purge chain: marker store → Media3 gateways → storage/Room purgers →
 * orchestrator.
 *
 * Every binding here is an interface deliberately kept between the orchestrator and Media3/Room, so
 * the ordering and failure rules can be unit-tested on the JVM against fakes. See
 * [LogoutPurger] for the order itself.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LogoutModule {

    @Binds
    @Singleton
    abstract fun bindLogoutPurgeMarkerStore(impl: DataStoreLogoutPurgeMarkerStore): LogoutPurgeMarkerStore

    @Binds
    @Singleton
    abstract fun bindMedia3DownloadIndexGateway(impl: LiveMedia3DownloadIndexGateway): Media3DownloadIndexGateway

    @Binds
    @Singleton
    abstract fun bindMedia3CacheGateway(impl: LiveMedia3CacheGateway): Media3CacheGateway

    @Binds
    @Singleton
    abstract fun bindDownloadStoragePurger(impl: Media3DownloadStoragePurger): DownloadStoragePurger

    @Binds
    @Singleton
    abstract fun bindLocalCachePurger(impl: RoomLocalCachePurger): LocalCachePurger

    /**
     * The repository is already the single owner of the in-memory catalogue memos and of the fetch
     * generations that make a late result unpublishable, so the purge invalidates through it rather
     * than keeping a second copy of that state it would have to hold in sync.
     */
    @Binds
    @Singleton
    abstract fun bindSessionCacheInvalidator(impl: CatalogRepositoryImpl): SessionCacheInvalidator

    @Binds
    @Singleton
    abstract fun bindLogoutFinalizer(impl: DataStoreLogoutFinalizer): LogoutFinalizer

    @Binds
    @Singleton
    abstract fun bindLogoutPurger(impl: DefaultLogoutPurger): LogoutPurger

    /**
     * The repository talks to the queue through [DownloadCommander] rather than the concrete
     * commander, so its enqueue guard can be tested without an Android `Context`.
     */
    @Binds
    @Singleton
    abstract fun bindDownloadCommander(impl: IptvDownloadService.Commander): DownloadCommander
}

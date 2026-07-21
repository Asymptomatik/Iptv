package com.bobot.iptvapp.di

import android.content.Context
import androidx.room.Room
import com.bobot.iptvapp.data.local.IptvDatabase
import com.bobot.iptvapp.data.local.DatabaseMigrations
import com.bobot.iptvapp.data.local.dao.CatalogCacheDao
import com.bobot.iptvapp.data.local.dao.EpgDao
import com.bobot.iptvapp.data.local.dao.DownloadDao
import com.bobot.iptvapp.data.local.dao.FavoriteDao
import com.bobot.iptvapp.data.local.dao.PlaybackProgressDao
import com.bobot.iptvapp.data.local.dao.ProfileDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing the Room [IptvDatabase] singleton and all DAO instances.
 *
 * ## Database singleton
 * [IptvDatabase] is provided as `@Singleton` — one Room instance shared for the entire
 * application lifetime. All DAOs are vended from that single instance.
 *
 * ## DAO scoping
 * Individual DAO providers carry no explicit scope annotation. Room DAOs are stateless
 * wrappers over the underlying connection; Hilt will call each provider on every
 * injection site, but `db.xyzDao()` is memoised by Room internally, so the effective
 * behaviour is singleton-like without the overhead of an explicit scope.
 *
 * ## Database file
 * The database is stored as `"iptv.db"` in the app's default database directory
 * (managed by the Android OS, typically `/data/data/<package>/databases/`).
 *
 * ## Migrations
 * Version 1 — no migrations. See [IptvDatabase] KDoc for the full migration policy.
 * When a new database version is needed, add a `Migration` object and pass it to
 * `addMigrations(...)` on the builder below. Never call
 * `fallbackToDestructiveMigration()` without reading the migration policy in [IptvDatabase].
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideIptvDatabase(@ApplicationContext context: Context): IptvDatabase =
        Room.databaseBuilder(
            context,
            IptvDatabase::class.java,
            "iptv.db",
        ).addMigrations(DatabaseMigrations.MIGRATION_1_2).build()

    @Provides
    fun provideProfileDao(db: IptvDatabase): ProfileDao = db.profileDao()

    @Provides
    fun provideFavoriteDao(db: IptvDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun providePlaybackProgressDao(db: IptvDatabase): PlaybackProgressDao =
        db.playbackProgressDao()

    @Provides
    fun provideCatalogCacheDao(db: IptvDatabase): CatalogCacheDao = db.catalogCacheDao()

    @Provides
    fun provideEpgDao(db: IptvDatabase): EpgDao = db.epgDao()

    @Provides
    fun provideDownloadDao(db: IptvDatabase): DownloadDao = db.downloadDao()
}

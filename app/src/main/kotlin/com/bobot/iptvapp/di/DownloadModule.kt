package com.bobot.iptvapp.di

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.Executor
import javax.inject.Qualifier
import javax.inject.Singleton

/** Provides the single Media3 download queue and its app-private persistent cache. */
@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class DownloadCache

    @Provides
    @Singleton
    fun provideDownloadDatabase(@ApplicationContext context: Context) = StandaloneDatabaseProvider(context)

    @Provides
    @Singleton
    @DownloadCache
    fun provideDownloadCache(
        @ApplicationContext context: Context,
        databaseProvider: StandaloneDatabaseProvider,
    ): Cache = SimpleCache(
        File(context.filesDir, "downloads"),
        NoOpCacheEvictor(),
        databaseProvider,
    )

    @Provides
    @Singleton
    fun provideDownloadManager(
        @ApplicationContext context: Context,
        databaseProvider: StandaloneDatabaseProvider,
        @DownloadCache downloadCache: Cache,
    ): DownloadManager = DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            DefaultHttpDataSource.Factory(),
            Executor(Runnable::run),
        ).apply { setMaxParallelDownloads(2) }
}

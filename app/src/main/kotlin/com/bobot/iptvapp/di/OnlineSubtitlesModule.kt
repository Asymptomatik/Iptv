package com.bobot.iptvapp.di

import android.content.Context
import com.bobot.iptvapp.BuildConfig
import com.bobot.iptvapp.data.logout.DownloadedSubtitlePurger
import com.bobot.iptvapp.data.logout.LogoutCoordinator
import com.bobot.iptvapp.data.logout.SessionWriteGate
import com.bobot.iptvapp.data.preferences.OpenSubtitlesApiKeyStore
import com.bobot.iptvapp.data.remote.opensubtitles.AndroidCueHtml
import com.bobot.iptvapp.data.remote.opensubtitles.OnlineSubtitleFileStore
import com.bobot.iptvapp.data.remote.opensubtitles.OpenSubtitlesDownloader
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifies the directory holding the subtitles downloaded from OpenSubtitles — the only place the
 * player reads a `file:` subtitle from (see [com.bobot.iptvapp.player.IptvMediaSourceFactory]).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OnlineSubtitleDirectory

/**
 * Hilt module for OpenSubtitles downloads: where the files live, and the downloader writing them.
 * The search client stays in [NetworkModule]; both share the [OpenSubtitlesHttpClient].
 */
@Module
@InstallIn(SingletonComponent::class)
object OnlineSubtitlesModule {

    /**
     * App-private cache storage: nothing else can read it, it is excluded from backups, and the
     * system may reclaim it — acceptable for a file one download call can recreate. The logout
     * purge empties it explicitly (see [DownloadedSubtitlePurger]).
     */
    @Provides
    @Singleton
    @OnlineSubtitleDirectory
    fun provideOnlineSubtitleDirectory(@ApplicationContext context: Context): File =
        File(context.cacheDir, "online_subtitles")

    /**
     * The gate is [LogoutCoordinator], reached through [Lazy]: the coordinator's purger depends on
     * this very store (as [DownloadedSubtitlePurger]), so a direct dependency would be a cycle.
     */
    @Provides
    @Singleton
    fun provideOnlineSubtitleFileStore(
        @OnlineSubtitleDirectory directory: File,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        logoutCoordinator: Lazy<LogoutCoordinator>,
    ): OnlineSubtitleFileStore = OnlineSubtitleFileStore(
        directory = directory,
        ioDispatcher = ioDispatcher,
        sessionGate = object : SessionWriteGate {
            override suspend fun <T : Any> runInSession(generation: Int, block: suspend () -> T): T? =
                logoutCoordinator.get().runInSession(generation, block)
        },
    )

    @Provides
    @Singleton
    fun provideDownloadedSubtitlePurger(store: OnlineSubtitleFileStore): DownloadedSubtitlePurger = store

    /** Same `User-Agent` as the search client — the API answers 403 without an app name and version. */
    @Provides
    @Singleton
    fun provideOpenSubtitlesDownloader(
        @OpenSubtitlesHttpClient httpClient: OkHttpClient,
        json: Json,
        apiKeyStore: OpenSubtitlesApiKeyStore,
        fileStore: OnlineSubtitleFileStore,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): OpenSubtitlesDownloader = OpenSubtitlesDownloader(
        httpClient = httpClient,
        json = json,
        apiKeyStore = apiKeyStore,
        userAgent = "IptvApp v${BuildConfig.VERSION_NAME}",
        fileStore = fileStore,
        ioDispatcher = ioDispatcher,
        cueHtml = AndroidCueHtml,
    )
}

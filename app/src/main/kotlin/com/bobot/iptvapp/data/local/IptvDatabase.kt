package com.bobot.iptvapp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.bobot.iptvapp.data.local.dao.CatalogCacheDao
import com.bobot.iptvapp.data.local.dao.DownloadDao
import com.bobot.iptvapp.data.local.dao.EpgDao
import com.bobot.iptvapp.data.local.dao.FavoriteDao
import com.bobot.iptvapp.data.local.dao.PlaybackProgressDao
import com.bobot.iptvapp.data.local.dao.ProfileDao
import com.bobot.iptvapp.data.local.entity.CategoryEntity
import com.bobot.iptvapp.data.local.entity.ChannelEntity
import com.bobot.iptvapp.data.local.entity.EpisodeEntity
import com.bobot.iptvapp.data.local.entity.EpgProgramEntity
import com.bobot.iptvapp.data.local.entity.DownloadEntity
import com.bobot.iptvapp.data.local.entity.FavoriteEntity
import com.bobot.iptvapp.data.local.entity.MovieEntity
import com.bobot.iptvapp.data.local.entity.PlaybackProgressEntity
import com.bobot.iptvapp.data.local.entity.ProfileEntity
import com.bobot.iptvapp.data.local.entity.SeasonEntity
import com.bobot.iptvapp.data.local.entity.SeriesEntity

/**
 * Room database for the IPTV app.
 *
 * ## Schema version
 * **Version 1** — initial schema. No migrations are defined yet (there is no prior
 * version to migrate from).
 *
 * ## Migration policy
 *
 * ### User data tables — `profiles`, `favorites`, `playback_progress`
 * These tables store user-generated content that **cannot** be recovered from the
 * Xtream Codes server. Schema changes to any of these tables **MUST** be accompanied
 * by a proper Room `Migration` object. `fallbackToDestructiveMigration()` must never
 * be applied globally in production builds once the app has been released, as it would
 * silently erase user profiles, favorites, and watch history.
 *
 * ### Catalog cache tables — `categories`, `channels`, `movies`, `series`, `seasons`, `episodes`, `epg_programs`
 * These are offline-first caches of the Xtream Codes API. Losing them is recoverable —
 * the next app open re-fetches and rebuilds the cache transparently. In the event of a
 * migration gap on these tables, `fallbackToDestructiveMigration()` is acceptable in an
 * explicit debug or CI configuration. In production, prefer writing migrations for all
 * tables to avoid the risk of accidentally destroying user data if the scope of a
 * destructive fallback is misconfigured.
 *
 * ### Schema export
 * `exportSchema = true` instructs KSP to write a JSON schema snapshot to the directory
 * configured by the `room.schemaLocation` KSP argument in `app/build.gradle.kts`
 * (`$projectDir/schemas`). These files should be committed to version control — they
 * serve as a migration audit log and are required by `MigrationTestHelper` when
 * migration tests are added in future versions.
 *
 * ## TypeConverters
 * [Converters] is registered at the database level so its converters apply automatically
 * to every entity and DAO without repeating the annotation on individual elements.
 */
@Database(
    entities = [
        ProfileEntity::class,
        FavoriteEntity::class,
        PlaybackProgressEntity::class,
        CategoryEntity::class,
        ChannelEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        SeasonEntity::class,
        EpisodeEntity::class,
        EpgProgramEntity::class,
        DownloadEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class IptvDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao

    abstract fun favoriteDao(): FavoriteDao

    abstract fun playbackProgressDao(): PlaybackProgressDao

    abstract fun catalogCacheDao(): CatalogCacheDao

    abstract fun epgDao(): EpgDao

    abstract fun downloadDao(): DownloadDao
}

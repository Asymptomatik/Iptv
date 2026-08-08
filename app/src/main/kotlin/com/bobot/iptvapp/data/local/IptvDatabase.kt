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
 * **Version 3** — cache tables are now partitioned by account.
 *
 * ### Migration history
 * - `MIGRATION_1_2`: adds the `downloads` table (Media3-projected download index); does not
 *   touch any existing table.
 * - `MIGRATION_2_3`: recreates the seven catalog cache tables with `accountKey` as the leading
 *   column of their composite primary key, partitioning them by account.
 *
 * **No `fallbackToDestructiveMigration()` is registered at the database level.**
 *
 * ## Migration policy
 *
 * ### User data tables — `profiles`, `favorites`, `playback_progress`, `downloads`
 * These tables store user-generated content that **cannot** be recovered from the
 * Xtream Codes server. Schema changes to any of these tables **MUST** be accompanied
 * by a proper Room `Migration` object. `fallbackToDestructiveMigration()` must never
 * be applied globally in production builds once the app has been released, as it would
 * silently erase user profiles, favorites, watch history, and downloaded episodes.
 *
 * ### Catalog cache tables — `categories`, `channels`, `movies`, `series`, `seasons`, `episodes`, `epg_programs`
 * These are offline-first caches of the Xtream Codes API, now **partitioned by account**
 * via `accountKey` in the composite primary key. Losing them is recoverable — the next app
 * open re-fetches and rebuilds the cache transparently for the current account. This allows
 * cache table schema changes to be handled by destructive recreation (as in `MIGRATION_2_3`),
 * since each account's partition is self-contained and loss of cache is not permanent.
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

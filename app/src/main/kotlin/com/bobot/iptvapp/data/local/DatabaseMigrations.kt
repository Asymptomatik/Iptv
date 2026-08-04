package com.bobot.iptvapp.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Explicit migrations for user-visible local data. */
object DatabaseMigrations {

    /** Adds the Media3-projected download index without touching existing user data. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `downloads` (
                    `downloadId` TEXT NOT NULL,
                    `contentType` TEXT NOT NULL,
                    `contentId` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `artworkUrl` TEXT,
                    `streamUrl` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `bytesDownloaded` INTEGER NOT NULL,
                    `contentLength` INTEGER NOT NULL,
                    `createdAtMillis` INTEGER NOT NULL,
                    `updatedAtMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`downloadId`)
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * Partitions the seven catalog cache tables by `accountKey` (Task 3): each table's
     * primary key gains `accountKey` as its leading column. Cache tables are recreated
     * **empty** — the next app open transparently re-fetches and rebuilds them from the
     * Xtream Codes API, this time scoped per account.
     *
     * Only affects `categories`, `channels`, `movies`, `series`, `seasons`, `episodes`,
     * `epg_programs` and the index on `epg_programs.endMillis`. Does **not** touch
     * `profiles`, `favorites`, `playback_progress`, or `downloads` — those hold
     * unrecoverable user data and are out of scope for this migration.
     *
     * The `CREATE TABLE` / `CREATE INDEX` statements below are copied verbatim from the
     * `createSql` fields of `app/schemas/com.bobot.iptvapp.data.local.IptvDatabase/3.json`
     * (the schema Room/KSP exports for the entities as declared after this task's entity
     * changes) — not hand-written — so they match byte-for-byte what Room validates
     * against at runtime.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("DROP TABLE IF EXISTS `categories`")
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `categories` (`accountKey` TEXT NOT NULL, `id` TEXT NOT NULL, `name` TEXT NOT NULL, `contentType` TEXT NOT NULL, PRIMARY KEY(`accountKey`, `id`))",
            )

            database.execSQL("DROP TABLE IF EXISTS `channels`")
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `channels` (`accountKey` TEXT NOT NULL, `id` TEXT NOT NULL, `name` TEXT NOT NULL, `logoUrl` TEXT, `categoryId` TEXT NOT NULL, `epgChannelId` TEXT, PRIMARY KEY(`accountKey`, `id`))",
            )

            database.execSQL("DROP TABLE IF EXISTS `movies`")
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `movies` (`accountKey` TEXT NOT NULL, `id` TEXT NOT NULL, `title` TEXT NOT NULL, `posterUrl` TEXT, `plot` TEXT, `categoryId` TEXT NOT NULL, `rating` TEXT, `year` INTEGER, `addedMillis` INTEGER, `durationMillis` INTEGER, `containerExtension` TEXT, PRIMARY KEY(`accountKey`, `id`))",
            )

            database.execSQL("DROP TABLE IF EXISTS `series`")
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `series` (`accountKey` TEXT NOT NULL, `id` TEXT NOT NULL, `title` TEXT NOT NULL, `coverUrl` TEXT, `plot` TEXT, `categoryId` TEXT NOT NULL, `rating` TEXT, `year` INTEGER, PRIMARY KEY(`accountKey`, `id`))",
            )

            database.execSQL("DROP TABLE IF EXISTS `seasons`")
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `seasons` (`accountKey` TEXT NOT NULL, `seriesId` TEXT NOT NULL, `seasonNumber` INTEGER NOT NULL, `name` TEXT, `coverUrl` TEXT, PRIMARY KEY(`accountKey`, `seriesId`, `seasonNumber`))",
            )

            database.execSQL("DROP TABLE IF EXISTS `episodes`")
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `episodes` (`accountKey` TEXT NOT NULL, `id` TEXT NOT NULL, `seriesId` TEXT NOT NULL, `seasonNumber` INTEGER NOT NULL, `title` TEXT NOT NULL, `episodeNumber` INTEGER NOT NULL, `plot` TEXT, `durationMillis` INTEGER, `containerExtension` TEXT, `coverUrl` TEXT, PRIMARY KEY(`accountKey`, `id`))",
            )

            database.execSQL("DROP TABLE IF EXISTS `epg_programs`")
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `epg_programs` (`accountKey` TEXT NOT NULL, `channelId` TEXT NOT NULL, `startMillis` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT, `endMillis` INTEGER NOT NULL, PRIMARY KEY(`accountKey`, `channelId`, `startMillis`))",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_epg_programs_endMillis` ON `epg_programs` (`endMillis`)",
            )
        }
    }
}

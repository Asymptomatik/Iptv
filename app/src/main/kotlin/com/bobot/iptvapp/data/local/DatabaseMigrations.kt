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
}

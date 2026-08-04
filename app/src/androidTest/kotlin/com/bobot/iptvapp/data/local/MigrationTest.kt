package com.bobot.iptvapp.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bobot.iptvapp.data.local.entity.MovieEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [DatabaseMigrations.MIGRATION_2_3] using [MigrationTestHelper] against
 * the schemas exported under `app/schemas/com.bobot.iptvapp.data.local.IptvDatabase/`.
 *
 * ## ⚠️ NOT RUNNABLE IN THIS (WSL) ENVIRONMENT — VALIDATION IS AN OPEN POINT ⚠️
 *
 * This file lives under `androidTest`, which requires a connected Android device or emulator
 * (`./gradlew connectedAndroidTest` / `connectedDebugAndroidTest`). The environment this test
 * was authored in (WSL, no device/emulator attached) can only *compile* this file — it CANNOT
 * execute it. **Nobody has run these tests successfully yet.** Whoever reads this comment must
 * run them from Android Studio on Windows (or any machine with a connected device/emulator)
 * before treating [DatabaseMigrations.MIGRATION_2_3] as verified end-to-end:
 * ```
 * ./gradlew connectedDebugAndroidTest --tests "com.bobot.iptvapp.data.local.MigrationTest"
 * ```
 * or via the Android Studio test runner gutter icon on this class. Until that has actually been
 * done and reported green, migration 2→3 has only been verified by static review of the
 * `CREATE TABLE` SQL in [DatabaseMigrations] against `app/schemas/.../3.json` — not by an
 * executed test.
 *
 * ## What is covered
 *  - [migrate2To3_preservesAllFourUserDataTables_columnByColumn]: seeds `profiles`, `favorites`,
 *    `playback_progress` and `downloads` (rows untouched by this migration) plus several cache
 *    tables (`categories`, `channels`, `movies`, `series`, `seasons`, `episodes`,
 *    `epg_programs`) on a v2 database, runs the migration with `validateDroppedTables = true`,
 *    then asserts every column of every user-data row survived unchanged and every cache table
 *    exists with the new `accountKey`-leading composite primary key while being empty.
 *  - [migrate2To3_thenSameIdDifferentAccountKey_coexistInMovies]: after migrating, inserts two
 *    `movies` rows sharing the same `id` but different `accountKey` and asserts both persist
 *    without a primary-key conflict — the invariant the new composite key (`accountKey`, `id`)
 *    is meant to guarantee.
 *
 * Row construction for the v2 database is built directly from
 * `app/schemas/com.bobot.iptvapp.data.local.IptvDatabase/2.json`'s `createSql`/`fields` (column
 * names, types, and `NOT NULL`-ness) — not guessed — so it matches exactly what Room validated
 * at export time.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val testDbName = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        IptvDatabase::class.java,
    )

    @Test
    fun migrate2To3_preservesAllFourUserDataTables_columnByColumn() {
        helper.createDatabase(testDbName, 2).apply {
            // ── User data (must be preserved verbatim) ─────────────────────────────
            execSQL(
                "INSERT INTO profiles (id, name, avatarUrl) VALUES " +
                    "('p1', 'Alice', 'http://example.com/alice.png'), " +
                    "('p2', 'Bob', NULL)",
            )
            execSQL(
                "INSERT INTO favorites (profileId, contentId, contentType, addedAt) VALUES " +
                    "('p1', 'movie1', 'MOVIE', 1000)",
            )
            execSQL(
                "INSERT INTO playback_progress " +
                    "(profileId, contentId, contentType, positionMillis, durationMillis, lastUpdatedMillis) " +
                    "VALUES ('p1', 'movie1', 'MOVIE', 30000, 90000, 1700000000000)",
            )
            execSQL(
                "INSERT INTO downloads " +
                    "(downloadId, contentType, contentId, title, artworkUrl, streamUrl, state, " +
                    "bytesDownloaded, contentLength, createdAtMillis, updatedAtMillis) VALUES " +
                    "('d1', 'MOVIE', 'movie1', 'Movie One', NULL, 'http://stream.example.com/movie1.m3u8', " +
                    "'COMPLETED', 123456, 123456, 1700000000000, 1700000001000)",
            )

            // ── Cache tables (must be dropped and recreated empty) ─────────────────
            execSQL(
                "INSERT INTO categories (id, name, contentType) VALUES ('cat1', 'Action', 'MOVIE')",
            )
            execSQL(
                "INSERT INTO channels (id, name, logoUrl, categoryId, epgChannelId) VALUES " +
                    "('ch1', 'Channel One', NULL, 'cat1', NULL)",
            )
            execSQL(
                "INSERT INTO movies " +
                    "(id, title, posterUrl, plot, categoryId, rating, year, addedMillis, durationMillis, containerExtension) " +
                    "VALUES ('m1', 'Movie One', NULL, NULL, 'cat1', NULL, 2020, NULL, NULL, NULL)",
            )
            execSQL(
                "INSERT INTO series (id, title, coverUrl, plot, categoryId, rating, year) VALUES " +
                    "('s1', 'Series One', NULL, NULL, 'cat1', NULL, 2020)",
            )
            execSQL(
                "INSERT INTO seasons (seriesId, seasonNumber, name, coverUrl) VALUES " +
                    "('s1', 1, 'Season 1', NULL)",
            )
            execSQL(
                "INSERT INTO episodes " +
                    "(id, seriesId, seasonNumber, title, episodeNumber, plot, durationMillis, containerExtension, coverUrl) " +
                    "VALUES ('e1', 's1', 1, 'Episode 1', 1, NULL, NULL, NULL, NULL)",
            )
            execSQL(
                "INSERT INTO epg_programs (channelId, startMillis, title, description, endMillis) VALUES " +
                    "('ch1', 1700000000000, 'Program One', NULL, 1700003600000)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            testDbName,
            3,
            true,
            DatabaseMigrations.MIGRATION_2_3,
        )

        // ── profiles — every column, both rows ──────────────────────────────────────
        db.query("SELECT id, name, avatarUrl FROM profiles ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("p1", cursor.getString(0))
            assertEquals("Alice", cursor.getString(1))
            assertEquals("http://example.com/alice.png", cursor.getString(2))

            assertTrue(cursor.moveToNext())
            assertEquals("p2", cursor.getString(0))
            assertEquals("Bob", cursor.getString(1))
            assertTrue(cursor.isNull(2))

            assertFalse(cursor.moveToNext())
        }

        // ── favorites — every column ─────────────────────────────────────────────────
        db.query("SELECT profileId, contentId, contentType, addedAt FROM favorites").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("p1", cursor.getString(0))
            assertEquals("movie1", cursor.getString(1))
            assertEquals("MOVIE", cursor.getString(2))
            assertEquals(1000L, cursor.getLong(3))
            assertFalse(cursor.moveToNext())
        }

        // ── playback_progress — every column ────────────────────────────────────────
        db.query(
            "SELECT profileId, contentId, contentType, positionMillis, durationMillis, lastUpdatedMillis " +
                "FROM playback_progress",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("p1", cursor.getString(0))
            assertEquals("movie1", cursor.getString(1))
            assertEquals("MOVIE", cursor.getString(2))
            assertEquals(30000L, cursor.getLong(3))
            assertEquals(90000L, cursor.getLong(4))
            assertEquals(1700000000000L, cursor.getLong(5))
            assertFalse(cursor.moveToNext())
        }

        // ── downloads — every column ─────────────────────────────────────────────────
        db.query(
            "SELECT downloadId, contentType, contentId, title, artworkUrl, streamUrl, state, " +
                "bytesDownloaded, contentLength, createdAtMillis, updatedAtMillis FROM downloads",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("d1", cursor.getString(0))
            assertEquals("MOVIE", cursor.getString(1))
            assertEquals("movie1", cursor.getString(2))
            assertEquals("Movie One", cursor.getString(3))
            assertTrue(cursor.isNull(4))
            assertEquals("http://stream.example.com/movie1.m3u8", cursor.getString(5))
            assertEquals("COMPLETED", cursor.getString(6))
            assertEquals(123456L, cursor.getLong(7))
            assertEquals(123456L, cursor.getLong(8))
            assertEquals(1700000000000L, cursor.getLong(9))
            assertEquals(1700000001000L, cursor.getLong(10))
            assertFalse(cursor.moveToNext())
        }

        // ── Cache tables — recreated empty, with accountKey leading the primary key ──
        val cacheTables = listOf(
            "categories" to "accountKey, id, name, contentType",
            "channels" to "accountKey, id, name, logoUrl, categoryId, epgChannelId",
            "movies" to "accountKey, id, title, posterUrl, plot, categoryId, rating, year, addedMillis, durationMillis, containerExtension",
            "series" to "accountKey, id, title, coverUrl, plot, categoryId, rating, year",
            "seasons" to "accountKey, seriesId, seasonNumber, name, coverUrl",
            "episodes" to "accountKey, id, seriesId, seasonNumber, title, episodeNumber, plot, durationMillis, containerExtension, coverUrl",
            "epg_programs" to "accountKey, channelId, startMillis, title, description, endMillis",
        )
        for ((table, columns) in cacheTables) {
            // Every declared column (accountKey included) is selectable — proves the new
            // composite-key schema was actually created, not just an empty table left over
            // from a no-op migration.
            db.query("SELECT $columns FROM $table").use { cursor ->
                assertEquals("$table must be empty after migration", 0, cursor.count)
            }
        }

        db.close()
    }

    @Test
    fun migrate2To3_thenSameIdDifferentAccountKey_coexistInMovies() {
        // No pre-existing data required — this test only exercises the post-migration schema.
        helper.createDatabase(testDbName, 2).close()

        val migratedDb = helper.runMigrationsAndValidate(
            testDbName,
            3,
            true,
            DatabaseMigrations.MIGRATION_2_3,
        )
        migratedDb.close()

        // Reopen through Room (typed) to insert via the real entity/DAO path rather than raw
        // SQL, exercising the composite primary key exactly as CatalogRepositoryImpl does.
        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            IptvDatabase::class.java,
            testDbName,
        )
            .addMigrations(DatabaseMigrations.MIGRATION_1_2, DatabaseMigrations.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        val dao = db.catalogCacheDao()
        val movieAccountA = MovieEntity(
            accountKey = "accountA",
            id = "m1",
            title = "Movie One (Account A)",
            posterUrl = null,
            plot = null,
            categoryId = "cat1",
            rating = null,
            year = null,
            addedMillis = null,
            durationMillis = null,
            containerExtension = null,
        )
        val movieAccountB = movieAccountA.copy(accountKey = "accountB", title = "Movie One (Account B)")

        runBlocking {
            // Neither insert must throw a primary-key constraint violation — that is the
            // invariant under test. If it threw, this test would fail with an uncaught
            // SQLiteConstraintException before reaching the assertions below.
            dao.upsertMovies(listOf(movieAccountA, movieAccountB))

            val readBackA = dao.getMovieById("accountA", "m1")
            val readBackB = dao.getMovieById("accountB", "m1")

            assertEquals(movieAccountA, readBackA)
            assertEquals(movieAccountB, readBackB)
            assertNull(dao.getMovieById("accountC", "m1"))
        }

        db.close()
    }
}

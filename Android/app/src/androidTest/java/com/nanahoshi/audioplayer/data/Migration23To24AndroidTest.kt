package com.nanahoshi.audioplayer.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Migration23To24AndroidTest {
    @Test
    fun migrationPreservesV23JobsAndMatchesFreshSchema() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "subtitle-binding-migration-${UUID.randomUUID()}.db"
        try {
            val seed = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                assertEquals(24, seed.openHelper.writableDatabase.version)
            } finally {
                seed.close()
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.removeV24SchemaChanges()
                old.version = 23
                old.execSQL(
                    "INSERT INTO whisper_jobs(id,title,fingerprint,status,progress,createdAt) VALUES(1,'Voice','fixture','DONE',1,100)"
                )
                old.execSQL(
                    "INSERT INTO translation_jobs(id,title,status,kind,sourceFingerprint,createdAt) VALUES(2,'Voice','DONE','TRANSLATE','source',200)"
                )
            }
            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_23_24).build()
            try {
                val sql = migrated.openHelper.writableDatabase
                assertEquals(24, sql.version)

                val whisper = checkNotNull(migrated.whisper().get(1))
                assertEquals("fixture", whisper.fingerprint)
                assertEquals(DownloadStatus.DONE, whisper.status)
                assertEquals(100L, whisper.createdAt)
                assertNull(whisper.forceRegenerate)

                val translation = checkNotNull(migrated.translations().get(2))
                assertEquals("source", translation.sourceFingerprint)
                assertEquals(DownloadStatus.DONE, translation.status)
                assertEquals(200L, translation.createdAt)
                assertNull(translation.targetKind)
                assertNull(translation.outputFilename)

                for ((table, names) in mapOf(
                    "whisper_jobs" to listOf("forceRegenerate"),
                    "translation_jobs" to listOf("targetKind", "outputFilename"),
                )) {
                    val columns = pragma(sql, "table_info", table).associateBy { it[1] }
                    for (name in names) {
                        val column = columns.getValue(name)
                        assertEquals("$table.$name must be nullable", "0", column[3])
                        assertNull("$table.$name must have no SQL default", column[4])
                    }
                }
                sql.query("SELECT COUNT(*) FROM track_subtitles").use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(0, rows.getInt(0))
                }

                val fresh = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
                try {
                    val freshSql = fresh.openHelper.writableDatabase
                    assertEquals(24, freshSql.version)
                    for (table in listOf("track_subtitles", "whisper_jobs", "translation_jobs")) {
                        assertEquals(table, pragma(freshSql, "table_info", table), pragma(sql, "table_info", table))
                    }
                    assertEquals(
                        pragma(freshSql, "foreign_key_list", "track_subtitles"),
                        pragma(sql, "foreign_key_list", "track_subtitles"),
                    )
                    assertEquals(
                        pragma(freshSql, "index_list", "track_subtitles"),
                        pragma(sql, "index_list", "track_subtitles"),
                    )
                    for (index in listOf("index_track_subtitles_trackId", "index_track_subtitles_fileId")) {
                        assertEquals(index, pragma(freshSql, "index_info", index), pragma(sql, "index_info", index))
                    }
                } finally {
                    fresh.close()
                }
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun pragma(db: SupportSQLiteDatabase, pragma: String, name: String): Set<List<String?>> =
        db.query("PRAGMA $pragma($name)").use { rows ->
            buildSet {
                while (rows.moveToNext()) add(List(rows.columnCount) { rows.getString(it) })
            }
        }
}

internal fun SQLiteDatabase.removeV24SchemaChanges() {
    execSQL("DROP TABLE track_subtitles")
    // Android API 24–33 的 SQLite 不支持 ALTER TABLE ... DROP COLUMN（需要 3.35+），
    // 因此按 v23 结构重建任务表，再让 MIGRATION_23_24 追加新列。
    execSQL(
        """
        CREATE TABLE whisper_jobs__v23 (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            title TEXT NOT NULL,
            fingerprint TEXT NOT NULL,
            trackId INTEGER,
            localPath TEXT,
            remoteJobId TEXT,
            status TEXT NOT NULL,
            progress REAL NOT NULL,
            error TEXT,
            awaitRemoteUrl TEXT,
            endpoint TEXT,
            translatedAt INTEGER,
            translateStatus TEXT,
            skipTranslate INTEGER,
            model TEXT,
            awaitDownloadId INTEGER,
            awaitFileKey TEXT,
            remoteSubmittedAt INTEGER,
            progressSummary TEXT,
            createdAt INTEGER,
            dismissedAt INTEGER
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO whisper_jobs__v23 (
            id, title, fingerprint, trackId, localPath, remoteJobId, status, progress, error,
            awaitRemoteUrl, endpoint, translatedAt, translateStatus, skipTranslate, model,
            awaitDownloadId, awaitFileKey, remoteSubmittedAt, progressSummary, createdAt, dismissedAt
        )
        SELECT
            id, title, fingerprint, trackId, localPath, remoteJobId, status, progress, error,
            awaitRemoteUrl, endpoint, translatedAt, translateStatus, skipTranslate, model,
            awaitDownloadId, awaitFileKey, remoteSubmittedAt, progressSummary, createdAt, dismissedAt
        FROM whisper_jobs
        """.trimIndent(),
    )
    execSQL("DROP TABLE whisper_jobs")
    execSQL("ALTER TABLE whisper_jobs__v23 RENAME TO whisper_jobs")

    execSQL(
        """
        CREATE TABLE translation_jobs__v23 (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            title TEXT NOT NULL,
            trackId INTEGER,
            remoteUrl TEXT,
            queueItemId INTEGER,
            status TEXT NOT NULL,
            error TEXT,
            kind TEXT,
            generatedFingerprint TEXT,
            sourceFingerprint TEXT,
            bvid TEXT,
            cid INTEGER,
            localUri TEXT,
            progressSummary TEXT,
            createdAt INTEGER,
            dismissedAt INTEGER
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO translation_jobs__v23 (
            id, title, trackId, remoteUrl, queueItemId, status, error, kind,
            generatedFingerprint, sourceFingerprint, bvid, cid, localUri, progressSummary, createdAt, dismissedAt
        )
        SELECT
            id, title, trackId, remoteUrl, queueItemId, status, error, kind,
            generatedFingerprint, sourceFingerprint, bvid, cid, localUri, progressSummary, createdAt, dismissedAt
        FROM translation_jobs
        """.trimIndent(),
    )
    execSQL("DROP TABLE translation_jobs")
    execSQL("ALTER TABLE translation_jobs__v23 RENAME TO translation_jobs")
}

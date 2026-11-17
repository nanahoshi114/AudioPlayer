package com.nanahoshi.audioplayer.bilibili

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BiliCollectionMigrationAndroidTest {
    @Test fun upgrade16To17MatchesFreshSchemaAndPreservesOldReferences() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "collection-migration-${UUID.randomUUID()}.db"
        val seed = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val track = seed.tracks().insert(TrackEntity(source = TrackSource.BILIBILI, title = "old", artist = "UP",
            durationMs = 1000, coverUri = null, localUri = "/fixture/audio.m4s", bvid = "BV1234567890", cid = 101,
            page = 1, addedAt = 0))
        val playlist = seed.playlists().insert(PlaylistEntity(name = "old list", createdAt = 1, updatedAt = 2))
        seed.playlists().insertItem(PlaylistItemEntity(playlistId = playlist, trackId = track, position = 0))
        seed.queue().insert(QueueItemEntity(trackId = track, position = 0))
        val translation = seed.translations().insert(TranslationJobEntity(
            title = "legacy", trackId = track, remoteUrl = null, queueItemId = null,
            status = DownloadStatus.DONE, error = null,
        ))
        seed.close()
        // Reconstruct the schema from before the collection and subtitle task migrations.
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE subtitle_publications")
            val addedColumns = mapOf(
                "downloads" to listOf("workFolderId", "pendingBiliSubtitle"),
                "whisper_jobs" to listOf("remoteSubmittedAt"),
                "translation_jobs" to listOf("kind", "generatedFingerprint", "sourceFingerprint", "bvid", "cid", "localUri"),
            )
            for ((table, columns) in addedColumns) {
                for (column in columns) it.execSQL("ALTER TABLE $table DROP COLUMN $column")
            }
            it.execSQL("DROP TABLE bili_collection_videos")
            it.execSQL("DROP TABLE bili_collections")
            // v19 起 fresh 库不再含该表，但 17_18 仍会 DELETE 它；逆向 fixture 须补回 v16 结构。
            it.execSQL("CREATE TABLE bili_import_intents (bvid TEXT NOT NULL PRIMARY KEY, batchId TEXT NOT NULL, requestJson TEXT NOT NULL)")
            it.execSQL("CREATE UNIQUE INDEX index_bili_import_intents_batchId ON bili_import_intents (batchId)")
            it.version = 16
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_16_17, AppDatabase.MIGRATION_17_18, AppDatabase.MIGRATION_18_19).build()
        val fresh = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            assertEquals(track, migrated.playlists().items(playlist).single().trackId)
            assertEquals(track, migrated.queue().snapshot().single().trackId)
            assertEquals("/fixture/audio.m4s", migrated.tracks().getById(track)!!.localUri)
            assertEquals(track, migrated.translations().get(translation)?.trackId)
            assertNull(migrated.translations().get(translation)?.kind)
            fun schema(database: AppDatabase): List<String> {
                val sql = database.openHelper.writableDatabase
                assertEquals(19, sql.version)
                return buildList {
                    for (table in listOf("playlists", "playlist_items", "bili_collections", "bili_collection_videos",
                        "downloads", "whisper_jobs", "translation_jobs", "subtitle_publications")) {
                        for (pragma in listOf("table_info", "index_list", "foreign_key_list")) {
                            sql.query("PRAGMA $pragma($table)").use { rows ->
                                while (rows.moveToNext()) add("$table/$pragma/" + (1 until rows.columnCount).joinToString("|") { rows.getString(it).orEmpty() })
                            }
                        }
                    }
                }.sorted()
            }
            assertEquals(schema(fresh), schema(migrated))
        } finally { migrated.close(); fresh.close(); context.deleteDatabase(name) }
    }
}

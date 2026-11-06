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
        seed.close()
        // v17 only adds these two tables; removing them reconstructs the v16 base.
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE bili_collection_videos")
            it.execSQL("DROP TABLE bili_collections")
            it.version = 16
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_16_17).build()
        val fresh = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            assertEquals(track, migrated.playlists().items(playlist).single().trackId)
            assertEquals(track, migrated.queue().snapshot().single().trackId)
            assertEquals("/fixture/audio.m4s", migrated.tracks().getById(track)!!.localUri)
            fun schema(database: AppDatabase): List<String> {
                val sql = database.openHelper.writableDatabase
                assertEquals(17, sql.version)
                return buildList {
                    for (table in listOf("playlists", "playlist_items", "bili_collections", "bili_collection_videos")) {
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

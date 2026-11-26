package com.nanahoshi.audioplayer.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanahoshi.audioplayer.assertSchemaMatchesFresh
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.withIsolatedApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 用真实的 v1 表结构重建最老版本，跑完整迁移链到 v24，并确认旧曲目、队列、
 * 播放列表与播放位置都保留，最终结构与新装库一致。
 */
@RunWith(AndroidJUnit4::class)
class Migration1To12AndroidTest {
    @Test
    fun authenticV1SeedsSurviveTheWholeMigrationChainAndMatchFreshSchema() = runBlocking {
        withIsolatedApp { app ->
            val name = "v1-migration-${UUID.randomUUID()}.db"
            SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name), null).use { v1 ->
                createV1(v1)
                v1.execSQL(
                    "INSERT INTO tracks(id, source, title, artist, durationMs, coverUri, localUri, bvid, cid, page, addedAt) " +
                        "VALUES(1, 'LOCAL', 'v1 track', 'artist', 1000, NULL, '/v1/audio.mp3', NULL, NULL, NULL, 11)",
                )
                v1.execSQL("INSERT INTO queue_items(id, trackId, position) VALUES(1, 1, 0)")
                v1.execSQL("INSERT INTO playlists(id, name, createdAt, updatedAt) VALUES(1, 'v1 list', 1, 2)")
                v1.execSQL("INSERT INTO playlist_items(id, playlistId, trackId, position) VALUES(1, 1, 1, 0)")
                v1.execSQL("INSERT INTO playback_state(id, queueItemId, positionMs) VALUES(0, 1, 1234)")
                v1.version = 1
            }
            val migrated = Room.databaseBuilder(app, AppDatabase::class.java, name)
                .addMigrations(*AppDatabase.ALL_MIGRATIONS).build()
            try {
                assertEquals(24, migrated.openHelper.writableDatabase.version)
                val track = checkNotNull(migrated.tracks().getById(1))
                assertEquals("v1 track", track.title)
                assertEquals("/v1/audio.mp3", track.localUri)
                val queue = migrated.queue().snapshot().single()
                assertEquals(1L, queue.trackId)
                assertEquals(0, queue.position)
                assertEquals(1L, migrated.playlists().items(1).single().trackId)
                val state = checkNotNull(migrated.queue().playbackState())
                assertEquals(1L, state.queueItemId)
                assertEquals(1234L, state.positionMs)
                assertSchemaMatchesFresh(migrated, app)
            } finally {
                migrated.close()
            }
        }
    }

    private fun createV1(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tracks` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`artist` TEXT, `durationMs` INTEGER NOT NULL, `coverUri` TEXT, `localUri` TEXT, `bvid` TEXT, " +
                "`cid` INTEGER, `page` INTEGER, `addedAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `queue_items` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` INTEGER, `position` INTEGER NOT NULL, " +
                "FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `playlists` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `playlist_items` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, " +
                "`trackId` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                "FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_items_playlistId` ON `playlist_items` (`playlistId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_items_trackId` ON `playlist_items` (`trackId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `playback_state` (" +
                "`id` INTEGER NOT NULL, `queueItemId` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
    }
}
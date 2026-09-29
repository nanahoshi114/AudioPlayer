package com.nanahoshi.audioplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.nanahoshi.audioplayer.data.TrackSource

class TrackSourceConverter {
    @TypeConverter
    fun fromSource(source: TrackSource): String = source.name

    @TypeConverter
    fun toSource(value: String): TrackSource = TrackSource.valueOf(value)
}

@Database(
    entities = [
        FolderEntity::class,
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        QueueItemEntity::class,
        PlaybackStateEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(TrackSourceConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folders(): FolderDao
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun queue(): QueueDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "audio_player.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `parentId` INTEGER, `name` TEXT NOT NULL, `position` INTEGER NOT NULL, FOREIGN KEY(`parentId`) REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_folders_parentId` ON `folders` (`parentId`)")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tracks_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT, `durationMs` INTEGER NOT NULL, `coverUri` TEXT, `localUri` TEXT, `bvid` TEXT, `cid` INTEGER, `page` INTEGER, `addedAt` INTEGER NOT NULL, `folderId` INTEGER, FOREIGN KEY(`folderId`) REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.execSQL(
                    "INSERT INTO `tracks_new` (`id`, `source`, `title`, `artist`, `durationMs`, `coverUri`, `localUri`, `bvid`, `cid`, `page`, `addedAt`, `folderId`) SELECT `id`, `source`, `title`, `artist`, `durationMs`, `coverUri`, `localUri`, `bvid`, `cid`, `page`, `addedAt`, NULL FROM `tracks`",
                )
                connection.execSQL("DROP TABLE `tracks`")
                connection.execSQL("ALTER TABLE `tracks_new` RENAME TO `tracks`")
                connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tracks_localUri` ON `tracks` (`localUri`)")
                connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tracks_bvid_cid` ON `tracks` (`bvid`, `cid`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_folderId` ON `tracks` (`folderId`)")
                val hasSequence = connection.prepare(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'sqlite_sequence'",
                ).use { it.step() }
                if (hasSequence) {
                    connection.execSQL("UPDATE sqlite_sequence SET name = 'tracks' WHERE name = 'tracks_new'")
                }
            }
        }
    }
}

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

class FileKindConverter {
    @TypeConverter
    fun fromKind(kind: FileKind): String = kind.name

    @TypeConverter
    fun toKind(value: String): FileKind = FileKind.valueOf(value)
}

class DownloadStatusConverter {
    @TypeConverter
    fun fromStatus(status: DownloadStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): DownloadStatus = DownloadStatus.valueOf(value)
}

@Database(
    entities = [
        FolderEntity::class,
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        QueueItemEntity::class,
        PlaybackStateEntity::class,
        LibraryFileEntity::class,
        DownloadEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
@TypeConverters(TrackSourceConverter::class, FileKindConverter::class, DownloadStatusConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folders(): FolderDao
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun queue(): QueueDao
    abstract fun files(): LibraryFileDao
    abstract fun downloads(): DownloadDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "audio_player.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `folders` ADD COLUMN `addedAt` INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE `folders` ADD COLUMN `asmrWorkId` INTEGER")
                connection.execSQL("ALTER TABLE `folders` ADD COLUMN `asmrSourceId` TEXT")
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_folders_asmrSourceId` ON `folders` (`asmrSourceId`)",
                )
                connection.execSQL("ALTER TABLE `tracks` ADD COLUMN `remoteUrl` TEXT")
                connection.execSQL("ALTER TABLE `tracks` ADD COLUMN `asmrWorkId` INTEGER")
                connection.execSQL("ALTER TABLE `tracks` ADD COLUMN `fileHash` TEXT")
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_tracks_asmrWorkId_fileHash` ON `tracks` (`asmrWorkId`, `fileHash`)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `library_files` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `folderId` INTEGER, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `remoteUrl` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, `asmrWorkId` INTEGER NOT NULL, `fileHash` TEXT NOT NULL, FOREIGN KEY(`folderId`) REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_library_files_folderId` ON `library_files` (`folderId`)",
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_library_files_asmrWorkId_fileHash` ON `library_files` (`asmrWorkId`, `fileHash`)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `downloads` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` INTEGER NOT NULL, `title` TEXT NOT NULL, `bytesDone` INTEGER NOT NULL, `bytesTotal` INTEGER NOT NULL, `status` TEXT NOT NULL, `error` TEXT)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_downloads_trackId` ON `downloads` (`trackId`)",
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `folders` ADD COLUMN `coverUri` TEXT")
                connection.execSQL(
                    """
                    UPDATE `folders` SET `coverUri` = (
                        SELECT `coverUri` FROM `tracks`
                        WHERE `tracks`.`asmrWorkId` = `folders`.`asmrWorkId`
                          AND `coverUri` IS NOT NULL AND `coverUri` != ''
                        LIMIT 1
                    ) WHERE `asmrSourceId` IS NOT NULL
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `folders` ADD COLUMN `workMeta` TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `remoteUrl` TEXT")
                connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `referer` TEXT")
                connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `source` TEXT")
                connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `workKey` TEXT")
                connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `localPath` TEXT")
                connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `fileKey` TEXT")
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `queue_items_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `trackId` INTEGER,
                        `position` INTEGER NOT NULL,
                        `title` TEXT,
                        `artist` TEXT,
                        `coverUri` TEXT,
                        `remoteUrl` TEXT,
                        `source` TEXT,
                        `referer` TEXT,
                        `workno` TEXT,
                        `fileKey` TEXT,
                        FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "INSERT INTO `queue_items_new` (`id`, `trackId`, `position`) SELECT `id`, `trackId`, `position` FROM `queue_items`",
                )
                connection.execSQL("DROP TABLE `queue_items`")
                connection.execSQL("ALTER TABLE `queue_items_new` RENAME TO `queue_items`")
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_queue_items_trackId` ON `queue_items` (`trackId`)",
                )
            }
        }
    }
}

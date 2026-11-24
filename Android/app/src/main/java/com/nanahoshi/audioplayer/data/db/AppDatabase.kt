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
        BiliCollectionEntity::class,
        BiliCollectionVideoEntity::class,
        PlaylistItemEntity::class,
        QueueItemEntity::class,
        PlaybackStateEntity::class,
        LibraryFileEntity::class,
        TrackSubtitleBindingEntity::class,
        DownloadEntity::class,
        WhisperJobEntity::class,
        TranslationJobEntity::class,
        SubtitlePublicationEntity::class,
        DlsiteOriginalWorkEntity::class,
        DlsiteOriginalEntryEntity::class,
        JapaneseAsmrPostEntity::class,
        JapaneseAsmrMediaEntity::class,
        JaDownloadWorkEntity::class,
        JaDownloadEntryEntity::class,
        JaDownloadPackageEntity::class,
    ],
    version = 24,
    exportSchema = false,
)
@TypeConverters(TrackSourceConverter::class, FileKindConverter::class, DownloadStatusConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun japaneseAsmr(): JapaneseAsmrDao
    abstract fun jaDownloads(): JaDownloadDao
    abstract fun dlsiteOriginals(): DlsiteOriginalDao
    abstract fun folders(): FolderDao
    abstract fun tracks(): TrackDao
    abstract fun collections(): BiliCollectionDao
    abstract fun playlists(): PlaylistDao
    abstract fun queue(): QueueDao
    abstract fun files(): LibraryFileDao
    abstract fun trackSubtitles(): TrackSubtitleDao
    abstract fun downloads(): DownloadDao
    abstract fun whisper(): WhisperDao
    abstract fun translations(): TranslationDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "audio_player.db")
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                    MIGRATION_21_22,
                    MIGRATION_22_23,
                    MIGRATION_23_24,
                )
                .build()

        internal val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS track_subtitles (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        trackId INTEGER NOT NULL,
                        kind TEXT NOT NULL,
                        fileId INTEGER,
                        uri TEXT,
                        treeUri TEXT,
                        displayName TEXT,
                        offsetMs INTEGER,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(trackId) REFERENCES tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(fileId) REFERENCES library_files(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_track_subtitles_trackId ON track_subtitles(trackId)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_track_subtitles_fileId ON track_subtitles(fileId)")
                connection.execSQL("ALTER TABLE translation_jobs ADD COLUMN targetKind TEXT")
                connection.execSQL("ALTER TABLE translation_jobs ADD COLUMN outputFilename TEXT")
                connection.execSQL("ALTER TABLE whisper_jobs ADD COLUMN forceRegenerate INTEGER")
            }
        }

        internal val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE downloads ADD COLUMN dismissedAt INTEGER")
                connection.execSQL("ALTER TABLE whisper_jobs ADD COLUMN createdAt INTEGER")
                connection.execSQL("ALTER TABLE whisper_jobs ADD COLUMN dismissedAt INTEGER")
                connection.execSQL("ALTER TABLE translation_jobs ADD COLUMN createdAt INTEGER")
                connection.execSQL("ALTER TABLE translation_jobs ADD COLUMN dismissedAt INTEGER")
            }
        }

        internal val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE downloads ADD COLUMN jaEntryKey TEXT")
                connection.execSQL("ALTER TABLE downloads ADD COLUMN jaReplace INTEGER")
                connection.execSQL("ALTER TABLE downloads ADD COLUMN jaForce INTEGER")
                connection.execSQL("ALTER TABLE queue_items ADD COLUMN mediaPathHash TEXT")
                connection.execSQL("ALTER TABLE japaneseasmr_media ADD COLUMN pathHash TEXT")
                connection.execSQL("UPDATE japaneseasmr_media SET pathHash = substr(mediaKey, 3) WHERE mediaKey GLOB 'p:*'")
                connection.execSQL("CREATE INDEX index_japaneseasmr_media_postId_pathHash ON japaneseasmr_media(postId, pathHash)")
                connection.execSQL("CREATE TABLE japaneseasmr_download_works (rj TEXT NOT NULL PRIMARY KEY, rootFolderId INTEGER, postId TEXT, directory TEXT, updatedAt INTEGER, entryKey TEXT)")
                connection.execSQL("CREATE TABLE japaneseasmr_download_entries (rj TEXT NOT NULL, postId TEXT NOT NULL, relativePath TEXT NOT NULL, kind TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT, localPath TEXT, packageKey TEXT, trackId INTEGER, fileId INTEGER, folderId INTEGER, packageRevision TEXT, PRIMARY KEY(rj, relativePath))")
                connection.execSQL("CREATE TABLE japaneseasmr_download_packages (rj TEXT NOT NULL, packageKey TEXT NOT NULL, relativePath TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT, localPath TEXT, extractedAt INTEGER, publishedAt INTEGER, revision TEXT, PRIMARY KEY(rj, packageKey))")
                connection.execSQL("CREATE INDEX index_japaneseasmr_download_entries_trackId ON japaneseasmr_download_entries(trackId)")
                connection.execSQL("CREATE INDEX index_japaneseasmr_download_entries_fileId ON japaneseasmr_download_entries(fileId)")
                connection.execSQL("CREATE INDEX index_japaneseasmr_download_entries_folderId ON japaneseasmr_download_entries(folderId)")
                connection.execSQL("CREATE INDEX index_japaneseasmr_download_entries_packageKey ON japaneseasmr_download_entries(rj, packageKey)")
            }
        }

        internal val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE queue_items ADD COLUMN mediaKind TEXT")
                connection.execSQL("CREATE TABLE japaneseasmr_posts (postId TEXT NOT NULL PRIMARY KEY, rootFolderId INTEGER, articleFolderId INTEGER, rj TEXT NOT NULL, title TEXT NOT NULL, coverUri TEXT, publishedAt TEXT, url TEXT NOT NULL, refreshedAt INTEGER)")
                connection.execSQL("CREATE INDEX index_japaneseasmr_posts_rootFolderId ON japaneseasmr_posts(rootFolderId)")
                connection.execSQL("CREATE TABLE japaneseasmr_media (postId TEXT NOT NULL, mediaKey TEXT NOT NULL, trackId INTEGER, kind TEXT NOT NULL, title TEXT NOT NULL, durationMs INTEGER NOT NULL, chapters TEXT, PRIMARY KEY(postId, mediaKey))")
                connection.execSQL("CREATE INDEX index_japaneseasmr_media_trackId ON japaneseasmr_media(trackId)")
            }
        }

        internal val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE downloads ADD COLUMN progressSummary TEXT")
                connection.execSQL("ALTER TABLE whisper_jobs ADD COLUMN progressSummary TEXT")
                connection.execSQL("ALTER TABLE translation_jobs ADD COLUMN progressSummary TEXT")
            }
        }

        /** 移除废弃的 B 站自动入库批次表；downloads.biliImportPart 列恒为 NULL，保留以免重建下载表。 */
        internal val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("DROP TABLE bili_import_intents")
            }
        }

        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE downloads ADD COLUMN workFolderId INTEGER")
                connection.execSQL("ALTER TABLE downloads ADD COLUMN pendingBiliSubtitle INTEGER")
                connection.execSQL("ALTER TABLE whisper_jobs ADD COLUMN remoteSubmittedAt INTEGER")
                connection.execSQL("ALTER TABLE translation_jobs ADD COLUMN kind TEXT")
                connection.execSQL("ALTER TABLE translation_jobs ADD COLUMN generatedFingerprint TEXT")
                connection.execSQL("ALTER TABLE translation_jobs ADD COLUMN sourceFingerprint TEXT")
                connection.execSQL("ALTER TABLE translation_jobs ADD COLUMN bvid TEXT")
                connection.execSQL("ALTER TABLE translation_jobs ADD COLUMN cid INTEGER")
                connection.execSQL("ALTER TABLE translation_jobs ADD COLUMN localUri TEXT")
                connection.execSQL("CREATE TABLE subtitle_publications (filename TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL, whisperJobId INTEGER, translationJobId INTEGER, translatedAt INTEGER NOT NULL)")
                connection.execSQL("DELETE FROM bili_import_intents")
                connection.execSQL("UPDATE downloads SET biliImportPart = NULL WHERE biliImportPart IS NOT NULL")
            }
        }

        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("CREATE TABLE bili_collections (playlistId INTEGER NOT NULL PRIMARY KEY, kind TEXT NOT NULL, remoteId INTEGER NOT NULL, metadata TEXT NOT NULL, revision INTEGER NOT NULL, refreshedAt INTEGER, FOREIGN KEY(playlistId) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                connection.execSQL("CREATE UNIQUE INDEX index_bili_collections_kind_remoteId ON bili_collections(kind, remoteId)")
                connection.execSQL("CREATE TABLE bili_collection_videos (playlistId INTEGER NOT NULL, bvid TEXT NOT NULL, position INTEGER NOT NULL, video TEXT NOT NULL, parts TEXT, PRIMARY KEY(playlistId, bvid), FOREIGN KEY(playlistId) REFERENCES bili_collections(playlistId) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }

        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE folders ADD COLUMN biliBvid TEXT")
                connection.execSQL("CREATE UNIQUE INDEX index_folders_biliBvid ON folders (biliBvid)")
                connection.execSQL("CREATE TABLE bili_import_intents (bvid TEXT NOT NULL PRIMARY KEY, batchId TEXT NOT NULL, requestJson TEXT NOT NULL)")
                connection.execSQL("CREATE UNIQUE INDEX index_bili_import_intents_batchId ON bili_import_intents (batchId)")
            }
        }

        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE downloads ADD COLUMN kind TEXT")
                connection.execSQL("ALTER TABLE downloads ADD COLUMN phase TEXT")
                connection.execSQL("ALTER TABLE library_files ADD COLUMN localUri TEXT")
                connection.execSQL("ALTER TABLE whisper_jobs ADD COLUMN awaitDownloadId INTEGER")
                connection.execSQL("ALTER TABLE whisper_jobs ADD COLUMN awaitFileKey TEXT")
                connection.execSQL("CREATE TABLE dlsite_original_works (workno TEXT NOT NULL PRIMARY KEY, rootFolderId INTEGER, directory TEXT NOT NULL, generation TEXT NOT NULL)")
                connection.execSQL("CREATE TABLE dlsite_original_entries (workno TEXT NOT NULL, relativePath TEXT NOT NULL, kind TEXT NOT NULL, size INTEGER NOT NULL, localPath TEXT NOT NULL, playFileKey TEXT, trackId INTEGER, fileId INTEGER, folderId INTEGER, PRIMARY KEY(workno, relativePath))")
                connection.execSQL("CREATE INDEX index_dlsite_original_entries_trackId ON dlsite_original_entries(trackId)")
                connection.execSQL("CREATE INDEX index_dlsite_original_entries_fileId ON dlsite_original_entries(fileId)")
                connection.execSQL("CREATE INDEX index_dlsite_original_entries_folderId ON dlsite_original_entries(folderId)")
                connection.execSQL("CREATE INDEX index_dlsite_original_entries_workno_playFileKey ON dlsite_original_entries(workno, playFileKey)")
            }
        }

        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE downloads ADD COLUMN biliImportPart TEXT")
            }
        }

        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE tracks ADD COLUMN youtubeVideoId TEXT")
                connection.execSQL("CREATE UNIQUE INDEX index_tracks_youtubeVideoId ON tracks (youtubeVideoId)")
            }
        }

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

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `folders` ADD COLUMN `contentSource` TEXT")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `whisper_jobs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `fingerprint` TEXT NOT NULL,
                        `trackId` INTEGER,
                        `localPath` TEXT,
                        `remoteJobId` TEXT,
                        `status` TEXT NOT NULL,
                        `progress` REAL NOT NULL,
                        `error` TEXT,
                        `awaitRemoteUrl` TEXT
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `whisper_jobs` ADD COLUMN `endpoint` TEXT")
                connection.execSQL("ALTER TABLE `whisper_jobs` ADD COLUMN `translatedAt` INTEGER")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `whisper_jobs` ADD COLUMN `translateStatus` TEXT")
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `translation_jobs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `trackId` INTEGER,
                        `remoteUrl` TEXT,
                        `queueItemId` INTEGER,
                        `status` TEXT NOT NULL,
                        `error` TEXT
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `whisper_jobs` ADD COLUMN `skipTranslate` INTEGER")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `whisper_jobs` ADD COLUMN `model` TEXT")
            }
        }
    }
}

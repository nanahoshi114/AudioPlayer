package com.nanahoshi.audioplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
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
        /** 按版本顺序排列的全部迁移；测试也应直接复用，避免各自复制遗漏。 */
        internal val ALL_MIGRATIONS: Array<Migration>
            get() = DB_MIGRATIONS

        /** 迁移对象集中在 [DB_MIGRATIONS]；保留该名称以满足直接引用单个迁移的测试。 */
        internal val MIGRATION_23_24: Migration
            get() = com.nanahoshi.audioplayer.data.db.MIGRATION_23_24

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "audio_player.db")
                .addMigrations(*ALL_MIGRATIONS)
                .build()
    }
}
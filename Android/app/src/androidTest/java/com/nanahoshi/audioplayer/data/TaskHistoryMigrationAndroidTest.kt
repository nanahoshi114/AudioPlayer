package com.nanahoshi.audioplayer.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.data.db.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TaskHistoryMigrationAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun migration22To23AddsNullableHistoryColumnsWithoutSqlDefaults() = runBlocking {
        val name = "task-history-migration-${UUID.randomUUID()}.db"
        val seed = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val download = seed.downloads().insert(download(DownloadStatus.DONE))
        val whisper = seed.whisper().insert(whisper(DownloadStatus.DONE))
        val translation = seed.translations().insert(translation(DownloadStatus.DONE))
        seed.close()
        // 删除 v23、v24 新增结构，重建 v22 schema 后执行迁移。
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
            old.removeV24SchemaChanges()
            old.execSQL("ALTER TABLE downloads DROP COLUMN dismissedAt")
            old.execSQL("ALTER TABLE whisper_jobs DROP COLUMN createdAt")
            old.execSQL("ALTER TABLE whisper_jobs DROP COLUMN dismissedAt")
            old.execSQL("ALTER TABLE translation_jobs DROP COLUMN createdAt")
            old.execSQL("ALTER TABLE translation_jobs DROP COLUMN dismissedAt")
            old.version = 22
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_22_23, AppDatabase.MIGRATION_23_24).build()
        val fresh = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val downloadRow = migrated.downloads().get(download)!!
            assertEquals(DownloadStatus.DONE, downloadRow.status)
            assertNull(downloadRow.dismissedAt)
            val whisperRow = migrated.whisper().get(whisper)!!
            assertEquals(DownloadStatus.DONE, whisperRow.status)
            assertNull(whisperRow.createdAt)
            assertNull(whisperRow.dismissedAt)
            val translationRow = migrated.translations().get(translation)!!
            assertEquals(DownloadStatus.DONE, translationRow.status)
            assertNull(translationRow.createdAt)
            assertNull(translationRow.dismissedAt)
            assertEquals(24, migrated.openHelper.writableDatabase.version)

            fun columns(db: AppDatabase, table: String): Map<String, String?> =
                db.openHelper.writableDatabase.query("PRAGMA table_info($table)").use { rows ->
                    buildMap {
                        while (rows.moveToNext()) put(rows.getString(1), rows.getString(4))
                    }
                }
            for ((table, names) in mapOf(
                "downloads" to listOf("dismissedAt"),
                "whisper_jobs" to listOf("createdAt", "dismissedAt"),
                "translation_jobs" to listOf("createdAt", "dismissedAt"),
            )) {
                val upgradedColumns = columns(migrated, table)
                val freshColumns = columns(fresh, table)
                for (name in names) {
                    assertTrue("$table.$name 应存在", name in upgradedColumns)
                    // PRAGMA table_info 的 dflt_value 为空，说明列可空且没有 SQL DEFAULT。
                    assertNull("$table.$name 不应带默认值", upgradedColumns.getValue(name))
                    assertEquals("$table.$name 新装与升级结构应一致", freshColumns[name], upgradedColumns[name])
                }
            }
        } finally {
            migrated.close(); fresh.close(); context.deleteDatabase(name)
        }
    }

    @Test
    fun dismissAndClearOnlyTouchTerminalRecordsAndSkipActivePublications() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val downloadDone = db.downloads().insert(download(DownloadStatus.DONE))
            val downloadPending = db.downloads().insert(download(DownloadStatus.DONE).copy(pendingBiliSubtitle = true))
            val downloadFailed = db.downloads().insert(download(DownloadStatus.FAILED))
            assertEquals(1, db.downloads().dismissAllDone(100))
            assertNotNull(db.downloads().get(downloadDone)!!.dismissedAt)
            assertNull(db.downloads().get(downloadPending)!!.dismissedAt)
            assertNull(db.downloads().get(downloadFailed)!!.dismissedAt)
            assertEquals(setOf(downloadPending, downloadFailed), db.downloads().observeVisible().first().map { it.id }.toSet())
            assertEquals(0, db.downloads().dismissDone(downloadPending, 101))

            val whisperDone = db.whisper().insert(whisper(DownloadStatus.DONE))
            val whisperTranslated = db.whisper().insert(whisper(DownloadStatus.DONE, TranslateMark.DONE))
            val whisperTranslateFailed = db.whisper().insert(whisper(DownloadStatus.DONE, TranslateMark.FAILED))
            val whisperTranslating = db.whisper().insert(whisper(DownloadStatus.DONE, TranslateMark.RUNNING))
            val whisperFailed = db.whisper().insert(whisper(DownloadStatus.FAILED))
            assertEquals(0, db.whisper().dismissTerminal(whisperTranslating, 200))
            assertEquals(1, db.whisper().dismissTerminal(whisperTranslateFailed, 200))
            assertNotNull(db.whisper().get(whisperTranslateFailed)!!.dismissedAt)
            assertNull(db.whisper().get(whisperTranslating)!!.dismissedAt)
            assertEquals(2, db.whisper().dismissAllCompleted(300))
            assertNotNull(db.whisper().get(whisperDone)!!.dismissedAt)
            assertNotNull(db.whisper().get(whisperTranslated)!!.dismissedAt)
            assertNull(db.whisper().get(whisperFailed)!!.dismissedAt)
            assertEquals(setOf(whisperTranslating, whisperFailed), db.whisper().observeVisible().first().map { it.id }.toSet())

            val publishedWhisper = db.whisper().insert(whisper(DownloadStatus.DONE))
            db.translations().stagePublication(SubtitlePublicationEntity("published-whisper.json", "{}", publishedWhisper, null, 0))
            assertEquals(0, db.whisper().dismissTerminal(publishedWhisper, 400))
            assertEquals(0, db.whisper().dismissAllCompleted(401))
            assertNull(db.whisper().get(publishedWhisper)!!.dismissedAt)
            assertTrue(publishedWhisper in db.translations().observeWhisperIds().first())

            val translationDone = db.translations().insert(translation(DownloadStatus.DONE))
            val translationAlsoDone = db.translations().insert(translation(DownloadStatus.DONE))
            val translationFailed = db.translations().insert(translation(DownloadStatus.FAILED))
            val translationPublished = db.translations().insert(translation(DownloadStatus.DONE))
            db.translations().stagePublication(SubtitlePublicationEntity("published-translation.json", "{}", null, translationPublished, 0))
            assertEquals(0, db.translations().dismissDone(translationPublished, 500))
            assertEquals(2, db.translations().dismissAllCompleted(501))
            assertNotNull(db.translations().get(translationDone)!!.dismissedAt)
            assertNotNull(db.translations().get(translationAlsoDone)!!.dismissedAt)
            assertNull(db.translations().get(translationFailed)!!.dismissedAt)
            assertNull(db.translations().get(translationPublished)!!.dismissedAt)
            assertTrue(translationPublished in db.translations().observeTranslationIds().first())
            assertEquals(setOf(translationFailed, translationPublished),
                db.translations().observeVisible().first().map { it.id }.toSet())
        } finally {
            db.close()
        }
    }

    private fun download(status: DownloadStatus) = DownloadEntity(
        trackId = 1, title = "fixture", bytesDone = 0, bytesTotal = 0, status = status, error = null,
    )

    private fun whisper(status: DownloadStatus, translateStatus: String? = null) = WhisperJobEntity(
        title = "fixture", fingerprint = "sha", trackId = null, localPath = "/audio",
        remoteJobId = null, status = status, progress = 0f, error = null, awaitRemoteUrl = null,
        translateStatus = translateStatus,
    )

    private fun translation(status: DownloadStatus) = TranslationJobEntity(
        title = "fixture", trackId = null, remoteUrl = null, queueItemId = null, status = status, error = null,
    )
}
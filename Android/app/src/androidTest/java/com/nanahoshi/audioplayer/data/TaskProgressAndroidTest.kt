package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.text.resolveUserError
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.resolve
import com.nanahoshi.audioplayer.text.resolveNoUrl
import com.nanahoshi.audioplayer.R
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.db.*
import com.nanahoshi.audioplayer.playback.SubtitleSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TaskProgressAndroidTest {
    @Test fun resolvesArgumentsAndRejectsAddressesInFinalText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = TextRef.Res(
            R.string.translate_error_model_response,
            listOf(TextRef.Raw("https://example.test/private")),
        )
        assertEquals(context.getString(R.string.translate_status_failed), context.resolveNoUrl(text, R.string.translate_status_failed))
        assertEquals("GitHub · 75%", context.resolve(TextRef.Res(R.string.notification_model_progress, listOf("GitHub", 75))))
    }

    @Test fun invalidWhisperAddressKeepsTheFormatHintInFinalText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val error = WhisperException(TextRef.Res(R.string.whisper_error_invalid_base))
        assertEquals(
            context.getString(R.string.whisper_error_invalid_base),
            context.resolveUserError(error, R.string.whisper_error_failed),
        )
    }

    @Test fun migration19To20MatchesFreshTablesAndKeepsOldRowsNull() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "progress-migration-${UUID.randomUUID()}.db"
        val original = Room.databaseBuilder(app, AppDatabase::class.java, name).build()
        val audio = original.downloads().insert(download())
        val asr = original.whisper().insert(whisper())
        val translation = original.translations().insert(translation())
        original.close()
        // 从当前完整 schema 删除本次新增列，构造真实的 v19 三表结构。
        SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
            for (table in listOf("downloads", "whisper_jobs", "translation_jobs")) old.execSQL("ALTER TABLE $table DROP COLUMN progressSummary")
            old.execSQL("ALTER TABLE downloads DROP COLUMN jaEntryKey")
            old.execSQL("ALTER TABLE downloads DROP COLUMN jaReplace")
            old.execSQL("ALTER TABLE downloads DROP COLUMN jaForce")
            old.execSQL("ALTER TABLE queue_items DROP COLUMN mediaPathHash")
            old.execSQL("DROP TABLE japaneseasmr_download_entries")
            old.execSQL("DROP TABLE japaneseasmr_download_packages")
            old.execSQL("DROP TABLE japaneseasmr_download_works")
            old.execSQL("ALTER TABLE queue_items DROP COLUMN mediaKind")
            old.execSQL("DROP TABLE japaneseasmr_media")
            old.execSQL("DROP TABLE japaneseasmr_posts")
            old.version = 19
        }
        val upgraded = Room.databaseBuilder(app, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_19_20, AppDatabase.MIGRATION_20_21, AppDatabase.MIGRATION_21_22).build()
        val fresh = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            assertNull(upgraded.downloads().get(audio)!!.progressSummary)
            assertNull(upgraded.whisper().get(asr)!!.progressSummary)
            assertNull(upgraded.translations().get(translation)!!.progressSummary)
            assertEquals("fixture", upgraded.downloads().get(audio)!!.title)
            fun columns(db: AppDatabase) = buildList {
                assertEquals(22, db.openHelper.writableDatabase.version)
                for (table in listOf("downloads", "whisper_jobs", "translation_jobs")) {
                    db.openHelper.writableDatabase.query("PRAGMA table_info($table)").use { cursor ->
                        while (cursor.moveToNext()) add(table + "|" + (1 until cursor.columnCount).joinToString("|") { cursor.getString(it) ?: "NULL" })
                    }
                }
            }.sorted()
            assertEquals(columns(fresh), columns(upgraded))
        } finally { upgraded.close(); fresh.close(); app.deleteDatabase(name) }
    }

    @Test fun allRequeuesClearThePreviousAttemptSummary() = withDatabase { _, db ->
        val encoded = TaskProgressSummary().merge(ProgressPart.DOWNLOAD, TaskSegmentSummary(result = ProgressResult.FAILED)).encode()
        val audio = db.downloads().insert(download().copy(progressSummary = encoded))
        val asr = db.whisper().insert(whisper().copy(progressSummary = encoded))
        val translated = db.translations().insert(translation().copy(progressSummary = encoded))
        db.downloads().requeue(audio)
        db.whisper().requeueWithFile(asr, "fixture", "sha", null, "/audio", null, null, true, WHISPER_MODEL_QWEN)
        db.translations().requeue(translated)
        assertNull(db.downloads().get(audio)!!.progressSummary)
        assertNull(db.whisper().get(asr)!!.progressSummary)
        assertNull(db.translations().get(translated)!!.progressSummary)
        db.whisper().setProgressSummary(asr, encoded)
        db.whisper().requeueForDownload(asr)
        assertNull(db.whisper().get(asr)!!.progressSummary)
    }

    @Test fun sharedCenterMergesSubtasksAndFinalizesBulkFailureWithoutNumericWrites() = withDatabase { _, db ->
        val audio = db.downloads().insert(download().copy(status = DownloadStatus.RUNNING))
        val center = TaskProgressCenter(db)
        try {
            val key = TaskKey(TaskKind.DOWNLOAD, audio)
            val report = center.begin(key, ProgressPart.DOWNLOAD)
            report.phase(ProgressPhase.DOWNLOADING).bytes(1000, 1000, 5000)
            center.tracker.sample()
            db.downloads().stopActive(audio, "后台下载超时")
            center.finishFromDb(key, ProgressPart.DOWNLOAD, true)
            waitUntil { decodeProgressSummary(db.downloads().get(audio)!!.progressSummary).parts[ProgressPart.DOWNLOAD]?.result == ProgressResult.INTERRUPTED }
            val summary = decodeProgressSummary(db.downloads().get(audio)!!.progressSummary).parts[ProgressPart.DOWNLOAD]!!
            assertEquals(1000L, summary.transferredBytes)
            val child = center.begin(key.copy(child = ProgressPart.BILI_SUBTITLE), ProgressPart.BILI_SUBTITLE, ProgressPhase.FETCHING_SUBTITLE)
            child.finish(ProgressResult.NEEDS_LOGIN)
            waitUntil { decodeProgressSummary(db.downloads().get(audio)!!.progressSummary).parts.size == 2 }
            assertEquals(summary, decodeProgressSummary(db.downloads().get(audio)!!.progressSummary).parts[ProgressPart.DOWNLOAD])
            assertFalse(center.tracker.hasActive())

            center.reset(key)
            db.downloads().requeue(audio)
            report.phase(ProgressPhase.SAVING); report.finish()
            delay(50)
            assertNull(db.downloads().get(audio)!!.progressSummary)
        } finally { center.close() }
    }

    @Test fun automaticTranslationSummaryPreservesTranscriptionAndInterruptedStatsAreUnknown() = withDatabase { _, db ->
        val asr = TaskSegmentSummary(result = ProgressResult.COMPLETED, elapsedMs = 2000, model = "Qwen3-ASR")
        val id = db.whisper().insert(whisper().copy(status = DownloadStatus.DONE, translateStatus = TranslateMark.RUNNING,
            progressSummary = TaskProgressSummary().merge(ProgressPart.TRANSCRIPTION, asr).encode()))
        val center = TaskProgressCenter(db)
        try {
            val key = TaskKey(TaskKind.WHISPER, id)
            val report = center.begin(key, ProgressPart.TRANSLATION, ProgressPhase.TRANSLATING)
            report.units(20, 40); report.batch(1, 2)
            db.whisper().markTranslated(id, 0)
            center.end(report)
            waitUntil { decodeProgressSummary(db.whisper().get(id)!!.progressSummary).parts[ProgressPart.TRANSLATION]?.result == ProgressResult.COMPLETED }
            assertEquals(asr, decodeProgressSummary(db.whisper().get(id)!!.progressSummary).parts[ProgressPart.TRANSCRIPTION])
            center.interrupted(key, ProgressPart.TRANSLATION)
            val interrupted = decodeProgressSummary(db.whisper().get(id)!!.progressSummary).parts[ProgressPart.TRANSLATION]!!
            assertNull(interrupted.elapsedMs); assertNull(interrupted.done)
            assertEquals(ProgressResult.INTERRUPTED, interrupted.result)
            assertEquals(asr, decodeProgressSummary(db.whisper().get(id)!!.progressSummary).parts[ProgressPart.TRANSCRIPTION])
        } finally { center.close() }
    }

    @Test fun publicationRecoveryCompletesSummaryAfterWritingAndPreservesAsrPart() = withDatabase { context, db ->
        val asr = TaskSegmentSummary(result = ProgressResult.COMPLETED, model = WHISPER_MODEL_QWEN)
        val summary = TaskProgressSummary().merge(ProgressPart.TRANSCRIPTION, asr)
            .merge(ProgressPart.TRANSLATION, TaskSegmentSummary(phase = ProgressPhase.SAVING_TRANSLATION, result = ProgressResult.INTERRUPTED, elapsedMs = 999)).encode()
        val id = db.whisper().insert(whisper().copy(status = DownloadStatus.DONE, translateStatus = TranslateMark.RUNNING, progressSummary = summary))
        db.translations().stagePublication(SubtitlePublicationEntity("fixture.json", "{}", id, null, 123))
        val source = SubtitleSource(context, db, BilibiliClient(CredentialStore(context)), AsmrClient())
        source.recoverPublications()
        val done = db.whisper().get(id)!!
        val recovered = decodeProgressSummary(done.progressSummary)
        assertEquals(TranslateMark.DONE, done.translateStatus)
        assertEquals(ProgressResult.COMPLETED, recovered.parts[ProgressPart.TRANSLATION]!!.result)
        assertNull(recovered.parts[ProgressPart.TRANSLATION]!!.elapsedMs)
        assertEquals(asr, recovered.parts[ProgressPart.TRANSCRIPTION])
        assertTrue(File(context.filesDir, "subtitles/fixture.json").isFile)
        assertTrue(db.translations().pendingPublications().isEmpty())
    }

    @Test fun failedPublicationRecoveryRecordsSavingFailure() = withDatabase { context, db ->
        val id = db.translations().insert(translation().copy(status = DownloadStatus.RUNNING))
        File(context.filesDir, "subtitles").writeText("blocks-directory")
        db.translations().stagePublication(SubtitlePublicationEntity("fixture.json", "{}", null, id, 0))
        SubtitleSource(context, db, BilibiliClient(CredentialStore(context)), AsmrClient()).recoverPublications()
        val done = db.translations().get(id)!!
        assertEquals(DownloadStatus.FAILED, done.status)
        val summary = decodeProgressSummary(done.progressSummary).parts[ProgressPart.TRANSLATION]!!
        assertEquals(ProgressResult.FAILED, summary.result)
        assertEquals(ProgressPhase.SAVING_TRANSLATION, summary.failurePhase)
    }

    private fun download() = DownloadEntity(trackId = 1, title = "fixture", bytesDone = 0, bytesTotal = 0, status = DownloadStatus.FAILED, error = "fixture")
    private fun whisper() = WhisperJobEntity(title = "fixture", fingerprint = "sha", trackId = null, localPath = "/audio", remoteJobId = null,
        status = DownloadStatus.FAILED, progress = 0f, error = "fixture", awaitRemoteUrl = null, model = WHISPER_MODEL_QWEN)
    private fun translation() = TranslationJobEntity(title = "fixture", trackId = null, remoteUrl = null, queueItemId = null, status = DownloadStatus.FAILED, error = "fixture")
    private suspend fun waitUntil(condition: suspend () -> Boolean) = withTimeout(5000) { while (!condition()) delay(10) }
    private fun withDatabase(block: suspend (Context, AppDatabase) -> Unit) = runBlocking {
        withTimeout(10_000) {
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(app.cacheDir, "progress-${UUID.randomUUID()}").apply { mkdirs() }
            val context = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = root
                override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("${root.name}-$name", mode)
            }
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try { block(context, db) } finally { db.close(); root.deleteRecursively() }
        }
    }
}

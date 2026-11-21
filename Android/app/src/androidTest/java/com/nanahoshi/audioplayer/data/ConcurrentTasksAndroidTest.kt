package com.nanahoshi.audioplayer.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.playback.SubtitleSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConcurrentTasksAndroidTest {
    @Test fun onlyOneWorkerCanClaimTheSameDownload() = withDatabase { _, db ->
        val id = db.downloads().insert(DownloadEntity(trackId = 1, title = "audio", bytesDone = 0,
            bytesTotal = 0, status = DownloadStatus.QUEUED, error = null))
        val claimed = (1..5).map { async(Dispatchers.IO) { db.downloads().startQueued(id) } }.awaitAll()
        assertEquals(1, claimed.sum())
        assertEquals(DownloadStatus.RUNNING, db.downloads().get(id)?.status)
    }

    @Test fun queuedAutomaticTranslationSurvivesRestartWhileRunningTranslationFails() = withDatabase { _, db ->
        val queued = db.whisper().insert(whisper("queued"))
        val running = db.whisper().insert(whisper("running"))
        assertEquals(1, db.whisper().queueTranslationIfActive(queued))
        assertEquals(1, db.whisper().queueTranslationIfActive(running))
        assertEquals(1, db.whisper().startQueuedTranslation(running))
        db.whisper().failInterruptedTranslations(SUBTITLE_INTERRUPTED)
        assertEquals(TranslateMark.QUEUED, db.whisper().get(queued)?.translateStatus)
        assertEquals(TranslateMark.FAILED, db.whisper().get(running)?.translateStatus)
        assertEquals(listOf(queued), db.whisper().pendingWork().map { it.id })
        assertEquals(1, db.whisper().startQueuedTranslation(queued))
        assertEquals(0, db.whisper().startQueuedTranslation(queued))
        assertEquals(DownloadStatus.DONE, db.whisper().get(running)?.status)
    }

    @Test fun queuedTranslationCanCancelAndServiceStopFailsAllPendingPhases() = withDatabase { _, db ->
        val cancelled = db.whisper().insert(whisper("cancelled"))
        val waiting = db.whisper().insert(whisper("waiting"))
        db.whisper().queueTranslationIfActive(cancelled)
        db.whisper().queueTranslationIfActive(waiting)
        assertEquals(1, db.whisper().failTranslateIfUnpublished(cancelled, "已中止"))
        assertEquals(0, db.whisper().startQueuedTranslation(cancelled))
        assertEquals(DownloadStatus.DONE, db.whisper().get(cancelled)?.status)
        db.whisper().failActiveTranslations("后台字幕任务超时")
        assertEquals(TranslateMark.FAILED, db.whisper().get(waiting)?.translateStatus)
        assertEquals("已中止", db.whisper().get(cancelled)?.error)
        assertTrue(db.whisper().pendingWork().isEmpty())
    }

    @Test fun automaticTranslationsDeduplicateByFingerprintBeforeAnyNetworkRequest() = withDatabase { context, db ->
        val credentials = CredentialStore(context)
        val subtitles = SubtitleSource(context, db, BilibiliClient(credentials), AsmrClient())
        val translator = TranslateCenter(context, db, credentials, subtitles, SubtitleTaskRequests { false },
            Job().apply { complete() })
        try {
            val first = db.whisper().insert(whisper("same"))
            val second = db.whisper().insert(whisper("same"))
            subtitles.generatedWriteLock.withLock {
                assertTrue(translator.queueGenerated(first, "same"))
                assertFalse(translator.queueGenerated(second, "same"))
            }
            assertEquals(first, db.whisper().activeTranslation("same")?.id)
            assertEquals(DownloadStatus.DONE, db.whisper().get(second)?.status)
            assertNull(db.whisper().get(second)?.translateStatus)
        } finally { translator.close() }
    }

    @Test fun downloadConcurrencyPersistsAndCanReturnToOne() = withDatabase { context, _ ->
        val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val initial = DownloadPreferences(context)
        assertEquals(3, initial.maxConcurrentDownloads.value)
        initial.setMaxConcurrentDownloads(5)
        assertEquals(5, DownloadPreferences(context).maxConcurrentDownloads.value)
        initial.setMaxConcurrentDownloads(1)
        assertEquals(1, initial.maxConcurrentDownloads.value)
        prefs.edit().putInt("concurrency", 0).commit()
        assertEquals(3, DownloadPreferences(context).maxConcurrentDownloads.value)
    }

    private fun whisper(fingerprint: String) = WhisperJobEntity(title = fingerprint, fingerprint = fingerprint,
        trackId = null, localPath = "/audio", remoteJobId = null, status = DownloadStatus.RUNNING,
        progress = 0f, error = null, awaitRemoteUrl = null, model = WHISPER_MODEL_QWEN)

    private fun withDatabase(block: suspend kotlinx.coroutines.CoroutineScope.(Context, AppDatabase) -> Unit) = runBlocking {
        withTimeout(5_000) {
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(app.cacheDir, "concurrent-${UUID.randomUUID()}").apply { mkdirs() }
            val context = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = root
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences("${root.name}-$name", mode)
            }
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try { block(context, db) } finally { db.close(); root.deleteRecursively() }
        }
    }
}

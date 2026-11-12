package com.nanahoshi.audioplayer.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TranslationJobEntity
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.playback.SubtitleCue
import com.nanahoshi.audioplayer.playback.SubtitleSource
import com.nanahoshi.audioplayer.playback.overlayFileName
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StoredCatalogOverlayAndroidTest {
    @Test fun overlayIsListedAndDeletableWithoutTouchingWhisper() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "stored-overlay-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = root
            override fun getApplicationContext(): Context = this
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val audio = File(root, "asmr/RJ001/song.mp3").apply { parentFile?.mkdirs(); writeText("audio") }
            val trackId = db.tracks().insert(
                TrackEntity(source = TrackSource.ASMR, title = "song", artist = null, durationMs = 1000,
                    coverUri = null, localUri = audio.absolutePath, bvid = null, cid = null, page = null, addedAt = 0))
            val subtitlesDir = File(root, "subtitles").apply { mkdirs() }
            val cue = File(subtitlesDir, "abc.json").apply { writeText("[]") }
            db.whisper().insert(
                WhisperJobEntity(title = "song.mp3", fingerprint = "abc", trackId = trackId,
                    localPath = audio.absolutePath, remoteJobId = null, status = DownloadStatus.DONE,
                    progress = 1f, error = null, awaitRemoteUrl = null))
            val overlay = File(subtitlesDir, overlayFileName(trackId, null, null, null)!!).apply { writeText("[]") }
            db.translations().insert(
                TranslationJobEntity(title = "song.mp3", trackId = trackId, remoteUrl = null,
                    queueItemId = null, status = DownloadStatus.DONE, error = null))
            val credentials = CredentialStore(context)
            val subtitles = SubtitleSource(context, db, BilibiliClient(credentials), AsmrClient())
            val catalog = StoredCatalog(context, db, subtitles)

            val listed = catalog.list()
            val item = listed.single { it.path == overlay.absolutePath }
            assertEquals(StoredKind.GENERATED, item.kind)
            assertEquals("song.json", item.name)
            assertEquals(trackId, item.trackId)
            assertTrue(listed.any { it.path == cue.absolutePath })

            catalog.delete(listOf(item), deleteSubtitles = true)
            assertFalse(overlay.exists())
            assertNull(db.translations().latestForTrack(trackId))
            assertTrue(cue.isFile)
            assertNotNull(db.whisper().doneForTrack(trackId).firstOrNull())

            // 重翻失败后旧覆盖文件仍应列出并可删除。
            overlay.writeText("[]")
            db.translations().insert(
                TranslationJobEntity(title = "song.mp3", trackId = trackId, remoteUrl = null,
                    queueItemId = null, status = DownloadStatus.FAILED, error = "翻译失败"))
            val again = catalog.list().single { it.path == overlay.absolutePath }
            catalog.delete(listOf(again), deleteSubtitles = true)
            assertFalse(overlay.exists())
            assertTrue(cue.isFile)
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    private fun testContext(root: File): Context = object : ContextWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext,
    ) {
        override fun getFilesDir(): File = root
        override fun getApplicationContext(): Context = this
    }

    private fun queueRow(trackId: Long?, queueItemId: Long = 1L) = QueuedTrack(
        queueItemId = queueItemId, position = 0, trackId = trackId, source = TrackSource.ASMR,
        title = "song", artist = null, durationMs = 1000, coverUri = null, localUri = null,
        bvid = null, cid = null, page = null, remoteUrl = null, referer = null, workno = null, fileKey = null,
    )

    @Test fun runningTranslationSurvivesOverlayDeleteAndCompletes() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "stored-overlay-running-${UUID.randomUUID()}").apply { mkdirs() }
        val context = testContext(root)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val trackId = db.tracks().insert(
                TrackEntity(source = TrackSource.ASMR, title = "song", artist = null, durationMs = 1000,
                    coverUri = null, localUri = null, bvid = null, cid = null, page = null, addedAt = 0))
            val subtitlesDir = File(root, "subtitles").apply { mkdirs() }
            val overlay = File(subtitlesDir, overlayFileName(trackId, null, null, null)!!).apply { writeText("[]") }
            val jobId = db.translations().insert(
                TranslationJobEntity(title = "song", trackId = trackId, remoteUrl = null,
                    queueItemId = null, status = DownloadStatus.RUNNING, error = null))
            val subtitles = SubtitleSource(context, db, BilibiliClient(CredentialStore(context)), AsmrClient())
            val catalog = StoredCatalog(context, db, subtitles)

            val item = catalog.list().single { it.path == overlay.absolutePath }
            catalog.delete(listOf(item), deleteSubtitles = true)
            assertFalse(overlay.exists())
            assertEquals(DownloadStatus.RUNNING, db.translations().latestForTrack(trackId)?.status)

            // 模拟运行器完成：真实写路径重建覆盖文件，条件提交成功，记录转 DONE。
            subtitles.saveTranslations(queueRow(trackId), listOf(SubtitleCue(0, 1000, "こんにちは", "你好")))
            assertEquals(1, db.translations().finishIfActive(jobId))
            assertTrue(overlay.isFile)
            assertEquals(DownloadStatus.DONE, db.translations().latestForTrack(trackId)?.status)
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun audioAndSubtitlesDeletePreservesRunningTranslation() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "stored-audio-running-${UUID.randomUUID()}").apply { mkdirs() }
        val context = testContext(root)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val audio = File(root, "asmr/RJ001/song.mp3").apply { parentFile?.mkdirs(); writeText("audio") }
            val trackId = db.tracks().insert(
                TrackEntity(source = TrackSource.ASMR, title = "song", artist = null, durationMs = 1000,
                    coverUri = null, localUri = audio.absolutePath, bvid = null, cid = null, page = null, addedAt = 0))
            val subtitlesDir = File(root, "subtitles").apply { mkdirs() }
            val cue = File(subtitlesDir, "abc.json").apply { writeText("[]") }
            db.whisper().insert(
                WhisperJobEntity(title = "song.mp3", fingerprint = "abc", trackId = trackId,
                    localPath = audio.absolutePath, remoteJobId = null, status = DownloadStatus.DONE,
                    progress = 1f, error = null, awaitRemoteUrl = null))
            val overlay = File(subtitlesDir, overlayFileName(trackId, null, null, null)!!).apply { writeText("[]") }
            db.translations().insert(
                TranslationJobEntity(title = "song.mp3", trackId = trackId, remoteUrl = null,
                    queueItemId = null, status = DownloadStatus.RUNNING, error = null))
            val subtitles = SubtitleSource(context, db, BilibiliClient(CredentialStore(context)), AsmrClient())
            val catalog = StoredCatalog(context, db, subtitles)

            val audioItem = catalog.list().single { it.kind == StoredKind.AUDIO }
            catalog.delete(listOf(audioItem), deleteSubtitles = true)
            assertFalse(audio.exists())
            assertFalse(overlay.exists())
            assertFalse(cue.exists())
            assertTrue(db.whisper().doneForTrack(trackId).isEmpty())
            assertEquals(DownloadStatus.RUNNING, db.translations().latestForTrack(trackId)?.status)
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun overlayDeleteRemovesTerminalHistoryButKeepsRunning() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "stored-overlay-mixed-${UUID.randomUUID()}").apply { mkdirs() }
        val context = testContext(root)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val trackId = db.tracks().insert(
                TrackEntity(source = TrackSource.ASMR, title = "song", artist = null, durationMs = 1000,
                    coverUri = null, localUri = null, bvid = null, cid = null, page = null, addedAt = 0))
            val subtitlesDir = File(root, "subtitles").apply { mkdirs() }
            val overlay = File(subtitlesDir, overlayFileName(trackId, null, null, null)!!).apply { writeText("[]") }
            for (status in listOf(DownloadStatus.DONE, DownloadStatus.FAILED, DownloadStatus.RUNNING)) {
                db.translations().insert(
                    TranslationJobEntity(title = "song", trackId = trackId, remoteUrl = null,
                        queueItemId = null, status = status, error = null))
            }
            val subtitles = SubtitleSource(context, db, BilibiliClient(CredentialStore(context)), AsmrClient())
            val catalog = StoredCatalog(context, db, subtitles)

            val item = catalog.list().single { it.path == overlay.absolutePath }
            catalog.delete(listOf(item), deleteSubtitles = true)
            assertFalse(overlay.exists())
            assertEquals(listOf(DownloadStatus.RUNNING), db.translations().all().map { it.status })
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun discardByQueueIdentityKeepsRunningTranslation() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "stored-overlay-queue-${UUID.randomUUID()}").apply { mkdirs() }
        val context = testContext(root)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val subtitlesDir = File(root, "subtitles").apply { mkdirs() }
            val overlay = File(subtitlesDir, overlayFileName(null, null, null, 42L)!!).apply { writeText("[]") }
            db.translations().insert(
                TranslationJobEntity(title = "song", trackId = null, remoteUrl = null,
                    queueItemId = 42L, status = DownloadStatus.RUNNING, error = null))
            val subtitles = SubtitleSource(context, db, BilibiliClient(CredentialStore(context)), AsmrClient())

            subtitles.discard(queueRow(trackId = null, queueItemId = 42L))
            assertFalse(overlay.exists())
            assertEquals(DownloadStatus.RUNNING, db.translations().latestForQueue(42L)?.status)
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }
}
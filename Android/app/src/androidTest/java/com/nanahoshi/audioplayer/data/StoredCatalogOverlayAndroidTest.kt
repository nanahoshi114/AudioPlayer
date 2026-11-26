package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.text.PersistedErrors
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.FileKind
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.JapaneseAsmrMediaEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.SubtitlePublicationEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleBindingEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleKind
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.TranslationJobEntity
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.japaneseasmr.jaIdentity
import com.nanahoshi.audioplayer.playback.LoadedSubtitles
import com.nanahoshi.audioplayer.playback.SubtitleCue
import com.nanahoshi.audioplayer.playback.SubtitleLanguageDelete
import com.nanahoshi.audioplayer.playback.SubtitleLanguageDeleteResult
import com.nanahoshi.audioplayer.playback.SubtitleOrigin
import com.nanahoshi.audioplayer.playback.SubtitleSource
import com.nanahoshi.audioplayer.playback.encodeGeneratedCues
import com.nanahoshi.audioplayer.playback.hasTranslation
import com.nanahoshi.audioplayer.playback.overlayFileName
import com.nanahoshi.audioplayer.playback.parseGeneratedSubtitles
import com.nanahoshi.audioplayer.playback.subtitleSourceFingerprint
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
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

    @Test fun fingerprintedOverlayAndGeneratedFileAreKeptWhilePublicationIsPending() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "stored-pending-${UUID.randomUUID()}").apply { mkdirs() }
        val context = testContext(root)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val subtitles = SubtitleSource(context, db, BilibiliClient(CredentialStore(context)), AsmrClient())
            val audio = File(root, "asmr/RJ001/song.mp3").apply { parentFile?.mkdirs(); writeText("audio") }
            val trackId = db.tracks().insert(TrackEntity(
                source = TrackSource.ASMR, title = "song", artist = null, durationMs = 1000, coverUri = null,
                localUri = audio.path, bvid = null, cid = null, page = null, addedAt = 0,
            ))
            val row = queueRow(trackId)
            val overlay = subtitles.expectedOverlayFile(row, listOf(SubtitleCue(0, 1000, "こんにちは")))
                .apply { parentFile?.mkdirs(); writeText("[]") }
            val cue = subtitles.generatedFile("abc").apply { writeText("[]") }
            val whisperId = db.whisper().insert(WhisperJobEntity(
                title = "song", fingerprint = "abc", trackId = trackId, localPath = audio.path, remoteJobId = null,
                status = DownloadStatus.DONE, progress = 1f, error = null, awaitRemoteUrl = null,
            ))
            val translationId = db.translations().insert(TranslationJobEntity(
                title = "song", trackId = trackId, remoteUrl = null, queueItemId = row.queueItemId,
                status = DownloadStatus.RUNNING, error = null, outputFilename = overlay.name,
            ))
            db.translations().stagePublication(SubtitlePublicationEntity(overlay.name, "[]", null, translationId, 1))
            db.translations().stagePublication(SubtitlePublicationEntity(cue.name, "[]", whisperId, null, 1))

            val catalog = StoredCatalog(context, db, subtitles)
            val items = catalog.list()
            val overlayItem = items.single { it.path == overlay.absolutePath }
            assertEquals(trackId, overlayItem.trackId)
            assertTrue(overlayItem.translationOverlay)
            catalog.delete(items, deleteSubtitles = true)

            assertFalse(audio.exists())
            assertTrue(overlay.isFile)
            assertTrue(cue.isFile)
            assertNotNull(db.whisper().get(whisperId))
            assertEquals(2, db.translations().pendingPublications().size)

            subtitles.recoverPublications()
            catalog.delete(listOf(overlayItem), deleteSubtitles = true)
            assertFalse(overlay.exists())
            assertNull(db.translations().get(translationId))
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

    private fun withSubtitleFixture(test: suspend (AppDatabase, File, SubtitleSource) -> Unit) = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "removable-subtitles-${UUID.randomUUID()}").apply { mkdirs() }
        val context = testContext(root)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val subtitles = SubtitleSource(context, db, BilibiliClient(CredentialStore(context)), AsmrClient())
            test(db, root, subtitles)
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    private suspend fun newTrack(db: AppDatabase, source: TrackSource, folderId: Long? = null): TrackEntity {
        val id = db.tracks().insert(TrackEntity(
            source = source, title = "song", artist = null, durationMs = 1000, coverUri = null,
            localUri = null, bvid = null, cid = null, page = null, addedAt = 0, folderId = folderId,
        ))
        return checkNotNull(db.tracks().getById(id))
    }

    @Test fun removableSubtitleDetectionCountsCorruptOverlayWithoutGeneratedFile() =
        withSubtitleFixture { db, root, subtitles ->
            val track = newTrack(db, TrackSource.ASMR)
            val name = checkNotNull(overlayFileName(track.id, null, null, null))
            File(root, "subtitles/$name").apply {
                parentFile?.mkdirs()
                writeText("{not-json")
            }
            assertTrue(subtitles.hasRemovableSubtitlesForTrack(track))

            val untouched = newTrack(db, TrackSource.ASMR)
            assertFalse(subtitles.hasRemovableSubtitlesForTrack(untouched))
        }

    @Test fun removableSubtitleDetectionCountsJapaneseAsmrIdentityOverlay() =
        withSubtitleFixture { db, root, subtitles ->
            val folder = db.folders().insert(FolderEntity(name = "work", position = 0, asmrSourceId = "RJ123456"))
            val track = newTrack(db, TrackSource.JAPANESE_ASMR, folder)
            db.japaneseAsmr().upsertMedia(
                JapaneseAsmrMediaEntity("51", "p:abc", track.id, "DIRECT", "voice", 1000, null),
            )
            val name = checkNotNull(overlayFileName(track.id, jaIdentity("51", "p:abc"), null, null))
            File(root, "subtitles/$name").apply {
                parentFile?.mkdirs()
                writeText("[]")
            }
            assertTrue(subtitles.hasRemovableSubtitlesForTrack(track))
        }

    @Test fun removableSubtitleDetectionCountsCorruptAndEmptyGeneratedFiles() =
        withSubtitleFixture { db, root, subtitles ->
            val track = newTrack(db, TrackSource.ASMR)
            val fingerprint = "abc123"
            db.whisper().insert(WhisperJobEntity(
                title = "song", fingerprint = fingerprint, trackId = track.id, localPath = null,
                remoteJobId = null, status = DownloadStatus.DONE, progress = 1f, error = null, awaitRemoteUrl = null,
            ))
            val cue = subtitles.generatedFile(fingerprint).apply {
                parentFile?.mkdirs()
                writeText("not json")
            }
            assertTrue(subtitles.hasRemovableSubtitlesForTrack(track))

            cue.writeText("{\"cues\":[]}")
            assertTrue(subtitles.hasRemovableSubtitlesForTrack(track))

            assertTrue(cue.delete())
            assertFalse(subtitles.hasRemovableSubtitlesForTrack(track))
        }

    @Test fun manualBindingWithoutGeneratedFilesIsNotRemovable() =
        withSubtitleFixture { db, root, subtitles ->
            val folder = db.folders().insert(FolderEntity(name = "work", position = 0))
            val srt = File(root, "work/manual.srt").apply {
                parentFile?.mkdirs()
                writeText("1\n00:00:01,000 --> 00:00:02,000\nはい\n")
            }
            val fileId = db.files().insert(LibraryFileEntity(
                folderId = folder, name = "manual.srt", kind = FileKind.TEXT, remoteUrl = "", addedAt = 0,
                asmrWorkId = 1, fileHash = "asmr:manual.srt", localUri = srt.path,
            ))
            val track = newTrack(db, TrackSource.ASMR, folder)
            db.trackSubtitles().upsert(TrackSubtitleBindingEntity(
                trackId = track.id, kind = TrackSubtitleKind.LIBRARY_FILE, fileId = fileId,
                displayName = "manual.srt", updatedAt = 0,
            ))
            assertFalse(subtitles.hasRemovableSubtitlesForTrack(track))
        }

    private fun queueRow(trackId: Long?, queueItemId: Long = 1L) = QueuedTrack(
        queueItemId = queueItemId, position = 0, trackId = trackId, source = TrackSource.ASMR,
        title = "song", artist = null, durationMs = 1000, coverUri = null, localUri = null,
        bvid = null, cid = null, page = null, remoteUrl = null, referer = null, workno = null, fileKey = null,
    )

    @Test fun revisedTranslationPublishesFromOneSourceAndKeepsItAvailable() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "subtitle-revision-${UUID.randomUUID()}").apply { mkdirs() }
        val context = testContext(root)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val audio = File(root, "song.mp3").apply { writeText("audio") }
            val srt = File(root, "song.srt").apply {
                writeText("1\n00:00:00,000 --> 00:00:00,800\nはい\n\n2\n00:00:00,900 --> 00:00:01,800\nそうです\n")
            }
            val trackId = db.tracks().insert(TrackEntity(
                source = TrackSource.LOCAL, title = "song", artist = null, durationMs = 1800,
                coverUri = null, localUri = audio.absolutePath, bvid = null, cid = null, page = null, addedAt = 0,
            ))
            val row = queueRow(trackId).copy(source = TrackSource.LOCAL, localUri = audio.absolutePath)
            val subtitles = SubtitleSource(context, db, BilibiliClient(CredentialStore(context)), AsmrClient())
            val source = subtitles.load(row)
            assertEquals(listOf("はい", "そうです"), source.cues.map { it.text })
            val revised = listOf(SubtitleCue(0, 1800, "はい、そうです。", "嗯，是的。", sourceCount = 2))
            val jobId = db.translations().insert(TranslationJobEntity(
                title = "song", trackId = trackId, remoteUrl = null, queueItemId = row.queueItemId,
                status = DownloadStatus.RUNNING, error = null,
            ))
            val subtitleDir = File(root, "subtitles").apply { mkdirs() }
            val overlay = subtitles.expectedOverlayFile(row, source.sourceCues)

            // 目标被目录占用时，文件替换失败，任务仍可重新提交。
            assertTrue(overlay.mkdir())
            assertFalse(subtitles.saveTranslations(row, revised, source, jobId))
            assertEquals(DownloadStatus.FAILED, db.translations().get(jobId)?.status)
            assertEquals(source, subtitles.load(row))
            assertTrue(overlay.delete())
            db.translations().markRunning(jobId, "song")

            assertTrue(subtitles.saveTranslations(row, revised, source, jobId))
            assertEquals(DownloadStatus.DONE, db.translations().get(jobId)?.status)
            assertEquals(revised, subtitles.load(row).cues)
            assertEquals(source.cues, subtitles.load(row).sourceCues)

            val oldOverlay = overlay.readText()
            srt.writeText(srt.readText().replace("はい", "いいえ"))
            assertEquals("いいえ", subtitles.load(row).cues.first().text)
            assertFalse(subtitles.saveTranslations(row, revised, source))
            assertEquals(oldOverlay, overlay.readText())

            // 听写字幕使用同一个手动翻译保存入口，校对结果单独保留。
            val origin = SubtitleOrigin(PARAKEET_MODEL, null)
            val generated = File(subtitleDir, "abc.json").apply { writeText(encodeGeneratedCues(source.cues, origin)) }
            db.whisper().insert(WhisperJobEntity(
                title = "song", fingerprint = "abc", trackId = trackId, localPath = audio.absolutePath,
                remoteJobId = null, status = DownloadStatus.DONE, progress = 1f, error = null, awaitRemoteUrl = null,
                model = PARAKEET_MODEL,
            ))
            val generatedSource = subtitles.load(row)
            assertTrue(generatedSource.generated)
            assertFalse(subtitles.saveTranslations(row, revised, generatedSource, jobId))
            assertEquals(generatedSource, subtitles.load(row))
            assertTrue(subtitles.saveTranslations(row, revised, generatedSource))
            assertEquals(revised, subtitles.load(row).cues)
            assertEquals(source.cues, parseGeneratedSubtitles(generated.readText())?.cues)
            assertEquals(origin, parseGeneratedSubtitles(generated.readText())?.origin)
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun standaloneTranslationAndPolishRegisterBeforeServiceRuns() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "subtitle-registration-${UUID.randomUUID()}").apply { mkdirs() }
        val context = testContext(root)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        var translatorToClose: TranslateCenter? = null
        try {
            val credentials = CredentialStore(context)
            val subtitles = SubtitleSource(context, db, BilibiliClient(credentials), AsmrClient())
            val starts = kotlinx.coroutines.channels.Channel<Long>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val requests = SubtitleTaskRequests { starts.trySend(it).isSuccess }
            val recovery = kotlinx.coroutines.Job().apply { complete() }
            val translate = TranslateCenter(context, db, credentials, subtitles, requests, recovery)
            translatorToClose = translate
            val row = queueRow(77L)
            val source = com.nanahoshi.audioplayer.playback.LoadedSubtitles(
                cues = listOf(SubtitleCue(0, 1000, "こんにちは")), generatedEmpty = false, generated = true,
                generatedFingerprint = "abc",
            )

            kotlinx.coroutines.withTimeout(5_000) {
                for (task in listOf(SubtitleTask.TRANSLATE, SubtitleTask.POLISH)) {
                    val result = async { translate.submit(task, row, source) }
                    val requestId = starts.receive()
                    val job = db.translations().observe().first { rows ->
                        rows.any { it.operation == task && it.status == DownloadStatus.QUEUED }
                    }.first { it.operation == task }
                    assertEquals("abc", job.generatedFingerprint)
                    assertEquals(task.name, job.kind)
                    val request = checkNotNull(requests.take(requestId))
                    val expected = if (task == SubtitleTask.TRANSLATE) {
                        translate.cancel(job.id)
                        PersistedErrors.CANCELLED
                    } else {
                        request.cancel(SubtitleTaskStopped(PersistedErrors.SUBTITLE_TIMEOUT))
                        PersistedErrors.SUBTITLE_TIMEOUT
                    }
                    val failed = db.translations().observe().first { rows ->
                        rows.any { it.id == job.id && it.status == DownloadStatus.FAILED }
                    }.first { it.id == job.id }
                    assertEquals(expected, failed.error)
                    request.cancel(SubtitleTaskStopped(expected))
                    assertTrue(runCatching { result.await() }.exceptionOrNull() is SubtitleTaskStopped)
                }
            }
        } finally {
            translatorToClose?.close()
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun recoveringPublicationFinishesTaskWithThePublishedTranslation() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "subtitle-publication-${UUID.randomUUID()}").apply { mkdirs() }
        val context = testContext(root)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val subtitles = SubtitleSource(context, db, BilibiliClient(CredentialStore(context)), AsmrClient())
            val job = db.translations().insert(TranslationJobEntity(
                title = "song", trackId = null, remoteUrl = null, queueItemId = 1L,
                status = DownloadStatus.RUNNING, error = null, kind = SubtitleTask.POLISH.name,
                generatedFingerprint = "abc",
            ))
            val payload = encodeGeneratedCues(listOf(SubtitleCue(0, 1000, "はい", "是的")))
            db.translations().stagePublication(
                com.nanahoshi.audioplayer.data.db.SubtitlePublicationEntity("abc.json", payload, null, job, 10L),
            )
            assertEquals(0, db.translations().failIfActive(job, "已中止"))
            subtitles.recoverPublications()
            subtitles.recoverPublications()
            assertEquals(payload, File(root, "subtitles/abc.json").readText())
            assertEquals(DownloadStatus.DONE, db.translations().get(job)?.status)
            assertTrue(db.translations().pendingPublications().isEmpty())
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

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

    @Test fun recoverySurvivesBadPublicationAndInterruptedFailuresKeepStagedTasks() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "subtitle-recovery-bad-${UUID.randomUUID()}").apply { mkdirs() }
        val context = testContext(root)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val subtitles = SubtitleSource(context, db, BilibiliClient(CredentialStore(context)), AsmrClient())
            val badJob = db.translations().insert(TranslationJobEntity(
                title = "bad", trackId = null, remoteUrl = null, queueItemId = 1L,
                status = DownloadStatus.RUNNING, error = null))
            val goodJob = db.translations().insert(TranslationJobEntity(
                title = "good", trackId = null, remoteUrl = null, queueItemId = 2L,
                status = DownloadStatus.RUNNING, error = null))
            val whisperJob = db.whisper().insert(WhisperJobEntity(
                title = "w", fingerprint = "wfp", trackId = null, localPath = null, remoteJobId = null,
                status = DownloadStatus.DONE, progress = 1f, error = null, awaitRemoteUrl = null,
                translateStatus = TranslateMark.RUNNING))
            val payload = encodeGeneratedCues(listOf(SubtitleCue(0, 1000, "はい", "是的")))
            db.translations().stagePublication(SubtitlePublicationEntity("../evil.json", payload, null, badJob, 10L))
            db.translations().stagePublication(SubtitlePublicationEntity("good.json", payload, null, goodJob, 10L))
            db.translations().stagePublication(SubtitlePublicationEntity("w.json", payload, whisperJob, null, 10L))
            // 已 staged 发布的任务不被中断登记误标失败，交由恢复补完。
            db.translations().failInterrupted("已中止")
            db.whisper().failInterruptedTranslations("已中止")
            assertEquals(DownloadStatus.RUNNING, db.translations().get(badJob)?.status)
            assertEquals(DownloadStatus.RUNNING, db.translations().get(goodJob)?.status)
            assertEquals(TranslateMark.RUNNING, db.whisper().get(whisperJob)?.translateStatus)
            // 坏文件名不抛异常、不阻塞其余发布；对应任务标失败，记录清空。
            subtitles.recoverPublications()
            assertEquals(DownloadStatus.FAILED, db.translations().get(badJob)?.status)
            assertEquals(DownloadStatus.DONE, db.translations().get(goodJob)?.status)
            assertEquals(TranslateMark.DONE, db.whisper().get(whisperJob)?.translateStatus)
            assertEquals(payload, File(root, "subtitles/good.json").readText())
            assertFalse(File(root, "evil.json").exists())
            assertTrue(db.translations().pendingPublications().isEmpty())
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun saveKeepsTranslationWhenTheSourceCannotBeReloaded() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "subtitle-missing-source-${UUID.randomUUID()}").apply { mkdirs() }
        val context = testContext(root)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val audio = File(root, "song.mp3").apply { writeText("audio") }
            val originalSrt = "1\n00:00:00,000 --> 00:00:00,800\nはい\n\n2\n00:00:00,900 --> 00:00:01,800\nそうです\n"
            val srt = File(root, "song.srt").apply { writeText(originalSrt) }
            val trackId = db.tracks().insert(TrackEntity(
                source = TrackSource.LOCAL, title = "song", artist = null, durationMs = 1800,
                coverUri = null, localUri = audio.absolutePath, bvid = null, cid = null, page = null, addedAt = 0,
            ))
            val row = queueRow(trackId).copy(source = TrackSource.LOCAL, localUri = audio.absolutePath)
            val subtitles = SubtitleSource(context, db, BilibiliClient(CredentialStore(context)), AsmrClient())
            val source = subtitles.load(row)
            val revised = listOf(SubtitleCue(0, 1800, "はい、そうです。", "嗯，是的。", sourceCount = 2))
            // 核对读不到源（模拟网络抖动/源暂时缺失）：不丢弃已完成的翻译。
            assertTrue(srt.delete())
            assertTrue(subtitles.saveTranslations(row, revised, source))
            val overlay = subtitles.expectedOverlayFile(row, source.sourceCues)
            assertEquals(subtitleSourceFingerprint(source.sourceCues), parseGeneratedSubtitles(overlay.readText())?.sourceFingerprint)
            // 源恢复且一致 → 译文套用；源变化 → 不套用。
            srt.writeText(originalSrt)
            assertEquals(revised, subtitles.load(row).cues)
            srt.writeText(originalSrt.replace("はい", "いいえ"))
            assertEquals(listOf("いいえ", "そうです"), subtitles.load(row).cues.map { it.text })
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun submitKeepsTheVisibleNonGeneratedSourceDespiteAStaleWhisperRecord() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "subtitle-stale-fingerprint-${UUID.randomUUID()}").apply { mkdirs() }
        val context = testContext(root)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        var translatorToClose: TranslateCenter? = null
        try {
            val credentials = CredentialStore(context)
            val subtitles = SubtitleSource(context, db, BilibiliClient(credentials), AsmrClient())
            val starts = kotlinx.coroutines.channels.Channel<Long>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val requests = SubtitleTaskRequests { starts.trySend(it).isSuccess }
            val recovery = kotlinx.coroutines.Job().apply { complete() }
            val translate = TranslateCenter(context, db, credentials, subtitles, requests, recovery)
            translatorToClose = translate
            // DONE 听写记录存在但生成文件缺失：翻译目标应以用户当前看到的非生成字幕为准。
            db.whisper().insert(WhisperJobEntity(
                title = "song", fingerprint = "stale", trackId = 77L, localPath = null, remoteJobId = null,
                status = DownloadStatus.DONE, progress = 1f, error = null, awaitRemoteUrl = null))
            val row = queueRow(77L)
            val visible = LoadedSubtitles(
                cues = listOf(SubtitleCue(0, 1000, "こんにちは")), generatedEmpty = false, generated = false,
            )
            kotlinx.coroutines.withTimeout(5_000) {
                val result = async { translate.submit(SubtitleTask.TRANSLATE, row, visible) }
                val requestId = starts.receive()
                val job = db.translations().observe().first { rows -> rows.any { it.status == DownloadStatus.QUEUED } }.first()
                assertNull(job.generatedFingerprint)
                checkNotNull(requests.take(requestId)).cancel(SubtitleTaskStopped(SUBTITLE_INTERRUPTED))
                assertTrue(runCatching { result.await() }.exceptionOrNull() is SubtitleTaskStopped)
                db.translations().observe().first { rows -> rows.any { it.id == job.id && it.status == DownloadStatus.FAILED } }
            }
        } finally {
            translatorToClose?.close()
            db.close()
            root.deleteRecursively()
        }
        // JUnit4 要求测试方法返回 void：表达式体不能把 withTimeout 的查询结果暴露为返回值。
        Unit
    }

    @Test fun largeStagedPublicationsRecoverThroughChunksAndFileMarkers() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "publication-large-${UUID.randomUUID()}").apply { mkdirs() }
        val context = testContext(root)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val subtitles = SubtitleSource(context, db, BilibiliClient(CredentialStore(context)), AsmrClient())
            // 超过 256K 的内联 payload 不能整段读回（避免 CursorWindow 限制），须按块拼接。
            val inline = "あ".repeat(300_000)
            val inlineName = "huge.json"
            db.translations().stagePublication(
                SubtitlePublicationEntity(inlineName, inline, null, null, 1L),
            )
            // 生产写路径用 @file: 标记把大 payload 放到暂存文件，恢复时也须识别。
            val markerName = "marked.json"
            val markerPayload = "い".repeat(300_000)
            val staging = File(root, "subtitle-publications").apply { mkdirs() }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(markerName.toByteArray())
                .joinToString("") { "%02x".format(it) }
            File(staging, "$digest.json").writeText(markerPayload)
            db.translations().stagePublication(
                SubtitlePublicationEntity(markerName, "@file:$digest.json", null, null, 2L),
            )

            subtitles.recoverPublications()

            assertEquals(inline.length, File(root, "subtitles/$inlineName").readText().length)
            assertEquals(markerPayload.length, File(root, "subtitles/$markerName").readText().length)
            assertTrue(db.translations().pendingPublications().isEmpty())
            assertTrue(staging.listFiles().orEmpty().isEmpty())
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun listSkipsUncommittedOriginalFilesAndAudioDeleteKeepsSiblingJson() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "stored-original-scan-${UUID.randomUUID()}").apply { mkdirs() }
        val context = testContext(root)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val audio = File(root, "dlsite/RJ001/bilibili/disc/voice.wav").apply {
                parentFile?.mkdirs()
                writeText("audio")
            }
            val sibling = File(audio.parentFile, "voice.json").apply { writeText("{\"body\":[]}") }
            val uncommitted = File(root, "dlsite/RJ001/original/uncommitted.wav").apply {
                parentFile?.mkdirs()
                writeText("not published")
            }
            db.tracks().insert(TrackEntity(
                source = TrackSource.DLSITE, title = "voice.wav", artist = null, durationMs = 1000,
                coverUri = null, localUri = audio.absolutePath, bvid = null, cid = null, page = null,
                addedAt = 0,
            ))
            val subtitles = SubtitleSource(context, db, BilibiliClient(CredentialStore(context)), AsmrClient())
            val catalog = StoredCatalog(context, db, subtitles)

            val listed = catalog.list()
            assertTrue("未提交到清单的 original/ 文件不能出现在下载管理", listed.none { it.path == uncommitted.absolutePath })
            val item = listed.single { it.path == audio.absolutePath && it.kind == StoredKind.AUDIO }
            catalog.delete(listOf(item), deleteSubtitles = true)

            assertFalse(audio.exists())
            assertTrue("删除音频副本不能连带删除同目录字幕文件", sibling.isFile)
            assertTrue(uncommitted.isFile)
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun deletingStoredBiliAudioRemovesBiliNamedOverlayAndTerminalHistory() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "stored-bili-overlay-${UUID.randomUUID()}").apply { mkdirs() }
        val context = testContext(root)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val audio = File(root, "bilibili/BV1234567890/42.m4s").apply { parentFile?.mkdirs(); writeText("audio") }
            val subtitlesDir = File(root, "subtitles").apply { mkdirs() }
            val overlay = File(subtitlesDir, overlayFileName(null, null, null, null, "BV1234567890", 42L)!!).apply { writeText("[]") }
            val doneJob = db.translations().insert(TranslationJobEntity(
                title = "bili", trackId = null, remoteUrl = "https://www.bilibili.com/video/BV1234567890",
                queueItemId = null, status = DownloadStatus.DONE, error = null, bvid = "BV1234567890", cid = 42L))
            val runningJob = db.translations().insert(TranslationJobEntity(
                title = "bili", trackId = null, remoteUrl = "https://www.bilibili.com/video/BV1234567890",
                queueItemId = null, status = DownloadStatus.RUNNING, error = null, bvid = "BV1234567890", cid = 42L))
            val subtitles = SubtitleSource(context, db, BilibiliClient(CredentialStore(context)), AsmrClient())

            assertTrue(subtitles.hasGenerated(null, audio.absolutePath, null))
            subtitles.discardStored(null, audio.absolutePath, null)
            assertFalse(overlay.exists())
            assertNull(db.translations().get(doneJob))
            assertEquals(DownloadStatus.RUNNING, db.translations().get(runningJob)?.status)
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun singleLanguageDeleteSupportsChineseOriginalAndBusyGuards() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "single-lang-delete-${UUID.randomUUID()}").apply { mkdirs() }
        val context = testContext(root)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        var translatorToClose: TranslateCenter? = null
        try {
            val credentials = CredentialStore(context)
            credentials.saveTranslate(TranslatePlatform.QWEN, "sk-test", "")
            val subtitles = SubtitleSource(context, db, BilibiliClient(credentials), AsmrClient())
            val requests = SubtitleTaskRequests { false }
            val recovery = kotlinx.coroutines.Job().apply { complete() }
            val translate = TranslateCenter(context, db, credentials, subtitles, requests, recovery)
            translatorToClose = translate
            val catalog = StoredCatalog(context, db, subtitles)

            val audio = File(root, "asmr/RJ001/track.mp3").apply { parentFile?.mkdirs(); writeText("audio") }
            val trackId = db.tracks().insert(TrackEntity(
                source = TrackSource.ASMR, title = "track", artist = null, durationMs = 2000,
                coverUri = null, localUri = audio.absolutePath, bvid = null, cid = null, page = null, addedAt = 0,
            ))
            val row = queueRow(trackId).copy(localUri = audio.absolutePath)
            val original = listOf(SubtitleCue(0, 900, "今日は"), SubtitleCue(1000, 1900, "晴れ"))
            val revised = listOf(SubtitleCue(0, 1900, "今日は晴れ。", "今天晴天。", sourceCount = 2))
            val origin = SubtitleOrigin(PARAKEET_MODEL, null)
            val fingerprint = "abcdef123456"
            val generatedFile = subtitles.generatedFile(fingerprint).apply {
                parentFile?.mkdirs()
                writeText(encodeGeneratedCues(original, origin, revisedCues = revised))
            }
            val summary = TaskProgressSummary(
                mapOf(
                    ProgressPart.TRANSCRIPTION to TaskSegmentSummary(result = ProgressResult.COMPLETED),
                    ProgressPart.TRANSLATION to TaskSegmentSummary(result = ProgressResult.COMPLETED),
                ),
            ).encode()
            val whisperId = db.whisper().insert(WhisperJobEntity(
                title = "track.mp3", fingerprint = fingerprint, trackId = trackId,
                localPath = audio.absolutePath, remoteJobId = null, status = DownloadStatus.DONE,
                progress = 1f, error = null, awaitRemoteUrl = null, translatedAt = 999L,
                translateStatus = TranslateMark.DONE, model = PARAKEET_MODEL, progressSummary = summary,
            ))
            val doneTranslationId = db.translations().insert(TranslationJobEntity(
                title = "track", trackId = trackId, remoteUrl = null, queueItemId = row.queueItemId,
                status = DownloadStatus.DONE, error = null, generatedFingerprint = fingerprint,
            ))

            // 待发布记录或进行中任务存在时阻止单语删除。
            db.translations().stagePublication(SubtitlePublicationEntity(generatedFile.name, "{}", whisperId, null, 1L))
            assertEquals(SubtitleLanguageDeleteResult.BUSY, subtitles.deleteGeneratedLanguage(fingerprint, SubtitleLanguageDelete.CHINESE))
            db.translations().clearPublication(generatedFile.name)

            // 1. 删除中文：恢复听写原文、清理翻译状态与译文分段、允许重新翻译。
            val item = catalog.list().single { it.path == generatedFile.absolutePath }
            assertEquals(SubtitleLanguageDeleteResult.DELETED, catalog.deleteGeneratedLanguage(item, SubtitleLanguageDelete.CHINESE))
            val afterChineseDelete = subtitles.load(row)
            assertEquals(original, afterChineseDelete.cues)
            assertFalse(afterChineseDelete.cues.hasTranslation())
            assertTrue(afterChineseDelete.canTranslate)
            assertTrue(translate.readyToTranslate(fingerprint))
            val whisperAfter = checkNotNull(db.whisper().get(whisperId))
            assertEquals(DownloadStatus.DONE, whisperAfter.status)
            assertNull(whisperAfter.translateStatus)
            assertNull(whisperAfter.translatedAt)
            val summaryAfter = decodeProgressSummary(whisperAfter.progressSummary)
            assertNotNull(summaryAfter.parts[ProgressPart.TRANSCRIPTION])
            assertNull(summaryAfter.parts[ProgressPart.TRANSLATION])
            assertNull(db.translations().get(doneTranslationId))

            // 2. 删除原文：保留纯中文并标 zh-Hans，阻止再翻。
            generatedFile.writeText(encodeGeneratedCues(
                listOf(SubtitleCue(0, 1000, "はい")), origin,
                revisedCues = listOf(SubtitleCue(0, 1000, "はい。", "好")),
            ))
            assertEquals(SubtitleLanguageDeleteResult.DELETED, catalog.deleteGeneratedLanguage(item, SubtitleLanguageDelete.ORIGINAL))
            val afterOriginalDelete = subtitles.load(row)
            assertEquals(listOf(SubtitleCue(0, 1000, "好")), afterOriginalDelete.cues)
            assertEquals("zh-Hans", afterOriginalDelete.language)
            assertFalse(afterOriginalDelete.cues.hasTranslation())
            assertFalse(afterOriginalDelete.canTranslate)
            assertFalse(translate.readyToTranslate(fingerprint))

            // 3. 外挂覆盖单删中文：校验路径安全、运行中保护并清理终态任务。
            val overlayName = checkNotNull(overlayFileName(trackId, null, null, null))
            val overlay = File(root, "subtitles/$overlayName").apply {
                writeText(encodeGeneratedCues(listOf(SubtitleCue(0, 1000, "はい", "好"))))
            }
            val activeOverlayJob = db.translations().insert(TranslationJobEntity(
                title = "track", trackId = trackId, remoteUrl = null, queueItemId = row.queueItemId,
                status = DownloadStatus.RUNNING, error = null, outputFilename = overlayName,
            ))
            assertEquals(SubtitleLanguageDeleteResult.BUSY, subtitles.deleteTranslationOverlayByName(overlayName))
            db.translations().failIfActive(activeOverlayJob, "已中止")

            val overlayItem = catalog.list().single { it.path == overlay.absolutePath }
            assertEquals(SubtitleLanguageDeleteResult.DELETED, catalog.deleteTranslationOverlay(overlayItem))
            assertFalse(overlay.exists())
            assertNull(db.translations().get(activeOverlayJob))
            assertEquals(SubtitleLanguageDeleteResult.NOT_APPLICABLE, subtitles.deleteTranslationOverlayByName("../escape.json"))
        } finally {
            translatorToClose?.close()
            db.close()
            root.deleteRecursively()
        }
        Unit
    }
}

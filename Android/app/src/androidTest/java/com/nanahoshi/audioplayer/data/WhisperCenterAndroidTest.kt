package com.nanahoshi.audioplayer.data

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleKind
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.playback.SubtitleSource
import com.nanahoshi.audioplayer.playback.storedQueueRow
import com.nanahoshi.audioplayer.withIsolatedApp
import com.nanahoshi.audioplayer.youtube.YouTubeClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WhisperCenterAndroidTest {
    @Test
    fun retryRehashesAReplacedLocalAudioFile() = runBlocking {
        Fixture().use { fixture ->
            withTimeout(20_000) {
                val audio = File(fixture.app.filesDir, "voice-${UUID.randomUUID()}.bin").apply { writeText("old bytes") }
                val oldHash = sha256(audio.readBytes())
                val jobId = fixture.db.whisper().insert(
                    WhisperJobEntity(
                        title = "voice", fingerprint = oldHash, trackId = null, localPath = audio.absolutePath,
                        remoteJobId = null, status = DownloadStatus.FAILED, progress = 0f, error = "failed",
                        awaitRemoteUrl = null, skipTranslate = true,
                    ),
                )
                audio.writeText("new bytes after replacement")
                val newHash = sha256(audio.readBytes())

                val preparing = async { fixture.whisper.retry(jobId, DlsiteDownloadMode.PARTIAL) }
                val request = withTimeout(5_000) {
                    val id = fixture.starts.receive()
                    checkNotNull(fixture.requests.take(id))
                }
                val worker = request.launchIn(this).also { it.start() }
                assertNull(preparing.await())

                val requeued = checkNotNull(fixture.db.whisper().get(jobId))
                assertEquals("重试必须重新计算替换后文件的指纹", newHash, requeued.fingerprint)
                request.cancel()
                worker.cancelAndJoin()
            }
        }
    }

    @Test
    fun concurrentStagingProducesOneCompleteFile() = runBlocking {
        Fixture().use { fixture ->
            val payload = ByteArray(1_500_000) { index -> (index % 251).toByte() }
            val source = File(exposedCache(fixture.base), "whisper-stage-${UUID.randomUUID()}.bin").apply {
                writeBytes(payload)
            }
            try {
                val uri = FileProvider.getUriForFile(
                    fixture.base, "${fixture.base.packageName}.files", source,
                ).toString()
                val destination = File(fixture.whisper.stagedFilePath(uri))
                val first = async { fixture.whisper.stageContent(uri) }
                val second = async { fixture.whisper.stageContent(uri) }
                val staged = listOf(first.await(), second.await()).filterNotNull()
                assertEquals(2, staged.size)
                assertEquals(destination.canonicalPath, staged.first().canonicalPath)
                assertEquals(destination.canonicalPath, staged.last().canonicalPath)
                assertTrue("并发暂存不能留下被截断的文件", destination.readBytes().contentEquals(payload))
            } finally {
                source.delete()
            }
        }
    }

    @Test
    fun requestedAtKeepsAManualSelectionMadeWhileStaging() = runBlocking {
        Fixture().use { fixture ->
            withTimeout(20_000) {
                val payload = ByteArray(512_000) { index -> (index % 251).toByte() }
                val source = File(exposedCache(fixture.base), "whisper-manual-${UUID.randomUUID()}.bin").apply {
                    writeBytes(payload)
                }
                try {
                    val uri = FileProvider.getUriForFile(
                        fixture.base, "${fixture.base.packageName}.files", source,
                    ).toString()
                    val trackId = fixture.db.tracks().insert(
                        TrackEntity(
                            source = TrackSource.LOCAL, title = "voice", artist = null, durationMs = 1000,
                            coverUri = null, localUri = uri, bvid = null, cid = null, page = null, addedAt = 0,
                        ),
                    )
                    val row = storedQueueRow(trackId, uri, null).copy(title = "voice")
                    val requestedAt = 1_000L
                    val preparing = async {
                        fixture.whisper.startIfLocal(
                            row, translate = false, model = WHISPER_MODEL_QWEN3, requestedAt = requestedAt,
                        )
                    }
                    val request = withTimeout(5_000) {
                        val id = fixture.starts.receive()
                        checkNotNull(fixture.requests.take(id))
                    }
                    val worker = request.launchIn(this).also { it.start() }
                    assertEquals(WhisperStart.Started, preparing.await())
                    val job = fixture.db.whisper().forFingerprint(sha256(payload))
                        .single { it.createdAt == requestedAt }
                    assertEquals(requestedAt, job.createdAt)

                    // 暂存/开始识别期间用户手动关闭字幕（updatedAt 晚于请求时间）。
                    fixture.bindings.bindNone(trackId)
                    val binding = checkNotNull(fixture.db.trackSubtitles().getForTrack(trackId))
                    assertTrue(binding.updatedAt > requestedAt)
                    assertFalse(
                        "晚于请求时间的手动选择不能被自动绑定覆盖",
                        fixture.bindings.bindGeneratedIfOlder(trackId, job.createdAt),
                    )
                    assertEquals(TrackSubtitleKind.NONE, fixture.db.trackSubtitles().getForTrack(trackId)?.kind)

                    fixture.whisper.cancel(job.id)
                    request.cancel()
                    worker.cancelAndJoin()
                } finally {
                    source.delete()
                }
            }
        }
    }

    private fun exposedCache(app: Context): File =
        File(app.cacheDir, "external-open").apply { mkdirs() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private inner class Fixture : AutoCloseable {
        val base: Context = InstrumentationRegistry.getInstrumentation().targetContext
        private val isolated = com.nanahoshi.audioplayer.IsolatedTestApp.create(base)
        val app: com.nanahoshi.audioplayer.IsolatedTestApp get() = isolated
        val db: AppDatabase = isolated.inMemoryDatabase()
        val credentials = CredentialStore(isolated)
        private val asmr = AsmrClient()
        private val bili = BilibiliClient(credentials)
        private val dlsite = DlsiteClient(credentials)
        val subtitles = SubtitleSource(isolated, db, bili, asmr, dlsite)
        val bindings = SubtitleBindingStore(isolated, db, subtitles)
        val starts = Channel<Long>(Channel.UNLIMITED)
        val requests = SubtitleTaskRequests { starts.trySend(it).isSuccess }
        private val recovery = Job().apply { complete() }
        private val translator = TranslateCenter(isolated, db, credentials, subtitles, requests, recovery)
        private val downloads = DownloadCenter(
            isolated, db, asmr, dlsite, bili,
            YouTubeClient({ null }, { _, _, _ -> error("unexpected YouTube request") }),
            LibraryRepository(isolated, db, bili, asmr, dlsite), subtitles,
            startService = { _, _ -> false },
        )
        val whisper = WhisperCenter(
            isolated, db, credentials, downloads, translator, subtitles, emptyMap(), requests, recovery,
        )

        override fun close() {
            runBlocking {
                withContext(NonCancellable) {
                    starts.close()
                    downloads.close()
                    translator.close()
                    db.close()
                }
            }
            isolated.cleanup()
        }
    }
}
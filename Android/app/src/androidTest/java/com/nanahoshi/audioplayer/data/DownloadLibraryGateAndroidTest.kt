package com.nanahoshi.audioplayer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.playback.SubtitleSource
import com.nanahoshi.audioplayer.withIsolatedApp
import com.nanahoshi.audioplayer.youtube.YouTubeClient
import com.nanahoshi.audioplayer.youtube.YouTubeSolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadLibraryGateAndroidTest {
    @Test fun pendingDownloadRequiresAnExactLibraryMatchBeforeRetry() = runBlocking {
        withIsolatedApp { app ->
            val db = app.inMemoryDatabase()
            var centerToClose: DownloadCenter? = null
            try {
                val credentials = CredentialStore(app)
                val asmr = AsmrClient()
                val bili = BilibiliClient(credentials)
                val dlsite = DlsiteClient(credentials)
                val library = LibraryRepository(app, db, bili, asmr, dlsite)
                val solver = object : YouTubeSolver {
                    override suspend fun solve(player: String, sigs: List<String>, ns: List<String>): String =
                        error("A retry should only enqueue")
                }
                val serviceStarts = Channel<Long>(Channel.UNLIMITED)
                val center = DownloadCenter(
                    app, db, asmr, dlsite, bili, YouTubeClient({ null }, solver), library,
                    SubtitleSource(app, db, bili, asmr),
                    startService = { _, requestId -> serviceStarts.trySend(requestId).isSuccess },
                )
                centerToClose = center
                center.awaitStartup()
                val part = BiliPart("BV1234567890", 42L, 1, "Part", "Author", 1000L, "")
                val failedId = db.downloads().insert(
                    DownloadEntity(
                        trackId = 0L, title = part.title, bytesDone = 0L, bytesTotal = 0L,
                        status = DownloadStatus.FAILED, error = "Interrupted", source = TrackSource.BILIBILI.name,
                        workKey = part.bvid, fileKey = part.cid.toString(),
                    ),
                )
                suspend fun CoroutineScope.runRetry(): Result<Long?> = withTimeout(5_000) {
                    val result = async { runCatching { center.retry(failedId) } }
                    checkNotNull(center.requests.take(serviceStarts.receive())).launchIn(this).start()
                    result.await()
                }

                library.importParts(listOf(part.copy(cid = 43L)))
                assertEquals(DOWNLOAD_LIBRARY_REQUIRED, runRetry().exceptionOrNull()?.message)
                assertNull(db.downloads().nextQueued())
                val saved = library.importParts(listOf(part)).single()
                assertEquals(failedId, runRetry().getOrThrow())
                assertEquals(saved.id, db.downloads().nextQueued()?.trackId)

                library.deleteTrack(saved.id)
                assertEquals(DOWNLOAD_LIBRARY_REQUIRED, db.downloads().get(failedId)?.error)
                assertNull(db.downloads().nextQueued())
            } finally {
                centerToClose?.close()
                db.close()
            }
        }
    }

    @Test fun reusingAnActiveArchiveIsNotCountedAsNewlyQueued() = runBlocking {
        withIsolatedApp { app ->
            val db = app.inMemoryDatabase()
            var centerToClose: DownloadCenter? = null
            try {
                val credentials = CredentialStore(app)
                val asmr = AsmrClient()
                val bili = BilibiliClient(credentials)
                val dlsite = DlsiteClient(credentials)
                val library = LibraryRepository(app, db, bili, asmr, dlsite)
                val solver = object : YouTubeSolver {
                    override suspend fun solve(player: String, sigs: List<String>, ns: List<String>): String =
                        error("This test must not resolve media")
                }
                val serviceStarts = Channel<Long>(Channel.UNLIMITED)
                val center = DownloadCenter(
                    app, db, asmr, dlsite, bili, YouTubeClient({ null }, solver), library,
                    SubtitleSource(app, db, bili, asmr),
                    startService = { _, requestId -> serviceStarts.trySend(requestId).isSuccess },
                )
                centerToClose = center
                center.awaitStartup()
                suspend fun <T> drive(block: suspend () -> T): T = withTimeout(5_000) {
                    val result = async { block() }
                    checkNotNull(center.requests.take(serviceStarts.receive())).launchIn(this).start()
                    result.await()
                }
                db.folders().insert(FolderEntity(name = "RJ123456", position = 0, asmrSourceId = "RJ123456"))
                val first = drive { center.enqueueDlsiteArchive("RJ123456") }
                assertTrue(first.added)
                assertNotNull(first.id)
                // 重复请求复用活跃整包：不计新增，但 id 仍携带（听写等待绑定依赖它）。
                val second = drive { center.enqueueDlsiteArchive("RJ123456") }
                assertEquals(first.id, second.id)
                assertFalse(second.added)
                val trackId = db.tracks().insert(TrackEntity(
                    source = TrackSource.DLSITE, title = "voice", artist = null, durationMs = 1000,
                    coverUri = null, localUri = null, bvid = null, cid = null, page = null, addedAt = 0,
                    folderId = db.folders().findBySourceId("RJ123456")!!.id,
                ))
                val viaTrack = drive { center.enqueueTrack(trackId) }
                assertEquals(first.id, viaTrack.id)
                assertFalse(viaTrack.added)
            } finally {
                centerToClose?.close()
                db.close()
            }
        }
    }
}
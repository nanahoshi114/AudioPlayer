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
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.playback.SubtitleSource
import com.nanahoshi.audioplayer.youtube.YouTubeClient
import com.nanahoshi.audioplayer.youtube.YouTubeSolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DownloadTransferAndroidTest {
    @Test fun parallelTransfersRespectLoweredLimitAndSurviveCancelAndFailure() = runBlocking {
        withTimeout(20_000) {
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(app.cacheDir, "transfer-${UUID.randomUUID()}").apply { mkdirs() }
            val context = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = root
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences("${root.name}-$name", mode)
            }
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            val server = AudioServer(this)
            var centerToClose: DownloadCenter? = null
            var worker: kotlinx.coroutines.Deferred<Unit>? = null
            try {
                val credentials = CredentialStore(context)
                val asmr = AsmrClient()
                val bili = BilibiliClient(credentials)
                val dlsite = DlsiteClient(credentials)
                val library = LibraryRepository(context, db, bili, asmr, dlsite)
                val requests = Channel<Long>(Channel.UNLIMITED)
                val solver = object : YouTubeSolver {
                    override suspend fun solve(player: String, sigs: List<String>, ns: List<String>): String =
                        error("测试不得请求外部媒体")
                }
                val center = DownloadCenter(context, db, asmr, dlsite, bili, YouTubeClient({ null }, solver),
                    library, SubtitleSource(context, db, bili, asmr),
                    startService = { _, id -> requests.trySend(id).isSuccess })
                centerToClose = center
                center.awaitStartup()
                center.preferences.setMaxConcurrentDownloads(3)
                val folder = db.folders().insert(FolderEntity(name = "fixture", position = 0,
                    asmrWorkId = 123456, asmrSourceId = "RJ123456"))
                val tracks = (1..6).map { index ->
                    val track = TrackEntity(source = TrackSource.ASMR, title = "same-name.mp3", artist = null,
                        durationMs = 1000, coverUri = null, localUri = null, bvid = null, cid = null,
                        page = null, addedAt = 0, folderId = folder, remoteUrl = server.url(index),
                        asmrWorkId = 123456, fileHash = "asmr:track-$index")
                    track.copy(id = db.tracks().insert(track))
                }
                val prepare = async { center.enqueue(tracks) }
                checkNotNull(center.requests.take(requests.receive())).launchIn(this).start()
                assertEquals(6, prepare.await())
                val jobs = db.downloads().observe().first().associateBy { it.trackId }
                worker = async { center.drainQueue {} }

                // 三项请求必须在任意响应放行之前全部到达，直接证明真实传输是并行的。
                assertEquals(setOf(1, 2, 3), List(3) { server.started.receive() }.toSet())
                assertEquals(3, db.downloads().active().count { it.status == DownloadStatus.RUNNING })
                center.preferences.setMaxConcurrentDownloads(1)
                val cancelled = jobs.getValue(tracks[0].id).id
                center.cancel(cancelled)
                server.release(2)
                db.downloads().observe().first { rows ->
                    rows.single { it.id == cancelled }.status == DownloadStatus.FAILED &&
                        rows.single { it.trackId == tracks[1].id }.status == DownloadStatus.DONE
                }
                assertTrue(server.started.tryReceive().isFailure)
                assertEquals(DownloadStatus.RUNNING, db.downloads().get(jobs.getValue(tracks[2].id).id)?.status)

                server.release(3)
                assertEquals(4, server.started.receive())
                server.release(4)
                assertEquals(5, server.started.receive())
                server.release(5) // 此项返回 HTTP 500，后续任务仍须开工。
                assertEquals(6, server.started.receive())
                server.release(6)
                worker.await()

                val finished = db.downloads().observe().first()
                assertEquals("已中止", finished.single { it.id == cancelled }.error)
                assertEquals(DownloadStatus.FAILED, finished.single { it.trackId == tracks[4].id }.status)
                val completed = finished.filter { it.status == DownloadStatus.DONE }
                assertEquals(4, completed.size)
                assertEquals(4, completed.map { it.localPath }.distinct().size)
                for (index in listOf(2, 3, 4, 6)) {
                    val saved = db.tracks().getById(tracks[index - 1].id)!!
                    val file = File(checkNotNull(saved.localUri))
                    assertEquals("same-name.mp3", file.name)
                    assertArrayEquals(AudioServer.payload(index), file.readBytes())
                    assertEquals(saved.id, jobs.getValue(saved.id).trackId)
                }
                assertFalse(root.walkTopDown().any { it.isFile && it.name.endsWith(".part") })
            } finally {
                withContext(NonCancellable) {
                    worker?.cancelAndJoin()
                    server.close()
                    centerToClose?.close()
                    db.close()
                    root.deleteRecursively()
                }
            }
        }
    }

    private class AudioServer(scope: CoroutineScope) {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val releases = (1..6).associateWith { CompletableDeferred<Unit>() }
        val started = Channel<Int>(Channel.UNLIMITED)
        private val listener = scope.launch(Dispatchers.IO) {
            try {
                repeat(6) {
                    val connection = server.accept()
                    launch {
                        try {
                            connection.use { socket ->
                                socket.soTimeout = 5_000
                                val input = socket.getInputStream().bufferedReader()
                                val index = input.readLine().split(' ')[1].removePrefix("/").toInt()
                                while (!input.readLine().isNullOrEmpty()) Unit
                                started.send(index)
                                releases.getValue(index).await()
                                val bytes = payload(index)
                                val status = if (index == 5) "500 Internal Server Error" else "200 OK"
                                val headers = "HTTP/1.1 $status\r\nContent-Type: audio/mpeg\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                socket.getOutputStream().apply {
                                    write(headers.toByteArray(Charsets.US_ASCII))
                                    write(bytes)
                                    flush()
                                }
                            }
                        } catch (_: IOException) {
                            // 用户中止后对端可能已关闭，不能把这一项的清理变成服务端整体失败。
                        }
                    }
                }
            } catch (error: IOException) {
                if (isActive) throw error
            }
        }
        fun url(index: Int) = "http://127.0.0.1:${server.localPort}/$index"
        fun release(index: Int) { releases.getValue(index).complete(Unit) }
        suspend fun close() {
            listener.cancel()
            server.close()
            listener.join()
        }
        companion object {
            fun payload(index: Int) = ByteArray(8192) { index.toByte() }
        }
    }
}

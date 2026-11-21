package com.nanahoshi.audioplayer.data

import android.app.Application
import android.content.Intent
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.MainActivity
import com.nanahoshi.audioplayer.data.db.*
import com.nanahoshi.audioplayer.graph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/** 用 adb 检查实际界面和通知。默认跳过；不依赖 API 36 不兼容的 Espresso。 */
@RunWith(AndroidJUnit4::class)
class TaskProgressPreviewAndroidTest {
    @Test fun previewRealDownloadAndSavedDetails() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("progressUiPreview") == "1")
        withTimeout(600_000) {
            val cancelAndRetry = InstrumentationRegistry.getArguments().getString("progressUiCancelRetry") == "1"
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val graph = app.graph()
            graph.downloads.awaitStartup()
            // 只匹配本测试的固定 fixture 身份，重复运行时清理遗留数据。
            graph.db.withTransaction {
                val sql = graph.db.openHelper.writableDatabase
                sql.execSQL("DELETE FROM whisper_jobs WHERE fingerprint = 'progress-ui-fixture'")
                sql.execSQL("DELETE FROM downloads WHERE trackId IN (SELECT id FROM tracks WHERE fileHash = 'asmr:progress-ui')")
                sql.execSQL("DELETE FROM folders WHERE name = '进度界面测试' AND asmrSourceId = 'RJ987654' AND NOT EXISTS (SELECT 1 FROM tracks WHERE folderId = folders.id AND fileHash <> 'asmr:progress-ui')")
            }
            val workId = System.currentTimeMillis()
            val root = File(app.cacheDir, "task-progress-preview").apply { deleteRecursively(); mkdirs() }
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val serving = launch(Dispatchers.IO) {
                try {
                    repeat(if (cancelAndRetry) 2 else 1) { attempt ->
                        try {
                            server.accept().use { socket ->
                                val input = socket.getInputStream().bufferedReader()
                                while (!input.readLine().isNullOrEmpty()) Unit
                                val cancelledAttempt = cancelAndRetry && attempt == 0
                                if (cancelAndRetry && attempt == 1) {
                                    File(root, "retry-request").writeText("ready")
                                    val waitingAt = System.currentTimeMillis()
                                    while (!File(root, "release-retry").isFile && System.currentTimeMillis() - waitingAt < 20_000) delay(100)
                                }
                                val size = 4 * 1024 * 1024
                                val output = socket.getOutputStream()
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: $size\r\nConnection: close\r\n\r\n".toByteArray())
                                val chunk = ByteArray(64 * 1024)
                                // 有效 WAV 文件，保证完成行的播放入口可以实际验证。
                                java.nio.ByteBuffer.wrap(chunk).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                                    put("RIFF".toByteArray()); putInt(size - 8); put("WAVEfmt ".toByteArray()); putInt(16)
                                    putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
                                    put("data".toByteArray()); putInt(size - 44)
                                }
                                output.write(chunk); output.flush()
                                repeat(15) { output.write(ByteArray(64 * 1024)); output.flush(); delay(100) }
                                if (!cancelledAttempt) {
                                    File(root, "ready").writeText("ready")
                                    val waitAt = System.currentTimeMillis()
                                    while (!File(root, "release").isFile && System.currentTimeMillis() - waitAt < 10_000) delay(100)
                                }
                                repeat(48) { output.write(ByteArray(64 * 1024)); output.flush(); delay(if (cancelledAttempt) 1000 else 500) }
                            }
                        } catch (error: java.io.IOException) {
                            if (isActive && !(cancelAndRetry && attempt == 0)) throw error
                        }
                    }
                } catch (error: java.io.IOException) { if (isActive) throw error }
            }
            var folder = 0L
            var track = 0L
            var id: Long? = null
            var asr: Long? = null
            try {
                folder = graph.db.folders().insert(FolderEntity(name = "进度界面测试", position = 0, asmrWorkId = workId, asmrSourceId = "RJ$workId"))
                val row = TrackEntity(source = TrackSource.ASMR, title = "进度测试.wav", artist = null, durationMs = 131_070,
                    coverUri = null, localUri = null, bvid = null, cid = null, page = null, addedAt = System.currentTimeMillis(),
                    folderId = folder, asmrWorkId = workId, fileHash = "asmr:progress-ui-$workId", remoteUrl = "http://127.0.0.1:${server.localPort}/audio")
                track = graph.db.tracks().insert(row)
                app.startActivity(Intent(app, MainActivity::class.java).setAction(AudioDownloadService.OPEN_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                delay(1000)
                assertEquals(1, graph.downloads.enqueue(listOf(row.copy(id = track))))
                id = graph.db.downloads().latestForTrack(track)!!.id
                withTimeout(10_000) {
                    val manager = app.getSystemService(android.app.NotificationManager::class.java)
                    while (manager.activeNotifications.none { item ->
                        val detail = item.notification.extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
                        detail.contains("运行 1 项") && detail.contains("总速率") && detail.contains("下载中")
                    }) delay(100)
                }
                val summary = TaskProgressSummary().merge(ProgressPart.TRANSCRIPTION, TaskSegmentSummary(result = ProgressResult.COMPLETED,
                    phase = ProgressPhase.SAVING_SUBTITLES, elapsedMs = 12_000, model = "Qwen3-ASR", subtitleCount = 44))
                    .merge(ProgressPart.TRANSLATION, TaskSegmentSummary(result = ProgressResult.COMPLETED, phase = ProgressPhase.SAVING_TRANSLATION,
                        elapsedMs = 8000, done = 44, total = 44, unit = ProgressUnit.ITEMS, batch = 2, batches = 2, model = "fixture", polishFallback = true)).encode()
                asr = graph.db.whisper().insert(WhisperJobEntity(title = "字幕详情测试", fingerprint = "progress-ui-fixture", trackId = track,
                    localPath = null, remoteJobId = null, status = DownloadStatus.DONE, progress = 1f, error = null, awaitRemoteUrl = null,
                    skipTranslate = false, model = WHISPER_MODEL_QWEN3, translateStatus = TranslateMark.DONE, progressSummary = summary))
                if (cancelAndRetry) {
                    val cancelled = graph.db.downloads().observe().first { rows -> rows.any { it.id == id && it.status == DownloadStatus.FAILED } }.single { it.id == id }
                    assertEquals("已中止", cancelled.error)
                    withTimeout(5000) {
                        while (decodeProgressSummary(graph.db.downloads().get(id!!)!!.progressSummary).parts[ProgressPart.DOWNLOAD]?.result != ProgressResult.CANCELLED) delay(50)
                    }
                    File(root, "cancelled").writeText("verified")
                    graph.db.downloads().observe().first { rows -> rows.any { it.id == id && it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING) } }
                    assertNotEquals(ProgressResult.CANCELLED, decodeProgressSummary(graph.db.downloads().get(id!!)!!.progressSummary).parts[ProgressPart.DOWNLOAD]?.result)
                }
                graph.db.downloads().observe().first { rows -> rows.any { it.id == id && it.status == DownloadStatus.DONE } }
                serving.join()
                while (!File(root, "verified").isFile) delay(100)
                assertTrue(decodeProgressSummary(graph.db.downloads().get(id!!)!!.progressSummary).parts[ProgressPart.DOWNLOAD]?.result == ProgressResult.COMPLETED)
            } finally { withContext(NonCancellable) {
                id?.let { graph.downloads.cancel(it) }
                serving.cancel(); server.close(); serving.join()
                graph.player.clearQueue().join()
                val saved = track.takeIf { it > 0 }?.let { graph.db.tracks().getById(it)?.localUri }?.let(::File)
                graph.db.withTransaction {
                    id?.let { graph.db.openHelper.writableDatabase.execSQL("DELETE FROM downloads WHERE id = ?", arrayOf(it)) }
                    asr?.let { graph.db.openHelper.writableDatabase.execSQL("DELETE FROM whisper_jobs WHERE id = ?", arrayOf(it)) }
                    if (folder > 0) graph.db.folders().delete(folder)
                }
                saved?.delete(); root.deleteRecursively()
            } }
        }
    }
}

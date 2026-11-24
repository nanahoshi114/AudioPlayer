package com.nanahoshi.audioplayer.japaneseasmr

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.AudioPcmReader
import com.nanahoshi.audioplayer.data.CredentialStore
import com.nanahoshi.audioplayer.data.LibraryRepository
import com.nanahoshi.audioplayer.data.db.JapaneseAsmrMediaEntity
import com.nanahoshi.audioplayer.data.db.PlaylistEntity
import com.nanahoshi.audioplayer.data.db.PlaylistItemEntity
import com.nanahoshi.audioplayer.data.ProgressPart
import com.nanahoshi.audioplayer.data.ProgressResult
import com.nanahoshi.audioplayer.data.StoredCatalog
import com.nanahoshi.audioplayer.data.TaskKey
import com.nanahoshi.audioplayer.data.TaskKind
import com.nanahoshi.audioplayer.data.TaskProgressTracker
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.removeV24SchemaChanges
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.QueueItemEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.playback.SubtitleSource
import com.nanahoshi.audioplayer.popJaDownloadVerification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class JapaneseAsmrAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    @Test fun lateVerificationReturnKeepsTheWorkPageAndAnyNewVerification() = runBlocking {
        withContext(Dispatchers.Main) {
            val controller = NavHostController(app)
            val store = ViewModelStore()
            try {
                controller.setViewModelStore(store)
                controller.navigatorProvider.addNavigator(ComposeNavigator())
                controller.graph = controller.createGraph(startDestination = "library") {
                    composable("library") {}
                    composable("work") {}
                    composable("verify/{token}") {}
                }
                controller.navigate("work")
                val work = requireNotNull(controller.currentBackStackEntry)
                controller.navigate("verify/one")
                val first = requireNotNull(controller.currentBackStackEntry)

                assertTrue(controller.popJaDownloadVerification(first))
                assertFalse(controller.popJaDownloadVerification(first))
                assertEquals(work.id, controller.currentBackStackEntry?.id)

                controller.navigate("verify/two")
                val second = requireNotNull(controller.currentBackStackEntry)
                assertFalse(controller.popJaDownloadVerification(first))
                assertEquals(second.id, controller.currentBackStackEntry?.id)
                assertTrue(controller.popJaDownloadVerification(second))
                assertEquals(work.id, controller.currentBackStackEntry?.id)
            } finally {
                store.clear()
            }
        }
    }

    @Test fun migration21To22KeepsQueueAndSubtitlesAndMatchesFreshSchema() = runBlocking {
        val name = "ja-migration-${UUID.randomUUID()}.db"
        val original = Room.databaseBuilder(app, AppDatabase::class.java, name).build()
        val id = original.tracks().insert(TrackEntity(source = TrackSource.LOCAL, title = "fixture",
            artist = null, durationMs = 500, coverUri = null, localUri = "/voice.wav",
            bvid = null, cid = null, page = null, addedAt = 0))
        original.queue().insert(QueueItemEntity(trackId = id, position = 0))
        original.japaneseAsmr().upsertMedia(JapaneseAsmrMediaEntity("11", "p:0123456789abcdef", null, "DIRECT",
            "path media", 0, null))
        original.japaneseAsmr().upsertMedia(JapaneseAsmrMediaEntity("11", "t:legacy", null, "DIRECT", "old", 0, null))
        original.whisper().insert(WhisperJobEntity(title = "fixture", fingerprint = "fixture", trackId = id,
            localPath = "/voice.wav", remoteJobId = null, status = DownloadStatus.DONE, progress = 1f, error = null, awaitRemoteUrl = null))
        original.close()
        SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
            old.removeV24SchemaChanges()
            old.execSQL("ALTER TABLE downloads DROP COLUMN jaEntryKey")
            old.execSQL("ALTER TABLE downloads DROP COLUMN jaReplace")
            old.execSQL("ALTER TABLE downloads DROP COLUMN jaForce")
            old.execSQL("ALTER TABLE queue_items DROP COLUMN mediaPathHash")
            old.execSQL("DROP TABLE japaneseasmr_download_entries")
            old.execSQL("DROP TABLE japaneseasmr_download_packages")
            old.execSQL("DROP TABLE japaneseasmr_download_works")
            old.execSQL("DROP INDEX index_japaneseasmr_media_postId_pathHash")
            old.execSQL("ALTER TABLE japaneseasmr_media DROP COLUMN pathHash")
            old.execSQL("ALTER TABLE downloads DROP COLUMN dismissedAt")
            old.execSQL("ALTER TABLE whisper_jobs DROP COLUMN createdAt")
            old.execSQL("ALTER TABLE whisper_jobs DROP COLUMN dismissedAt")
            old.execSQL("ALTER TABLE translation_jobs DROP COLUMN createdAt")
            old.execSQL("ALTER TABLE translation_jobs DROP COLUMN dismissedAt")
            old.version = 21
        }
        val upgraded = Room.databaseBuilder(app, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_21_22, AppDatabase.MIGRATION_22_23, AppDatabase.MIGRATION_23_24).build()
        val fresh = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val restored = upgraded.queue().snapshot().single()
            assertEquals(id, restored.trackId)
            assertEquals("/voice.wav", restored.localUri)
            assertNull(restored.mediaPathHash)
            assertEquals("0123456789abcdef", upgraded.japaneseAsmr().media("11", "p:0123456789abcdef")?.pathHash)
            assertNull(upgraded.japaneseAsmr().media("11", "t:legacy")?.pathHash)
            assertEquals(id, upgraded.whisper().done().single().trackId)
            fun schema(db: AppDatabase): List<String> = buildList {
                val sql = db.openHelper.writableDatabase
                assertEquals(24, sql.version)
                for (table in listOf("downloads", "queue_items", "japaneseasmr_posts", "japaneseasmr_media",
                    "japaneseasmr_download_works", "japaneseasmr_download_entries", "japaneseasmr_download_packages")) {
                    for (pragma in listOf("table_info", "index_list")) {
                        sql.query("PRAGMA $pragma($table)").use { rows ->
                            while (rows.moveToNext()) add(table + "|" + pragma + "|" +
                                (1 until rows.columnCount).joinToString("|") { rows.getString(it) ?: "NULL" })
                        }
                    }
                }
            }.sorted()
            assertEquals(schema(fresh), schema(upgraded))
        } finally {
            upgraded.close()
            fresh.close()
            app.deleteDatabase(name)
        }
        Unit
    }

    @Test fun twoArticlesShareRootMoveTracksAndRestoreOnlyExactDownload() = runBlocking {
        val files = File(app.cacheDir, "japanese-asmr-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = files
            override fun getApplicationContext(): Context = this
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val credentials = CredentialStore(context)
            val bili = BilibiliClient(credentials)
            val asmr = AsmrClient()
            val library = LibraryRepository(context, db, bili, asmr, DlsiteClient(credentials))
            val subtitles = SubtitleSource(context, db, bili, asmr)
            val catalog = StoredCatalog(context, db, subtitles)
            val first = article("11", "t:first", "t:sibling")
            val second = article("22", "t:second")
            val root = library.importJapaneseAsmrPost(first).rootId
            assertEquals("RJ01258546", db.folders().get(root)?.asmrSourceId)
            val one = db.japaneseAsmr().track("11", "t:first")!!
            assertEquals(root, one.folderId)
            val local = File(files, "japaneseasmr/11/japaneseasmr-${one.id}/voice.mp3").apply {
                parentFile!!.mkdirs()
                writeText("fixture")
            }
            db.tracks().updateLocalUri(one.id, local.path)
            db.downloads().insert(DownloadEntity(trackId = one.id, title = one.title, bytesDone = 7, bytesTotal = 7,
                status = DownloadStatus.DONE, error = null, source = TrackSource.JAPANESE_ASMR.name,
                workKey = "11", fileKey = "t:first", localPath = local.path))
            File(files, "subtitles/1234.json").apply {
                parentFile!!.mkdirs()
                writeText("[]")
            }
            db.whisper().insert(WhisperJobEntity(title = one.title, fingerprint = "1234", trackId = one.id,
                localPath = local.path, remoteJobId = null, status = DownloadStatus.DONE,
                progress = 1f, error = null, awaitRemoteUrl = jaIdentity("11", "t:first")))

            assertEquals(root, library.importJapaneseAsmrPost(second).rootId)
            val moved = db.japaneseAsmr().track("11", "t:first")!!
            assertEquals(one.id, moved.id)
            assertEquals(local.path, moved.localUri)
            assertEquals(db.japaneseAsmr().post("11")!!.articleFolderId, moved.folderId)
            assertNotEquals(root, moved.folderId)
            val unchanged = library.importJapaneseAsmrPost(first)
            assertEquals(0, unchanged.added)
            assertEquals(0, unchanged.retained)

            library.deleteItems(emptyList(), listOf(root))
            val orphan = catalog.list().first { it.path == local.path }
            assertEquals(TrackSource.JAPANESE_ASMR, orphan.source)
            assertEquals("11", orphan.workKey)
            assertEquals("t:first", orphan.fileKey)
            assertNull(db.downloads().latestForUrl(first.post.url))

            val restored = library.importJapaneseAsmrPost(first).rootId
            assertNotEquals(root, restored)
            val rebound = db.japaneseAsmr().track("11", "t:first")!!
            assertEquals(local.path, rebound.localUri)
            assertNull(db.japaneseAsmr().track("11", "t:sibling")!!.localUri)
            assertTrue(subtitles.hasGenerated(rebound.id, local.path, null))
            assertFalse(subtitles.hasGenerated(db.japaneseAsmr().track("11", "t:sibling")!!.id, null, null))
        } finally {
            db.close()
            files.deleteRecursively()
        }
        Unit
    }

    @Test fun completePureRemovalPrunesOnlyUnprotectedTracksAndIncompleteRefreshPreservesAll() = runBlocking {
        val file = File.createTempFile("ja-protected-", ".mp3", app.cacheDir).apply { writeText("local fixture") }
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val credentials = CredentialStore(app)
            val library = LibraryRepository(app, db, BilibiliClient(credentials), AsmrClient(), DlsiteClient(credentials))
            val keyList = listOf("keep", "local", "current", "gone").map { "p:" + jaHash(it).take(16) }
            val source = article("33", *keyList.toTypedArray())
            val root = library.importJapaneseAsmrPost(source).rootId
            val (keep, local, current, gone) = keyList.map { db.japaneseAsmr().track("33", it)!! }
            db.tracks().updateLocalUri(local.id, file.absolutePath)
            val playing = db.queue().insert(QueueItemEntity(position = 0, source = TrackSource.JAPANESE_ASMR.name,
                workno = "33", fileKey = "t:old-title", mediaPathHash = keyList[2].removePrefix("p:")))
            val removedQueueId = db.queue().insert(QueueItemEntity(trackId = gone.id, position = 1))
            db.queue().insert(QueueItemEntity(trackId = keep.id, position = 2))
            val list = db.playlists().insert(PlaylistEntity(name = "list", createdAt = 0, updatedAt = 0))
            db.playlists().insertItem(PlaylistItemEntity(playlistId = list, trackId = gone.id, position = 0))
            db.playlists().insertItem(PlaylistItemEntity(playlistId = list, trackId = keep.id, position = 1))
            val subtitle = db.whisper().insert(WhisperJobEntity(title = gone.title, fingerprint = "kept",
                trackId = gone.id, localPath = null, remoteJobId = null, status = DownloadStatus.DONE, progress = 1f, error = null, awaitRemoteUrl = null))
            val replacement = source.copy(media = source.media.take(1))
            val incomplete = library.refreshJapaneseAsmrPost("33", root, true, replacement) { playing }
            assertEquals(0, incomplete.removed)
            assertEquals(3, incomplete.retained)
            val complete = library.refreshJapaneseAsmrPost("33", root, true, replacement.copy(complete = true)) { playing }
            assertEquals(0, complete.added)
            assertEquals(1, complete.removed)
            assertEquals(2, complete.retained)
            assertEquals(listOf(removedQueueId), complete.queueIds)
            assertEquals(1, complete.playlistItems)
            assertNull(db.tracks().getById(gone.id))
            assertNotNull(db.tracks().getById(current.id))
            assertNotNull(db.whisper().get(subtitle))
            assertEquals(keep.id, db.japaneseAsmr().track("33", keyList.first())?.id)
            assertEquals(file.absolutePath, db.tracks().getById(local.id)?.localUri)
            assertEquals(listOf(0, 1), db.queue().items().map { it.position })
            assertEquals(listOf(0), db.playlists().items(list).map { it.position })
        } finally {
            db.close()
            file.delete()
        }
        Unit
    }

    @Test fun explicitRefreshCleansOnlyTheGoneOnlineRowsWhenStoredAudioIsUnchanged() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val credentials = CredentialStore(app)
            val library = LibraryRepository(app, db, BilibiliClient(credentials), AsmrClient(), DlsiteClient(credentials))
            val source = article("34", "p:keep").copy(complete = true)
            val root = library.importJapaneseAsmrPost(source).rootId
            suspend fun enqueue(key: String, position: Int) = db.queue().insert(QueueItemEntity(position = position,
                source = TrackSource.JAPANESE_ASMR.name, workno = "34", fileKey = key))
            val playing = enqueue("p:playing", 0)
            val gone = enqueue("p:gone", 1)
            val uncertain = enqueue("t:insufficient", 2)

            val sync = library.refreshJapaneseAsmrPost("34", root, false, source) { playing }
            assertTrue(sync.queueIds.isEmpty())
            assertEquals(listOf(playing, gone, uncertain), db.queue().items().map { it.id })
            val refresh = library.refreshJapaneseAsmrPost("34", root, true, source) { playing }
            assertEquals(0, refresh.removed)
            assertEquals(listOf(gone), refresh.queueIds)
            assertEquals(listOf(playing, uncertain), db.queue().items().map { it.id })
            assertEquals(listOf(0, 1), db.queue().items().map { it.position })
        } finally {
            db.close()
        }
        Unit
    }

    @Test fun hlsResumesCompleteSegmentsAndMuxesDecodableAudio() = runBlocking {
        val directory = File(app.cacheDir, "ja-hls-${UUID.randomUUID()}").apply { mkdirs() }
        val stage = File(directory, "stage")
        val output = File(directory, ".1.part")
        val requests = mutableListOf<String>()
        var rejectSecond = true
        var token = "one"
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals(JapaneseAsmrClient.REFERER, request.header("Referer"))
            val path = request.url.encodedPath
            synchronized(requests) { requests += path }
            val code = if (path.endsWith("1.ts") && rejectSecond) 404 else 200
            val bytes = when {
                path.endsWith("m3u8") -> """
                    #EXTM3U
                    #EXTINF:0.512,
                    0.ts?hash=$token
                    #EXTINF:0.512,
                    1.ts?hash=$token
                    #EXT-X-ENDLIST
                """.trimIndent().toByteArray()
                code == 404 -> byteArrayOf()
                else -> instrumentation.context.assets.open("japaneseasmr${path}").use { it.readBytes() }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body(bytes.toResponseBody()).build()
        }.build()
        val tracker = TaskProgressTracker()
        val key = TaskKey(TaskKind.DOWNLOAD, 1)
        val report = tracker.begin(key, ProgressPart.DOWNLOAD)
        val downloader = JapaneseAsmrHlsDownload(http)
        try {
            val url = "https://audio.example/index.m3u8"
            assertNull(downloader.download(url, stage, output, report))
            assertTrue(File(stage, "seg-0.ts").isFile)
            assertFalse(File(stage, "seg-1.ts.part").exists())
            rejectSecond = false
            val duration = downloader.download(url, stage, output, report)!!
            assertTrue(duration in 900..1150)
            assertEquals(1, requests.count { it == "/0.ts" })
            AudioPcmReader(output).use { assertNotNull(it.readChunk()) }

            val transmitted = tracker.current(key)!!.summary.transferredBytes
            assertTrue(requireNotNull(transmitted) > 0)
            assertNotNull(downloader.download(url, stage, output, report))
            report.finish()
            val summary = tracker.current(key)!!.summary
            assertEquals(ProgressResult.COMPLETED, summary.result)
            assertEquals(transmitted, summary.transferredBytes)
            assertNotNull(summary.transferMs)
            val cachedReport = tracker.begin(key, ProgressPart.DOWNLOAD)
            assertNotNull(downloader.download(url, stage, output, cachedReport))
            cachedReport.finish()
            assertEquals(ProgressResult.REUSED, tracker.current(key)!!.summary.result)
            assertNull(tracker.current(key)!!.summary.transferredBytes)

            token = "two"
            assertNotNull(downloader.download(url, stage, output, tracker.begin(key, ProgressPart.DOWNLOAD)))
            assertEquals(2, requests.count { it == "/0.ts" })
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

    @Test fun onlinePlaybackRewritesNestedPlaylistsAndPreservesMediaHeaders() {
        val requests = mutableListOf<String>()
        val pageClient = OkHttpClient.Builder().addInterceptor { chain ->
            val html = """
                <h1 class="page-title">RJ01258546</h1>
                <div class="cleanPlayer"><audio src="https://audio.example/index.m3u8" /></div>
            """.trimIndent()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(html.toResponseBody()).build()
        }.build()
        val mediaClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals(JapaneseAsmrClient.USER_AGENT, request.header("User-Agent"))
            assertEquals(JapaneseAsmrClient.REFERER, request.header("Referer"))
            requests += request.url.toString()
            val body = when (request.url.encodedPath) {
                "/index.m3u8" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\n/sub/child.m3u8"
                "/sub/child.m3u8" -> "#EXTM3U\n#EXTINF:1,\nhttps://audio.example/segment.ts?token=a%2Cb\n#EXT-X-ENDLIST"
                "/segment.ts" -> "fixture fragment"
                else -> error("unexpected request")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(body.toResponseBody()).build()
        }.build()
        val client = JapaneseAsmrClient(pageClient = pageClient, mediaClient = mediaClient)
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val credentials = CredentialStore(app)
            val factory = com.nanahoshi.audioplayer.playback.BiliPlaybackDataSource.Factory(
                app, BilibiliClient(credentials), AsmrClient(), DlsiteClient(credentials), db,
                app.applicationContext.let { (it as com.nanahoshi.audioplayer.AudioPlayerApplication).graph.youtube },
                client,
            )
            fun read(address: String): String {
                val source = factory.createDataSource()
                val output = java.io.ByteArrayOutputStream()
                try {
                    source.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse(address)))
                    val bytes = ByteArray(4096)
                    while (true) {
                        val size = source.read(bytes, 0, bytes.size)
                        if (size < 0) break
                        output.write(bytes, 0, size)
                    }
                } finally {
                    source.close()
                }
                return output.toString("UTF-8")
            }
            val root = jaIdentity("1", "p:" + jaHash("/index.m3u8").take(16))
            val nested = read(root).lineSequence().first { it.startsWith("jamedia:") }
            val segment = read(nested).lineSequence().first { it.startsWith("jamedia:") }
            assertEquals("fixture fragment", read(segment))
            assertEquals(listOf("https://audio.example/index.m3u8", "https://audio.example/sub/child.m3u8",
                "https://audio.example/segment.ts?token=a%2Cb"), requests)
            assertEquals(nested, read(root).lineSequence().first { it.startsWith("jamedia:") })
            assertEquals(3, requests.size)
        } finally {
            db.close()
        }
    }

    private fun article(post: String, vararg keys: String) = JaArticle(
        JaPostRef(post, "RJ01258546", "篇 $post", null, JapaneseAsmrClient.postUrl(post), null),
        emptyList(),
        keys.map { key ->
            JaMediaRef(key, key, listOf(JaCandidate("https://audio.example/voice.mp3", null)), emptyList())
        },
    )
}

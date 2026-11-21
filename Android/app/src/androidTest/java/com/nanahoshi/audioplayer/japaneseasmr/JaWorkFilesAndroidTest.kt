package com.nanahoshi.audioplayer.japaneseasmr

import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.resolve
import com.nanahoshi.audioplayer.R
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.CredentialStore
import com.nanahoshi.audioplayer.data.DownloadCenter
import com.nanahoshi.audioplayer.data.LibraryRepository
import com.nanahoshi.audioplayer.data.ProgressPart
import com.nanahoshi.audioplayer.data.StoredCatalog
import com.nanahoshi.audioplayer.data.StoredKind
import com.nanahoshi.audioplayer.data.TaskKey
import com.nanahoshi.audioplayer.data.TaskKind
import com.nanahoshi.audioplayer.data.TaskProgressTracker
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DlsiteOriginalEntryEntity
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.deleteLocalCopies
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.playback.SubtitleSource
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class JaWorkFilesAndroidTest {
    @Test fun resolvesBuzzStageNamesInNestedResources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((stage, name) in listOf(
            R.string.ja_stage_hosting_page to "托管文件页",
            R.string.ja_stage_file_download to "文件下载",
        )) {
            val text = TextRef.Res(R.string.ja_work_buzz_verify, listOf(TextRef.Res(stage)))
            assertEquals("Buzzheavier 的${name}需要网页验证", context.resolve(text))
        }
    }

    private val app = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun publishesRepairsAndRelinksWorkAndKeepsOldVersionWhenReplacementDeclined() = runBlocking {
        val storage = File(app.cacheDir, "ja-work-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = storage
            override fun getApplicationContext(): Context = this
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val credentials = CredentialStore(context)
            val bili = BilibiliClient(credentials)
            val asmr = AsmrClient()
            val library = LibraryRepository(context, db, bili, asmr, DlsiteClient(credentials))
            val subtitles = SubtitleSource(context, db, bili, asmr)
            val post = JaPostRef("51", "RJ01752865", "fixture", null, JapaneseAsmrClient.postUrl("51"), null)
            val article = JaArticle(post, emptyList(), listOf(
                JaMediaRef("p:fixture", "播放器音轨", listOf(JaCandidate("https://audio.invalid/voice.wav", null)), emptyList()),
            ))
            val root = library.importJapaneseAsmrPost(article).rootId
            val payloads = linkedMapOf(
                "/full" to wav(3),
                "/package" to zip("Parts/split.wav" to wav(8), "readme.txt" to "fixture".toByteArray()),
            )
            val requests = mutableListOf<String>()
            val client = JaWorkClient(OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                requests += path
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(payloads.getValue(path).toResponseBody()).build()
            }.build())
            fun remote(name: String, path: String): JaRemoteFile {
                val bytes = payloads.getValue(path)
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                return JaRemoteFile(name, bytes.size.toLong(), hash,
                    JaFileTransfer.Buzz("https://buzzheavier.com$path".toHttpUrl(), null))
            }
            val initial = JaWorkManifest(jaDownloadEntry(post, null).single(),
                listOf(remote("long.wav", "/full"), remote("extras.zip", "/package")), 0)
            suspend fun publish(
                manifest: JaWorkManifest,
                replace: Boolean = true,
                failPublish: Boolean = false,
                checkNotice: (String) -> Unit = {},
            ): Long {
                val entity = DownloadEntity(trackId = 0, title = post.rj, bytesDone = 0, bytesTotal = 0,
                    status = DownloadStatus.RUNNING, error = null, source = TrackSource.JAPANESE_ASMR.name,
                    workKey = post.rj, fileKey = post.postId, kind = JA_WORK_DOWNLOAD, workFolderId = root,
                    jaEntryKey = jaHash(manifest.entry.address), jaReplace = replace)
                val id = db.downloads().insert(entity)
                val report = TaskProgressTracker().begin(TaskKey(TaskKind.DOWNLOAD, id), ProgressPart.DOWNLOAD)
                var checks = 0
                val result = runCatching {
                    library.jaWorkFiles.execute(entity.copy(id = id), manifest, client, report, {}) {
                        if (failPublish && ++checks == 2) error("simulated publication failure")
                    }
                }
                assertEquals(!failPublish, result.isSuccess)
                result.getOrNull()?.let { checkNotice(context.resolve(it)) }
                return id
            }
            val firstJob = publish(initial)
            val paths = db.jaDownloads().entries(post.rj).associateBy { it.relativePath }
            val long = paths.getValue("long.wav")
            val split = paths.getValue("Parts/split.wav")
            assertNotNull(long.trackId)
            assertNotNull(split.trackId)
            assertEquals("FILE", paths.getValue("readme.txt").kind)
            assertNull(db.japaneseAsmr().mediaForTrack(requireNotNull(long.trackId)))
            assertEquals(2, StoredCatalog(context, db, subtitles).list().count { it.kind == StoredKind.AUDIO })
            assertEquals(0, library.jaWorkFiles.check(initial, jaHash(initial.entry.address), force = false).pending)
            val track = db.tracks().getById(requireNotNull(long.trackId))!!
            deleteLocalCopies(db, storage, subtitles, listOf(track), deleteSubtitles = false)
            assertNull(db.jaDownloads().entry(post.rj, "long.wav")?.localPath)
            assertEquals(DownloadStatus.DONE, db.downloads().get(firstJob)?.status)
            requests.clear()
            publish(initial.copy(skipped = 1)) { notice ->
                assertTrue(notice.contains(context.getString(R.string.ja_work_existing_files, 2)))
                assertTrue(notice.contains(context.getString(R.string.ja_work_skipped_files, 1)))
            }
            assertEquals(listOf("/full"), requests)
            assertEquals(long.trackId, db.jaDownloads().entry(post.rj, "long.wav")?.trackId)

            payloads["/package"] = zip("Parts/split.wav" to wav(16), "Parts/added.wav" to wav(25),
                "readme.txt" to "fixture".toByteArray())
            val different = initial.copy(entry = initial.entry.copy(address = initial.entry.address + "&slot=2"),
                files = listOf(remote("long.wav", "/full"), remote("extras.zip", "/package")))
            val newKey = jaHash(different.entry.address)
            assertTrue(library.jaWorkFiles.check(different, newKey, force = false).needsReplace)
            publish(different, replace = false) { notice ->
                assertTrue(notice.contains(context.getString(R.string.ja_work_kept_original_files, 1)))
                assertFalse(notice.contains(context.getString(R.string.ja_work_retained_files, 1)))
            }
            val declined = db.jaDownloads().entry(post.rj, "Parts/split.wav")!!
            assertEquals(split.localPath, declined.localPath)
            assertEquals(split.packageRevision, declined.packageRevision)
            assertNull(db.jaDownloads().packages(post.rj).single().publishedAt)
            assertTrue(library.jaWorkFiles.check(different, newKey, force = false).needsReplace)
            publish(different, failPublish = true)
            assertEquals(split.localPath, db.jaDownloads().entry(post.rj, "Parts/split.wav")?.localPath)
            assertTrue(File(requireNotNull(split.localPath)).isFile)
            publish(different)
            val replaced = db.jaDownloads().entry(post.rj, "Parts/split.wav")!!
            assertNotEquals(split.localPath, replaced.localPath)
            assertEquals(split.trackId, replaced.trackId)
            assertNotNull(db.jaDownloads().packages(post.rj).single().publishedAt)
            assertEquals(0, library.jaWorkFiles.check(different, newKey, force = false).pending)

            library.deleteItems(emptyList(), listOf(root))
            val restored = library.importJapaneseAsmrPost(article).rootId
            assertNotEquals(root, restored)
            val rebound = db.jaDownloads().entry(post.rj, "Parts/split.wav")!!
            assertEquals(replaced.localPath, rebound.localPath)
            assertNotNull(rebound.trackId?.let { db.tracks().getById(it) })
            assertEquals(restored, db.jaDownloads().work(post.rj)?.rootFolderId)
        } finally {
            db.close()
            storage.deleteRecursively()
        }
        Unit
    }

    @Test fun transferWaitsAtFailedFileAndResumesWithTheCompleteManifest() = runBlocking {
        val storage = File(app.cacheDir, "ja-verify-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = storage
            override fun getApplicationContext(): Context = this
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val credentials = CredentialStore(context)
        val bili = BilibiliClient(credentials)
        val asmr = AsmrClient()
        val dlsite = DlsiteClient(credentials)
        val library = LibraryRepository(context, db, bili, asmr, dlsite)
        val subtitles = SubtitleSource(context, db, bili, asmr)
        val audio = wav(2)
        val caption = "1\n00:00:00,000 --> 00:00:01,000\nFixture\n".toByteArray()
        var secondRequests = 0
        var ordinaryPage = false
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when (request.url.encodedPath) {
                "/audio-bin" -> audio
                "/subtitle-bin" -> when {
                    ++secondRequests == 1 -> caption
                    ordinaryPage -> "<html><title>Unavailable</title></html>".toByteArray()
                    else -> "<html><script src='/cdn-cgi/challenge-platform/fixture'></script></html>".toByteArray()
                }
                "/subtitle-fresh" -> caption
                else -> error("Unexpected fixture request")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .header("ETag", "\"fixture\"").body(body.toResponseBody()).build()
        }.build()
        val requests = Channel<Long>(Channel.UNLIMITED)
        val youtube = (app.applicationContext as com.nanahoshi.audioplayer.AudioPlayerApplication).graph.youtube
        val center = DownloadCenter(context, db, asmr, dlsite, bili, youtube, library, subtitles,
            startService = { _, id -> requests.trySend(id).isSuccess }, jaWorkHttp = http)
        try {
            withTimeout(20_000) {
                center.awaitStartup()
                val post = JaPostRef("52", "RJ01752865", "fixture", null, JapaneseAsmrClient.postUrl("52"), null)
                val root = library.importJapaneseAsmrPost(JaArticle(post, emptyList(), listOf(
                    JaMediaRef("p:fixture", "fixture", listOf(JaCandidate("https://audio.invalid/track.wav", null)), emptyList()),
                ))).rootId
                val entry = jaDownloadEntry(post, null).single()
                val first = "https://buzzheavier.com/audio"
                val second = "https://buzzheavier.com/subtitle"
                val site = JaVisibleDownload(entry.address, """<a href="$first">Audio</a><a href="$second">Subtitle</a>""")
                val firstPage = JaVisibleDownload(first, "<h1>audio.wav</h1>", fileUrl = "https://dd.buzzheavier.com/audio-bin")
                val secondPage = JaVisibleDownload(second, "<h1>notes.srt</h1>", fileUrl = "https://dd.buzzheavier.com/subtitle-bin")
                val manifest = JaWorkClient(http).manifest(entry, site, mapOf(first to firstPage, second to secondPage))
                val enqueue = async { center.enqueueJaWork(root, manifest, replace = false, force = false) }
                checkNotNull(center.requests.take(requests.receive())).launchIn(this).start()
                val id = requireNotNull(enqueue.await().id)
                center.drainQueue {}
                val waiting = db.downloads().get(id)!!
                assertEquals(DownloadStatus.FAILED, waiting.status)
                assertEquals(JA_VERIFY_PHASE, waiting.phase)
                assertTrue(db.jaDownloads().entries(post.rj).isEmpty())

                val token = center.beginJaTaskVerification(id)
                val request = center.jaVerification(token)!!
                assertEquals(second, request.address)
                assertEquals(firstPage, request.verifiedBuzzPages[first])
                val complete = async {
                    center.completeJaVerification(token, secondPage.copy(fileUrl = "https://dd.buzzheavier.com/subtitle-fresh"))
                }
                checkNotNull(center.requests.take(requests.receive())).launchIn(this).start()
                assertEquals(DownloadCenter.WorkVerificationResult.Finished(true), complete.await())
                center.drainQueue {}
                assertEquals(DownloadStatus.DONE, db.downloads().get(id)?.status)
                val entries = db.jaDownloads().entries(post.rj).associateBy { it.relativePath }
                assertArrayEquals(audio, File(requireNotNull(entries.getValue("audio.wav").localPath)).readBytes())
                assertArrayEquals(caption, File(requireNotNull(entries.getValue("notes.srt").localPath)).readBytes())

                ordinaryPage = true
                secondRequests = 0
                val rejected = JaWorkClient(http).manifest(entry, site, mapOf(first to firstPage, second to secondPage))
                val enqueueRejected = async { center.enqueueJaWork(root, rejected, replace = true, force = true) }
                checkNotNull(center.requests.take(requests.receive())).launchIn(this).start()
                val rejectedId = requireNotNull(enqueueRejected.await().id)
                center.drainQueue {}
                val failed = db.downloads().get(rejectedId)!!
                assertEquals(DownloadStatus.FAILED, failed.status)
                assertNotEquals(JA_VERIFY_PHASE, failed.phase)
                assertEquals(context.getString(R.string.ja_error_buzz_response_page), failed.error)
                assertEquals(entries, db.jaDownloads().entries(post.rj).associateBy { it.relativePath })

                val catalog = StoredCatalog(context, db, subtitles)
                catalog.delete(catalog.list().filter { it.kind == StoredKind.AUDIO }, deleteSubtitles = false)
                val original = File(storage, "dlsite/${post.rj}/original/extra.vtt").apply {
                    parentFile!!.mkdirs()
                    writeText("WEBVTT")
                }
                db.dlsiteOriginals().upsertEntries(listOf(DlsiteOriginalEntryEntity(post.rj, "extra.vtt",
                    "TEXT", original.length(), original.absolutePath)))
                val listed = catalog.list()
                assertTrue(listed.none { it.kind == StoredKind.AUDIO })
                val captions = listed.filter { it.kind == StoredKind.SUBTITLE }
                assertEquals(setOf("notes.srt", "extra.vtt"), captions.map { it.name }.toSet())
                catalog.delete(captions, deleteSubtitles = false)
                assertTrue(catalog.list().none { it.kind == StoredKind.SUBTITLE })
                assertNull(db.jaDownloads().entry(post.rj, "notes.srt")?.localPath)
                assertFalse(original.exists())
            }
        } finally {
            center.close()
            db.close()
            storage.deleteRecursively()
        }
        Unit
    }

    @Test fun verificationReservationExcludesItselfAndLeavesOrdinaryFailureFree() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val job = DownloadEntity(trackId = 0, title = "fixture", bytesDone = 0, bytesTotal = 0,
                status = DownloadStatus.FAILED, error = "已中止", source = TrackSource.JAPANESE_ASMR.name,
                workKey = "RJ01752865", fileKey = "51", kind = JA_WORK_DOWNLOAD)
            val old = db.downloads().insert(job)
            assertNull(db.downloads().reserveWorkTask("RJ01752865", 0))
            val waiting = db.downloads().insert(job.copy(status = DownloadStatus.RUNNING, fileKey = "61"))
            db.downloads().waitForJaVerification(waiting)
            assertEquals(waiting, db.downloads().reserveWorkTask("RJ01752865", old)?.id)
            assertNull(db.downloads().reserveWorkTask("RJ01752865", waiting))
            db.downloads().requeue(waiting)
            val restored = db.downloads().get(waiting)!!
            assertEquals("61", restored.fileKey)
            assertEquals(DownloadStatus.QUEUED, restored.status)
            assertNull(restored.phase)
        } finally {
            db.close()
        }
        Unit
    }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((path, contents) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(contents)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun wav(sample: Int): ByteArray {
        val bytes = 3200
        return ByteBuffer.allocate(bytes + 44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(bytes + 36).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(bytes).apply {
                repeat(bytes / 2) { putShort(sample.toShort()) }
            }.array()
    }
}

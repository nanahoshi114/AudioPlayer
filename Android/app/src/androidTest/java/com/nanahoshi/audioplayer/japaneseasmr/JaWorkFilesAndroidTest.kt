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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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

    @Test fun megaPublishedVersionsProtectIntactCopiesAndPreserveOnlyFolderArchiveParents() = runBlocking {
        withMegaWork("55") { work ->
            val storageCalls = mutableListOf<String>()
            val firstOffsets = mutableListOf<Int>()
            val payloads = mutableMapOf<String, ByteArray>()
            val client = JaWorkClient(OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val slot = request.url.pathSegments.first()
                storageCalls += slot
                val bytes = payloads.getValue(slot)
                if (slot == "direct01") {
                    val offset = request.url.pathSegments.last().substringBefore('-').toInt()
                    firstOffsets += offset
                    if (offset == 0) {
                        val body = object : ResponseBody() {
                            private val buffer = Buffer().write(bytes, 0, 7)
                            override fun contentType() = null
                            override fun contentLength() = bytes.size.toLong()
                            override fun source() = buffer
                        }
                        response(request).newBuilder().body(body).build()
                    } else response(request, bytes.copyOfRange(offset, bytes.size))
                } else response(request, bytes)
            }.build())
            fun mega(path: String, slot: String, bytes: ByteArray): JaRemoteFile {
                val payload = megaPayload(bytes)
                payloads[slot] = payload.encrypted
                return payload.remote(path, slot)
            }
            fun bonus(sample: Int) = zip("01.wav" to wav(sample), "Empty/" to byteArrayOf(), level = 0)
            val legacy = zip("note.txt" to "legacy".toByteArray())
            payloads["legacy"] = legacy
            val start = JaWorkManifest(work.entry, listOf(
                mega("01.wav", "direct01", wav(1)),
                mega("特典/音声.zip", "archive1", bonus(2)),
                JaRemoteFile("Other/extras.zip", legacy.size.toLong(), jaHashBytes(legacy),
                    JaFileTransfer.Buzz("https://dd.buzzheavier.com/legacy".toHttpUrl(), "fixture")),
            ), 0)
            suspend fun file(path: String) = work.db.jaDownloads().entry(work.entry.post.rj, path)!!
            suspend fun check(manifest: JaWorkManifest) =
                work.library.jaWorkFiles.check(manifest, jaHash(manifest.entry.address), force = false)
            suspend fun publish(manifest: JaWorkManifest, replace: Boolean = true) {
                val job = work.job(manifest.entry).copy(jaReplace = replace)
                val id = work.db.downloads().insert(job)
                val tracker = TaskProgressTracker()
                val report = tracker.begin(TaskKey(TaskKind.DOWNLOAD, id), ProgressPart.DOWNLOAD)
                work.library.jaWorkFiles.execute(job.copy(id = id), manifest, client, report, {}) {}
            }
            work.rootId = work.importWork()
            publish(start)
            assertEquals(listOf(0, 7), firstOffsets)
            val original = file("01.wav")
            val archive = file("特典/01.wav")
            assertNotNull(original.trackId)
            assertNotNull(archive.trackId)
            assertArrayEquals(wav(2), File(archive.localPath!!).readBytes())
            assertEquals("FOLDER", file("特典/Empty").kind)
            assertEquals("legacy", File(file("note.txt").localPath!!).readText())
            assertNull(work.db.jaDownloads().entry(work.entry.post.rj, "Other/note.txt"))
            assertTrue(archive.packageRevision!!.startsWith("mega:"))
            assertEquals(jaHashBytes(wav(2)), archive.sha256)
            assertFalse(check(start).lengthOnly)
            storageCalls.clear()
            assertEquals(0, check(start).pending)
            publish(start)
            assertTrue(storageCalls.isEmpty())

            val updatedDirect = mega("01.wav", "direct02", wav(3))
            val changedEntry = work.entry.copy(address = work.entry.address + "&slot=2")
            val switched = JaWorkManifest(changedEntry, listOf(updatedDirect), 0)
            assertTrue(check(switched).needsReplace)
            publish(switched, replace = false)
            assertTrue(storageCalls.isEmpty())
            assertArrayEquals(wav(1), File(file("01.wav").localPath!!).readBytes())
            val updatedArchive = mega("特典/音声.zip", "archive2", bonus(4))
            assertEquals(start.files[1].size, updatedArchive.size)
            val updated = start.copy(files = listOf(updatedDirect, updatedArchive, start.files[2]))
            assertEquals(2, check(updated).pending)
            publish(updated)
            assertEquals(listOf("direct02", "archive2"), storageCalls)
            assertEquals(original.trackId, file("01.wav").trackId)
            assertEquals(archive.trackId, file("特典/01.wav").trackId)
            assertNotEquals(archive.packageRevision, file("特典/01.wav").packageRevision)
            assertArrayEquals(wav(4), File(file("特典/01.wav").localPath!!).readBytes())

            File(file("01.wav").localPath!!).writeBytes(wav(8))
            assertFalse(check(switched).needsReplace)
            publish(switched, replace = false)
            assertArrayEquals(wav(3), File(file("01.wav").localPath!!).readBytes())
            val sameFromArchive = JaWorkManifest(work.entry, listOf(mega("特典/01.wav", "detached", wav(4))), 0)
            assertFalse(check(sameFromArchive).needsReplace)
            publish(sameFromArchive, replace = false)
            assertEquals(archive.trackId, file("特典/01.wav").trackId)
            assertNull(file("特典/01.wav").packageKey)
        }
    }

    @Test fun megaQueueReusesOnlyTheCurrentWorkManifestAndRecoversItAfterRestart() = runBlocking {
        withTimeout(35_000) {
            withMegaWork("56") { work ->
                val requests = Channel<Long>(Channel.UNLIMITED)
                val siteReads = AtomicInteger()
                val gets = AtomicInteger()
                val oldGets = AtomicInteger()
                val payloads = ConcurrentHashMap<String, MegaPayload>()
                val first = megaPayload(wav(1)).also { payloads["AudioOld"] = it }
                val second = megaPayload(wav(2)).also { payloads["AudioNew"] = it }
                val third = megaPayload(wav(3)).also { payloads["AudioTmp"] = it }
                var currentNode = "AudioNew"
                var quota = false
                var busy = false
                val storageOpened = Channel<Unit>(Channel.CONFLATED)
                var pausedStorage: CountDownLatch? = null
                val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                    override fun canceled(call: Call) {
                        if (call.request().url.pathSegments.firstOrNull() == "AudioOld") pausedStorage?.countDown()
                    }
                }).addInterceptor { chain ->
                    val request = chain.request()
                    when (request.url.host) {
                        "japaneseasmr.com" -> {
                            siteReads.incrementAndGet()
                            response(request, code = 302, location = megaFolderUrl())
                        }
                        "g.api.mega.co.nz" -> {
                            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                            val command = (Json.parseToJsonElement(body) as JsonArray).single() as JsonObject
                            if (command.getValue("a").jsonPrimitive.content == "f") {
                                response(request, megaTree(currentNode, payloads.getValue(currentNode)))
                            } else {
                                gets.incrementAndGet()
                                val id = command.getValue("n").jsonPrimitive.content
                                if (busy) response(request, """[{"e":-4}]""".toByteArray())
                                else response(request, """[{"g":"https://store.userstorage.mega.co.nz/$id"}]""".toByteArray())
                            }
                        }
                        else -> {
                            val path = request.url.pathSegments.first()
                            if (path == "AudioOld") pausedStorage?.let { cancelled ->
                                storageOpened.trySend(Unit)
                                check(cancelled.await(5, TimeUnit.SECONDS)) { "Expected the storage request to be cancelled" }
                                throw IOException("Cancelled storage fixture")
                            }
                            if (path == "old-ticket") {
                                oldGets.incrementAndGet()
                                response(request, code = 403)
                            } else if (quota) response(request, code = 509)
                            else {
                                val offset = request.url.pathSegments.last().substringBefore('-').toInt()
                                val bytes = payloads.getValue(path).encrypted
                                response(request, bytes.copyOfRange(offset, bytes.size))
                            }
                        }
                    }
                }.build()
                var center = work.center(http, requests)
                suspend fun submit(action: suspend () -> Long?): Long = coroutineScope {
                    val submitted = async { action() }
                    checkNotNull(center.requests.take(requests.receive())).launchIn(this).start()
                    requireNotNull(submitted.await())
                }
                fun manifest(file: JaRemoteFile) = JaWorkManifest(work.entry, listOf(file,
                    megaPayload(byteArrayOf()).remote("z-empty.txt", "Empty___", initial = false)), 0,
                    sourcePage = JaVisibleDownload(megaFolderUrl(), "fixture"))
                suspend fun enqueue(manifest: JaWorkManifest) = submit {
                    center.enqueueJaWork(work.rootId, manifest, replace = true, force = false).id
                }
                suspend fun state(id: Long, status: DownloadStatus) = center.observe().first { jobs ->
                    jobs.any { it.id == id && it.status == status }
                }.first { it.id == id }
                suspend fun published() = File(work.db.jaDownloads().entry(work.entry.post.rj, "voice.wav")!!.localPath!!).readBytes()
                try {
                    center.awaitStartup()
                    work.rootId = work.importWork()
                    quota = true
                    val old = enqueue(manifest(first.remote("voice.wav", "AudioOld", "old-ticket")).let {
                        it.copy(resolvedAtMs = it.resolvedAtMs - 120_001)
                    })
                    center.drainQueue {}
                    assertEquals(work.context.getString(R.string.ja_error_mega_quota), state(old, DownloadStatus.FAILED).error)
                    assertEquals(0, siteReads.get())
                    assertEquals(0, oldGets.get())
                    assertEquals(1, gets.get())

                    quota = false
                    busy = true
                    val newer = enqueue(manifest(second.remote("voice.wav", "AudioNew", "old-ticket")))
                    center.drainQueue {}
                    assertEquals(work.context.getString(R.string.ja_error_mega_busy), state(newer, DownloadStatus.FAILED).error)
                    assertEquals(1, oldGets.get())
                    assertEquals(4, gets.get())
                    busy = false
                    submit { center.retry(newer) }
                    center.drainQueue {}
                    state(newer, DownloadStatus.DONE)
                    assertEquals(0, siteReads.get())
                    assertEquals(5, gets.get())
                    assertArrayEquals(wav(2), published())
                    assertEquals(0L, File(work.db.jaDownloads().entry(work.entry.post.rj, "z-empty.txt")!!.localPath!!).length())
                    submit { center.retry(old) }
                    center.drainQueue {}
                    state(old, DownloadStatus.DONE)
                    assertEquals(1, siteReads.get())
                    assertEquals(5, gets.get())
                    assertArrayEquals(wav(2), published())

                    currentNode = "AudioTmp"
                    val queued = enqueue(manifest(third.remote("voice.wav", "AudioTmp")))
                    center.close()
                    center = work.center(http, requests)
                    center.awaitStartup()
                    quota = true
                    center.drainQueue {}
                    state(queued, DownloadStatus.FAILED)
                    assertEquals(2, siteReads.get())
                    quota = false
                    submit { center.retry(queued) }
                    center.drainQueue {}
                    state(queued, DownloadStatus.DONE)
                    assertEquals(2, siteReads.get())
                    assertArrayEquals(wav(3), published())

                    val cancelled = enqueue(manifest(first.remote("voice.wav", "AudioOld")))
                    pausedStorage = CountDownLatch(1)
                    val draining = async { center.drainQueue {} }
                    storageOpened.receive()
                    center.cancel(cancelled)
                    state(cancelled, DownloadStatus.FAILED)
                    draining.await()
                    pausedStorage = null
                    quota = true
                    submit { center.retry(cancelled) }
                    center.drainQueue {}
                    state(cancelled, DownloadStatus.FAILED)
                    assertEquals(2, siteReads.get())
                    work.library.deleteItems(emptyList(), listOf(work.rootId))
                    work.rootId = work.importWork()
                    quota = false
                    submit { center.retry(cancelled) }
                    center.drainQueue {}
                    state(cancelled, DownloadStatus.DONE)
                    assertEquals(3, siteReads.get())
                    assertArrayEquals(wav(3), published())
                } finally {
                    center.close()
                }
            }
        }
    }

    private class MegaWork(
        val context: Context,
        val db: AppDatabase,
        val entry: JaWorkEntry,
        val app: Context,
    ) {
        private val credentials = CredentialStore(context)
        private val bili = BilibiliClient(credentials)
        private val asmr = AsmrClient()
        private val dlsite = DlsiteClient(credentials)
        private val subtitles = SubtitleSource(context, db, bili, asmr)
        val library = LibraryRepository(context, db, bili, asmr, dlsite, JapaneseAsmrClient(
            pageClient = OkHttpClient.Builder().addInterceptor { chain ->
                response(chain.request(), code = 403)
            }.build(),
        ))
        var rootId = 0L

        suspend fun importWork(): Long = library.importJapaneseAsmrPost(JaArticle(entry.post, emptyList(), listOf(
            JaMediaRef("p:fixture", "fixture", listOf(JaCandidate("https://audio.invalid/track.wav", null)), emptyList()),
        ))).rootId

        fun job(entry: JaWorkEntry) = DownloadEntity(trackId = 0, title = entry.post.rj, bytesDone = 0, bytesTotal = 0,
            status = DownloadStatus.RUNNING, error = null, source = TrackSource.JAPANESE_ASMR.name,
            workKey = entry.post.rj, fileKey = entry.post.postId, kind = JA_WORK_DOWNLOAD, workFolderId = rootId,
            jaEntryKey = jaHash(entry.address))

        fun center(http: OkHttpClient, requests: Channel<Long>): DownloadCenter {
            val youtube = (app.applicationContext as com.nanahoshi.audioplayer.AudioPlayerApplication).graph.youtube
            return DownloadCenter(context, db, asmr, dlsite, bili, youtube, library, subtitles,
                startService = { _, id -> requests.trySend(id).isSuccess }, jaWorkHttp = http)
        }
    }

    private suspend fun withMegaWork(postId: String, block: suspend (MegaWork) -> Unit) {
        val storage = File(app.cacheDir, "ja-mega-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = storage
            override fun getApplicationContext(): Context = this
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val post = JaPostRef(postId, "RJ01752865", "fixture", null, JapaneseAsmrClient.postUrl(postId), null)
        try {
            block(MegaWork(context, db, jaDownloadEntry(post, null).single(), app))
        } finally {
            db.close()
            storage.deleteRecursively()
        }
    }

    private data class MegaPayload(val encrypted: ByteArray, val key: ByteArray) {
        fun remote(path: String, id: String, ticket: String = id, initial: Boolean = true) =
            JaRemoteFile(path, encrypted.size.toLong(), null,
                JaFileTransfer.Mega(JaMegaFileSource.FolderFile("Public__", id), JaMegaCrypto(key),
                    if (initial) "https://store.userstorage.mega.co.nz/$ticket".toHttpUrl() else null))
    }

    private fun megaPayload(bytes: ByteArray): MegaPayload {
        require(bytes.size < 128 * 1024)
        val aes = ByteArray(16) { (it + 17).toByte() }
        val nonce = ByteArray(8) { (it + 10).toByte() }
        val chunkMac = if (bytes.isEmpty()) ByteArray(16) else encrypt("CBC", aes, nonce + nonce,
            bytes.copyOf((bytes.size + 15) and 15.inv())).takeLast(16).toByteArray()
        val mac = if (bytes.isEmpty()) chunkMac else encrypt("ECB", aes, null, chunkMac)
        val suffix = nonce + ByteArray(8) { index ->
            val offset = if (index < 4) index else index + 4
            (mac[offset].toInt() xor mac[offset + 4].toInt()).toByte()
        }
        val key = ByteArray(16) { (aes[it].toInt() xor suffix[it].toInt()).toByte() } + suffix
        return MegaPayload(encrypt("CTR", aes, nonce + ByteArray(8), bytes), key)
    }

    private fun megaTree(id: String, payload: MegaPayload): ByteArray {
        fun node(handle: String, name: String, type: Int, key: ByteArray, size: Int): String {
            val aes = if (type == 1) key else ByteArray(16) { (key[it].toInt() xor key[it + 16].toInt()).toByte() }
            val raw = """MEGA{"n":${JsonPrimitive(name)}}""".toByteArray()
            val attributes = encrypt("CBC", aes, ByteArray(16), raw.copyOf((raw.size + 15) and 15.inv()))
            val wrapped = encrypt("ECB", folderKey, null, key)
            return """{"h":"$handle","p":"Root____","t":$type,"k":"Root____:${encode(wrapped)}","a":"${encode(attributes)}","s":$size}"""
        }
        val root = node("Root____", "Shared", 1, ByteArray(16) { it.toByte() }, 0)
        val file = node(id, "voice.wav", 0, payload.key, payload.encrypted.size)
        return """[{"f":[$root,$file]}]""".toByteArray()
    }

    private fun zip(vararg files: Pair<String, ByteArray>, level: Int = -1): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.setLevel(level)
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

    private companion object {
        val folderKey = ByteArray(16) { (it + 7).toByte() }
        fun encode(bytes: ByteArray) = bytes.toByteString().base64Url().trimEnd('=')
        fun megaFolderUrl() = "https://mega.nz/folder/Public__#${encode(folderKey)}"
        fun jaHashBytes(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toByteString().hex()
        fun encrypt(mode: String, key: ByteArray, iv: ByteArray?, bytes: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/$mode/NoPadding")
            val spec = SecretKeySpec(key, "AES")
            if (iv == null) cipher.init(Cipher.ENCRYPT_MODE, spec)
            else cipher.init(Cipher.ENCRYPT_MODE, spec, IvParameterSpec(iv))
            return cipher.doFinal(bytes)
        }
        fun response(request: Request, body: ByteArray = byteArrayOf(), code: Int = 200, location: String? = null): Response =
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").apply {
                if (location != null) header("Location", location)
                if (code == 403 && request.url.host == "japaneseasmr.com") header("cf-mitigated", "challenge")
            }.body(body.toResponseBody()).build()
    }
}

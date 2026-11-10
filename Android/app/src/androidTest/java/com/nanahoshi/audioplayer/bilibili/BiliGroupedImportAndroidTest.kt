package com.nanahoshi.audioplayer.bilibili

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.data.*
import com.nanahoshi.audioplayer.data.db.*
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.ui.library.favoriteDownloadNotice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BiliGroupedImportAndroidTest {
    private val video = BiliVideoHit("BV1234567890", "video", "UP", "", 2000)
    private val parts = (1..2).map { BiliPart(video.bvid, 40L + it, it, "part $it", "UP", 1000, "") }
    private fun request(mode: BiliImportMode, confirmed: Boolean = false, chosen: List<BiliPart> = parts) =
        BiliImportRequest(video, chosen, mode, confirmed)

    private suspend fun fixture(test: suspend (Context, AppDatabase, LibraryRepository) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "bili-group-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = root
            override fun getApplicationContext(): Context = this
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val credentials = CredentialStore(context)
            val library = LibraryRepository(context, db, BilibiliClient(credentials), AsmrClient(), DlsiteClient(credentials))
            test(context, db, library)
        } finally { db.close(); root.deleteRecursively() }
    }

    private fun cached(context: Context, part: BiliPart): File = biliAudioFile(context.filesDir, part.bvid, part.cid)!!
        .apply { parentFile!!.mkdirs(); writeBytes(ByteArray(128) { 1 }) }

    @Test fun oneThreePartVideoReportsThreeNewDownloadTasks() = runBlocking {
        fixture { context, db, library ->
            val downloads = BiliDownloads(context.filesDir, db, library)
            val threeParts = parts + parts.last().copy(cid = 43, page = 3, title = "part 3")
            assertEquals("已加入 3 个下载任务", favoriteDownloadNotice(downloads.enqueue(threeParts), 0))
            assertEquals("已在本地或正在下载", favoriteDownloadNotice(downloads.enqueue(threeParts), 0))
        }
    }

    @Test fun conversionsKeepIdsAndAssociationsAndRetainUncheckedExistingParts() = runBlocking {
        fixture { context, db, library ->
            val local = cached(context, parts[0])
            val first = library.importBili(request(BiliImportMode.SPLIT, chosen = parts.take(1))).single()
            val list = db.playlists().insert(PlaylistEntity(name = "list", createdAt = 0, updatedAt = 0))
            db.playlists().insertItem(PlaylistItemEntity(playlistId = list, trackId = first.id, position = 0))
            db.queue().insert(QueueItemEntity(trackId = first.id, position = 0))
            val whisper = db.whisper().insert(WhisperJobEntity(title = "part", fingerprint = "fixture", trackId = first.id,
                localPath = null, remoteJobId = null, status = DownloadStatus.DONE, progress = 1f, error = null, awaitRemoteUrl = null))
            assertTrue(runCatching { library.importBili(request(BiliImportMode.GROUPED)) }.isFailure)
            library.importBili(request(BiliImportMode.GROUPED, confirmed = true))
            val group = db.folders().findBiliVideo(video.bvid)!!
            assertNull(group.asmrSourceId)
            assertEquals(2, db.tracks().inFolder(group.id).size)
            assertEquals(first.id, db.tracks().findByBvid(video.bvid, parts[0].cid)!!.id)
            assertTrue(runCatching { library.deleteTrack(first.id) }.exceptionOrNull() is LibraryRemovalRejected)
            library.importBili(request(BiliImportMode.SPLIT, confirmed = true, chosen = parts.take(1)))
            assertNull(db.folders().findBiliVideo(video.bvid))
            assertEquals(2, db.tracks().inFolder(null).size)
            assertEquals(local.path, db.tracks().getById(first.id)!!.localUri)
            assertEquals(first.id, db.playlists().items(list).single().trackId)
            assertEquals(first.id, db.queue().snapshot().single().trackId)
            assertEquals(first.id, db.whisper().get(whisper)!!.trackId)
        }
    }

    @Test fun groupedDownloadWaitsForAllPartsAndSurvivesStoreRecreation() = runBlocking {
        fixture { context, db, library ->
            val batch = library.biliLibrary.beginDownload(request(BiliImportMode.GROUPED))
            val downloads = BiliDownloads(context.filesDir, db, library)
            assertEquals(2, downloads.enqueue(parts, batchId = batch))
            val first = db.downloads().activeForFile("BILIBILI", video.bvid, parts[0].cid.toString())!!
            db.downloads().markRunning(first.id)
            library.completeBiliDownload(first.id, video.bvid, parts[0].cid, cached(context, parts[0]).path)
            assertNull(db.folders().findBiliVideo(video.bvid))
            assertTrue(db.tracks().forBvid(video.bvid).isEmpty())
            val second = db.downloads().activeForFile("BILIBILI", video.bvid, parts[1].cid.toString())!!
            db.downloads().fail(second.id, "fixture interruption")
            assertNotNull(db.biliImports().get(video.bvid))
            cached(context, parts[1])
            val restarted = BiliLibraryStore(context, db) { part, folder -> library.importParts(listOf(part), folder).single() }
            restarted.publish() // startup recovery can use all valid on-disk parts
            val group = db.folders().findBiliVideo(video.bvid)!!
            assertEquals(2, db.tracks().inFolder(group.id).size)
            assertNull(db.biliImports().get(video.bvid))
            assertTrue(db.downloads().get(first.id)!!.trackId > 0)
            library.biliLibrary.publish()
            assertEquals(group.id, db.folders().findBiliVideo(video.bvid)!!.id)
        }
    }

    @Test fun existingCacheTaskGetsTheNewIntentAndOldCallbacksCannotRecreateAGroup() = runBlocking {
        fixture { context, db, library ->
            val downloads = BiliDownloads(context.filesDir, db, library)
            downloads.enqueue(parts, autoImport = true)
            val first = db.downloads().activeForFile("BILIBILI", video.bvid, parts[0].cid.toString())!!
            val batch = library.biliLibrary.beginDownload(request(BiliImportMode.GROUPED))
            assertEquals(batch, decodeBiliDownloadImport(db.downloads().get(first.id)!!.biliImportPart!!, video.bvid, parts[0].cid).batchId)
            assertEquals(0, downloads.enqueue(parts, batchId = batch))
            library.importBili(request(BiliImportMode.SPLIT, chosen = parts.take(1)))
            for (part in parts) {
                val job = db.downloads().activeForFile("BILIBILI", video.bvid, part.cid.toString())!!
                db.downloads().markRunning(job.id)
                library.completeBiliDownload(job.id, video.bvid, part.cid, cached(context, part).path)
            }
            assertNull(db.folders().findBiliVideo(video.bvid))
            assertEquals(listOf(parts[0].cid), db.tracks().forBvid(video.bvid).map { it.cid })
        }
    }

    @Test fun removingAnExistingGroupInvalidatesPendingImportsWithoutDeletingCachedFiles() = runBlocking {
        fixture { context, db, library ->
            library.importBili(request(BiliImportMode.GROUPED))
            val group = db.folders().findBiliVideo(video.bvid)!!
            val batch = library.biliLibrary.beginDownload(request(BiliImportMode.GROUPED))
            BiliDownloads(context.filesDir, db, library).enqueue(parts, batchId = batch)
            library.deleteItems(emptyList(), listOf(group.id))
            for (part in parts) {
                val job = db.downloads().activeForFile("BILIBILI", video.bvid, part.cid.toString())!!
                db.downloads().markRunning(job.id)
                library.completeBiliDownload(job.id, video.bvid, part.cid, cached(context, part).path)
            }
            assertNull(db.folders().findBiliVideo(video.bvid))
            assertTrue(db.tracks().forBvid(video.bvid).isEmpty())
            assertTrue(parts.all { biliAudioFile(context.filesDir, it.bvid, it.cid)!!.isFile })
        }
    }
}

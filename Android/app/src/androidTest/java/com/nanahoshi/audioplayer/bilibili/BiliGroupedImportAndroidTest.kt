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

    @Test fun removingAGroupStopsItsDownloadsAndPreservesExistingFiles() = runBlocking {
        fixture { context, db, library ->
            val saved = library.importBili(request(BiliImportMode.GROUPED))
            val group = db.folders().findBiliVideo(video.bvid)!!
            val old = cached(context, parts[0])
            val downloading = saved.last()
            val id = db.downloads().insert(DownloadEntity(
                trackId = downloading.id, title = downloading.title, bytesDone = 0, bytesTotal = 0,
                status = DownloadStatus.RUNNING, error = null, source = TrackSource.BILIBILI.name,
                workKey = video.bvid, fileKey = downloading.cid.toString(),
            ))

            library.deleteItems(emptyList(), listOf(group.id))

            assertEquals(DownloadStatus.FAILED, db.downloads().get(id)!!.status)
            assertEquals(DOWNLOAD_LIBRARY_REQUIRED, db.downloads().get(id)!!.error)
            assertNull(db.folders().findBiliVideo(video.bvid))
            assertTrue(db.tracks().forBvid(video.bvid).isEmpty())
            assertTrue(old.isFile)
        }
    }
}

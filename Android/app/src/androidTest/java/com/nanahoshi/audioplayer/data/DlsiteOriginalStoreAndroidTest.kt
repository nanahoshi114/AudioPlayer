package com.nanahoshi.audioplayer.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.PlaylistEntity
import com.nanahoshi.audioplayer.data.db.PlaylistItemEntity
import com.nanahoshi.audioplayer.data.db.QueueItemEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.dlsite.PlayEntry
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DlsiteOriginalStoreAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun replacingGenerationKeepsTracksPlaylistsQueueAndSubtitles() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val directory = File(context.filesDir, "original-store-test/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val credentials = CredentialStore(context)
            val dlsite = DlsiteClient(credentials)
            val library = LibraryRepository(context, db, BilibiliClient(credentials), AsmrClient(), dlsite)
            val facts = WorkFacts("fixture", "circle", emptyList(), emptyList(), "", "RJ123456")
            val root = db.folders().insert(FolderEntity(name = "fixture", position = 0,
                asmrWorkId = 123456, asmrSourceId = "RJ123456", workMeta = facts.toJson()))
            val wav = db.folders().insert(FolderEntity(parentId = root, name = "WAV", position = 0,
                asmrWorkId = 123456, contentSource = TrackSource.DLSITE.name))
            val trackId = db.tracks().insert(TrackEntity(source = TrackSource.DLSITE, title = "voice.wav",
                artist = "circle", durationMs = 1000, coverUri = null, localUri = "content://fixture/user-audio",
                bvid = null, cid = null, page = null, addedAt = 0, folderId = wav, asmrWorkId = 123456,
                fileHash = "dlsite:play.mp3", remoteUrl = "https://example.invalid/play.mp3"))
            val playlistId = db.playlists().insert(PlaylistEntity(name = "list", createdAt = 0, updatedAt = 0))
            db.playlists().insertItem(PlaylistItemEntity(playlistId = playlistId, trackId = trackId, position = 0))
            db.queue().insert(QueueItemEntity(trackId = trackId, position = 0))
            val whisperId = db.whisper().insert(WhisperJobEntity(title = "voice", fingerprint = "subtitle-fixture",
                trackId = trackId, localPath = null, remoteJobId = null, status = DownloadStatus.DONE,
                progress = 1f, error = null, awaitRemoteUrl = null))
            val play = listOf(PlayEntry("WAV", listOf(PlayEntry("voice.wav", emptyList(),
                RemotePlayable("voice.wav", "circle", null, "https://example.invalid/play.mp3", TrackSource.DLSITE,
                    DlsiteClient.PLAY_REFERER, workno = "RJ123456", fileKey = "play.mp3"), null)), null, null))
            val store = DlsiteOriginalStore(context, db, dlsite, library) { _, _, _ -> play }
            fun generation(name: String) = File(directory, name).apply {
                File(this, "original/WAV").mkdirs()
                File(this, "original/WAV/voice.wav").writeBytes(ByteArray(32))
                File(this, "original/bonus.flac").writeBytes(ByteArray(32))
                File(this, "original/readme.vtt").writeText("WEBVTT\n")
            }
            val first = generation("first")
            store.importDirectory("RJ00123456", first, "first")
            val firstEntries = db.dlsiteOriginals().entries("RJ123456")
            assertEquals(trackId, firstEntries.single { it.relativePath == "original/WAV/voice.wav" }.trackId)
            assertEquals("play.mp3", firstEntries.single { it.trackId == trackId }.playFileKey)
            val extraId = firstEntries.single { it.relativePath == "original/bonus.flac" }.trackId!!
            assertNull(db.tracks().getById(extraId)!!.fileHash)
            assertNull(db.tracks().getById(extraId)!!.remoteUrl)
            val fileEntry = firstEntries.single { it.relativePath == "original/readme.vtt" }
            assertEquals(fileEntry.localPath, db.files().get(fileEntry.fileId!!)!!.localUri)
            assertEquals("dlsite:play.mp3", db.tracks().getById(trackId)!!.fileHash)
            val second = generation("second")
            store.importDirectory("RJ123456", second, "second")
            assertEquals(2, db.tracks().observeAll().first().size)
            assertEquals(extraId, db.dlsiteOriginals().entries("RJ123456").single { it.relativePath == "original/bonus.flac" }.trackId)
            assertEquals(File(second, "original/WAV/voice.wav").canonicalPath, db.tracks().getById(trackId)!!.localUri)
            assertEquals(trackId, db.playlists().items(playlistId).single().trackId)
            assertEquals(trackId, db.queue().snapshot().single().trackId)
            assertEquals("subtitle-fixture", db.whisper().get(whisperId)!!.fingerprint)
            assertTrue(File(first, "original/WAV/voice.wav").isFile)

            val unpublished = generation("unpublished")
            var checks = 0
            try {
                store.importDirectory("RJ123456", unpublished, "unpublished", beforeCommit = {
                    if (++checks == 2) throw CancellationException()
                })
                fail("cancelled import must not publish")
            } catch (_: CancellationException) { }
            assertEquals("second", db.dlsiteOriginals().work("RJ123456")!!.generation)
            assertEquals(File(second, "original/WAV/voice.wav").canonicalPath, db.tracks().getById(trackId)!!.localUri)
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun conflictingFileIsSkippedAndKeepsItsExistingOwner() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val directory = File(context.filesDir, "original-store-conflict/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val credentials = CredentialStore(context)
            val dlsite = DlsiteClient(credentials)
            val library = LibraryRepository(context, db, BilibiliClient(credentials), AsmrClient(), dlsite)
            val facts = WorkFacts("fixture", "circle", emptyList(), emptyList(), "", "RJ123456")
            val root = db.folders().insert(FolderEntity(name = "fixture", position = 0,
                asmrWorkId = 123456, asmrSourceId = "RJ123456", workMeta = facts.toJson()))
            val wav = db.folders().insert(FolderEntity(parentId = root, name = "WAV", position = 0,
                asmrWorkId = 123456, contentSource = TrackSource.DLSITE.name))
            val generation = File(directory, "gen").apply {
                File(this, "original/WAV").mkdirs()
                File(this, "original/WAV/voice.wav").writeBytes(ByteArray(32))
                File(this, "original/bonus.flac").writeBytes(ByteArray(32))
            }
            val occupied = File(generation, "original/WAV/voice.wav").absolutePath
            val owner = db.tracks().insert(TrackEntity(source = TrackSource.DLSITE, title = "kept.wav",
                artist = "circle", durationMs = 1000, coverUri = null, localUri = occupied,
                bvid = null, cid = null, page = null, addedAt = 0, folderId = wav, asmrWorkId = 123456,
                fileHash = null, remoteUrl = null))
            val selected = db.tracks().insert(TrackEntity(source = TrackSource.DLSITE, title = "voice.wav",
                artist = "circle", durationMs = 1000, coverUri = null, localUri = null,
                bvid = null, cid = null, page = null, addedAt = 0, folderId = wav, asmrWorkId = 123456,
                fileHash = "dlsite:play.mp3", remoteUrl = "https://example.invalid/play.mp3"))
            val play = listOf(PlayEntry("WAV", listOf(PlayEntry("voice.wav", emptyList(),
                RemotePlayable("voice.wav", "circle", null, "https://example.invalid/play.mp3", TrackSource.DLSITE,
                    DlsiteClient.PLAY_REFERER, workno = "RJ123456", fileKey = "play.mp3"), null)), null, null))
            val store = DlsiteOriginalStore(context, db, dlsite, library) { _, _, _ -> play }

            val result = store.importDirectory("RJ123456", generation, "gen")

            assertEquals(listOf("original/WAV/voice.wav"), result.skipped)
            val entries = db.dlsiteOriginals().entries("RJ123456")
            assertEquals(owner, entries.single { it.relativePath == "original/WAV/voice.wav" }.trackId)
            assertEquals(occupied, db.tracks().getById(owner)!!.localUri)
            assertNull(db.tracks().getById(selected)!!.localUri)
            assertNotNull(entries.single { it.relativePath == "original/bonus.flac" }.trackId)
            assertEquals("gen", db.dlsiteOriginals().work("RJ123456")!!.generation)
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }
}

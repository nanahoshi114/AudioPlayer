package com.nanahoshi.audioplayer.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.playback.SubtitleSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalRemovalAndroidTest {
    @Test
    fun unlinksUserFilesAndOnlyDeletesSubtitlesWhenRequestedForDownloads() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val directory = File(context.filesDir, "local-removal-test/${UUID.randomUUID()}").apply { mkdirs() }
        val cues = mutableListOf<File>()
        try {
            val subtitles = SubtitleSource(context, db, BilibiliClient(CredentialStore(context)), AsmrClient())
            suspend fun fixture(name: String, path: String): Pair<TrackEntity, File> {
                val track = TrackEntity(source = TrackSource.ASMR, title = name, artist = null,
                    durationMs = 1000, coverUri = null, localUri = path, bvid = null, cid = null,
                    page = null, addedAt = 0)
                val saved = track.copy(id = db.tracks().insert(track))
                val fingerprint = UUID.randomUUID().toString().replace("-", "")
                val cue = File(context.filesDir, "subtitles/$fingerprint.json")
                cues += cue
                cue.parentFile?.mkdirs()
                cue.writeText("[]")
                db.whisper().insert(WhisperJobEntity(title = name, fingerprint = fingerprint,
                    trackId = saved.id, localPath = path, remoteJobId = null, status = DownloadStatus.DONE,
                    progress = 1f, error = null, awaitRemoteUrl = null))
                return saved to cue
            }

            val userAudio = File(directory, "user.wav").apply { writeText("user") }
            val (linked, linkedCue) = fixture("linked", userAudio.toURI().toString())
            val (mixedLink, mixedLinkCue) = fixture("mixed link", "content://fixture/audio")
            val audio = File(directory, "download.wav").apply { writeText("download") }
            val (downloaded, downloadedCue) = fixture("downloaded", audio.path)
            val changed = File(directory, "changed.wav").apply { writeText("replacement") }
            val (old, oldCue) = fixture("changed", "content://fixture/old")
            db.tracks().updateLocalUri(old.id, changed.path)

            assertEquals(DeleteResult(0, 1), deleteLocalCopies(db, context.filesDir, subtitles, listOf(linked), true))
            assertTrue(userAudio.isFile)
            assertTrue(linkedCue.isFile)
            assertNull(db.tracks().getById(linked.id)?.localUri)
            assertNotNull(db.whisper().latestDoneForTrack(linked.id))

            assertEquals(DeleteResult(1, 1, 1),
                deleteLocalCopies(db, context.filesDir, subtitles, listOf(downloaded, mixedLink, old), false))
            assertFalse(audio.exists())
            assertTrue(downloadedCue.isFile)
            assertTrue(mixedLinkCue.isFile)
            assertNull(db.tracks().getById(mixedLink.id)?.localUri)
            assertTrue(oldCue.isFile)
            assertTrue(changed.isFile)
            assertEquals(changed.path, db.tracks().getById(old.id)?.localUri)

            val deleteAudio = File(directory, "delete.wav").apply { writeText("download") }
            val (deleteTrack, deleteCue) = fixture("delete subtitles", deleteAudio.path)
            assertEquals(DeleteResult(1, 0), deleteLocalCopies(db, context.filesDir, subtitles, listOf(deleteTrack), true))
            assertFalse(deleteAudio.exists())
            assertFalse(deleteCue.exists())
            assertNull(db.whisper().latestDoneForTrack(deleteTrack.id))
        } finally {
            db.close()
            cues.forEach { it.delete() }
            directory.deleteRecursively()
        }
    }
}

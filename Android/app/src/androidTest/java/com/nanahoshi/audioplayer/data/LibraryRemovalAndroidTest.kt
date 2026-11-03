package com.nanahoshi.audioplayer.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.db.*
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryRemovalAndroidTest {
    @Test fun rejectedPartialRemovalIsAtomicAndWholeRemovalKeepsOriginalIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val credentials = CredentialStore(context)
            val library = LibraryRepository(context, db, BilibiliClient(credentials), AsmrClient(), DlsiteClient(credentials))
            val root = db.folders().insert(FolderEntity(name = "work", position = 0, asmrSourceId = "RJ123456"))
            val child = db.folders().insert(FolderEntity(parentId = root, name = "wav", position = 0))
            fun track(title: String, parent: Long?) = TrackEntity(source = TrackSource.LOCAL, title = title, artist = null,
                durationMs = 0, coverUri = null, localUri = null, bvid = null, cid = null, page = null, addedAt = 0, folderId = parent)
            val internal = db.tracks().insert(track("inside", child))
            val ordinary = db.tracks().insert(track("outside", null))
            val file = db.files().insert(LibraryFileEntity(folderId = child, name = "book.pdf", kind = FileKind.OTHER,
                remoteUrl = "", addedAt = 0, asmrWorkId = 123456, fileHash = "original-pdf"))
            db.queue().insert(QueueItemEntity(trackId = internal, position = 0))
            db.dlsiteOriginals().upsertWork(DlsiteOriginalWorkEntity("RJ123456", root, "/fixture", "fixture"))
            assertTrue(runCatching { library.deleteItems(listOf(ordinary, internal), emptyList()) }.exceptionOrNull() is LibraryRemovalRejected)
            assertNotNull(db.tracks().getById(ordinary))
            assertNotNull(db.tracks().getById(internal))
            assertEquals(1, db.queue().snapshot().size)
            assertTrue(runCatching { library.deleteFile(file) }.exceptionOrNull() is LibraryRemovalRejected)
            assertNotNull(db.files().get(file))
            assertTrue(runCatching { library.deleteTrack(internal) }.exceptionOrNull() is LibraryRemovalRejected)
            assertTrue(runCatching { library.deleteItems(emptyList(), listOf(child)) }.exceptionOrNull() is LibraryRemovalRejected)
            library.deleteItems(listOf(internal), listOf(root, child))
            assertNull(db.folders().get(root))
            assertNull(db.tracks().getById(internal))
            assertNotNull(db.tracks().getById(ordinary))
            assertNotNull(db.dlsiteOriginals().work("RJ123456"))
        } finally { db.close() }
    }
}

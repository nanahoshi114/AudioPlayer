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
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import com.nanahoshi.audioplayer.playback.QueuePlayable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BiliCollectionAndroidTest {
    private val ref = BiliCollection(BiliCollectionKind.SEASON, 7, 123, "collection")
    private val video = BiliVideoHit("BV1234567890", "video", "UP", "", 2000)
    private val parts = (1..2).map { BiliPart(video.bvid, 100L + it, it, "P$it", "UP", 1000, "") }

    private suspend fun fixture(block: suspend (Context, AppDatabase, BilibiliClient, LibraryRepository) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "collection-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = root
            override fun getApplicationContext(): Context = this
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val credentials = CredentialStore(context)
        val client = BilibiliClient(credentials)
        try { block(context, db, client, LibraryRepository(context, db, client, AsmrClient(), DlsiteClient(credentials))) }
        finally { db.close(); root.deleteRecursively() }
    }

    @Test fun bookmarkIsIdempotentAndOnlineListsAreNotEditable() = runBlocking {
        fixture { context, db, client, library ->
            val store = BiliCollectionRepository(context, db, client)
            val id = store.bookmark(ref)
            assertEquals(id, store.bookmark(ref))
            assertNotEquals(id, store.bookmark(ref.copy(kind = BiliCollectionKind.SERIES)))
            assertTrue(db.tracks().observeAll().first().isEmpty())
            assertTrue(library.observePlaylists().first().isEmpty())
            assertTrue(runCatching { library.renamePlaylist(id, "new") }.isFailure)
            assertTrue(runCatching { library.addTracksToPlaylist(id, emptyList()) }.isFailure)
            assertTrue(runCatching { library.movePlaylistItem(id, 0, 1) }.isFailure)
            assertTrue(runCatching { library.removePlaylistItem(id, 1) }.isFailure)
        }
    }

    @Test fun legacyMetadataCoverIsReplacedByCachedFirstVideo() = runBlocking {
        fixture { context, db, client, _ ->
            val store = BiliCollectionRepository(context, db, client)
            val id = store.bookmark(ref)
            val first = video.copy(coverUrl = "https://example.invalid/first.jpg")
            db.collections().insertVideos(listOf(BiliCollectionVideoEntity(id, first.bvid, 0,
                biliLibraryJson.encodeToString(BiliVideoHit.serializer(), first))))
            val legacy = ref.copy(coverUrl = "https://example.invalid/old-meta.jpg")
            db.collections().published(id, biliLibraryJson.encodeToString(BiliCollection.serializer(), legacy), 1)
            assertEquals("", store.decode(db.collections().get(id)!!).coverUrl)
            assertEquals(first.coverUrl, store.cached(id)!!.collection.coverUrl)
            assertEquals(first.coverUrl, store.decode(store.sources.first().single()).coverUrl)
        }
    }

    @Test fun incompleteRefreshRetainsSnapshotAndLateCompletionCannotRecreateRemovedSource() = runBlocking {
        fixture { context, db, client, _ ->
            var fail = false
            val store = BiliCollectionRepository(context, db, client, loadPage = { value, page ->
                if (fail && page == 2) throw BilibiliException(-412, "restricted")
                BiliCollectionPage(value.copy(total = if (fail) 2 else 1), listOf(video), fail)
            })
            val id = store.bookmark(ref)
            store.refresh(ref, id, { true }, {})
            fail = true
            assertTrue(runCatching { store.refresh(ref, id, { true }, {}) }.isFailure)
            assertEquals(listOf(video.bvid), store.cached(id)!!.videos.map { it.bvid })
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val slow = BiliCollectionRepository(context, db, client, loadPage = { value, _ ->
                started.complete(Unit); release.await(); BiliCollectionPage(value.copy(total = 1), listOf(video), false)
            })
            val result = async { runCatching { slow.refresh(ref, id, { true }, {}) } }
            started.await(); store.unbookmark(id)
            val replacement = store.bookmark(ref)
            release.complete(Unit)
            assertTrue(result.await().isFailure)
            assertNull(db.collections().get(id))
            assertNull(store.cached(replacement))
        }
    }

    @Test fun newerRefreshWinsAndPartsSurviveReordering() = runBlocking {
        fixture { context, db, client, _ ->
            val oldStarted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val old = BiliCollectionRepository(context, db, client, loadPage = { value, _ ->
                oldStarted.complete(Unit); release.await(); BiliCollectionPage(value.copy(total = 1, title = "old"), listOf(video), false)
            })
            val newer = BiliCollectionRepository(context, db, client, loadPage = { value, _ ->
                BiliCollectionPage(value.copy(total = 1, title = "new"), listOf(video), false)
            })
            val id = old.bookmark(ref)
            val previous = async { runCatching { old.refresh(ref, id, { true }, {}) } }
            oldStarted.await()
            newer.refresh(ref, id, { true }, {})
            db.collections().cacheParts(id, video.bvid, "fixture-parts")
            newer.refresh(ref, id, { true }, {})
            release.complete(Unit)
            assertTrue(previous.await().isFailure)
            assertEquals("new", newer.cached(id)!!.collection.title)
            assertEquals("fixture-parts", db.collections().videos(id).single().parts)
        }
    }

    @Test fun copyingOnlyChosenPartsAndUnbookmarkingPreserveLibraryAndMixedQueue() = runBlocking {
        fixture { context, db, client, library ->
            library.importBili(BiliImportRequest(video, parts, BiliImportMode.SPLIT))
            val chosen = library.publishBiliPlaylist("copy", parts.takeLast(1))!!
            assertEquals(listOf(parts.last().cid), library.playlistTracks(chosen).map { it.cid })
            assertNull(library.publishBiliPlaylist("empty", emptyList()))
            val saved = db.tracks().findByBvid(video.bvid, parts.first().cid)!!
            val remote = QueuePlayable.Remote(RemotePlayable("remote", "UP", null,
                "https://www.bilibili.com/video/BV1234567891", TrackSource.BILIBILI,
                "https://www.bilibili.com", 1000, "BV1234567891", "201"))
            library.writeMixedQueue(listOf(QueuePlayable.Saved(saved), remote), true)
            val before = library.queueSnapshot()
            assertEquals(saved.id, before[0].trackId); assertNull(before[1].trackId)
            assertEquals("201", before[1].fileKey)
            val store = BiliCollectionRepository(context, db, client)
            store.unbookmark(store.bookmark(ref))
            assertEquals(before, library.queueSnapshot())
            assertEquals(2, db.tracks().forBvid(video.bvid).size)
            assertEquals(1, library.playlistTracks(chosen).size)
            assertTrue(runCatching { library.writeMixedQueue(listOf(QueuePlayable.Saved(saved.copy(id = Long.MAX_VALUE))), true) }.isFailure)
            assertEquals(before, library.queueSnapshot())
            library.writeMixedQueue(listOf(remote), false)
            assertEquals(listOf(0, 1, 2), library.queueSnapshot().map { it.position })
        }
    }

    @Test fun offlineDetailsRequireBothPartMetadataAndRealLocalFiles() = runBlocking {
        fixture { context, db, client, _ ->
            var online = true
            val store = BiliCollectionRepository(context, db, client,
                loadPage = { value, _ -> BiliCollectionPage(value.copy(total = 1), listOf(video), false) },
                loadDetails = { if (!online) throw BilibiliException(0, "offline") else BiliVideoDetails(video, parts) })
            val id = store.bookmark(ref)
            store.refresh(ref, id, { true }, {})
            store.details(video, id)
            online = false
            assertTrue(runCatching { store.details(video, id) }.isFailure)
            parts.forEach { part -> biliAudioFile(context.filesDir, part.bvid, part.cid)!!.apply { parentFile!!.mkdirs(); writeBytes(ByteArray(128) { 1 }) } }
            assertEquals(parts, store.details(video, id).parts)
            assertTrue(store.playable(parts.first()) is QueuePlayable.Remote)
        }
    }
}

package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.bilibili.*
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class PlayingBiliCollectionTest {
    private val first = "BV1234567890"
    private val second = "BV1234567891"
    private val collection = BiliCollection(BiliCollectionKind.SEASON, 7, 123, "合集")
    private suspend fun settle() { repeat(12) { yield() } }
    private fun row(source: TrackSource = TrackSource.BILIBILI, bvid: String? = null, workno: String? = null) =
        QueuedTrack(1, 0, null, source, "video", null, 1000, null, null, bvid, null, null, null, null, workno, "101")

    @Test fun bothLibraryTracksAndRemoteQueueItemsResolveTheSameVideoIdentity() {
        assertEquals(first, row(bvid = first).biliVideoId())
        assertEquals(first, row(workno = first).biliVideoId())
        assertNull(row(TrackSource.YOUTUBE, workno = first).biliVideoId())
        assertNull(row(workno = "invalid").biliVideoId())
        assertNull(playingBiliCollection(row(bvid = second), PlayingBiliCollectionLink(first, collection)))
        assertNull(playingBiliCollection(row(TrackSource.LOCAL, bvid = first), PlayingBiliCollectionLink(first, collection)))
        assertEquals(collection, playingBiliCollection(row(workno = first), PlayingBiliCollectionLink(first, collection)))
    }

    @Test fun cachedMembershipIsReusedAcrossPartsAndNonBiliTracksHideTheLink() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val cache = BiliVideoCollectionCache().apply { record(first, collection) }
            val selection = MutableStateFlow(PlayingBiliVideo(first, 1, 0))
            var calls = 0
            val tracker = PlayingBiliCollection(scope, selection, cache) { calls++; null }
            settle(); assertEquals(collection, tracker.link.value!!.collection)
            selection.value = PlayingBiliVideo(first, 2, 0)
            settle(); assertEquals(0, calls)
            selection.value = PlayingBiliVideo(null, 3, 0)
            settle(); assertNull(tracker.link.value); assertEquals(0, calls)
        } finally { scope.cancel() }
    }

    @Test fun lateResponseForPreviousVideoCannotShowItsCollectionOnTheNewVideo() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val release = CompletableDeferred<Unit>()
        try {
            val cache = BiliVideoCollectionCache()
            val selection = MutableStateFlow(PlayingBiliVideo(first, 1, 0))
            val started = CompletableDeferred<Unit>()
            val tracker = PlayingBiliCollection(scope, selection, cache) { bvid ->
                if (bvid == first) {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    collection
                } else null
            }
            started.await()
            selection.value = PlayingBiliVideo(second, 2, 0)
            settle(); assertNull(tracker.link.value)
            release.complete(Unit)
            settle(); assertNull(tracker.link.value)
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test fun failedMetadataLookupDoesNotBlockLaterLookupOrCreateFalseAbsence() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val cache = BiliVideoCollectionCache()
            val selection = MutableStateFlow(PlayingBiliVideo(first, 1, 0))
            var calls = 0
            val tracker = PlayingBiliCollection(scope, selection, cache) {
                calls++
                if (calls == 1) throw BilibiliException(-412, "restricted")
                collection
            }
            settle(); assertNull(tracker.link.value); assertFalse(cache.values.value.containsKey(first))
            selection.value = PlayingBiliVideo(first, 1, 1)
            settle(); assertEquals(collection, tracker.link.value!!.collection)
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }
}

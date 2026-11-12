package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.bilibili.*
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class PlayingBiliCollectionTest {
    private val first = "BV1234567890"
    private val second = "BV1234567891"
    private val collection = BiliCollection(BiliCollectionKind.SEASON, 7, 123, "合集")
    private suspend fun settle() { repeat(16) { yield() } }
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
            scope.launch { tracker.link.collect() }
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
            scope.launch { tracker.link.collect() }
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
            scope.launch { tracker.link.collect() }
            settle(); assertNull(tracker.link.value); assertFalse(cache.values.value.containsKey(first))
            selection.value = PlayingBiliVideo(first, 1, 1)
            settle(); assertEquals(collection, tracker.link.value!!.collection)
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }

    @Test fun withoutLinkSubscriberNoLookupHappens() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val cache = BiliVideoCollectionCache()
            val selection = MutableStateFlow(PlayingBiliVideo(first, 1, 0))
            var calls = 0
            val tracker = PlayingBiliCollection(scope, selection, cache) { calls++; collection }
            settle()
            selection.value = PlayingBiliVideo(second, 2, 0)
            settle()
            assertEquals(0, calls)
            assertNull(tracker.link.value)
            assertTrue(cache.values.value.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun unsubscribedLinkStopsUpdatingWhenSelectionOrCacheChanges() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val cache = BiliVideoCollectionCache()
            val selection = MutableStateFlow(PlayingBiliVideo(first, 1, 0))
            val tracker = PlayingBiliCollection(scope, selection, cache) { collection }
            val observer = scope.launch { tracker.link.collect() }
            settle()
            cache.record(first, collection)
            settle(); assertEquals(first, tracker.link.value?.bvid)
            observer.cancel(); settle()
            // 离开播放页后不允许再有收集器写 link：旧实现每次订阅都泄漏一个 combine 收集器。
            selection.value = PlayingBiliVideo(second, 2, 0)
            cache.record(second, collection)
            settle()
            assertEquals(first, tracker.link.value?.bvid)
        } finally { scope.cancel() }
    }

    @Test fun lookupResumesWithSubscriberAndStopsWithout() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val cache = BiliVideoCollectionCache()
            val selection = MutableStateFlow(PlayingBiliVideo(first, 1, 0))
            var calls = 0
            val tracker = PlayingBiliCollection(scope, selection, cache) { calls++; collection }
            settle(); assertEquals(0, calls)
            val observer = scope.launch { tracker.link.collect() }
            settle(); assertEquals(1, calls); assertEquals(collection, tracker.link.value!!.collection)
            observer.cancel(); settle()
            selection.value = PlayingBiliVideo(second, 2, 0)
            settle(); assertEquals(1, calls)
        } finally { scope.cancel() }
    }
}

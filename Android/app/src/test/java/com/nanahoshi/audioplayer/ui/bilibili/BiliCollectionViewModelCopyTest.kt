package com.nanahoshi.audioplayer.ui.bilibili

import com.nanahoshi.audioplayer.bilibili.BiliCollection
import com.nanahoshi.audioplayer.bilibili.BiliCollectionKind
import com.nanahoshi.audioplayer.bilibili.BiliCollectionPage
import com.nanahoshi.audioplayer.bilibili.BiliImportMode
import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.bilibili.BiliVideoDetails
import com.nanahoshi.audioplayer.bilibili.BiliVideoHit
import com.nanahoshi.audioplayer.data.BiliImportRequests
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliCollectionViewModelCopyTest {
    private val collection = BiliCollection(BiliCollectionKind.SEASON, 1, 2, "合集", total = 4)
    private val directory = (1..4).map { BiliVideoHit("BV123456789$it", "视频 $it", "UP", "", 2000) }

    @Test fun wholeCopyUsesTheFrozenRandomSeedAfterDirectoryReadCompletes() = runBlocking {
        val draft = CollectionCopyDraft(collection.title, CollectionSort.RANDOM, 42, null)
        draft.name = "我的列表"
        assertNull(draft.targets)
        val warning = draft.freezeDirectory(directory.take(1), complete = false) {
            assertNull(draft.targets)
            BiliCollectionPage(collection, directory, hasMore = false, complete = true)
        }
        assertNull(warning)
        assertEquals(orderCollectionVideos(directory, CollectionSort.RANDOM, 42), draft.targets)
        assertEquals("我的列表", draft.name)
    }

    @Test fun selectedCopyUsesItsOwnSnapshotAndSkipsTheDirectoryRead() = runBlocking {
        val selection = mutableListOf(directory[2], directory[0])
        val draft = CollectionCopyDraft(collection.title, CollectionSort.FORWARD, 42, selection)
        selection.clear()
        assertNull(draft.freezeDirectory(directory.take(1), complete = false) {
            error("A selected copy should skip the directory read")
        })
        assertEquals(listOf(directory[2], directory[0]), draft.targets)
    }

    @Test fun incompleteReadReportsTheRangeAndFreezesItForContinuation() = runBlocking {
        val draft = CollectionCopyDraft(collection.title, CollectionSort.REVERSE, 42, null)
        val partial = directory.take(2).toMutableList()
        val warning = draft.freezeDirectory(emptyList(), complete = false) {
            BiliCollectionPage(collection, partial, hasMore = false, complete = false)
        }
        partial.clear()
        assertEquals(IncompleteCollectionCopy(count = 2, total = 4), warning)
        assertEquals(listOf(directory[1], directory[0]), draft.targets)
        draft.freezeDirectory(directory, complete = true) { error("The copy already has a snapshot") }
        assertEquals(listOf(directory[1], directory[0]), draft.targets)
    }

    @Test fun cancellingTheChooserPreservesTheEnteredNameAndPreparedDetails() = runBlocking {
        val draft = CollectionCopyDraft(collection.title, CollectionSort.REVERSE, 42, directory.take(2))
        draft.name = "我的列表"
        val video = directory[0]
        val parts = (1..2).map { BiliPart(video.bvid, it.toLong(), it, "P$it", "UP", 1000, "") }
        val prepared = CollectionPreparation(
            CollectionAction.COPY, listOf(BiliVideoDetails(video, parts)), listOf(directory[1].title),
            checkNotNull(draft.targets), copyName = draft.name,
        )
        val chooser = BiliImportRequests(existingMode = { null })
        val submitted = mutableListOf<CollectionPreparation>()
        val save: suspend (CollectionPreparation) -> Boolean = {
            submitted += it
            chooser.choose(it.details) != null
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { draft.submit(prepared, save) }
        chooser.cancel(checkNotNull(chooser.prompt.value).id)
        assertFalse(first.await())
        assertEquals("我的列表", draft.name)
        assertEquals(prepared, draft.preparation)

        val retry = async(start = CoroutineStart.UNDISPATCHED) { draft.submit(checkNotNull(draft.preparation), save) }
        val prompt = checkNotNull(chooser.prompt.value)
        chooser.edit(prompt.id, 0) { it.copy(mode = BiliImportMode.SPLIT) }
        chooser.confirm(prompt.id)
        assertTrue(retry.await())
        assertEquals(listOf(prepared, prepared), submitted)
    }

    @Test fun cancelledRefreshKeepsTheGateUntilCleanupAndFailureReleasesIt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val work = CollectionWork(scope)
        val finishCleanup = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val errors = mutableListOf<Exception>()
        try {
            work.start(CollectionOperation.REFRESH, { errors += it }) {
                events += "refresh"
                try {
                    CompletableDeferred<Unit>().await()
                } finally {
                    withContext(NonCancellable) { finishCleanup.await() }
                    events += "cleanup"
                }
            }
            work.start(CollectionOperation.REFRESH, { errors += it }) { events += "duplicate" }
            work.cancel()
            work.start(CollectionOperation.PREPARE, { errors += it }) { events += "premature copy" }
            assertEquals(CollectionOperation.REFRESH, work.operation)
            assertEquals(listOf("refresh"), events)

            finishCleanup.complete(Unit)
            assertNull(work.operation)
            val failure = IllegalStateException("failed")
            work.start(CollectionOperation.PREPARE, { errors += it }) { throw failure }
            assertEquals(listOf(failure), errors)
            assertNull(work.operation)
            work.start(CollectionOperation.REFRESH, { errors += it }) { events += "retry" }
            assertEquals(listOf("refresh", "cleanup", "retry"), events)
        } finally {
            finishCleanup.complete(Unit)
            scope.cancel()
        }
    }
}

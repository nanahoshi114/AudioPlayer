package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TaskPumpTest {
    @Test fun downloadsRespectOneThreeAndFiveSlotsAndRefill() = runBlocking {
        withTimeout(5_000) {
            for (limit in listOf(1, 3, 5)) {
                val queue = Downloads(limit)
                (1L..7L).forEach { queue.add(it) }
                val worker = async { queue.run() }
                repeat(limit) { assertEquals(it + 1L, queue.started.receive()) }
                assertTrue(queue.started.tryReceive().isFailure)
                queue.release(1)
                assertEquals(limit + 1L, queue.started.receive())
                queue.finishAll()
                worker.await()
                assertEquals(limit, queue.peak)
            }
        }
    }

    @Test fun changingLimitWakesQueueAndNeverCancelsExistingDownloads() = runBlocking {
        withTimeout(5_000) {
            val queue = Downloads(1)
            (1L..5L).forEach { queue.add(it) }
            val worker = async { queue.run() }
            assertEquals(1L, queue.started.receive())
            queue.limit = 3
            queue.wake.trySend(Unit)
            assertEquals(2L, queue.started.receive())
            assertEquals(3L, queue.started.receive())
            queue.limit = 1
            queue.release(1)
            queue.release(2)
            queue.settle()
            assertEquals(setOf(3L), queue.active)
            assertTrue(queue.started.tryReceive().isFailure)
            queue.release(3)
            assertEquals(4L, queue.started.receive())
            queue.finishAll()
            worker.await()
        }
    }

    @Test fun enqueueWakesAnIdleSlotWithoutWaitingForAnotherDownload() = runBlocking {
        withTimeout(5_000) {
            val queue = Downloads(3)
            queue.add(1)
            val worker = async { queue.run() }
            assertEquals(1L, queue.started.receive())
            queue.add(2)
            assertEquals(2L, queue.started.receive())
            assertEquals(setOf(1L, 2L), queue.active)
            queue.finishAll()
            worker.await()
        }
    }

    @Test fun archivesExcludeTheirWorkAndLaterPlayFilesDoNotOvertakeThem() {
        val play = DownloadSlot(1, "RJ123456", false)
        val archive = DownloadSlot(2, "RJ123456", true)
        val later = DownloadSlot(3, "RJ123456", false)
        val other = DownloadSlot(4, "RJ654321", false)
        val anotherArchive = DownloadSlot(5, "RJ654321", true)
        val queued = listOf(archive, later, other, anotherArchive)
        assertFalse(canStartDownload(archive, queued, listOf(play), 3))
        assertFalse(canStartDownload(later, queued, listOf(play), 3))
        assertTrue(canStartDownload(other, queued, listOf(play), 3))
        assertFalse(canStartDownload(anotherArchive, queued, listOf(archive), 3))
        assertFalse(canStartDownload(later, listOf(later), listOf(archive), 3))
        assertTrue(canStartDownload(archive, queued, emptyList(), 3))
        assertTrue(canStartDownload(later, listOf(later), listOf(play), 3))
    }

    @Test fun japaneseWholeWorksUseOrdinaryCapacityAndExcludeOnlyTheirOwnWholeWork() {
        val first = DownloadSlot(1, "RJ123456", archive = false, exclusiveWork = true)
        val second = first.copy(id = 2)
        val other = first.copy(id = 3, work = "RJ654321")
        val single = DownloadSlot(4, null, archive = false)
        val archive = DownloadSlot(5, "RJ123456", archive = true)
        val queued = listOf(first, second, other, single, archive)
        assertFalse(canStartDownload(second, queued, emptyList(), 3))
        assertFalse(canStartDownload(second, listOf(second), listOf(first), 3))
        assertTrue(canStartDownload(other, queued, listOf(first), 3))
        assertTrue(canStartDownload(single, queued, listOf(first), 3))
        assertTrue(canStartDownload(archive, queued, listOf(first), 3))
        assertTrue(canStartDownload(first, queued, listOf(archive), 3))
        assertFalse(canStartDownload(other, queued, listOf(first, archive), 2))
    }

    @Test fun cancellationKeepsSlotUntilCleanupAndLeavesOtherTasksAlive() = runBlocking {
        withTimeout(5_000) {
            val wake = Channel<Unit>(Channel.CONFLATED)
            val pending = mutableListOf(1, 2, 3)
            val started = Channel<Int>(Channel.UNLIMITED)
            val workers = mutableMapOf<Int, Job>()
            val cleanup = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val releaseOthers = CompletableDeferred<Unit>()
            val pump = async {
                pumpTasks(wake, { pending.toList() }, { it }, { _, _, running -> running.size < 2 }) { id ->
                    pending.remove(id)
                    workers[id] = currentCoroutineContext()[Job]!!
                    started.send(id)
                    if (id == 1) try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleanup.complete(Unit)
                            releaseCleanup.await()
                        }
                    } else releaseOthers.await()
                }
            }
            assertEquals(1, started.receive())
            assertEquals(2, started.receive())
            workers.getValue(1).cancel()
            cleanup.await()
            assertTrue(started.tryReceive().isFailure)
            assertTrue(workers.getValue(2).isActive)
            releaseCleanup.complete(Unit)
            assertEquals(3, started.receive())
            releaseOthers.complete(Unit)
            pump.await()
        }
    }

    @Test fun serviceCancellationWaitsForAllChildCleanup() = runBlocking {
        withTimeout(5_000) {
            val queue = Downloads(3)
            (1L..4L).forEach { queue.add(it) }
            val worker = async { queue.run() }
            repeat(3) { queue.started.receive() }
            worker.cancelAndJoin()
            assertTrue(queue.active.isEmpty())
            assertTrue(queue.started.tryReceive().isFailure)
        }
    }

    @Test fun oneFailureIsRecordedWhileSiblingsContinueAndTheQueueRefills() = runBlocking {
        withTimeout(5_000) {
            val wake = Channel<Unit>(Channel.CONFLATED)
            val pending = mutableListOf(1, 2, 3)
            val started = Channel<Int>(Channel.UNLIMITED)
            val failures = mutableListOf<Int>()
            val release = CompletableDeferred<Unit>()
            val pump = async {
                pumpTasks(wake, { pending.toList() }, { it }, { _, _, running -> running.size < 2 },
                    onFailure = { id, _ -> failures += id },
                ) { id ->
                    pending.remove(id)
                    started.send(id)
                    if (id == 1) error("模拟传输失败")
                    release.await()
                }
            }
            assertEquals(listOf(1, 2, 3), List(3) { started.receive() })
            assertEquals(listOf(1), failures)
            assertFalse(pump.isCompleted)
            release.complete(Unit)
            pump.await()
        }
    }

    private class Downloads(var limit: Int) {
        val wake = Channel<Unit>(Channel.CONFLATED)
        val started = Channel<Long>(Channel.UNLIMITED)
        private val snapshots = Channel<Unit>(Channel.UNLIMITED)
        private val pending = mutableListOf<DownloadSlot>()
        private val releases = mutableMapOf<Long, CompletableDeferred<Unit>>()
        val active = mutableSetOf<Long>()
        var peak = 0
        fun add(id: Long) {
            pending += DownloadSlot(id, null, false)
            releases[id] = CompletableDeferred()
            wake.trySend(Unit)
        }
        fun release(id: Long) { releases.getValue(id).complete(Unit) }
        fun finishAll() { releases.values.forEach { it.complete(Unit) } }
        suspend fun settle() {
            while (snapshots.tryReceive().isSuccess) Unit
            wake.trySend(Unit)
            snapshots.receive()
        }
        suspend fun run() = pumpTasks(wake,
            load = { pending.toList().also { snapshots.trySend(Unit) } },
            key = { it.id },
            canStart = { task, queued, running -> canStartDownload(task, queued, running, limit) },
            execute = { task ->
                pending.remove(task)
                active += task.id
                peak = maxOf(peak, active.size)
                try {
                    started.send(task.id)
                    releases.getValue(task.id).await()
                } finally {
                    active -= task.id
                }
            },
        )
    }
}

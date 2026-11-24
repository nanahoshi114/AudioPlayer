package com.nanahoshi.audioplayer.ui.components

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class SubtitleOffsetWriterTest {
    private val trackId = 7L

    @Test
    fun rapidBurstsAccumulateAndPersistOnlyTheLatestValue(): Unit = runBlocking {
        val writes = mutableListOf<Long?>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val writer = SubtitleOffsetWriter(scope, persist = { _, offset -> writes += offset })
        try {
            assertEquals(-500L, writer.step(trackId, 0L, -500L))
            assertEquals(-1000L, writer.step(trackId, 0L, -500L))
            assertEquals(-500L, writer.step(trackId, 0L, 500L))
            assertEquals(-1000L, writer.step(trackId, 0L, -500L))
            assertEquals(-1000L, writer.pendingOffsets.value[trackId])

            withTimeout(5_000) {
                while (writes.lastOrNull() != -1000L) delay(5)
            }
            assertEquals(-1000L, writes.last())

            // Room 回流尚未追上时保持乐观值。
            assertEquals(-1000L, writer.displayedOffset(trackId, 0L))
            assertEquals(-1000L, writer.pendingOffsets.value[trackId])

            // 已落盘值追上后清除覆盖，不会一直停在旧乐观值。
            withTimeout(5_000) {
                while (writer.pendingOffsets.value[trackId] != null) {
                    writer.sync(trackId, -1000L)
                    delay(5)
                }
            }
            assertEquals(-1000L, writer.displayedOffset(trackId, -1000L))
            assertNull(writer.pendingOffsets.value[trackId])

            // 归零按既有约定落盘为 null。
            assertEquals(0L, writer.reset(trackId, -1000L))
            withTimeout(5_000) {
                while (writes.lastOrNull() != null) delay(5)
            }
            assertNull(writes.last())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun slowPersistenceKeepsMonotonicOptimismAndWritesTheLatestValueLast(): Unit = runBlocking {
        val firstWrite = CompletableDeferred<Unit>()
        val writes = Collections.synchronizedList(mutableListOf<Long?>())
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val writer = SubtitleOffsetWriter(scope, persist = { _, offset ->
            writes += offset
            if (writes.size == 1) firstWrite.await()
        })
        try {
            writer.step(trackId, 0L, -500L)
            withTimeout(5_000) { while (writes.size < 1) delay(5) }
            assertEquals(listOf<Long?>(-500L), writes)

            writer.step(trackId, 0L, -500L)
            writer.step(trackId, 0L, 500L)
            writer.step(trackId, 0L, -500L)
            assertEquals(-1000L, writer.pendingOffsets.value[trackId])

            firstWrite.complete(Unit)
            withTimeout(5_000) { while (writes.size < 2) delay(5) }
            assertEquals(listOf<Long?>(-500L, -1000L), writes)
            assertEquals(-1000L, writer.pendingOffsets.value[trackId])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun persistenceFailureRollsBackAndKeepsLaterClicksWorking(): Unit = runBlocking {
        var fail = true
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val writes = Collections.synchronizedList(mutableListOf<Long?>())
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val writer = SubtitleOffsetWriter(
            scope,
            persist = { _, offset ->
                if (fail) throw IllegalStateException("fixture failure")
                writes += offset
            },
            onError = { errors += it },
        )
        try {
            writer.step(trackId, 0L, -500L)
            withTimeout(5_000) { while (errors.isEmpty()) delay(5) }
            assertTrue(errors.single() is IllegalStateException)
            assertNull(writer.pendingOffsets.value[trackId])

            fail = false
            assertEquals(500L, writer.step(trackId, 0L, 500L))
            withTimeout(5_000) { while (writes.isEmpty()) delay(5) }
            assertEquals(listOf<Long?>(500L), writes)
            assertEquals(500L, writer.pendingOffsets.value[trackId])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun writesSurviveCancellationOfTheSheetScope(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = Collections.synchronizedList(mutableListOf<Long?>())
        val writerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val sheetScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val writer = SubtitleOffsetWriter(writerScope, persist = { _, offset ->
            started.complete(Unit)
            release.await()
            writes += offset
        })
        try {
            writer.step(trackId, 0L, -500L)
            withTimeout(5_000) { started.await() }
            sheetScope.cancel()
            release.complete(Unit)
            withTimeout(5_000) { while (writes.isEmpty()) delay(5) }
            assertEquals(listOf<Long?>(-500L), writes)
        } finally {
            sheetScope.cancel()
            writerScope.cancel()
        }
    }

    @Test
    fun externalBindingResetDropsTheStaleOverlay(): Unit = runBlocking {
        val releaseWrite = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val writer = SubtitleOffsetWriter(scope, persist = { _, _ -> releaseWrite.await() })
        try {
            writer.step(trackId, -500L, -500L)
            assertEquals(-1000L, writer.displayedOffset(trackId, -500L))
            // 同一曲目换绑到归零的字幕：已落盘值变化且不是本次写入结果。
            assertEquals(0L, writer.displayedOffset(trackId, 0L))
            assertNull(writer.pendingOffsets.value[trackId])
            releaseWrite.complete(Unit)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clearCancelsInFlightWriteAndAllowsFreshEditsAfterRebind(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val writes = Collections.synchronizedList(mutableListOf<Long?>())
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val writer = SubtitleOffsetWriter(scope, persist = { _, offset ->
            if (started.complete(Unit)) {
                delay(5_000)
            }
            writes += offset
        })
        try {
            writer.step(trackId, 0L, 500L)
            withTimeout(5_000) { started.await() }
            writer.clear(trackId)
            assertNull(writer.pendingOffsets.value[trackId])

            writer.step(trackId, 0L, -500L)
            withTimeout(5_000) { while (writes.isEmpty()) delay(5) }
            assertEquals(listOf<Long?>(-500L), writes)
        } finally {
            scope.cancel()
        }
    }
}
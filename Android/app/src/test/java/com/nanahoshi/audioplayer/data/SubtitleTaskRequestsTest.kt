package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTaskRequestsTest {
    @Test
    fun rejectedServiceStartDoesNotRunWork() = runBlocking {
        val requests = SubtitleTaskRequests { false }
        val request = requests.submit(SubtitleTask.WHISPER) { error("不可执行") }

        assertEquals(SUBTITLE_START_FAILED, runCatching { request.result.await() }.exceptionOrNull()?.message)
        assertNull(requests.take(1))
    }

    @Test
    fun workWaitsForServiceAndSurvivesLossOfUiWaiter() = runBlocking {
        withTimeout(5_000) {
            val requests = SubtitleTaskRequests { true }
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val request = requests.submit(SubtitleTask.WHISPER) {
                started.complete(Unit)
                release.await()
                "字幕"
            }
            val waiter = launch { request.result.await() }
            assertFalse(started.isCompleted)
            val worker = checkNotNull(requests.take(1)).launchIn(this)
            assertFalse(started.isCompleted)
            worker.start()
            started.await()
            waiter.cancelAndJoin()
            assertTrue(worker.isActive)

            release.complete(Unit)
            assertEquals("字幕", request.result.await())
        }
    }

    @Test
    fun cancellingTranslationWaitsForCleanupAndLeavesPolishRunning() = runBlocking {
        withTimeout(5_000) {
            val requests = SubtitleTaskRequests { true }
            val started = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val releasePolish = CompletableDeferred<Unit>()
            val translation = requests.submit(SubtitleTask.TRANSLATE) {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleaning.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            }
            val polish = requests.submit(SubtitleTask.POLISH) {
                releasePolish.await()
                true
            }
            checkNotNull(requests.take(1)).launchIn(this).start()
            checkNotNull(requests.take(2)).launchIn(this).start()
            try {
                started.await()
                translation.cancel()
                cleaning.await()
                assertFalse(translation.result.isCompleted)
                assertFalse(polish.result.isCompleted)
            } finally {
                releaseCleanup.complete(Unit)
                releasePolish.complete(Unit)
            }

            assertTrue(runCatching { translation.result.await() }.exceptionOrNull() is CancellationException)
            assertTrue(polish.result.await())
        }
    }

    @Test
    fun stoppingServiceClearsPendingWorkAndAllowsNewRequests() = runBlocking {
        val requests = SubtitleTaskRequests { true }
        val pending = requests.submit(SubtitleTask.POLISH) { error("不可执行") }
        val reason = SubtitleTaskStopped("后台字幕任务超时")
        var stopped = false
        requests.stopPending(reason) { stopped = true }

        assertTrue(stopped)
        assertEquals(reason.message, runCatching { pending.result.await() }.exceptionOrNull()?.message)
        assertNull(requests.take(1))
        val next = requests.submit(SubtitleTask.POLISH) { true }
        checkNotNull(requests.take(2)).launchIn(this).start()
        assertTrue(next.result.await())
    }
}

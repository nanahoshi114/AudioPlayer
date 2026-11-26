package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HttpSupportTest {
    @Test
    fun copiesWholeStreamAndReportsMonotonicProgress() = runBlocking {
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val copied = copyStreamToFile(
            ByteArrayInputStream(ByteArray(700 * 1024)),
            output,
            total = 700L * 1024,
        ) { done, _ -> progress += done }

        assertEquals(700L * 1024, copied)
        assertEquals(700 * 1024, output.size())
        assertEquals(progress.sorted(), progress)
        assertEquals(700L * 1024, progress.last())
    }

    @Test
    fun rejectsAStreamLongerThanTheLimit() = runBlocking {
        val failure = runCatching {
            copyStreamToFile(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), total = null, maxBytes = 5)
        }
        assertTrue(failure.exceptionOrNull() is StreamCopyLimitExceededException)
    }

    @Test
    fun rejectsTruncatedOrEmptyStreams() = runBlocking {
        val truncated = runCatching {
            copyStreamToFile(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), total = 5)
        }
        assertTrue(truncated.exceptionOrNull() is StreamCopyIncompleteException)

        val empty = runCatching {
            copyStreamToFile(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), total = null)
        }
        assertTrue(empty.exceptionOrNull() is StreamCopyIncompleteException)
    }

    @Test
    fun concurrentIdenticalFetchesShareOneExecution() = runBlocking {
        val calls = InflightCalls()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executions = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            calls.execute("same") { executions += 1; started.complete(Unit); release.await(); "value" }
        }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            calls.execute("same") { executions += 1; "other" }
        }
        yield()
        assertFalse(second.isCompleted)
        release.complete(Unit)
        assertEquals("value", first.await())
        assertEquals("value", second.await())
        assertEquals(1, executions)

        assertEquals("later", calls.execute("same") { executions += 1; "later" })
        assertEquals(2, executions)
    }

    @Test
    fun cancelledOwnerCancelsWaiters() = runBlocking {
        val calls = InflightCalls()
        val started = CompletableDeferred<Unit>()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            calls.execute("shared-failure") { started.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        }
        started.await()
        val waiter = async { calls.execute("shared-failure") { "unused" } }
        owner.cancel()
        waiter.join()
        assertTrue(runCatching { waiter.await() }.isFailure)
    }
}
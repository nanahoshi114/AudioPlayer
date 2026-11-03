package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlsiteDownloadRequestsTest {
    @Test
    fun choosingPartialReturnsOnlyAfterExplicitAnswer() = runBlocking {
        val requests = DlsiteDownloadRequests()
        val result = async(start = CoroutineStart.UNDISPATCHED) { requests.choose(listOf("RJ123", "RJ123")) }
        val prompt = requests.pending.value!!
        assertEquals(listOf("RJ123"), prompt.workNames)
        assertFalse(result.isCompleted)
        requests.answer(prompt.id, DlsiteDownloadMode.PARTIAL)
        assertEquals(DlsiteDownloadMode.PARTIAL, result.await())
        assertNull(requests.pending.value)
    }

    @Test
    fun originalOnlyCannotBeAnsweredWithPartial() = runBlocking {
        val requests = DlsiteDownloadRequests()
        val result = async(start = CoroutineStart.UNDISPATCHED) { requests.choose(listOf("RJ123"), allowPartial = false) }
        val prompt = requests.pending.value!!
        requests.answer(prompt.id, DlsiteDownloadMode.PARTIAL)
        yield()
        assertFalse(result.isCompleted)
        requests.answer(prompt.id, DlsiteDownloadMode.ARCHIVE)
        assertEquals(DlsiteDownloadMode.ARCHIVE, result.await())
    }

    @Test
    fun cancellationClearsPromptAndStaleButtonCannotAnswerNextRequest() = runBlocking {
        val requests = DlsiteDownloadRequests()
        val first = async(start = CoroutineStart.UNDISPATCHED) { requests.choose(listOf("RJ123")) }
        val stale = requests.pending.value!!
        first.cancelAndJoin()
        assertNull(requests.pending.value)
        val second = async(start = CoroutineStart.UNDISPATCHED) { requests.choose(listOf("RJ456")) }
        val prompt = requests.pending.value!!
        assertTrue(prompt.id != stale.id)
        requests.answer(stale.id, DlsiteDownloadMode.ARCHIVE)
        yield()
        assertFalse(second.isCompleted)
        requests.answer(prompt.id, null)
        assertNull(second.await())
        assertNull(requests.pending.value)
    }

    @Test
    fun simultaneousRequestsArePresentedInOrder() = runBlocking {
        val requests = DlsiteDownloadRequests()
        val first = async(start = CoroutineStart.UNDISPATCHED) { requests.choose(listOf("RJ123")) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { requests.choose(listOf("RJ456")) }
        val firstPrompt = requests.pending.value!!
        assertEquals(listOf("RJ123"), firstPrompt.workNames)
        requests.answer(firstPrompt.id, DlsiteDownloadMode.ARCHIVE)
        assertEquals(DlsiteDownloadMode.ARCHIVE, first.await())
        yield()
        val secondPrompt = requests.pending.value!!
        assertEquals(listOf("RJ456"), secondPrompt.workNames)
        requests.answer(secondPrompt.id, DlsiteDownloadMode.PARTIAL)
        assertEquals(DlsiteDownloadMode.PARTIAL, second.await())
        assertNull(requests.pending.value)
    }
}

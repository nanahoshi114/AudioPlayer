package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.bilibili.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BiliImportRequestsTest {
    private val first = BiliPart("BV1234567890", 42, 1, "first", "UP", 1000, "")
    private val second = first.copy(cid = 43, page = 2, title = "second")
    private fun details(parts: List<BiliPart> = listOf(first, second)) = BiliVideoDetails(
        BiliVideoHit(first.bvid, "video", "UP", "", 2000), parts)

    @Test fun splitRequiresExplicitChoiceAndOnlyImportsCheckedParts() = runBlocking {
        val requests = BiliImportRequests({ null })
        val result = async(start = CoroutineStart.UNDISPATCHED) { requests.choose(listOf(details())) }
        val id = requests.prompt.value!!.id
        assertFalse(requests.prompt.value!!.ready)
        requests.confirm(id)
        assertFalse(result.isCompleted)
        requests.edit(id, 0) { it.copy(mode = BiliImportMode.SPLIT, selected = setOf(43)) }
        requests.confirm(id)
        assertEquals(listOf(second), result.await()!!.single().parts)
        assertNull(requests.prompt.value)
    }

    @Test fun groupedAlwaysContainsAllPartsAndConversionNeedsASecondConfirmation() = runBlocking {
        val requests = BiliImportRequests({ BiliImportMode.SPLIT })
        val result = async(start = CoroutineStart.UNDISPATCHED) { requests.choose(listOf(details())) }
        val id = requests.prompt.value!!.id
        requests.edit(id, 0) { it.copy(mode = BiliImportMode.GROUPED, selected = emptySet()) }
        requests.confirm(id)
        assertTrue(requests.prompt.value!!.confirming)
        assertFalse(result.isCompleted)
        requests.confirm(id)
        val saved = result.await()!!.single()
        assertEquals(listOf(first, second), saved.parts)
        assertTrue(saved.conversionConfirmed)
    }

    @Test fun cancellationCreatesNoRequestAndStaleButtonsCannotConfirmAnotherPrompt() = runBlocking {
        val requests = BiliImportRequests({ null })
        val firstResult = async(start = CoroutineStart.UNDISPATCHED) { requests.choose(listOf(details())) }
        val old = requests.prompt.value!!.id
        requests.cancel(old)
        assertNull(firstResult.await())
        val secondResult = async(start = CoroutineStart.UNDISPATCHED) { requests.choose(listOf(details())) }
        requests.cancel(old)
        assertFalse(secondResult.isCompleted)
        requests.cancel(requests.prompt.value!!.id)
        assertNull(secondResult.await())
    }

    @Test fun singlePartKeepsTheExistingDirectImportFlow() = runBlocking {
        val requests = BiliImportRequests({ null })
        val result = requests.choose(listOf(details(listOf(first))))!!.single()
        assertEquals(BiliImportMode.SPLIT, result.mode)
        assertEquals(listOf(first), result.parts)
        assertNull(requests.prompt.value)
    }

    @Test fun busyPromptRejectsSecondRequestWithoutQueueing() = runBlocking {
        var busyCount = 0
        val requests = BiliImportRequests({ null }, { busyCount++ })
        val firstResult = async(start = CoroutineStart.UNDISPATCHED) { requests.choose(listOf(details())) }
        val id = requests.prompt.value!!.id
        assertNull(requests.choose(listOf(details())))
        assertEquals(1, busyCount)
        assertEquals(id, requests.prompt.value!!.id)
        assertFalse(firstResult.isCompleted)
        requests.edit(id, 0) { it.copy(mode = BiliImportMode.SPLIT, selected = setOf(42, 43)) }
        requests.confirm(id)
        assertNotNull(firstResult.await())
        assertNull(requests.prompt.value)
    }

    @Test fun promptIsReusableAfterTheFirstRequestFinishes() = runBlocking {
        val requests = BiliImportRequests({ null })
        val firstResult = async(start = CoroutineStart.UNDISPATCHED) { requests.choose(listOf(details())) }
        val firstId = requests.prompt.value!!.id
        requests.edit(firstId, 0) { it.copy(mode = BiliImportMode.SPLIT, selected = setOf(42)) }
        requests.confirm(firstId)
        assertNotNull(firstResult.await())
        assertNull(requests.prompt.value)
        val secondResult = async(start = CoroutineStart.UNDISPATCHED) { requests.choose(listOf(details())) }
        val secondId = requests.prompt.value!!.id
        assertNotEquals(firstId, secondId)
        requests.cancel(secondId)
        assertNull(secondResult.await())
        assertNull(requests.prompt.value)
    }

    @Test fun cancellingTheCallerStillClearsThePrompt() = runBlocking {
        val requests = BiliImportRequests({ null })
        val job = launch(start = CoroutineStart.UNDISPATCHED) { requests.choose(listOf(details())) }
        assertNotNull(requests.prompt.value)
        job.cancelAndJoin()
        assertNull(requests.prompt.value)
        val next = async(start = CoroutineStart.UNDISPATCHED) { requests.choose(listOf(details())) }
        assertNotNull(requests.prompt.value)
        requests.cancel(requests.prompt.value!!.id)
        assertNull(next.await())
        assertNull(requests.prompt.value)
    }

    @Test fun legacyMetadataAndBatchMetadataBothRetainBvCidIdentity() {
        assertEquals(first, decodeBiliDownloadImport(first.importMetadata(), first.bvid, first.cid).part)
        val raw = biliLibraryJson.encodeToString(BiliDownloadImport.serializer(), BiliDownloadImport(first, "batch"))
        assertEquals("batch", decodeBiliDownloadImport(raw, first.bvid, first.cid).batchId)
        assertTrue(runCatching { decodeBiliDownloadImport(raw, first.bvid, 99) }.isFailure)
    }
}

package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.bilibili.BiliPart
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ClipboardImportTest {
    private val bv = ClipboardTarget(ClipboardSource.BILIBILI, "BV1234567890")
    private val rj = ClipboardTarget(ClipboardSource.ASMR, "RJ123456")
    private val yt = ClipboardTarget(ClipboardSource.YOUTUBE, "jNQXAC9IVRw")
    private val parts = listOf(BiliPart(bv.id, 42, 1, "part one", "UP", 1000, ""), BiliPart(bv.id, 43, 2, "part two", "UP", 1000, ""))
    private fun preview(target: ClipboardTarget) = ClipboardImportPreview(target, "title", "https://example.org/cover.jpg",
        parts = if (target.source == ClipboardSource.BILIBILI) parts else emptyList())

    @Test fun recognizesIdsAndSharingTextWithoutTreatingOrdinaryTextAsIds() {
        assertEquals(bv, clipboardImportTarget("复制了 ${bv.id} 来听"))
        assertEquals(rj, clipboardImportTarget("作品 rj123456"))
        assertEquals(ClipboardTarget(ClipboardSource.ASMR, "RJ000123"), clipboardImportTarget("RJ00000123"))
        assertEquals(rj, clipboardImportTarget("https://www.dlsite.com/maniax/work/=/product_id/RJ123456.html"))
        for (raw in listOf("hello", "123456", "jNQXAC9IVRw", "someBV1234567890text", "RJ000000", "RJ1234567890123", "prefixRJ123456", "BV12345678901")) {
            assertNull(raw, clipboardImportTarget(raw))
        }
        assertNull(clipboardImportTarget("x".repeat(MAX_IMPORT_CLIPBOARD_LENGTH) + " ${bv.id}"))
    }
    @Test fun youtubeLinksAcceptOnlyKnownHostsAndAValidVideoId() {
        for (url in listOf("https://youtu.be/${yt.id}?si=test", "https://www.youtube.com/watch?v=${yt.id}&list=PL1234567890123",
            "youtu.be/${yt.id}", "www.youtube.com/watch?v=${yt.id}",
            "https://m.youtube.com/shorts/${yt.id}", "https://www.youtube.com/live/${yt.id}",
            "https://www.youtube.com/embed/${yt.id}", "https://www.youtube-nocookie.com/embed/${yt.id}")) {
            assertEquals(url, yt, clipboardImportTarget("分享视频：$url。"))
        }
        for (url in listOf("https://youtube.com.evil.test/watch?v=${yt.id}", "https://youtu.be@evil.test/${yt.id}",
            "https://www.youtube.com/playlist?list=PL1234567890123", "https://www.youtube.com/watch?v=too-short", "https://youtu.be/${yt.id}extra")) {
            assertNull(url, clipboardImportTarget(url))
        }
    }
    @Test fun explicitVideoLinkBeatsRjInTheSharingTitleOtherwiseUsesFirstId() {
        assertEquals(yt, clipboardImportTarget("RJ123456 的试听 https://youtu.be/${yt.id}"))
        assertEquals(bv, clipboardImportTarget("RJ123456 的试听 https://www.bilibili.com/video/${bv.id}"))
        assertEquals(rj, clipboardImportTarget("RJ123456 和 ${bv.id}"))
        assertEquals(bv, clipboardImportTarget("${bv.id} 和 RJ123456"))
    }
    @Test fun fingerprintContainsNeitherClipboardTextNorUrlsAndDistinguishesCopyEvents() {
        val same = ClipboardImportCandidate(yt, 123)
        assertEquals(same.fingerprint, same.copy().fingerprint)
        assertTrue(Regex("[a-f0-9]{64}").matches(same.fingerprint))
        assertNotEquals(same.fingerprint, same.copy(timestamp = 124).fingerprint)
        assertNotEquals(same.fingerprint, ClipboardImportCandidate(bv, 123).fingerprint)
    }

    private class Memory { var last: String? = null; val messages = mutableListOf<String>() }
    private fun controller(scope: CoroutineScope, memory: Memory,
        read: suspend (ClipboardTarget) -> ClipboardImportPreview,
        save: suspend (ClipboardImportPreview, List<BiliPart>) -> String = { _, _ -> error("must not import") },
    ) = ClipboardImportController(scope, object : ClipboardImportBackend {
        override suspend fun preview(target: ClipboardTarget) = read(target)
        override suspend fun save(preview: ClipboardImportPreview, selectedParts: List<BiliPart>) = save(preview, selectedParts)
    }, { memory.last }, { memory.last = it }, { memory.messages += it })

    @Test fun previewIsReadOnlyAndDismissalIsRememberedAcrossControllerRecreation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined);val memory = Memory();var reads = 0
        try {
            val read: suspend (ClipboardTarget) -> ClipboardImportPreview = { reads++; preview(it) }
            val first = controller(scope, memory, read)
            first.offer(ClipboardImportCandidate(bv, 1));assertEquals("title", first.state!!.preview!!.title)
            assertTrue(memory.messages.isEmpty());first.dismiss();assertNull(first.state)
            first.offer(ClipboardImportCandidate(bv, 1));assertNull(first.state)
            val recreated = controller(scope, memory, read)
            recreated.offer(ClipboardImportCandidate(bv, 1));assertNull(recreated.state);assertEquals(1, reads)
            recreated.offer(ClipboardImportCandidate(bv, 2));assertNotNull(recreated.state);assertEquals(2, reads)
        } finally { scope.cancel() }
    }
    @Test fun onlyConfirmedBiliPartsAreImportedAndAnEmptySelectionIsDisabled() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined);val memory = Memory();val imported = mutableListOf<List<BiliPart>>()
        try {
            val c = controller(scope, memory, { preview(it) }, { _, selected -> imported += selected; "done" })
            c.offer(ClipboardImportCandidate(bv));assertEquals(setOf(42L, 43L), c.state!!.selected);assertTrue(imported.isEmpty())
            c.toggleAllParts();assertFalse(c.state!!.canImport);c.confirm();assertTrue(imported.isEmpty())
            c.togglePart(43);assertTrue(c.state!!.canImport);c.confirm()
            assertEquals(listOf(listOf(parts[1])), imported);assertEquals(listOf("done"), memory.messages);assertNull(c.state)
        } finally { scope.cancel() }
    }
    @Test fun pendingImportCannotBeDismissedOrDoubleSubmittedOrReplaced() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined);val memory = Memory();val gate = CompletableDeferred<Unit>();var writes = 0
        try {
            val c = controller(scope, memory, { preview(it) }, { _, _ -> writes++;gate.await();"done" })
            c.offer(ClipboardImportCandidate(rj, 1));c.confirm();c.confirm();c.dismiss();c.offer(ClipboardImportCandidate(yt, 2))
            assertEquals(1, writes);assertTrue(c.state!!.saving);assertEquals(rj, c.state!!.candidate.target)
            gate.complete(Unit);assertNull(c.state)
            c.offer(ClipboardImportCandidate(yt, 2));assertEquals(yt, c.state!!.candidate.target)
        } finally { scope.cancel() }
    }
    @Test fun failedMetadataCanRetryAndRawErrorsNeverReachTheDialog() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined);val memory = Memory();var reads = 0
        try {
            val c = controller(scope, memory, { if (reads++ == 0) error("Cookie=secret https://example.org/private") else preview(it) })
            c.offer(ClipboardImportCandidate(yt));assertFalse(c.state!!.canImport);assertNotNull(c.state!!.error)
            assertFalse(c.state!!.error!!.contains("secret"));assertFalse(c.state!!.error!!.contains("https://"))
            c.load();assertNull(c.state!!.error);assertTrue(c.state!!.canImport)
        } finally { scope.cancel() }
    }
    @Test fun failedImportKeepsPreviewAndSelectedPartsForRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined);val memory = Memory();var writes = 0
        try {
            val c = controller(scope, memory, { preview(it) }, { _, selected ->
                assertEquals(listOf(parts[0]), selected)
                if (writes++ == 0) error("private details") else "done"
            })
            c.offer(ClipboardImportCandidate(bv));c.togglePart(43);c.confirm()
            assertNotNull(c.state!!.preview);assertEquals(setOf(42L), c.state!!.selected);assertFalse(c.state!!.saving)
            assertEquals("导入失败，请重试", c.state!!.error);c.confirm();assertEquals(2, writes);assertNull(c.state)
        } finally { scope.cancel() }
    }
    @Test fun lateMetadataCannotRestoreADismissedOrReplacedDialog() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined);val memory = Memory()
        try {
            lateinit var response: Continuation<ClipboardImportPreview>
            val c = controller(scope, memory, { if (it == bv) suspendCoroutine { response = it } else preview(it) })
            c.offer(ClipboardImportCandidate(bv, 1));c.dismiss();response.resume(preview(bv));assertNull(c.state)
            c.offer(ClipboardImportCandidate(bv, 2));c.offer(ClipboardImportCandidate(yt, 3))
            response.resume(preview(bv));assertEquals(yt, c.state!!.preview!!.target)
        } finally { scope.cancel() }
    }
    @Test fun alreadySavedContentIsShownButCannotCreateAnotherImport() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined);val memory = Memory()
        try {
            val c = controller(scope, memory, { preview(it).copy(alreadySaved = true) })
            c.offer(ClipboardImportCandidate(yt));assertEquals("title", c.state!!.preview!!.title)
            assertFalse(c.state!!.canImport);c.confirm();assertNotNull(c.state);c.dismiss();assertNull(c.state)
        } finally { scope.cancel() }
    }
}

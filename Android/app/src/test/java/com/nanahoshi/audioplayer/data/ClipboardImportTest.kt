package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.asmr.AsmrCatalogItem
import com.nanahoshi.audioplayer.asmr.CatalogLookup
import com.nanahoshi.audioplayer.bilibili.BiliImportMode
import com.nanahoshi.audioplayer.bilibili.BiliImportRequest
import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.bilibili.BiliVideoDetails
import com.nanahoshi.audioplayer.bilibili.BiliVideoHit
import com.nanahoshi.audioplayer.dlsite.DlsiteShowcase
import com.nanahoshi.audioplayer.japaneseasmr.JaArticle
import com.nanahoshi.audioplayer.japaneseasmr.JaCandidate
import com.nanahoshi.audioplayer.japaneseasmr.JaMediaRef
import com.nanahoshi.audioplayer.japaneseasmr.JaPostRef
import com.nanahoshi.audioplayer.text.TextRef
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
    private val video = BiliVideoHit(bv.id, "title", "UP", "", 2000)
    private fun preview(target: ClipboardTarget) = ClipboardImportPreview(target, "title", "https://example.org/cover.jpg",
        parts = if (target.source == ClipboardSource.BILIBILI) parts else emptyList())

    @Test
    fun japaneseArticleLinkHasItsOwnIdentityAndVideoLinkTakesPrecedence() {
        val url = "https://www.japaneseasmr.com/00135575/?share=1#part"
        val target = ClipboardTarget(ClipboardSource.JAPANESE_ASMR, "00135575")
        assertEquals(target, clipboardImportTarget("RJ01258546 $url"))
        assertEquals(yt, clipboardImportTarget("$url https://youtu.be/jNQXAC9IVRw"))
        assertEquals(ClipboardImportCandidate(target, 3).fingerprint,
            ClipboardImportCandidate(checkNotNull(clipboardImportTarget(url)), 3).fingerprint)
    }

    @Test
    fun japaneseArticleChallengeCanOpenInBrowserBeforeImport() {
        val page = "https://japaneseasmr.com/135575/"
        val target = ClipboardTarget(ClipboardSource.JAPANESE_ASMR, "135575")
        val preview = preview(target).copy(jaVerificationPage = page)
        assertEquals(page, preview.jaBrowserUrl)
        assertFalse(ClipboardImportState(preview).canImport)
    }

    @Test fun verifiedClipboardArticleResumesTheMatchingPreviewBeforeImport() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val page = "https://japaneseasmr.com/135575/"
        val target = ClipboardTarget(ClipboardSource.JAPANESE_ASMR, "135575")
        val challenged = preview(target).copy(jaVerificationPage = page)
        val media = JaMediaRef("track", "Track", listOf(JaCandidate("https://example.org/audio.mp4", null)), emptyList())
        val article = JaArticle(JaPostRef(target.id, "RJ123456", "Verified", null, page, null), emptyList(), listOf(media))
        val saved = mutableListOf<JaArticle?>()
        try {
            val c = controller(scope, Memory(), { challenged }, { item, _ -> saved += item.jaArticle; "done" })
            c.offer(ClipboardImportCandidate(target, 1))
            val token = checkNotNull(c.beginJaVerification(challenged))
            c.finishJaVerification("stale-token", article)
            c.finishJaVerification(token, article.copy(post = article.post.copy(postId = "135576")))
            assertNull(c.state)
            c.finishJaVerification(token, article)
            assertEquals(article, c.state?.preview?.jaArticle)
            assertTrue(c.state?.canImport == true)
            assertTrue(saved.isEmpty())
            c.confirm()
            assertEquals(listOf(article), saved)
        } finally { scope.cancel() }
    }

    @Test fun recognizesIdsAndSharingTextWithoutTreatingOrdinaryTextAsIds() {
        assertEquals(bv, clipboardImportTarget("复制了 ${bv.id} 来听"))
        assertEquals(rj, clipboardImportTarget("作品 rj123456"))
        assertEquals(ClipboardTarget(ClipboardSource.ASMR, "RJ00000123"), clipboardImportTarget("RJ00000123"))
        assertEquals(rj, clipboardImportTarget("https://www.dlsite.com/maniax/work/=/product_id/RJ123456.html"))
        for (raw in listOf("hello", "jNQXAC9IVRw", "someBV1234567890text", "RJ000000", "RJ1234567890123", "BV12345678901")) {
            assertNull(raw, clipboardImportTarget(raw))
        }
        assertNull(clipboardImportTarget("x".repeat(MAX_IMPORT_CLIPBOARD_LENGTH) + " ${bv.id}"))
    }
    @Test fun rjIdentifiersPreserveLeadingZerosInTextAndProductLinks() {
        for (id in listOf("RJ01234567", "RJ00123456", "RJ00000123", "RJ012345", "RJ012345678901")) {
            val expected = ClipboardTarget(ClipboardSource.ASMR, id)
            for (input in listOf(id, "分享作品 ${id.lowercase()}，请试听", "https://www.dlsite.com/maniax/work/=/product_id/$id.html")) {
                assertEquals(input, expected, clipboardImportTarget(input))
            }
        }
        assertEquals(ClipboardTarget(ClipboardSource.ASMR, "RJ000123"), clipboardImportTarget("RJ123"))
        for (input in listOf("RJ0", "RJ00000000", "RJ000000000000", "RJ0123456789012")) {
            assertNull(input, clipboardImportTarget(input))
        }
        val padded = ClipboardImportCandidate(clipboardImportTarget("RJ01234567")!!, 1)
        val unpadded = ClipboardImportCandidate(clipboardImportTarget("RJ1234567")!!, 1)
        assertNotEquals(padded.fingerprint, unpadded.fingerprint)
    }

    @Test fun rjIdentifierReachesPreviewAndConfirmedImportWithoutLosingZeros() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val expected = ClipboardTarget(ClipboardSource.ASMR, "RJ01234567")
        val read = mutableListOf<ClipboardTarget>()
        val saved = mutableListOf<ClipboardTarget>()
        try {
            val c = controller(scope, Memory(), { read += it; preview(it) }, { item, _ -> saved += item.target; "done" })
            c.offer(ClipboardImportCandidate(clipboardImportTarget("分享 RJ01234567")!!, 1))
            assertEquals(listOf(expected), read)
            assertTrue(saved.isEmpty())
            c.confirm()
            assertEquals(listOf(expected), saved)
        } finally { scope.cancel() }
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
    @Test fun explicitVideoLinksStillTakePriorityAndRjWinsOverOtherTextNumbers() {
        assertEquals(yt, clipboardImportTarget("RJ123456 的试听 https://youtu.be/${yt.id}"))
        assertEquals(bv, clipboardImportTarget("RJ123456 的试听 https://www.bilibili.com/video/${bv.id}"))
        assertEquals(rj, clipboardImportTarget("RJ123456 和 ${bv.id}"))
        assertEquals(rj, clipboardImportTarget("${bv.id} 和 RJ123456"))
        assertEquals(rj, clipboardImportTarget("prefixRJ123456suffix"))
        assertEquals(rj, clipboardImportTarget("另一个编号 01234567，作品 rj123456"))
        val embeddedRj = "BV1rj1234567"
        assertEquals(ClipboardTarget(ClipboardSource.BILIBILI, embeddedRj), clipboardImportTarget(embeddedRj))
    }
    @Test fun bareWorkNumbersMatchWholeDigitRunsWithTheSupportedLengths() {
        assertEquals(rj, clipboardBareWorkCandidate("分享 123456 作品")?.target)
        assertEquals(rj, clipboardBareWorkCandidate("123456")?.target)
        assertEquals(ClipboardTarget(ClipboardSource.ASMR, "RJ01234567"), clipboardBareWorkCandidate("作品（01234567）")?.target)
        assertEquals(ClipboardTarget(ClipboardSource.ASMR, "RJ000123"), clipboardBareWorkCandidate("编号000123")?.target)
        assertNull(clipboardImportTarget("分享 123456 作品"))
        assertEquals(bv, clipboardImportTarget("123456 ${bv.id}"))
        for (input in listOf("12345", "1234567", "02123456", "10123456", "001234567", "012345678", "000000")) {
            assertNull(input, clipboardBareWorkCandidate(input))
        }
    }
    @Test fun fingerprintContainsNeitherClipboardTextNorUrlsAndDistinguishesCopyEvents() {
        val same = ClipboardImportCandidate(yt, 123)
        assertEquals(same.fingerprint, same.copy().fingerprint)
        assertTrue(Regex("[a-f0-9]{64}").matches(same.fingerprint))
        assertNotEquals(same.fingerprint, same.copy(timestamp = 124).fingerprint)
        assertNotEquals(same.fingerprint, ClipboardImportCandidate(bv, 123).fingerprint)
    }

    private class Memory { var last: String? = null; val messages = mutableListOf<TextRef>() }
    private fun controller(scope: CoroutineScope, memory: Memory,
        read: suspend (ClipboardTarget) -> ClipboardImportPreview?,
        save: suspend (ClipboardImportPreview, List<BiliPart>) -> String = { _, _ -> error("must not import") },
        choose: suspend (ClipboardImportPreview) -> List<BiliImportRequest>? = {
            listOf(BiliImportRequest(video, it.parts, BiliImportMode.SPLIT))
        },
    ) = ClipboardImportController(scope, object : ClipboardImportBackend {
        override suspend fun preview(target: ClipboardTarget) = read(target)
        override suspend fun chooseBiliParts(preview: ClipboardImportPreview) = choose(preview)
        override suspend fun save(preview: ClipboardImportPreview, biliRequests: List<BiliImportRequest>) =
            save(preview, biliRequests.flatMap { it.parts })
    }, { memory.last }, { memory.last = it }, { memory.messages += it })

    @Test fun numericClipboardCandidateOnlyStartsLookupAfterConfirmation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val reads = mutableListOf<ClipboardTarget>()
        val memory = Memory()
        try {
            val c = controller(scope, memory, { reads += it; preview(it) })
            val candidate = checkNotNull(clipboardBareWorkCandidate("示例验证码 123456", 1))
            c.offer(candidate)
            c.offer(candidate.copy())
            assertSame(candidate, c.pendingLookup)
            assertNull(c.state)
            assertNull(memory.last)
            assertTrue(reads.isEmpty())

            c.resolveLookup(candidate, confirmed = true)
            c.resolveLookup(candidate, confirmed = true)
            assertNull(c.pendingLookup)
            assertEquals(listOf(rj), reads)
            assertEquals(rj, c.state?.preview?.target)
            assertEquals(candidate.fingerprint, memory.last)
        } finally { scope.cancel() }
    }

    @Test fun dismissedNumericCandidateIsRememberedWithoutSuppressingAnExplicitRj() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val reads = mutableListOf<ClipboardTarget>()
        val memory = Memory().apply { last = ClipboardImportCandidate(rj, 1).fingerprint }
        try {
            val read: suspend (ClipboardTarget) -> ClipboardImportPreview = { reads += it; preview(it) }
            val first = controller(scope, memory, read)
            first.offer(checkNotNull(clipboardBareWorkCandidate("123456", 1)))
            assertNull(first.pendingLookup)
            assertTrue(reads.isEmpty())

            val candidate = checkNotNull(clipboardBareWorkCandidate("123456"))
            first.offer(candidate)
            first.resolveLookup(candidate, confirmed = false)
            assertEquals(candidate.fingerprint, memory.last)
            assertNull(first.pendingLookup)

            val reopened = controller(scope, memory, read)
            reopened.offer(candidate)
            assertNull(reopened.pendingLookup)
            assertTrue(reads.isEmpty())

            reopened.offer(ClipboardImportCandidate(rj))
            assertEquals(listOf(rj), reads)
            assertEquals(rj, reopened.state?.preview?.target)
        } finally { scope.cancel() }
    }

    @Test fun lateLookupActionCannotApproveAReplacementCandidate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val reads = mutableListOf<ClipboardTarget>()
        try {
            val c = controller(scope, Memory(), { reads += it; preview(it) })
            val previous = checkNotNull(clipboardBareWorkCandidate("123456", 1))
            val replacement = checkNotNull(clipboardBareWorkCandidate("01234567", 2))
            c.offer(previous)
            c.offer(replacement)
            c.resolveLookup(previous, confirmed = true)
            assertSame(replacement, c.pendingLookup)
            assertTrue(reads.isEmpty())

            c.resolveLookup(replacement, confirmed = true)
            assertEquals(listOf(ClipboardTarget(ClipboardSource.ASMR, "RJ01234567")), reads)
        } finally { scope.cancel() }
    }

    @Test fun previewIsReadOnlyAndDismissalIsRememberedAcrossControllerRecreation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined);val memory = Memory();var reads = 0
        try {
            val read: suspend (ClipboardTarget) -> ClipboardImportPreview = { reads++; preview(it) }
            val first = controller(scope, memory, read)
            first.offer(ClipboardImportCandidate(bv, 1));assertEquals("title", first.state!!.preview.title)
            assertTrue(memory.messages.isEmpty());first.dismiss();assertNull(first.state)
            first.offer(ClipboardImportCandidate(bv, 1));assertNull(first.state)
            val recreated = controller(scope, memory, read)
            recreated.offer(ClipboardImportCandidate(bv, 1));assertNull(recreated.state);assertEquals(1, reads)
            recreated.offer(ClipboardImportCandidate(bv, 2));assertNotNull(recreated.state);assertEquals(2, reads)
        } finally { scope.cancel() }
    }
    @Test fun multipartConfirmHandsOffToTheSharedChooserBeforeSavingSelectedParts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val memory = Memory()
        val chooser = BiliImportRequests(existingMode = { null })
        val finishSave = CompletableDeferred<Unit>()
        val imported = mutableListOf<List<BiliPart>>()
        try {
            val c = controller(scope, memory, ::preview, { _, selected ->
                imported += selected
                finishSave.await()
                "done"
            }, choose = { chooser.choose(listOf(BiliVideoDetails(video, it.parts))) })
            c.offer(ClipboardImportCandidate(bv))
            assertEquals(ClipboardImportStage.PREVIEW, c.state?.stage)

            c.confirm()
            val prompt = checkNotNull(chooser.prompt.value)
            assertEquals(ClipboardImportStage.CHOOSING_BILI_PARTS, c.state?.stage)
            assertTrue(c.state!!.busy)
            c.confirm()
            c.dismiss()
            c.offer(ClipboardImportCandidate(yt, 2))
            assertEquals(prompt, chooser.prompt.value)
            assertEquals(bv, c.state?.preview?.target)
            chooser.edit(prompt.id, 0) { it.copy(mode = BiliImportMode.SPLIT, selected = emptySet()) }
            chooser.confirm(prompt.id)
            assertTrue(imported.isEmpty())

            chooser.edit(prompt.id, 0) { it.copy(selected = setOf(parts[1].cid)) }
            chooser.confirm(prompt.id)
            assertEquals(ClipboardImportStage.SAVING, c.state?.stage)
            assertNull(chooser.prompt.value)
            assertEquals(listOf(listOf(parts[1])), imported)
            finishSave.complete(Unit)
            assertEquals(listOf(TextRef.Raw("done")), memory.messages)
            assertNull(c.state)
        } finally { scope.cancel() }
    }

    @Test fun cancellingAChoiceOrFindingTheChooserBusyRestoresTheSamePreview() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var busyMessages = 0
        val chooser = BiliImportRequests(existingMode = { null }, busy = { busyMessages++ })
        val details = listOf(BiliVideoDetails(video, parts))
        try {
            val c = controller(scope, Memory(), ::preview, choose = { chooser.choose(details) })
            c.offer(ClipboardImportCandidate(bv))
            val original = c.state
            c.confirm()
            chooser.cancel(checkNotNull(chooser.prompt.value).id)
            assertEquals(original, c.state)

            val other = scope.launch { chooser.choose(details) }
            val otherPrompt = chooser.prompt.value
            c.confirm()
            assertEquals(1, busyMessages)
            assertEquals(otherPrompt, chooser.prompt.value)
            assertEquals(original, c.state)
            other.cancelAndJoin()

            c.confirm()
            assertEquals(ClipboardImportStage.CHOOSING_BILI_PARTS, c.state?.stage)
            scope.coroutineContext.cancelChildren()
            assertNull(chooser.prompt.value)
            assertEquals(original, c.state)
            c.dismiss()
            assertNull(c.state)
        } finally { scope.cancel() }
    }
    @Test fun pendingImportCannotBeDismissedOrDoubleSubmittedOrReplaced() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined);val memory = Memory();val gate = CompletableDeferred<Unit>();var writes = 0
        try {
            val c = controller(scope, memory, { preview(it) }, { _, _ -> writes++;gate.await();"done" })
            c.offer(ClipboardImportCandidate(rj, 1));c.confirm();c.confirm();c.dismiss();c.offer(ClipboardImportCandidate(yt, 2))
            assertEquals(1, writes);assertEquals(ClipboardImportStage.SAVING, c.state!!.stage);assertEquals(rj, c.state!!.preview.target)
            gate.complete(Unit);assertNull(c.state)
            c.offer(ClipboardImportCandidate(yt, 2));assertEquals(yt, c.state!!.preview.target)
        } finally { scope.cancel() }
    }
    @Test fun connectionFailureShowsOnlyTheNetworkMessageAndCanRetryOnReentry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined);val memory = Memory();var reads = 0
        try {
            val c = controller(scope, memory, { if (reads++ == 0) error("Cookie=secret https://example.org/private") else preview(it) })
            val candidate = ClipboardImportCandidate(rj)
            c.offer(candidate)
            assertNull(c.state)
            assertNull(memory.last)
            assertEquals(listOf(TextRef.Res(R.string.clipboard_asmr_connection_failed)), memory.messages)
            c.offer(candidate)
            assertTrue(c.state!!.canImport)
        } finally { scope.cancel() }
    }
    @Test fun failedImportKeepsPreviewAndSelectedPartsForRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined);val memory = Memory();var writes = 0
        try {
            val c = controller(scope, memory, { preview(it) }, { _, selected ->
                assertEquals(parts, selected)
                if (writes++ == 0) error("private details") else "done"
            })
            c.offer(ClipboardImportCandidate(bv));c.confirm()
            assertEquals(parts, c.state?.preview?.parts)
            assertEquals(ClipboardImportStage.PREVIEW, c.state?.stage)
            assertEquals(TextRef.Res(R.string.clipboard_add_failed), c.state!!.error);c.confirm();assertEquals(2, writes);assertNull(c.state)
        } finally { scope.cancel() }
    }
    @Test fun lateMetadataCannotRestoreADismissedOrReplacedDialog() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined);val memory = Memory()
        try {
            lateinit var response: Continuation<ClipboardImportPreview>
            val c = controller(scope, memory, { if (it == bv) suspendCoroutine { response = it } else preview(it) })
            c.offer(ClipboardImportCandidate(bv, 1));assertNull(c.state)
            c.dismiss();response.resume(preview(bv));assertNull(c.state)
            c.offer(ClipboardImportCandidate(bv, 2));c.offer(ClipboardImportCandidate(yt, 3))
            response.resume(preview(bv));assertEquals(yt, c.state!!.preview.target)
        } finally { scope.cancel() }
    }

    @Test fun missingWorksFinishSilentlyWithoutOpeningADialog() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val memory = Memory()
        val candidate = ClipboardImportCandidate(rj)
        val checked = mutableListOf<String>()
        try {
            val c = controller(scope, memory, { target ->
                clipboardWorkPreview(target, { checked += "asmr:$it"; CatalogLookup.Missing },
                    { checked += "dlsite:$it"; null }, { error("Missing work has no purchase check") })
            })
            c.offer(candidate)
            assertEquals(listOf("asmr:${rj.id}", "dlsite:${rj.id}"), checked)
            assertEquals(candidate.fingerprint, memory.last)
            assertNull(c.state)
            assertTrue(memory.messages.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun confirmedLookupReportsAMissingWorkWithoutOpeningADialog() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val memory = Memory()
        val candidate = checkNotNull(clipboardBareWorkCandidate("123456"))
        try {
            val c = controller(scope, memory, { null })
            c.offer(candidate)
            assertTrue(memory.messages.isEmpty())

            c.resolveLookup(candidate, confirmed = true)
            assertEquals(listOf(TextRef.Res(R.string.clipboard_no_work)), memory.messages)
            assertEquals(candidate.fingerprint, memory.last)
            assertNull(c.state)
        } finally { scope.cancel() }
    }

    @Test fun returningToAHandledClipboardEntryInvalidatesAnOlderPendingLookup() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val memory = Memory()
        val handled = ClipboardImportCandidate(rj, 1)
        memory.last = handled.fingerprint
        try {
            lateinit var response: Continuation<ClipboardImportPreview>
            val c = controller(scope, memory, { suspendCoroutine { response = it } })
            c.offer(ClipboardImportCandidate(bv, 2))
            c.offer(handled)
            response.resume(preview(bv))
            assertNull(c.state)
            assertEquals(handled.fingerprint, memory.last)
        } finally { scope.cancel() }
    }

    @Test fun onlyConfirmedAsmrMissesUseDlsite() = runBlocking {
        val item = AsmrCatalogItem(123456, rj.id, "asmr title", "", emptyList(), emptyList(), "", null)
        val found = clipboardWorkPreview(rj, { CatalogLookup.Found(item) },
            { error("asmr.one already found the work") }, { error("No purchase check for asmr.one") })
        assertEquals("asmr title", found?.title)
        val failure = runCatching {
            clipboardWorkPreview(rj, { CatalogLookup.Unknown },
                { error("Do not treat a failed request as a miss") }, { error("No purchase check") })
        }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
    }

    @Test fun dlsiteWorksRequireAPurchaseBeforeTheControllerCanImport() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val work = ClipboardTarget(ClipboardSource.ASMR, "RJ01234567")
        val product = DlsiteShowcase(work.id, "DLsite title", "", emptyList(), emptyList(), "",
            null, "maniax", emptyList(), emptyList())
        try {
            for (purchased in listOf(false, true)) {
                val saved = mutableListOf<ClipboardTarget>()
                val c = controller(scope, Memory(), { target ->
                    clipboardWorkPreview(target, { CatalogLookup.Missing }, {
                        assertEquals(work.id, it)
                        product
                    }, {
                        assertEquals(work.id, it)
                        purchased
                    })
                }, { preview, _ -> saved += preview.target; "done" })
                c.offer(ClipboardImportCandidate(work))
                assertEquals(!purchased, c.state!!.preview.detailsOnly)
                assertEquals(purchased, c.state!!.canImport)
                c.confirm()
                assertEquals(if (purchased) listOf(work) else emptyList<ClipboardTarget>(), saved)
            }
        } finally { scope.cancel() }
    }
    @Test fun alreadySavedContentIsShownButCannotCreateAnotherImport() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined);val memory = Memory()
        try {
            val c = controller(scope, memory, { preview(it).copy(alreadySaved = true) })
            c.offer(ClipboardImportCandidate(yt));assertEquals("title", c.state!!.preview.title)
            assertFalse(c.state!!.canImport);c.confirm();assertNotNull(c.state);c.dismiss();assertNull(c.state)
        } finally { scope.cancel() }
    }

    @Test fun alreadySavedSinglePartCannotImportButMultipartCanChooseAnotherMode() {
        val saved = preview(bv).copy(alreadySaved = true)
        val single = ClipboardImportState(saved.copy(parts = parts.take(1)))
        assertTrue(single.preview.alreadyInLibrary)
        assertFalse(single.canImport)
        assertFalse(saved.alreadyInLibrary)
        assertTrue(ClipboardImportState(saved).canImport)
    }
}

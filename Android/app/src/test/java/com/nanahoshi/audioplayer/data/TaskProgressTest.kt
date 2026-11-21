package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class TaskProgressTest {
    private class Clock(var now: Long = 0) { fun read() = now }
    private val download = TaskKey(TaskKind.DOWNLOAD, 1)

    @Test fun coldRateFiveSecondWindowAndIdleDataAreSampled() {
        val clock = Clock()
        val tracker = TaskProgressTracker(clock::read)
        val report = tracker.begin(download, ProgressPart.DOWNLOAD)
        val transfer = report.phase(ProgressPhase.DOWNLOADING)
        transfer.bytes(1000, 1000, 20_000)
        clock.now = 999
        tracker.sample()
        assertNull(tracker.snapshots.value[download]!!.rate)
        clock.now = 1000
        tracker.sample()
        assertEquals(1000.0, tracker.snapshots.value[download]!!.rate!!, 0.01)
        for (second in 2..7) {
            clock.now = second * 1000L
            transfer.bytes(1000, second * 1000L, 20_000)
            tracker.sample()
        }
        assertEquals(1000.0, tracker.snapshots.value[download]!!.rate!!, 0.01)
        clock.now = 12_000
        tracker.sample()
        val stalled = tracker.snapshots.value.getValue(download)
        assertEquals(0.0, stalled.rate!!, 0.01)
        assertEquals(5000L, stalled.noDataMs)
        assertNull(stalled.remainingMs)
        report.phase(ProgressPhase.REMOTE_RUNNING)
        clock.now += 60_000
        tracker.sample()
        assertNull(tracker.current(download)!!.noDataMs)
        assertNull(tracker.current(download)!!.fraction())
    }

    @Test fun numericCallbacksDoNotPublishOrPersistBeforeTick() {
        val clock = Clock()
        var saves = 0
        val tracker = TaskProgressTracker(clock::read) { _, _, _, _ -> saves++ }
        val report = tracker.begin(download, ProgressPart.DOWNLOAD)
        val transfer = report.phase(ProgressPhase.DOWNLOADING)
        val before = tracker.snapshots.value
        val saved = saves
        repeat(100) { transfer.bytes(100, (it + 1) * 100L, null); report.cumulative((it + 1) * 100L) }
        assertSame(before, tracker.snapshots.value)
        assertEquals(saved, saves)
        clock.now = 1000
        tracker.sample()
        assertEquals(10_000L, tracker.snapshots.value.getValue(download).summary.transferredBytes)
        assertEquals(saved, saves)
    }

    @Test fun resumeOffsetIsExcludedAndRewrittenBytesCountWithNoNegativeRate() {
        val clock = Clock()
        val tracker = TaskProgressTracker(clock::read)
        val report = tracker.begin(download, ProgressPart.DOWNLOAD)
        var transfer = report.phase(ProgressPhase.DOWNLOADING)
        transfer.bytes(0, 5000, 10_000)
        transfer.bytes(1000, 6000, 10_000)
        clock.now = 1000; tracker.sample()
        assertEquals(1000.0, tracker.current(download)!!.rate!!, 0.01)
        report.retry()
        transfer = report.phase(ProgressPhase.DOWNLOADING, force = true)
        transfer.bytes(2000, 2000, 10_000) // 200 重写，逻辑位置回退，真实发送量继续累计。
        clock.now = 2000; tracker.sample()
        val live = tracker.current(download)!!
        assertEquals(3000L, live.summary.transferredBytes)
        assertEquals(2000L, live.summary.done)
        assertEquals(2000.0, live.rate!!, 0.01)
        assertEquals(4000L, live.remainingMs)
        assertEquals(1, live.summary.retries)
    }

    @Test fun entirelyCachedFinalAttemptKeepsEarlierTransferStats() {
        val clock = Clock()
        val tracker = TaskProgressTracker(clock::read)
        val report = tracker.begin(download, ProgressPart.DOWNLOAD)
        report.phase(ProgressPhase.DOWNLOADING).bytes(40, 40, null)
        clock.now = 500
        report.phase(ProgressPhase.RESOLVING)
        clock.now = 750
        report.phase(ProgressPhase.MUXING)
        report.reusedIfNoTransfer()
        report.finish(ProgressResult.COMPLETED)
        val summary = tracker.current(download)!!.summary
        assertEquals(ProgressResult.COMPLETED, summary.result)
        assertEquals(40L, summary.transferredBytes)
        assertEquals(500L, summary.transferMs)
    }

    @Test fun stageAndAttemptGuardsRejectLateCallbacks() {
        val clock = Clock()
        val tracker = TaskProgressTracker(clock::read)
        val old = tracker.begin(download, ProgressPart.DOWNLOAD)
        val transfer = old.phase(ProgressPhase.DOWNLOADING)
        old.phase(ProgressPhase.SAVING)
        transfer.bytes(999, 999, 999)
        assertNull(tracker.current(download)!!.summary.done)
        tracker.reset(download)
        val next = tracker.begin(download, ProgressPart.DOWNLOAD)
        old.phase(ProgressPhase.DOWNLOADING); old.finish(); transfer.bytes(999, 999, 999)
        next.phase(ProgressPhase.REQUESTING)
        assertEquals(ProgressPhase.REQUESTING, tracker.current(download)!!.summary.phase)
        assertTrue(tracker.current(download)!!.active)
        next.finish(ProgressResult.CANCELLED)
        assertFalse(tracker.hasActive())
        val ended = tracker.current(download)!!
        clock.now = 99_000; tracker.sample()
        assertEquals(ended.summary.elapsedMs, tracker.current(download)!!.summary.elapsedMs)
    }

    @Test fun independentTaskKeysPartsAndTerminalReconciliationStayIsolated() {
        val tracker = TaskProgressTracker(clock = { 1000L })
        val whisper = TaskKey(TaskKind.WHISPER, 1)
        val child = download.copy(child = ProgressPart.BILI_SUBTITLE)
        val audio = tracker.begin(download, ProgressPart.DOWNLOAD)
        val asr = tracker.begin(whisper, ProgressPart.TRANSCRIPTION)
        val translate = tracker.begin(whisper, ProgressPart.TRANSLATION, ProgressPhase.WAITING_TRANSLATION)
        val bili = tracker.begin(child, ProgressPart.BILI_SUBTITLE, ProgressPhase.FETCHING_SUBTITLE)
        asr.finish()
        assertTrue(translate.snapshot()!!.active)
        audio.finish()
        assertTrue(bili.snapshot()!!.active)
        tracker.reconcile(whisper, ProgressPart.TRANSLATION, ProgressResult.INTERRUPTED, true)
        bili.finish(ProgressResult.NEEDS_LOGIN)
        assertFalse(tracker.hasActive())
        assertEquals(ProgressResult.COMPLETED, tracker.currentPart(whisper, ProgressPart.TRANSCRIPTION)!!.summary.result)
        assertEquals(ProgressResult.INTERRUPTED, translate.snapshot()!!.summary.result)
    }

    @Test fun nativeUnknownDurationHasNoFractionOrEtaAndKnownAudioUsesInputCursor() {
        val clock = Clock()
        val tracker = TaskProgressTracker(clock::read)
        val asr = tracker.begin(TaskKey(TaskKind.WHISPER, 1), ProgressPart.TRANSCRIPTION)
        asr.phase(ProgressPhase.TRANSCRIBING)
        asr.units(2000, 0, ProgressUnit.AUDIO_MS, TextRef.Res(R.string.progress_detail_decode_voice))
        clock.now = 1000; tracker.sample()
        assertNull(asr.snapshot()!!.fraction())
        assertNull(asr.snapshot()!!.remainingMs)
        asr.units(8000, 10_000, ProgressUnit.AUDIO_MS, TextRef.Res(R.string.progress_detail_decode_voice))
        val snapshot = asr.snapshot()!!
        assertEquals(0.8f, snapshot.fraction()!!, 0.001f)
        assertEquals(250L, snapshot.remainingMs)
        assertEquals(ProgressPhase.TRANSCRIBING, snapshot.summary.phase)
    }

    @Test fun correctionKeepsBatchTimerAndPolishResetsUnits() {
        val clock = Clock()
        val tracker = TaskProgressTracker(clock::read)
        val report = tracker.begin(TaskKey(TaskKind.TRANSLATION, 1), ProgressPart.TRANSLATION)
        report.phase(ProgressPhase.TRANSLATING)
        report.units(20, 50); report.batch(2, 3)
        clock.now = 3000
        report.phase(ProgressPhase.CORRECTING); report.retry()
        clock.now = 5000
        assertEquals(5000L, report.snapshot()!!.batchElapsedMs)
        assertEquals(20L, report.snapshot()!!.summary.done)
        report.phase(ProgressPhase.POLISHING)
        report.units(0, 24); report.batch(1, 1)
        assertEquals(0L, report.snapshot()!!.summary.done)
        assertEquals(24L, report.snapshot()!!.summary.total)
        assertEquals(0L, report.snapshot()!!.batchElapsedMs)
        report.polishFallback(); report.finish()
        assertTrue(report.snapshot()!!.summary.polishFallback)
    }

    @Test fun uploadWriteToReplayResetsFilePositionButCountsRealWireBytes() {
        val clock = Clock()
        val tracker = TaskProgressTracker(clock::read)
        val report = tracker.begin(TaskKey(TaskKind.WHISPER, 2), ProgressPart.TRANSCRIPTION)
        val body = ProgressRequestBody(ByteArray(20_000).toRequestBody(), report, ProgressPhase.WAITING_TRANSCRIPT)
        repeat(2) { index ->
            val sink = Buffer(); body.writeTo(sink)
            assertEquals(20_000L, sink.size)
            val state = report.snapshot()!!
            assertEquals(ProgressPhase.WAITING_TRANSCRIPT, state.summary.phase)
            assertEquals((index + 1) * 20_000L, state.summary.transferredBytes)
            assertEquals(20_000L, state.summary.done)
            assertEquals(index, state.summary.retries)
            assertNull(state.fraction()); assertNull(state.rate); assertNull(state.noDataMs)
            clock.now += 1000
        }
    }

    @Test fun segmentedJsonMergesRoundTripsAndLegacyOrUnknownFieldsAreSafe() {
        val asr = TaskSegmentSummary(result = ProgressResult.REUSED, model = "Qwen3-ASR")
        val translated = TaskSegmentSummary(result = ProgressResult.FAILED, failurePhase = ProgressPhase.TRANSLATING)
        val summary = TaskProgressSummary().merge(ProgressPart.TRANSCRIPTION, asr).merge(ProgressPart.TRANSLATION, translated)
        assertEquals(summary, decodeProgressSummary(summary.encode()))
        assertEquals(asr, decodeProgressSummary(summary.encode()).parts[ProgressPart.TRANSCRIPTION])
        assertTrue(decodeProgressSummary(null).parts.isEmpty())
        assertTrue(decodeProgressSummary("{invalid").parts.isEmpty())
        assertEquals(summary, decodeProgressSummary(summary.encode().dropLast(1) + ",\"futureField\":true}"))
        assertNull(asr.averageRate)
        assertNull(TaskSegmentSummary(transferredBytes = 100, transferMs = 0).averageRate)
    }

    @Test fun reusedAndRestoredRemoteResultsDoNotInventLostStatistics() {
        val clock = Clock()
        val tracker = TaskProgressTracker(clock::read)
        val reused = tracker.begin(download, ProgressPart.DOWNLOAD)
        clock.now = 8000
        reused.finish(ProgressResult.REUSED)
        assertNull(reused.snapshot()!!.summary.elapsedMs)
        assertNull(reused.snapshot()!!.summary.averageRate)
        val restored = tracker.begin(TaskKey(TaskKind.WHISPER, 1), ProgressPart.TRANSCRIPTION)
        restored.resumedRemote(); restored.phase(ProgressPhase.REMOTE_RUNNING)
        clock.now = 9000; restored.finish()
        assertEquals(ProgressResult.RESUMED_REMOTE, restored.snapshot()!!.summary.result)
        assertEquals(1000L, restored.snapshot()!!.summary.elapsedMs)
        assertNull(restored.snapshot()!!.summary.transferredBytes)
    }

    @Test fun schedulerReasonMatchesEligibilityAndNotificationsDeduplicateNumbers() {
        val play = DownloadSlot(1, "RJ123456", false)
        val archive = DownloadSlot(2, "RJ123456", true)
        val other = DownloadSlot(3, null, false)
        assertEquals(DownloadStartDecision.WaitingWork, downloadStartDecision(archive, listOf(archive), listOf(play), 3))
        assertEquals(DownloadStartDecision.WaitingSlot, downloadStartDecision(other, listOf(other), listOf(play), 1))
        assertEquals(DownloadStartDecision.Ready, downloadStartDecision(other, listOf(other), listOf(play), 3))
        val clock = Clock()
        val tracker = TaskProgressTracker(clock::read)
        val first = tracker.begin(download, ProgressPart.DOWNLOAD)
        val secondKey = TaskKey(TaskKind.DOWNLOAD, 2)
        val second = tracker.begin(secondKey, ProgressPart.DOWNLOAD)
        first.phase(ProgressPhase.DOWNLOADING).bytes(1000, 1000, 10_000)
        second.phase(ProgressPhase.DOWNLOADING).bytes(2000, 2000, null)
        clock.now = 1000; tracker.sample()
        assertEquals(3000.0, totalDownloadRate(tracker.snapshots.value)!!, 0.01)
    }

    @Test
    fun transferDetailRetainsResourceAndArgumentsDuringByteUpdates() {
        val key = TaskKey(TaskKind.DOWNLOAD, 1L)
        val tracker = TaskProgressTracker()
        val report = tracker.begin(key, ProgressPart.DOWNLOAD, ProgressPhase.DOWNLOADING)
        val transfer = report.phase(ProgressPhase.DOWNLOADING)
        val detail = TextRef.Res(R.string.progress_detail_files, listOf(2, 5))
        transfer.units(3, 10, ProgressUnit.BYTES, detail)
        transfer.bytes(2, 5, 10)
        tracker.sample()
        assertEquals(detail, report.snapshot()?.detail)
        report.phase(ProgressPhase.SAVING)
        assertEquals(null, report.snapshot()?.detail)
    }
}

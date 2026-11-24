package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskProgressPartResetTest {
    @Test fun resettingTranslationPreservesTranscriptionAndInvalidatesOnlyTheOldTranslationAttempt() {
        val tracker = TaskProgressTracker(clock = { 0L })
        val key = TaskKey(TaskKind.WHISPER, 1)
        val transcription = tracker.begin(key, ProgressPart.TRANSCRIPTION, ProgressPhase.TRANSCRIBING)
        transcription.finish(ProgressResult.COMPLETED)
        val translation = tracker.begin(key, ProgressPart.TRANSLATION, ProgressPhase.TRANSLATING)
        val transcriptionSnapshot = transcription.snapshot()

        tracker.resetPart(key, ProgressPart.TRANSLATION)

        assertEquals(transcriptionSnapshot, tracker.snapshots.value[key])
        assertTrue(tracker.isCurrent(key, transcription.token))
        assertFalse(tracker.isCurrent(key, translation.token))
        assertNull(tracker.currentPart(key, ProgressPart.TRANSLATION))

        val retry = tracker.begin(key, ProgressPart.TRANSLATION, ProgressPhase.WAITING_TRANSLATION)
        translation.finish(ProgressResult.FAILED)
        assertTrue(retry.snapshot()!!.active)
        assertEquals(transcriptionSnapshot, transcription.snapshot())

        tracker.resetPart(key, ProgressPart.TRANSLATION)
        tracker.resetPart(key, ProgressPart.TRANSCRIPTION)
        assertFalse(tracker.snapshots.value.containsKey(key))
    }

    @Test fun persistedSummaryKeepsOnlyTranscriptionAndOmitsEmptySummaries() {
        val transcription = TaskSegmentSummary(phase = ProgressPhase.TRANSCRIBING, result = ProgressResult.COMPLETED)
        val translation = TaskSegmentSummary(phase = ProgressPhase.TRANSLATING, result = ProgressResult.FAILED)
        val summary = TaskProgressSummary().merge(ProgressPart.TRANSCRIPTION, transcription)
            .merge(ProgressPart.TRANSLATION, translation)
        val remaining = decodeProgressSummary(summary.encode()).without(ProgressPart.TRANSLATION)

        assertEquals(mapOf(ProgressPart.TRANSCRIPTION to transcription), decodeProgressSummary(remaining.encodeOrNull()).parts)
        assertEquals(translation, summary.parts[ProgressPart.TRANSLATION])
        assertNull(remaining.without(ProgressPart.TRANSCRIPTION).encodeOrNull())
    }
}

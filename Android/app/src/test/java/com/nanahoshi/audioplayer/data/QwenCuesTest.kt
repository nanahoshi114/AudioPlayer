package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.text.TextRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenCuesTest {
    @Test
    fun recognizedTextWithoutSentencesIsTimingFailureInsteadOfZeroCues() {
        val failure = runCatching {
            cuesFromQwenPayload("""{"transcripts":[{"text":"spoken","sentences":[]}]}""")
        }.exceptionOrNull()
        assertTrue(failure is WhisperException)
        assertEquals(
            TextRef.Res(R.string.whisper_error_no_timing),
            (failure as WhisperException).userText,
        )
    }

    @Test
    fun nestedAndRootSentenceArraysAreParsedAndSorted() {
        val root = cuesFromQwenPayload(
            """{"transcripts":[{"sentences":[{"begin_time":2000,"end_time":3000,"text":"後"}]}]}""",
        )
        assertEquals(listOf("後"), root.map { it.text })
        assertEquals(listOf(2000L), root.map { it.startMs })
        val output = cuesFromQwenPayload(
            """{"output":{"sentences":[{"begin_time":1000,"end_time":1500,"text":"前"}]}}""",
        )
        assertEquals(listOf("前"), output.map { it.text })
        assertEquals(listOf(1000L), output.map { it.startMs })
    }

    @Test
    fun plainRecognizedTextInOutputWithoutTimingAlsoFails() {
        val failure = runCatching {
            cuesFromQwenPayload("""{"output":{"text":"spoken","sentences":[]}}""")
        }.exceptionOrNull()
        assertEquals(
            TextRef.Res(R.string.whisper_error_no_timing),
            (failure as WhisperException).userText,
        )
    }
}
package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperModelTest {
    @Test
    fun oldAddressPicksWhisperOrJobs() {
        assertEquals(WHISPER_MODEL_1, inferredWhisperModel("https://api.openai.com/v1"))
        assertEquals(WHISPER_MODEL_LOCAL, inferredWhisperModel("http://192.168.1.20:8000/v1"))
        assertEquals(WHISPER_MODEL_JOBS, inferredWhisperModel("http://192.168.1.20:8765"))
        assertEquals(WHISPER_MODEL_JOBS, inferredWhisperModel(null))
    }

    @Test
    fun oldDoneRowMatchesTheInferredModel() {
        assertTrue(whisperModelsMatch(null, WHISPER_MODEL_1, "https://api.openai.com/v1"))
        assertTrue(whisperModelsMatch(null, WHISPER_MODEL_LOCAL, "http://192.168.1.20:8000/v1"))
        assertFalse(whisperModelsMatch(null, WHISPER_MODEL_1, "http://192.168.1.20:8000/v1"))
        assertFalse(whisperModelsMatch(null, WHISPER_MODEL_QWEN, "https://api.openai.com/v1"))
        assertTrue(whisperModelsMatch(null, WHISPER_MODEL_JOBS, "http://192.168.1.20:8765"))
        assertFalse(whisperModelsMatch(WHISPER_MODEL_1, WHISPER_MODEL_QWEN, "https://api.openai.com/v1"))
    }

    @Test
    fun openAiHostOver25MbAsksBeforeUpload() {
        val big = ONLINE_WHISPER_BYTES + 1
        assertTrue(needsOnlineSizeConfirm(WHISPER_MODEL_1, "https://API.OpenAI.com/v1", big))
        assertTrue(needsOnlineSizeConfirm(null, "https://api.openai.com/v1", big))
        assertFalse(needsOnlineSizeConfirm(WHISPER_MODEL_1, "https://api.openai.com/v1", ONLINE_WHISPER_BYTES))
        assertFalse(needsOnlineSizeConfirm(WHISPER_MODEL_1, "http://example.com/api.openai.com/v1", big))
        assertFalse(needsOnlineSizeConfirm(WHISPER_MODEL_1, "http://192.168.1.20:8000/v1", big))
        assertFalse(needsOnlineSizeConfirm(WHISPER_MODEL_QWEN, "https://api.openai.com/v1", big))
        assertFalse(needsOnlineSizeConfirm(WHISPER_MODEL_LOCAL, "https://api.openai.com/v1", big))
        assertFalse(needsOnlineSizeConfirm(WHISPER_MODEL_JOBS, "https://api.openai.com/v1", big))
    }

    @Test
    fun emptyAddressUsesTheOfficialOnlineBase() {
        assertEquals(OPENAI_WHISPER_BASE, effectiveWhisperBase(WHISPER_MODEL_1, null))
        assertEquals(OPENAI_WHISPER_BASE, effectiveWhisperBase(WHISPER_MODEL_1, "  "))
        assertEquals(OPENAI_WHISPER_BASE, effectiveWhisperBase(WHISPER_MODEL_1, "http://192.168.1.20:8000/"))
        assertEquals(QWEN_WHISPER_BASE, effectiveWhisperBase(WHISPER_MODEL_QWEN, null))
        assertEquals(QWEN_WHISPER_BASE, effectiveWhisperBase(WHISPER_MODEL_QWEN, "http://192.168.1.20:9000"))
        assertEquals("http://192.168.1.20:8000", effectiveWhisperBase(WHISPER_MODEL_LOCAL, "http://192.168.1.20:8000/"))
        assertNull(effectiveWhisperBase(WHISPER_MODEL_LOCAL, null))
        assertNull(effectiveWhisperBase(WHISPER_MODEL_JOBS, null))
        assertNull(effectiveWhisperBase(WHISPER_MODEL_QWEN3, null))
        assertNull(effectiveWhisperBase(null, null))
        assertEquals("whisper（本地部署）", whisperModelLabel(WHISPER_MODEL_LOCAL))
        assertEquals("Qwen3-ASR", whisperModelLabel(WHISPER_MODEL_QWEN3))
        assertEquals(WHISPER_MODEL_QWEN3, knownWhisperModel(WHISPER_MODEL_QWEN3))
    }

    @Test
    fun qwen3UsesTheLoadedModelNameAndTimedText() {
        assertEquals("Qwen/Qwen3-ASR-0.6B", qwen3AsrModelId(listOf("other", "Qwen/Qwen3-ASR-0.6B")))
        assertEquals("local-model", qwen3AsrModelId(listOf("local-model")))
        assertNull(qwen3AsrModelId(emptyList()))
        assertEquals("こんにちは", qwen3TranscriptText("language Japanese<asr_text>こんにちは"))
        assertEquals(listOf(WhisperCue(0L, 12_000L, "こんにちは。")), cueFromWholeTranscript("こんにちは。", 12_000L))
        assertEquals(
            listOf(
                WhisperCue(0L, 800L, "こんにちは。"),
                WhisperCue(800L, 1500L, "おはよう。"),
            ),
            groupTimedPieces(
                listOf(
                    WhisperCue(0L, 400L, "こんにちは"),
                    WhisperCue(400L, 800L, "。"),
                    WhisperCue(800L, 1500L, "おはよう。"),
                ),
            ),
        )
    }

    @Test(expected = WhisperException::class)
    fun qwen3PlainTextWithoutDurationFails() {
        cueFromWholeTranscript("こんにちは", 0L)
    }

    @Test
    fun whisperBaseGainsV1OnlyForTheRequest() {
        assertEquals("https://api.openai.com/v1", whisperTranscriptionBase("https://api.openai.com"))
        assertEquals("https://api.openai.com/v1", whisperTranscriptionBase("https://api.openai.com/v1/"))
        assertEquals("http://192.168.1.20:8000/v1", whisperTranscriptionBase("http://192.168.1.20:8000/v1"))
    }

    @Test
    fun oldEndpointWithV1MatchesTheSameService() {
        assertEquals(
            "https://api.openai.com",
            canonicalWhisperEndpoint(" https://api.openai.com/v1/ ", WHISPER_MODEL_1),
        )
        assertTrue(sameWhisperEndpoint("https://api.openai.com/v1", "https://api.openai.com", WHISPER_MODEL_1))
        assertTrue(sameWhisperEndpoint("http://192.168.1.20:8000/v1", "http://192.168.1.20:8000", WHISPER_MODEL_LOCAL))
        assertTrue(sameWhisperEndpoint("http://192.168.1.20:8000/v1/", "http://192.168.1.20:8000", WHISPER_MODEL_QWEN3))
        assertFalse(sameWhisperEndpoint("https://api.openai.com/v1", "https://api.openai.com.evil/v1", WHISPER_MODEL_1))
        assertFalse(sameWhisperEndpoint(null, "https://api.openai.com", WHISPER_MODEL_1))
    }

    @Test
    fun jobsEndpointKeepsTheV1RouteBoundary() {
        assertFalse(sameWhisperEndpoint("http://192.168.1.20:8765/v1", "http://192.168.1.20:8765", WHISPER_MODEL_JOBS))
        assertTrue(sameWhisperEndpoint("http://192.168.1.20:8765/", "http://192.168.1.20:8765", WHISPER_MODEL_JOBS))
    }

    @Test
    fun qwenHttpOriginDropsTheSocketPath() {
        assertEquals("https://dashscope.aliyuncs.com", qwenHttpOrigin("wss://dashscope.aliyuncs.com/api/v1"))
        assertNull(qwenHttpOrigin("   "))
    }
}

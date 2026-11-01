package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperModelTest {
    @Test
    fun emptyAddressUsesTheOfficialOnlineBase() {
        assertEquals(QWEN_WHISPER_BASE, effectiveWhisperBase(WHISPER_MODEL_QWEN, null))
        assertEquals(QWEN_WHISPER_BASE, effectiveWhisperBase(WHISPER_MODEL_QWEN, "http://192.168.1.20:9000"))
        assertEquals("http://192.168.1.20:8000", effectiveWhisperBase(WHISPER_MODEL_QWEN3, "http://192.168.1.20:8000/"))
        assertNull(effectiveWhisperBase(WHISPER_MODEL_QWEN3, null))
        assertNull(effectiveWhisperBase(SENSE_VOICE_MODEL, null))
        assertNull(effectiveWhisperBase(SENSE_VOICE_MODEL, "http://192.168.1.20:8000"))
        assertNull(effectiveWhisperBase(null, null))
        assertEquals("SenseVoiceSmall", whisperModelLabel(SENSE_VOICE_MODEL))
        assertEquals("Qwen3-ASR", whisperModelLabel(WHISPER_MODEL_QWEN3))
        assertEquals(WHISPER_MODEL_QWEN3, knownWhisperModel(WHISPER_MODEL_QWEN3))
        assertEquals(SENSE_VOICE_MODEL, knownWhisperModel(SENSE_VOICE_MODEL))
        assertNull(knownWhisperModel("whisper-1"))
        assertNull(knownWhisperModel("jobs"))
        assertTrue(isOnDeviceWhisperModel(SENSE_VOICE_MODEL))
        assertFalse(isOnDeviceWhisperModel(WHISPER_MODEL_QWEN))
        assertTrue(isCloudWhisperModel(WHISPER_MODEL_QWEN))
        assertFalse(isCloudWhisperModel(WHISPER_MODEL_QWEN3))
        assertFalse(isCloudWhisperModel(SENSE_VOICE_MODEL))
    }

    @Test
    fun oldDoneRowsOnlyMatchWhenTheStoredModelIsKnown() {
        assertTrue(whisperModelsMatch(WHISPER_MODEL_QWEN3, WHISPER_MODEL_QWEN3))
        assertTrue(whisperModelsMatch(SENSE_VOICE_MODEL, SENSE_VOICE_MODEL))
        assertFalse(whisperModelsMatch(null, WHISPER_MODEL_QWEN))
        assertFalse(whisperModelsMatch("whisper-1", WHISPER_MODEL_QWEN))
        assertFalse(whisperModelsMatch(WHISPER_MODEL_QWEN, WHISPER_MODEL_QWEN3))
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
        assertEquals("https://example.com/v1", whisperTranscriptionBase("https://example.com"))
        assertEquals("https://example.com/v1", whisperTranscriptionBase("https://example.com/v1/"))
        assertEquals("http://192.168.1.20:8000/v1", whisperTranscriptionBase("http://192.168.1.20:8000/v1"))
    }

    @Test
    fun oldEndpointWithV1MatchesTheSameService() {
        assertEquals(
            "https://example.com",
            canonicalWhisperEndpoint(" https://example.com/v1/ ", WHISPER_MODEL_QWEN3),
        )
        assertTrue(sameWhisperEndpoint("http://192.168.1.20:8000/v1", "http://192.168.1.20:8000", WHISPER_MODEL_QWEN3))
        assertTrue(sameWhisperEndpoint("http://192.168.1.20:8000/v1/", "http://192.168.1.20:8000", WHISPER_MODEL_QWEN3))
        assertFalse(sameWhisperEndpoint("https://example.com/v1", "https://example.com.evil/v1", WHISPER_MODEL_QWEN3))
        assertFalse(sameWhisperEndpoint(null, "https://example.com", WHISPER_MODEL_QWEN3))
        assertFalse(sameWhisperEndpoint("http://192.168.1.20:8000/v1", "http://192.168.1.20:8000", WHISPER_MODEL_QWEN))
        // 本机模型没有地址，双方都是 null 视为同一个服务，去重仍然成立。
        assertTrue(sameWhisperEndpoint(null, null, SENSE_VOICE_MODEL))
    }

    @Test
    fun qwenHttpOriginDropsTheSocketPath() {
        assertEquals("https://dashscope.aliyuncs.com", qwenHttpOrigin("wss://dashscope.aliyuncs.com/api/v1"))
        assertNull(qwenHttpOrigin("   "))
    }
}

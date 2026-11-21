package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.playback.SubtitleCue
import com.nanahoshi.audioplayer.playback.SubtitleOrigin
import com.nanahoshi.audioplayer.playback.encodeGeneratedCues
import com.nanahoshi.audioplayer.playback.parseGeneratedSubtitles
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
        assertEquals(R.string.settings_model_sensevoice, whisperModelLabelRes(SENSE_VOICE_MODEL))
        assertEquals(R.string.settings_model_qwen3, whisperModelLabelRes(WHISPER_MODEL_QWEN3))
        assertEquals(WHISPER_MODEL_QWEN3, knownWhisperModel(WHISPER_MODEL_QWEN3))
        assertEquals(SENSE_VOICE_MODEL, knownWhisperModel(SENSE_VOICE_MODEL))
        assertNull(knownWhisperModel("whisper-1"))
        assertNull(knownWhisperModel("jobs"))
        assertTrue(isOnDeviceWhisperModel(SENSE_VOICE_MODEL))
        assertTrue(isOnDeviceWhisperModel(PARAKEET_MODEL))
        assertEquals(PARAKEET_MODEL, knownWhisperModel(PARAKEET_MODEL))
        assertNull(effectiveWhisperBase(PARAKEET_MODEL, "http://192.168.1.20:8000"))
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
    fun onlyActiveJobsForTheSelectedModelBlockDeletion() {
        fun hasActiveSenseVoiceJob(jobs: List<WhisperJobEntity>) =
            hasActiveOfflineWhisperJob(jobs, OfflineWhisperModel.SenseVoice)
        fun job(status: DownloadStatus, model: String?) = WhisperJobEntity(
            title = "t", fingerprint = "f", trackId = null, localPath = null, remoteJobId = null,
            status = status, progress = 0f, error = null, awaitRemoteUrl = null, model = model)
        assertFalse(hasActiveSenseVoiceJob(emptyList()))
        assertTrue(hasActiveSenseVoiceJob(listOf(job(DownloadStatus.QUEUED, SENSE_VOICE_MODEL))))
        assertTrue(hasActiveSenseVoiceJob(listOf(job(DownloadStatus.RUNNING, SENSE_VOICE_MODEL))))
        assertFalse(hasActiveSenseVoiceJob(listOf(job(DownloadStatus.DONE, SENSE_VOICE_MODEL))))
        assertFalse(hasActiveSenseVoiceJob(listOf(job(DownloadStatus.FAILED, SENSE_VOICE_MODEL))))
        assertFalse(hasActiveSenseVoiceJob(listOf(job(DownloadStatus.QUEUED, null))))
        assertFalse(hasActiveSenseVoiceJob(listOf(job(DownloadStatus.RUNNING, null))))
        assertFalse(hasActiveSenseVoiceJob(listOf(job(DownloadStatus.RUNNING, WHISPER_MODEL_QWEN))))
        assertFalse(hasActiveSenseVoiceJob(listOf(job(DownloadStatus.RUNNING, WHISPER_MODEL_QWEN3))))
        assertFalse(hasActiveSenseVoiceJob(listOf(job(DownloadStatus.RUNNING, "whisper-1"))))
        assertFalse(hasActiveSenseVoiceJob(listOf(job(DownloadStatus.RUNNING, PARAKEET_MODEL))))
        assertTrue(hasActiveOfflineWhisperJob(listOf(job(DownloadStatus.QUEUED, PARAKEET_MODEL)), OfflineWhisperModel.Parakeet))
        assertTrue(hasActiveSenseVoiceJob(listOf(
            job(DownloadStatus.DONE, SENSE_VOICE_MODEL), job(DownloadStatus.QUEUED, SENSE_VOICE_MODEL))))
    }

    @Test
    fun generatedSubtitleOriginTakesPrecedenceOverHistoricalJobs() {
        val history = WhisperJobEntity(
            title = "t", fingerprint = "f", trackId = null, localPath = null, remoteJobId = null,
            status = DownloadStatus.DONE, progress = 1f, error = null, awaitRemoteUrl = null,
            model = SENSE_VOICE_MODEL,
        )
        val cues = listOf(SubtitleCue(0, 800, "こんにちは"))
        val parakeet = checkNotNull(parseGeneratedSubtitles(
            encodeGeneratedCues(cues, SubtitleOrigin(PARAKEET_MODEL, null)),
        ))
        assertFalse(generatedSubtitlesMatch(parakeet, SENSE_VOICE_MODEL, null, history))
        assertTrue(generatedSubtitlesMatch(parakeet, PARAKEET_MODEL, null, history))

        val translated = cues.map { it.copy(translation = "你好") }
        val updated = checkNotNull(parseGeneratedSubtitles(encodeGeneratedCues(translated, parakeet.origin)))
        assertEquals(translated, updated.cues)
        assertEquals(parakeet.origin, updated.origin)

        val legacy = checkNotNull(parseGeneratedSubtitles(encodeGeneratedCues(cues)))
        assertTrue(generatedSubtitlesMatch(legacy, SENSE_VOICE_MODEL, null, history))
        assertFalse(generatedSubtitlesMatch(legacy, PARAKEET_MODEL, null, history))
        assertFalse(generatedSubtitlesMatch(legacy, SENSE_VOICE_MODEL, null, history.copy(model = null)))

        val remote = checkNotNull(parseGeneratedSubtitles(
            encodeGeneratedCues(cues, SubtitleOrigin(WHISPER_MODEL_QWEN3, "https://example.com")),
        ))
        assertTrue(generatedSubtitlesMatch(remote, WHISPER_MODEL_QWEN3, "https://example.com/v1", history))
        assertFalse(generatedSubtitlesMatch(remote, WHISPER_MODEL_QWEN3, "https://other.example.com", history))
    }

    @Test
    fun qwen3UsesTheLoadedModelNameAndTimedText() {
        assertEquals("Qwen/Qwen3-ASR-0.6B", resolveQwen3Model(listOf("other", "Qwen/Qwen3-ASR-0.6B")))
        assertEquals("local-model", resolveQwen3Model(listOf("local-model")))
        assertEquals(QWEN3_ASR_MODEL, resolveQwen3Model(emptyList()))
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
    fun qwen3MultipleModelsWithoutMatchFailBeforeUpload() {
        resolveQwen3Model(listOf("whisper-1", "sense-voice"))
    }

    @Test
    fun qwen3ModelMatchIgnoresCase() {
        assertEquals("QWEN3-ASR-Flash", resolveQwen3Model(listOf("other", "QWEN3-ASR-Flash")))
    }

    @Test
    fun qwen3TrimsBlankIdsAndKeepsSingleCustomName() {
        assertEquals("local-model", resolveQwen3Model(listOf("   ", " local-model ")))
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

package com.nanahoshi.audioplayer.data

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** 超长语音段兜底再切：VAD 已经限制在 [MAX_SEGMENT_SECONDS] 内，这里保证即使 VAD 行为变化也不会超。 */
internal fun splitLongSegment(
    startSample: Long,
    samples: FloatArray,
    maxSamples: Int,
): List<Pair<Long, FloatArray>> {
    if (samples.size <= maxSamples) return listOf(startSample to samples)
    val pieces = mutableListOf<Pair<Long, FloatArray>>()
    var offset = 0
    while (offset < samples.size) {
        val end = minOf(offset + maxSamples, samples.size)
        pieces += (startSample + offset) to samples.copyOfRange(offset, end)
        offset = end
    }
    return pieces
}

/** 把一个分段里的 token 时间戳（秒，段内相对）换成整首音轨上的 cue，并按标点合并成句。 */
internal fun senseVoicePiecesToCues(
    tokens: Array<String>,
    timestamps: FloatArray,
    segmentStartMs: Long,
): List<WhisperCue> {
    val pieces = buildList {
        for (index in tokens.indices) {
            if (index >= timestamps.size) break
            val text = tokens[index]
            if (text.isBlank()) continue
            val startMs = segmentStartMs + (timestamps[index] * 1000).toLong()
            add(WhisperCue(startMs, startMs + 1, text))
        }
    }
    return groupTimedPieces(pieces)
}

/** SenseVoiceSmall 本机转写：PCM 解码 → Silero VAD 按静音分段 → 逐段识别 → 拼回整首时间轴。 */
class SenseVoiceTranscriber(
    context: Context,
    private val models: SenseVoiceModelManager,
) {
    private val appContext = context.applicationContext

    suspend fun transcribe(
        file: File,
        totalDurationMs: Long,
        onProgress: suspend (Float) -> Unit,
    ): List<WhisperCue> = withContext(Dispatchers.Default) {
        if (!models.isReady()) throw WhisperException("模型还没有下载")
        val recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = models.modelFile().absolutePath,
                        language = "ja",
                        useInverseTextNormalization = true,
                    ),
                    tokens = models.tokensFile().absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                ),
            ),
        )
        val vad = Vad(
            assetManager = appContext.assets,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = VAD_ASSET,
                    threshold = 0.5f,
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = MAX_SEGMENT_SECONDS,
                ),
                sampleRate = SAMPLE_RATE,
            ),
        )
        val reader = AudioPcmReader(file)
        val cues = mutableListOf<WhisperCue>()
        val totalSamples = (totalDurationMs * SAMPLE_RATE / 1000).coerceAtLeast(1L)
        try {
            while (true) {
                // 段间检查取消：绝不打断正在进行的 JNI 解码。
                coroutineContext.ensureActive()
                val chunk = reader.readChunk() ?: break
                vad.acceptWaveform(chunk)
                drainSegments(recognizer, vad, cues, totalSamples, onProgress)
            }
            vad.flush()
            drainSegments(recognizer, vad, cues, totalSamples, onProgress)
        } finally {
            reader.close()
            vad.release()
            recognizer.release()
        }
        cues
    }

    private suspend fun drainSegments(
        recognizer: OfflineRecognizer,
        vad: Vad,
        cues: MutableList<WhisperCue>,
        totalSamples: Long,
        onProgress: suspend (Float) -> Unit,
    ) {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            val pieces = splitLongSegment(segment.start, segment.samples, MAX_SEGMENT_SAMPLES)
            for ((startSample, samples) in pieces) {
                coroutineContext.ensureActive()
                cues += recognizeSegment(recognizer, startSample, samples)
                onProgress(((startSample + samples.size).toFloat() / totalSamples).coerceIn(0f, 1f))
            }
        }
    }

    private fun recognizeSegment(
        recognizer: OfflineRecognizer,
        startSample: Long,
        samples: FloatArray,
    ): List<WhisperCue> {
        val segmentStartMs = startSample * 1000L / SAMPLE_RATE
        val segmentEndMs = segmentStartMs + samples.size * 1000L / SAMPLE_RATE
        val stream: OfflineStream = recognizer.createStream()
        val result = try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream)
        } finally {
            stream.release()
        }
        val text = qwen3TranscriptText(result.text)
        if (text.isBlank()) return emptyList()
        if (result.timestamps.size < result.tokens.size) {
            return listOf(WhisperCue(segmentStartMs, maxOf(segmentEndMs, segmentStartMs + 1), text))
        }
        return senseVoicePiecesToCues(result.tokens, result.timestamps, segmentStartMs)
            .ifEmpty { listOf(WhisperCue(segmentStartMs, maxOf(segmentEndMs, segmentStartMs + 1), text)) }
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_SEGMENT_SECONDS = 28f
        const val MAX_SEGMENT_SAMPLES = 28 * SAMPLE_RATE
        const val VAD_ASSET = "silero_vad.onnx"
    }
}

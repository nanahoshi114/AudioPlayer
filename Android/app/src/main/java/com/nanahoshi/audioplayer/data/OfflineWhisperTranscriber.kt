package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.R
import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
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
import kotlin.math.sqrt

/** 将 VAD 返回的语音段切到识别长度上限内，保留每段在原音轨上的起点。 */
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

private const val PARAKEET_PAUSE_MS = 480L
private const val PARAKEET_TARGET_CUE_MS = 6_000L
private const val PARAKEET_TARGET_CUE_CHARS = 40
private const val PARAKEET_LOOKBACK_MS = 1_500L
private const val PARAKEET_SECONDARY_PAUSE_MS = 240L
private const val PARAKEET_MIN_CUE_MS = 1_000L
private const val PARAKEET_MIN_CUE_CHARS = 6
private val PARAKEET_SENTENCE_END = setOf('。', '！', '？', '!', '?')
private val PARAKEET_SOFT_BREAK = setOf('、', '，', ',', '；', ';')
private val PARAKEET_PUNCTUATION = Regex("""[\p{P}\s]+""")

internal fun parakeetSegmentToCues(
    text: String,
    tokens: Array<String>,
    timestamps: FloatArray,
    segmentStartMs: Long,
    segmentEndMs: Long,
): List<WhisperCue> {
    if (text.isBlank()) return emptyList()
    val endMs = maxOf(segmentEndMs, segmentStartMs + 1)
    val pieces = parakeetTimedPieces(text, tokens, timestamps, segmentStartMs, endMs)
        ?: return listOf(WhisperCue(segmentStartMs, endMs, text))
    return groupParakeetPieces(pieces)
}

internal fun groupParakeetPieces(pieces: List<WhisperCue>): List<WhisperCue> = buildList {
    val words = pieces.indices.filter { pieces[it].text.isNotBlank() }
    var first = 0
    while (first < words.size) {
        val startMs = pieces[words[first]].startMs
        var previous = first
        var characterCount = 0
        var hasText = false
        var sentenceEnded = false
        var endsWithSoftBreak = false
        var endsWithOpeningQuote = false
        var softBreak: Int? = null
        var shortPause: Int? = null
        var next = first
        while (next < words.size) {
            val piece = pieces[words[next]]
            val text = piece.text.trim()
            val punctuationOnly = PARAKEET_PUNCTUATION.matches(text)
            val trailingPunctuation = punctuationOnly && text.first().category !in openingPunctuation
            val characters = text.count { !it.isWhitespace() && !it.isLowSurrogate() }
            val lastPiece = pieces[words[previous]]
            val gap = piece.startMs - lastPiece.endMs
            if (hasText && !trailingPunctuation && !endsWithOpeningQuote && gap >= 0) {
                if (sentenceEnded || gap >= PARAKEET_PAUSE_MS) break
                if (lastPiece.endMs - startMs >= PARAKEET_MIN_CUE_MS &&
                    characterCount >= PARAKEET_MIN_CUE_CHARS
                ) {
                    if (endsWithSoftBreak) softBreak = next
                    if (gap >= PARAKEET_SECONDARY_PAUSE_MS) shortPause = next
                }
                if (piece.endMs - startMs > PARAKEET_TARGET_CUE_MS ||
                    characterCount + characters > PARAKEET_TARGET_CUE_CHARS
                ) {
                    val earliest = lastPiece.endMs - PARAKEET_LOOKBACK_MS
                    val cut = softBreak?.takeIf { pieces[words[it - 1]].endMs >= earliest }
                        ?: shortPause?.takeIf { pieces[words[it - 1]].endMs >= earliest }
                    if (cut != null) {
                        next = cut
                        break
                    }
                }
            }
            characterCount += characters
            hasText = hasText || !punctuationOnly
            val last = text.trimEnd { it.category in closingPunctuation }.lastOrNull()
            sentenceEnded = last in PARAKEET_SENTENCE_END || (trailingPunctuation && sentenceEnded)
            endsWithSoftBreak = last in PARAKEET_SOFT_BREAK || (last == null && endsWithSoftBreak)
            endsWithOpeningQuote = text.last().category in openingPunctuation
            previous = next
            next++
        }
        val last = words[next - 1]
        val line = pieces.subList(words[first], last + 1).joinToString("") { it.text }.trim()
        add(WhisperCue(startMs, pieces[last].endMs, line))
        first = next
    }
}

private val openingPunctuation = setOf(CharCategory.START_PUNCTUATION, CharCategory.INITIAL_QUOTE_PUNCTUATION)
private val closingPunctuation = setOf(CharCategory.END_PUNCTUATION, CharCategory.FINAL_QUOTE_PUNCTUATION)

internal fun selectStereoSpeech(
    left: FloatArray,
    right: FloatArray,
    startIndex: Int,
    endIndex: Int,
    analysisStart: Int = startIndex,
    analysisEnd: Int = endIndex,
): FloatArray {
    var leftEnergy = 0.0
    var rightEnergy = 0.0
    var dot = 0.0
    for (index in analysisStart until analysisEnd) {
        val l = left[index].toDouble()
        val r = right[index].toDouble()
        leftEnergy += l * l
        rightEnergy += r * r
        dot += l * r
    }
    val denominator = sqrt(leftEnergy * rightEnergy)
    if (denominator > 0 && dot / denominator >= 0.65) {
        return FloatArray(endIndex - startIndex) { (left[startIndex + it] + right[startIndex + it]) * 0.5f }
    }
    return (if (leftEnergy >= rightEnergy) left else right).copyOfRange(startIndex, endIndex)
}

internal data class OfflineTranscriptionProgress(val processedMs: Long, val totalMs: Long?, val detail: TextRef)

/** 本机转写：PCM 解码 → Silero VAD 按静音分段 → 逐段识别 → 拼回整首时间轴。 */
internal class OfflineWhisperTranscriber(context: Context) {
    private val appContext = context.applicationContext

    suspend fun transcribe(
        manager: OfflineWhisperModelManager,
        file: File,
        totalDurationMs: Long,
        onProgress: suspend (OfflineTranscriptionProgress) -> Unit,
    ): List<WhisperCue> = withContext(Dispatchers.Default) {
        manager.useModel { modelFile, tokensFile ->
            val modelConfig = OfflineModelConfig(tokens = tokensFile.absolutePath, numThreads = 2, provider = "cpu")
            when (manager.model) {
                OfflineWhisperModel.SenseVoice -> modelConfig.senseVoice = OfflineSenseVoiceModelConfig(
                    model = modelFile.absolutePath,
                    language = "ja",
                    useInverseTextNormalization = true,
                )
                OfflineWhisperModel.Parakeet -> modelConfig.nemo = OfflineNemoEncDecCtcModelConfig(
                    model = modelFile.absolutePath,
                )
            }
            val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
                featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = modelConfig,
            ))
            try {
                transcribeFile(recognizer, manager.model, file, totalDurationMs, onProgress)
            } finally {
                recognizer.release()
            }
        }
    }

    private suspend fun transcribeFile(
        recognizer: OfflineRecognizer,
        model: OfflineWhisperModel,
        file: File,
        totalDurationMs: Long,
        onProgress: suspend (OfflineTranscriptionProgress) -> Unit,
    ): List<WhisperCue> {
        val totalSamples = (totalDurationMs * SAMPLE_RATE / 1000).coerceAtLeast(0L)
        onProgress(OfflineTranscriptionProgress(0, totalDurationMs.takeIf { it > 0 }, TextRef.Res(R.string.progress_detail_decode)))
        val reader = AudioPcmReader(file, keepStereo = model == OfflineWhisperModel.Parakeet)
        try {
            return when (model) {
                OfflineWhisperModel.Parakeet -> transcribeParakeet(reader, recognizer, totalSamples, onProgress)
                OfflineWhisperModel.SenseVoice -> transcribeSenseVoice(reader, recognizer, totalSamples, onProgress)
            }
        } finally {
            reader.close()
        }
    }

    private suspend fun transcribeSenseVoice(
        reader: AudioPcmReader,
        recognizer: OfflineRecognizer,
        totalSamples: Long,
        onProgress: suspend (OfflineTranscriptionProgress) -> Unit,
    ): List<WhisperCue> {
        val vad = createVad()
        val cues = mutableListOf<WhisperCue>()
        var processed = 0L
        try {
            suspend fun drain() {
                while (!vad.empty()) {
                    val segment = vad.front()
                    vad.pop()
                    for ((startSample, samples) in splitLongSegment(segment.start, segment.samples, MAX_SEGMENT_SAMPLES)) {
                        coroutineContext.ensureActive()
                        onProgress(OfflineTranscriptionProgress(processed * 1000 / SAMPLE_RATE,
                            (totalSamples * 1000 / SAMPLE_RATE).takeIf { it > 0 }, TextRef.Res(R.string.progress_detail_recognize_segment)))
                        val result = recognize(recognizer, samples)
                        coroutineContext.ensureActive()
                        val startMs = startSample * 1000L / SAMPLE_RATE
                        val endMs = maxOf(startMs + samples.size * 1000L / SAMPLE_RATE, startMs + 1)
                        val text = qwen3TranscriptText(result.text)
                        if (text.isNotBlank()) {
                            val pieces = if (result.timestamps.size >= result.tokens.size) {
                                senseVoicePiecesToCues(result.tokens, result.timestamps, startMs)
                            } else {
                                emptyList()
                            }
                            cues += pieces.ifEmpty { listOf(WhisperCue(startMs, endMs, text)) }
                        }

                    }
                }
            }
            while (true) {
                coroutineContext.ensureActive()
                val chunk = reader.readChunk() ?: break
                vad.acceptWaveform(chunk)
                processed += chunk.size
                onProgress(OfflineTranscriptionProgress(processed * 1000 / SAMPLE_RATE,
                    (totalSamples * 1000 / SAMPLE_RATE).takeIf { it > 0 }, TextRef.Res(R.string.progress_detail_decode_voice)))
                drain()
            }
            vad.flush()
            drain()
            return cues
        } finally {
            vad.release()
        }
    }

    private fun createVad(): Vad = Vad(
        assetManager = appContext.assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = VAD_ASSET,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = VAD_WINDOW_SAMPLES,
                maxSpeechDuration = MAX_SEGMENT_SECONDS,
            ),
            sampleRate = SAMPLE_RATE,
        ),
    )

    private suspend fun transcribeParakeet(
        reader: AudioPcmReader,
        recognizer: OfflineRecognizer,
        totalSamples: Long,
        onProgress: suspend (OfflineTranscriptionProgress) -> Unit,
    ): List<WhisperCue> {
        val detectors = mutableListOf<Vad>()
        try {
            val channelCount = if (reader.isStereo) 2 else 1
            repeat(channelCount) { detectors += createVad() }
            val audio = ParakeetAudioBuffer(channelCount)
            val segments = ParakeetSpeechSegments()
            val transcripts = mutableListOf<ParakeetTranscript>()
            val ready = ArrayDeque<ParakeetSpeechWindow>()
            val frame = FloatArray(PARAKEET_VAD_WINDOW)
            var processed = 0L

            suspend fun drain() {
                while (ready.isNotEmpty()) {
                    val window = ready.removeFirst()
                    transcripts += recognizeParakeetWithContext(window) { input ->
                        decodeParakeet(recognizer, audio, input)
                    }
                }
            }

            while (true) {
                coroutineContext.ensureActive()
                val channels = reader.readChannels(PARAKEET_READ_SAMPLES) ?: break
                for (offset in channels[0].indices step PARAKEET_VAD_WINDOW) {
                    coroutineContext.ensureActive()
                    val count = minOf(PARAKEET_VAD_WINDOW, channels[0].size - offset)
                    audio.append(channels, offset, count)
                    val probability = detectors.indices.maxOf { channel ->
                        channels[channel].copyInto(frame, endIndex = offset + count, startIndex = offset)
                        frame.fill(0f, fromIndex = count)
                        detectors[channel].compute(frame).also {
                            if (!it.isFinite() || it !in 0f..1f) throw WhisperException(TextRef.Res(R.string.whisper_error_voice_detection))
                        }
                    }
                    segments.accept(probability, count, ready::addLast)
                    drain()
                }
                processed += channels[0].size
                onProgress(OfflineTranscriptionProgress(processed * 1000 / SAMPLE_RATE,
                    (totalSamples * 1000 / SAMPLE_RATE).takeIf { it > 0 }, TextRef.Res(R.string.progress_detail_decode_recognize)))
            }
            segments.finish(ready::addLast)
            drain()
            coroutineContext.ensureActive()
            return stitchParakeetTranscripts(transcripts)
        } finally {
            detectors.forEach { it.release() }
        }
    }

    private suspend fun decodeParakeet(
        recognizer: OfflineRecognizer,
        audio: ParakeetAudioBuffer,
        window: ParakeetSpeechWindow,
    ): ParakeetTranscript {
        coroutineContext.ensureActive()
        check(window.inputEnd - window.inputStart <= MAX_SEGMENT_SAMPLES)
        val channels = audio.read(window.inputStart, window.inputEnd)
        val samples = if (channels.size == 1) channels[0] else selectStereoSpeech(
            channels[0], channels[1], 0, channels[0].size,
            (window.coreStart - window.inputStart).toInt(), (window.coreEnd - window.inputStart).toInt(),
        )
        val result = recognize(recognizer, samples)
        coroutineContext.ensureActive()
        val startMs = window.inputStart * 1000L / SAMPLE_RATE
        val endMs = maxOf(window.inputEnd * 1000L / SAMPLE_RATE, startMs + 1)
        val text = qwen3TranscriptText(result.text)
        val pieces = parakeetTimedPieces(text, result.tokens, result.timestamps, startMs, endMs)
        return ParakeetTranscript(window, pieces, if (pieces == null) WhisperCue(startMs, endMs, text) else null)
    }

    private fun recognize(recognizer: OfflineRecognizer, samples: FloatArray): OfflineRecognizerResult {
        val stream: OfflineStream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream)
        } finally {
            stream.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val VAD_WINDOW_SAMPLES = 512
        const val PARAKEET_READ_SAMPLES = 32 * PARAKEET_VAD_WINDOW
        const val MAX_SEGMENT_SECONDS = 28f
        const val MAX_SEGMENT_SAMPLES = 28 * SAMPLE_RATE
        const val VAD_ASSET = "silero_vad.onnx"
    }
}

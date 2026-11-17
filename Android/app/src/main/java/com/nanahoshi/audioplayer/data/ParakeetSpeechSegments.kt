package com.nanahoshi.audioplayer.data

internal const val PARAKEET_VAD_WINDOW = 512
private const val SAMPLES_PER_MS = 16
private const val CORE_SAMPLES = 26_000 * SAMPLES_PER_MS
private const val LOOKBACK_SAMPLES = 4_000 * SAMPLES_PER_MS
private const val SPEECH_SAMPLES = 250 * SAMPLES_PER_MS
private const val SILENCE_SAMPLES = 500 * SAMPLES_PER_MS
private const val SPEECH_PADDING = 200 * SAMPLES_PER_MS
private const val BOUNDARY_CONTEXT = 800 * SAMPLES_PER_MS
private const val START_THRESHOLD = 0.5f
private const val STOP_THRESHOLD = 0.35f

internal data class ParakeetSpeechWindow(
    val coreStart: Long,
    val coreEnd: Long,
    val inputStart: Long,
    val inputEnd: Long,
    val overlapBefore: Boolean = false,
    val overlapAfter: Boolean = false,
) {
    val hasOverlap: Boolean get() = overlapBefore || overlapAfter
}

/** 连续消费人声概率。只缓存当前核心段的概率，音频由调用方按绝对采样位置保留。 */
internal class ParakeetSpeechSegments {
    private data class Frame(val start: Long, val end: Long, val score: Float)

    private var position = 0L
    private var candidateStart: Long? = null
    private var speechStart: Long? = null
    private var silenceStart: Long? = null
    private var inputStart = 0L
    private var overlapBefore = false
    private var previousSpeechEnd: Long? = null
    private var pending: ParakeetSpeechWindow? = null
    private val frames = ArrayDeque<Frame>()

    fun accept(score: Float, count: Int, emit: (ParakeetSpeechWindow) -> Unit) {
        val frameStart = position
        position += count
        var start = speechStart
        if (start == null) {
            candidateStart = if (score >= START_THRESHOLD) candidateStart ?: frameStart else null
            start = candidateStart ?: return
            if (position - start < SPEECH_SAMPLES) return
            speechStart = start
            inputStart = maxOf(
                start - SPEECH_PADDING,
                previousSpeechEnd?.let { it + (start - it) / 2 } ?: 0,
            )
            overlapBefore = false
            candidateStart = null
        }
        frames.addLast(Frame(frameStart, position, score))
        silenceStart = if (score < STOP_THRESHOLD) silenceStart ?: frameStart else null
        val silence = silenceStart
        val limit = start + CORE_SAMPLES
        if (silence != null && position - silence >= SILENCE_SAMPLES) {
            if (silence > limit) splitAtLimit(start)
            endSpeech(silence, emit)
        } else if (position >= limit && silence == null) {
            splitAtLimit(start)
        }
        emitPending(emit)
    }

    fun finish(emit: (ParakeetSpeechWindow) -> Unit) {
        val start = speechStart
        val end = silenceStart ?: position
        if (start != null && end - start > CORE_SAMPLES) splitAtLimit(start)
        endSpeech(end, emit)
    }

    private fun splitAtLimit(start: Long) {
        val limit = start + CORE_SAMPLES
        val quietCut = findQuietCut(limit)
        val cut = quietCut ?: limit
        val context = if (quietCut == null) BOUNDARY_CONTEXT else 0
        check(pending == null)
        pending = ParakeetSpeechWindow(start, cut, inputStart, cut + context, overlapBefore, context > 0)
        speechStart = cut
        inputStart = cut - context
        overlapBefore = context > 0
        while (frames.firstOrNull()?.end?.let { it <= cut } == true) frames.removeFirst()
    }

    private fun endSpeech(end: Long, emit: (ParakeetSpeechWindow) -> Unit) {
        val start = speechStart ?: return
        val coreEnd = maxOf(start, end)
        val inputEnd = minOf(position, coreEnd + SPEECH_PADDING)
        // 自然结束可能早于前一个硬切窗口的右上下文就绪时间。
        pending = pending?.let {
            it.copy(inputEnd = minOf(it.inputEnd, inputEnd), overlapAfter = it.overlapAfter && coreEnd > start)
        }
        emitPending(emit)
        if (coreEnd > start) {
            emit(ParakeetSpeechWindow(start, coreEnd, inputStart, inputEnd, overlapBefore))
        }
        previousSpeechEnd = coreEnd
        speechStart = null
        silenceStart = null
        frames.clear()
    }

    private fun emitPending(emit: (ParakeetSpeechWindow) -> Unit) {
        val window = pending ?: return
        if (window.inputEnd > position) return
        pending = null
        emit(window)
    }

    private fun findQuietCut(limit: Long): Long? {
        val candidates = frames.filter { it.start >= limit - LOOKBACK_SAMPLES && it.end <= limit }
        var silenceFirst = 0
        var longest = 3
        var quietCut: Long? = null
        var lowest = STOP_THRESHOLD
        var lowScoreCut: Long? = null
        for ((index, frame) in candidates.withIndex()) {
            if (frame.score < STOP_THRESHOLD) {
                val length = index - silenceFirst + 1
                if (length >= 4 && length >= longest) {
                    longest = length
                    val start = candidates[silenceFirst].start
                    quietCut = start + (frame.end - start) / 2
                }
            } else {
                silenceFirst = index + 1
            }
            if (index >= 2) {
                val mean = (candidates[index - 2].score + candidates[index - 1].score + frame.score) / 3
                if (mean <= lowest) {
                    lowest = mean
                    val start = candidates[index - 1].start
                    lowScoreCut = start + (candidates[index - 1].end - start) / 2
                }
            }
        }
        return quietCut ?: lowScoreCut
    }
}

/** 保存当前识别段和两侧上下文，内存占用与音频总时长无关。 */
internal class ParakeetAudioBuffer(channelCount: Int) {
    private val channels = Array(channelCount) { FloatArray(28_000 * SAMPLES_PER_MS + PARAKEET_VAD_WINDOW) }
    private val capacity = channels[0].size
    private var end = 0L

    fun append(source: Array<FloatArray>, offset: Int, count: Int) {
        val index = (end % capacity).toInt()
        val first = minOf(count, capacity - index)
        for (channel in channels.indices) {
            source[channel].copyInto(channels[channel], index, offset, offset + first)
            source[channel].copyInto(channels[channel], 0, offset + first, offset + count)
        }
        end += count
    }

    fun read(start: Long, stop: Long): Array<FloatArray> {
        check(start >= maxOf(0, end - capacity) && stop <= end && stop > start)
        val count = (stop - start).toInt()
        val index = (start % capacity).toInt()
        val first = minOf(count, capacity - index)
        return Array(channels.size) { channel ->
            FloatArray(count).also { samples ->
                channels[channel].copyInto(samples, 0, index, index + first)
                channels[channel].copyInto(samples, first, 0, count - first)
            }
        }
    }
}

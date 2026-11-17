package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.roundToLong

private const val FRAME_MS = 80L
private const val MATCH_TOLERANCE_MS = 240L
private const val SAMPLES_PER_MS = 16

internal fun parakeetTimedPieces(
    text: String,
    tokens: Array<String>,
    timestamps: FloatArray,
    startMs: Long,
    endMs: Long,
): List<WhisperCue>? {
    if (text.isBlank()) return emptyList()
    val durationSeconds = (endMs - startMs) / 1000f
    if (tokens.size != timestamps.size || timestamps.indices.any { index ->
            timestamps[index] !in 0f..<durationSeconds ||
                (index > 0 && timestamps[index] < timestamps[index - 1])
        }
    ) return null
    if (tokens.joinToString("").withoutWhitespace() != text.withoutWhitespace()) return null

    val spaces = StringBuilder()
    return buildList {
        tokens.forEachIndexed { index, token ->
            if (token.isBlank()) {
                if (isNotEmpty()) spaces.append(token)
            } else {
                val start = (startMs + (timestamps[index] * 1000f).roundToLong()).coerceAtMost(endMs - 1)
                val next = timestamps.getOrNull(index + 1)?.let { startMs + (it * 1000f).roundToLong() }
                val end = minOf(start + FRAME_MS, next ?: endMs, endMs).coerceAtLeast(start + 1)
                add(WhisperCue(start, end, spaces.toString() + token))
                spaces.clear()
            }
        }
    }
}

private fun String.withoutWhitespace() = filterNot(Char::isWhitespace)

internal data class ParakeetTranscript(
    val window: ParakeetSpeechWindow,
    val pieces: List<WhisperCue>?,
    val fallback: WhisperCue? = null,
)

internal suspend fun recognizeParakeetWithContext(
    window: ParakeetSpeechWindow,
    decode: suspend (ParakeetSpeechWindow) -> ParakeetTranscript,
): ParakeetTranscript {
    val transcript = decode(window)
    if (transcript.pieces != null || !window.hasOverlap) return transcript
    currentCoroutineContext().ensureActive()
    val core = ParakeetSpeechWindow(window.coreStart, window.coreEnd, window.coreStart, window.coreEnd)
    val retry = decode(core)
    // 核心范围重试完全没听出文字时，保留首次识别的全文。
    if (retry.pieces?.isEmpty() != true) return retry
    val fallback = transcript.fallback ?: return retry
    val startMs = core.coreStart / SAMPLES_PER_MS
    val endMs = maxOf(core.coreEnd / SAMPLES_PER_MS, startMs + 1)
    return ParakeetTranscript(core, null, fallback.copy(startMs = startMs, endMs = endMs))
}

internal fun stitchParakeetTranscripts(transcripts: List<ParakeetTranscript>): List<WhisperCue> = buildList {
    var pending: ParakeetTranscript? = null
    val pieces = mutableListOf<WhisperCue>()
    fun flush() {
        addAll(groupParakeetPieces(pieces))
        pieces.clear()
    }
    for (transcript in transcripts) {
        val left = pending
        pending = transcript
        if (left == null) continue

        val boundaryMs = left.window.coreEnd / SAMPLES_PER_MS
        val sharedBoundary = left.window.coreEnd == transcript.window.coreStart
        val overlapping = sharedBoundary && (left.window.overlapAfter || transcript.window.overlapBefore)
        val leftPieces = left.pieces
        val rightPieces = transcript.pieces
        if (leftPieces != null && rightPieces != null && overlapping) {
            val match = if (left.window.overlapAfter && transcript.window.overlapBefore) {
                findParakeetOverlap(
                    leftPieces, rightPieces, maxOf(left.window.inputStart, transcript.window.inputStart) / SAMPLES_PER_MS,
                    minOf(left.window.inputEnd, transcript.window.inputEnd) / SAMPLES_PER_MS, boundaryMs,
                )
            } else {
                null
            }
            if (match != null) {
                pieces += leftPieces.take(match.leftEnd)
                pending = transcript.copy(pieces = rightPieces.drop(match.rightEnd))
            } else {
                pieces += leftPieces.beforeBoundary(boundaryMs)
                pending = transcript.copy(pieces = rightPieces.dropWhile { it.startMs < boundaryMs })
            }
            continue
        }

        if (leftPieces != null) {
            pieces += if (overlapping || (sharedBoundary && rightPieces == null)) {
                leftPieces.beforeBoundary(boundaryMs)
            } else {
                leftPieces
            }
        } else {
            flush()
            left.fallback?.let { add(if (sharedBoundary) it.copy(endMs = boundaryMs) else it) }
            if (sharedBoundary && rightPieces != null) pending = transcript.copy(
                pieces = rightPieces.dropWhile { it.startMs < boundaryMs },
            )
        }
        if (rightPieces == null) flush()
    }
    pending?.let { last ->
        last.pieces?.let { pieces += it }
        flush()
        last.fallback?.let(::add)
    }
}

private fun List<WhisperCue>.beforeBoundary(boundaryMs: Long) =
    takeWhile { it.startMs < boundaryMs }.map { cue -> cue.copy(endMs = minOf(cue.endMs, boundaryMs)) }

private data class OverlapMatch(val leftEnd: Int, val rightEnd: Int)
private data class TimedCharacter(
    val value: Char,
    val index: Int,
    val startMs: Long,
    val first: Boolean,
    val last: Boolean,
)

private fun List<WhisperCue>.overlapCharacters(start: Long, end: Long): List<TimedCharacter> = buildList {
    this@overlapCharacters.forEachIndexed { index, piece ->
        if (piece.startMs !in start until end) return@forEachIndexed
        val text = piece.text.withoutWhitespace()
        text.forEachIndexed { offset, char ->
            add(TimedCharacter(char, index, piece.startMs, offset == 0, offset == text.lastIndex))
        }
    }
}

private fun findParakeetOverlap(
    left: List<WhisperCue>,
    right: List<WhisperCue>,
    start: Long,
    end: Long,
    boundary: Long,
): OverlapMatch? {
    val a = left.overlapCharacters(start, end)
    val b = right.overlapCharacters(start, end)
    val firstOwnedOnRight = right.indexOfFirst { it.startMs >= boundary }.let { if (it < 0) right.size else it }
    val lastOwnedOnLeft = left.indexOfLast { it.startMs < boundary }
    var best: OverlapMatch? = null
    var bestLength = 1
    var bestDistance = Long.MAX_VALUE
    var ambiguous = false
    for ((i, first) in a.withIndex()) {
        if (!first.first) continue
        for ((j, other) in b.withIndex()) {
            if (!other.first || other.index > firstOwnedOnRight) continue
            var length = 0
            var characters = 0
            while (i + length < a.size && j + length < b.size) {
                val x = a[i + length]
                val y = b[j + length]
                if (x.value != y.value || abs(x.startMs - y.startMs) > MATCH_TOLERANCE_MS) break
                length++
                if (!x.value.isLowSurrogate()) characters++
                // 锚点之外，不能丢掉任一段独有的核心区文字。
                if (characters < 2 || characters < bestLength || !x.last || !y.last || x.index < lastOwnedOnLeft) continue
                // 文字各自落在切点两侧且时间不重叠时，可能是实际说了两次。
                if (left[x.index].endMs <= boundary && other.startMs >= boundary) continue
                val nextRight = right.getOrNull(y.index + 1)
                if (nextRight != null && nextRight.startMs < left[x.index].endMs) continue
                val distance = abs(x.startMs + (y.startMs - x.startMs) / 2 - boundary)
                val match = OverlapMatch(x.index + 1, y.index + 1)
                if (characters > bestLength || distance < bestDistance) {
                    best = match
                    bestLength = characters
                    bestDistance = distance
                    ambiguous = false
                } else if (distance == bestDistance && match != best) {
                    ambiguous = true
                }
            }
        }
    }
    return best.takeUnless { ambiguous }
}

package com.nanahoshi.audioplayer.data

import java.net.URI

internal const val WHISPER_MODEL_1 = "whisper-1"
internal const val WHISPER_MODEL_LOCAL = "whisper-local"
internal const val WHISPER_MODEL_QWEN = "qwen-audio-3.1-asr"
internal const val WHISPER_MODEL_QWEN3 = "qwen3-asr"
internal const val WHISPER_MODEL_JOBS = "jobs"
internal const val QWEN3_ASR_MODEL = "Qwen/Qwen3-ASR-1.7B"
internal const val QWEN_FILE_MODEL = "qwen-audio-3.1-asr-flash-filetrans"

internal const val ONLINE_WHISPER_BYTES = 25L * 1024L * 1024L
internal const val QWEN_TEMP_BYTES = 1024L * 1024L * 1024L

private const val OPENAI_HOST = "api.openai.com"

internal const val OPENAI_WHISPER_BASE = "https://api.openai.com"
internal const val QWEN_WHISPER_BASE = "https://dashscope.aliyuncs.com"

internal fun whisperModelLabel(model: String): String = when (model) {
    WHISPER_MODEL_1 -> WHISPER_MODEL_1
    WHISPER_MODEL_LOCAL -> "whisper（本地部署）"
    WHISPER_MODEL_QWEN -> WHISPER_MODEL_QWEN
    WHISPER_MODEL_QWEN3 -> "Qwen3-ASR"
    else -> "本机任务接口"
}

internal fun knownWhisperModel(model: String?): String? =
    model?.takeIf {
        it == WHISPER_MODEL_1 ||
            it == WHISPER_MODEL_LOCAL ||
            it == WHISPER_MODEL_QWEN ||
            it == WHISPER_MODEL_QWEN3 ||
            it == WHISPER_MODEL_JOBS
    }

internal fun isCloudWhisperModel(model: String?): Boolean =
    model == WHISPER_MODEL_1 || model == WHISPER_MODEL_QWEN

internal fun inferredWhisperModel(base: String?): String = when {
    isOnlineWhisper(base) && whisperHost(base) == OPENAI_HOST -> WHISPER_MODEL_1
    isOnlineWhisper(base) -> WHISPER_MODEL_LOCAL
    else -> WHISPER_MODEL_JOBS
}

internal fun whisperModelsMatch(stored: String?, requested: String, endpoint: String?): Boolean {
    val actual = knownWhisperModel(stored) ?: inferredWhisperModel(endpoint)
    return actual == requested
}

/**
 * 比较两条件听写地址是否指向同一个服务。线上接口以前要填到 `/v1`，现在只存主机基址，
 * 所以按本次请求的模型去掉 `/v1` 再比；本机任务接口拿 `/v1` 区分路由，不能去掉。
 */
internal fun sameWhisperEndpoint(a: String?, b: String?, model: String?): Boolean {
    val left = canonicalWhisperEndpoint(a, model) ?: return false
    return left == canonicalWhisperEndpoint(b, model)
}

internal fun canonicalWhisperEndpoint(raw: String?, model: String?): String? {
    val base = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
    val route = knownWhisperModel(model) ?: inferredWhisperModel(base)
    return if (route == WHISPER_MODEL_JOBS) base else base.removeSuffix("/v1")
}

internal fun whisperHost(base: String?): String? {
    val raw = base?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return try {
        URI(raw).host?.lowercase()
    } catch (_: Exception) {
        null
    }
}

internal fun needsOnlineSizeConfirm(model: String?, base: String?, bytes: Long): Boolean {
    val route = knownWhisperModel(model) ?: if (isOnlineWhisper(base)) WHISPER_MODEL_1 else return false
    if (route != WHISPER_MODEL_1) return false
    if (bytes <= ONLINE_WHISPER_BYTES) return false
    return whisperHost(base) == OPENAI_HOST
}

internal fun officialWhisperBase(model: String?): String? = when (model) {
    WHISPER_MODEL_1 -> OPENAI_WHISPER_BASE
    WHISPER_MODEL_QWEN -> QWEN_WHISPER_BASE
    else -> null
}

internal fun effectiveWhisperBase(model: String?, saved: String?): String? {
    val known = knownWhisperModel(model)
    if (isCloudWhisperModel(known)) return officialWhisperBase(known)
    return saved?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
}

internal fun whisperTranscriptionBase(base: String): String {
    val trimmed = base.trim().trimEnd('/')
    return if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
}

internal fun qwen3AsrModelId(ids: List<String>): String? {
    val cleaned = ids.map { it.trim() }.filter { it.isNotEmpty() }
    return cleaned.firstOrNull { it.contains("qwen3-asr", ignoreCase = true) } ?: cleaned.firstOrNull()
}

internal fun qwen3TranscriptText(raw: String): String {
    val marker = "<asr_text>"
    val at = raw.indexOf(marker)
    return (if (at >= 0) raw.substring(at + marker.length) else raw).trim()
}

internal fun cueFromWholeTranscript(text: String, durationMs: Long): List<WhisperCue> {
    val line = qwen3TranscriptText(text)
    if (line.isEmpty()) return emptyList()
    if (durationMs <= 1L) throw WhisperException("没有分段时间")
    return listOf(WhisperCue(0L, durationMs, line))
}

internal fun groupTimedPieces(pieces: List<WhisperCue>): List<WhisperCue> {
    if (pieces.isEmpty()) return emptyList()
    val cues = mutableListOf<WhisperCue>()
    val buffer = StringBuilder()
    var start = 0L
    var end = 0L
    fun flush() {
        val line = qwen3TranscriptText(buffer.toString())
        buffer.clear()
        if (line.isEmpty()) return
        val stop = if (end > start) end else start + 1
        cues += WhisperCue(start, stop, line)
    }
    for (piece in pieces) {
        if (buffer.isEmpty()) start = piece.startMs
        buffer.append(piece.text)
        end = piece.endMs
        if (buffer.lastOrNull() in QWEN3_SENTENCE_END) flush()
    }
    flush()
    return cues
}

private val QWEN3_SENTENCE_END = charArrayOf('。', '！', '？', '!', '?').toSet()

internal fun qwenHttpOrigin(base: String): String? {
    val trimmed = base.trim()
    if (trimmed.isEmpty()) return null
    val swapped = when {
        trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring(6)
        trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring(5)
        trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        else -> "https://$trimmed"
    }
    val uri = try {
        URI(swapped)
    } catch (_: Exception) {
        return null
    }
    val host = uri.host ?: return null
    val scheme = uri.scheme ?: return null
    val port = if (uri.port != -1) ":${uri.port}" else ""
    return "$scheme://$host$port"
}

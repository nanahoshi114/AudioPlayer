package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import java.net.URI

internal const val WHISPER_MODEL_QWEN = "qwen-audio-3.1-asr"
internal const val WHISPER_MODEL_QWEN3 = "qwen3-asr"
internal const val SENSE_VOICE_MODEL = "sense-voice-small"
internal const val QWEN3_ASR_MODEL = "Qwen/Qwen3-ASR-1.7B"
internal const val QWEN_FILE_MODEL = "qwen-audio-3.1-asr-flash-filetrans"

internal const val QWEN_TEMP_BYTES = 1024L * 1024L * 1024L

/** 文件转写轮询的总上限；服务端长期 RUNNING 时不能让任务永久占住串行队列。 */
internal const val QWEN_POLL_TIMEOUT_MS = 30 * 60 * 1000L

internal const val QWEN_WHISPER_BASE = "https://dashscope.aliyuncs.com"

internal fun whisperModelLabel(model: String): String = when (model) {
    WHISPER_MODEL_QWEN -> WHISPER_MODEL_QWEN
    WHISPER_MODEL_QWEN3 -> "Qwen3-ASR"
    SENSE_VOICE_MODEL -> "SenseVoiceSmall"
    else -> SENSE_VOICE_MODEL
}

internal fun knownWhisperModel(model: String?): String? =
    model?.takeIf {
        it == WHISPER_MODEL_QWEN ||
            it == WHISPER_MODEL_QWEN3 ||
            it == SENSE_VOICE_MODEL
    }

internal fun isCloudWhisperModel(model: String?): Boolean = model == WHISPER_MODEL_QWEN

/** 完全在本机运行的听写模型：没有地址和口令，模型文件下载到手机里。 */
internal fun isOnDeviceWhisperModel(model: String?): Boolean = model == SENSE_VOICE_MODEL

/** 与运行时分发一致：只有模型明确解析为 SenseVoice 的排队/进行中任务才阻止删除本机模型；空模型任务不会落到 SenseVoice。 */
internal fun hasActiveSenseVoiceJob(jobs: List<WhisperJobEntity>): Boolean =
    jobs.any {
        (it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING) &&
            knownWhisperModel(it.model) == SENSE_VOICE_MODEL
    }

internal fun whisperModelsMatch(stored: String?, requested: String): Boolean =
    knownWhisperModel(stored) == requested

/**
 * 比较两条听写地址是否指向同一个服务。线上接口以前要填到 `/v1`，现在只存主机基址，
 * 所以按本次请求的模型去掉 `/v1` 再比。本机模型没有地址，双方都是 null 视为同一个。
 */
internal fun sameWhisperEndpoint(a: String?, b: String?, model: String?): Boolean {
    if (a == null && b == null) return true
    val left = canonicalWhisperEndpoint(a, model) ?: return false
    return left == canonicalWhisperEndpoint(b, model)
}

internal fun canonicalWhisperEndpoint(raw: String?, model: String?): String? {
    val base = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
    return if (knownWhisperModel(model) == WHISPER_MODEL_QWEN3) base.removeSuffix("/v1") else base
}

internal fun officialWhisperBase(model: String?): String? = when (model) {
    WHISPER_MODEL_QWEN -> QWEN_WHISPER_BASE
    else -> null
}

internal fun effectiveWhisperBase(model: String?, saved: String?): String? {
    val known = knownWhisperModel(model)
    if (isCloudWhisperModel(known)) return officialWhisperBase(known)
    if (isOnDeviceWhisperModel(known)) return null
    return saved?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
}

internal fun whisperTranscriptionBase(base: String): String {
    val trimmed = base.trim().trimEnd('/')
    return if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
}

/** 优先名称含 qwen3-asr 的模型；单模型自定义名沿用；多模型无匹配时宁可失败也不猜，避免把音频上传给非 ASR 模型。 */
internal fun resolveQwen3Model(ids: List<String>): String {
    val cleaned = ids.map { it.trim() }.filter { it.isNotEmpty() }
    return cleaned.firstOrNull { it.contains("qwen3-asr", ignoreCase = true) }
        ?: cleaned.singleOrNull()
        ?: if (cleaned.isEmpty()) QWEN3_ASR_MODEL
        else throw WhisperException("听写服务返回了多个模型，但没有名称包含 qwen3-asr 的模型，请检查服务配置")
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

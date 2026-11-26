package com.nanahoshi.audioplayer.playback

import androidx.room.withTransaction
import com.nanahoshi.audioplayer.data.db.SubtitlePublicationMeta
import com.nanahoshi.audioplayer.data.encode
import com.nanahoshi.audioplayer.text.PersistedErrors
import kotlinx.coroutines.CancellationException
import java.io.File

internal const val PUBLICATION_STAGING_DIR = "subtitle-publications"
internal const val PUBLICATION_FILE_PREFIX = "@file:"
internal const val INLINE_PUBLICATION_CHAR_LIMIT = 256 * 1024
private const val PUBLICATION_CHUNK_CHARS = 250_000

internal suspend fun SubtitleSource.finishPublication(
    meta: SubtitlePublicationMeta,
    preloadedPayload: String? = null,
    recovering: Boolean = false,
): Boolean {
    val target = File(File(context.filesDir, "subtitles"), meta.filename)
    val stagedFile = publicationFile(meta.filename)
    var written = false
    // 非法文件名（如目录穿越）与写入失败同走失败分支，不抛出中断恢复。
    if (target.name == meta.filename) {
        try {
            val payload = preloadedPayload ?: loadPublicationPayload(meta)
            if (payload != null) {
                target.writeTextAtomically(payload)
                written = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 含非 IO 异常（如 SecurityException）：清发布记录并标失败，可重试。
        }
    }
    if (!written) {
        db.withTransaction {
            db.translations().clearPublication(meta.filename)
            meta.translationJobId?.let { db.translations().failIfActive(it, PersistedErrors.TRANSLATE_SAVE_FAILED) }
                ?: meta.whisperJobId?.let { db.whisper().markTranslateFailed(it, PersistedErrors.TRANSLATE_SAVE_FAILED) }
            if (recovering) finishProgressPublication(meta, false)
        }
        stagedFile.delete()
        return false
    }
    return try {
        db.withTransaction {
            meta.translationJobId?.let { db.translations().finishIfActive(it) }
            meta.whisperJobId?.let { db.whisper().markTranslated(it, meta.translatedAt) }
            if (recovering) finishProgressPublication(meta, true)
            db.translations().clearPublication(meta.filename)
        }
        stagedFile.delete()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // 文件已写成但 DB 提交失败：保留发布记录，下次启动幂等重写再提交。
        false
    }
}

private suspend fun SubtitleSource.loadPublicationPayload(meta: SubtitlePublicationMeta): String? {
    if (meta.payloadLength <= 0) return ""
    val first = db.translations().payloadChunk(meta.filename, 1, PUBLICATION_CHUNK_CHARS) ?: return null
    if (first.startsWith(PUBLICATION_FILE_PREFIX)) {
        val expected = publicationFile(meta.filename)
        val marker = first.removePrefix(PUBLICATION_FILE_PREFIX)
        if (marker != expected.name || !expected.isFile) return null
        return expected.readText()
    }
    if (first.length >= meta.payloadLength) return first
    val full = StringBuilder(meta.payloadLength)
    full.append(first)
    var offset = 1 + first.length
    while (offset <= meta.payloadLength) {
        val chunk = db.translations().payloadChunk(meta.filename, offset, PUBLICATION_CHUNK_CHARS)
        if (chunk.isNullOrEmpty()) break
        full.append(chunk)
        offset += chunk.length
    }
    return full.toString()
}

internal fun SubtitleSource.publicationFile(filename: String): File {
    val digest = com.nanahoshi.audioplayer.data.sha256Hex(filename)
    return File(File(context.filesDir, PUBLICATION_STAGING_DIR), "$digest.json")
}

/** 发布恢复先完成，再登记进度；丢失的进程内统计不推算补齐。调用者持有事务。 */
private suspend fun SubtitleSource.finishProgressPublication(meta: SubtitlePublicationMeta, success: Boolean) {
    val translationId = meta.translationJobId
    val whisperId = meta.whisperJobId
    val raw = translationId?.let { db.translations().get(it)?.progressSummary }
        ?: whisperId?.let { db.whisper().get(it)?.progressSummary }
    val summary = com.nanahoshi.audioplayer.data.decodeProgressSummary(raw)
    val part = com.nanahoshi.audioplayer.data.ProgressPart.TRANSLATION
    val previous = summary.parts[part]
    val result = com.nanahoshi.audioplayer.data.TaskSegmentSummary(
        phase = com.nanahoshi.audioplayer.data.ProgressPhase.SAVING_TRANSLATION,
        result = if (success) com.nanahoshi.audioplayer.data.ProgressResult.COMPLETED else com.nanahoshi.audioplayer.data.ProgressResult.FAILED,
        failurePhase = com.nanahoshi.audioplayer.data.ProgressPhase.SAVING_TRANSLATION.takeUnless { success },
        model = previous?.model, platform = previous?.platform,
    )
    val encoded = summary.merge(part, result).encode()
    if (translationId != null) db.translations().setProgressSummary(translationId, encoded)
    else if (whisperId != null) db.whisper().setProgressSummary(whisperId, encoded)
}
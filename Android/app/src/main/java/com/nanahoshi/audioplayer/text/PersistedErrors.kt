package com.nanahoshi.audioplayer.text

import com.nanahoshi.audioplayer.R

/**
 * 数据库和控制流依赖的固定字符串。
 *
 * 这些值属于持久化状态或旧版本兼容值，不能迁移成资源文案；用户界面读取时
 * 通过 [persistedErrorText] 映射为资源。
 */
internal object PersistedErrors {
    const val CANCELLED = "已中止"
    const val JA_VERIFY = "需要验证后继续"
    const val JA_POST_REMOVED = "篇目已从音频库移除"
    const val JA_POST_REMOVED_LEGACY = "文章已从音频库移除"
    const val LIBRARY_REQUIRED = "请先加入音频库"
    const val AUDIO_DOWNLOAD_INTERRUPTED = "下载中断，请重试"
    const val AUDIO_DOWNLOAD_START_FAILED = "无法启动后台下载，请回到 App 重试"
    const val AUDIO_DOWNLOAD_TIMEOUT = "后台下载超时，请重试"
    const val SUBTITLE_INTERRUPTED = "后台字幕任务已中断，请重试"
    const val SUBTITLE_START_FAILED = "无法启动后台字幕任务，请回到应用后重试"
    const val SUBTITLE_TIMEOUT = "后台字幕任务超时，请重试"
    const val JA_WORK_FILE_MISSING = "该文件只能随整部作品一起下载，请重新下载整部作品"
    const val JA_ENTRY_CHANGED = "原下载入口已变化，请重新选择入口"
    const val DLSITE_ARCHIVE_INTERRUPTED = "压缩包下载中断，可重试继续下载"
    const val DLSITE_ARCHIVE_FAILED = "完整原始包下载失败，请重试"
    const val TRANSLATE_SAVE_FAILED = "无法保存译文，请重试"

    const val TRANSLATE_FAILED = "翻译失败"

    private val interrupted = setOf(
        DLSITE_ARCHIVE_INTERRUPTED, AUDIO_DOWNLOAD_INTERRUPTED, AUDIO_DOWNLOAD_START_FAILED,
        AUDIO_DOWNLOAD_TIMEOUT, SUBTITLE_INTERRUPTED, SUBTITLE_START_FAILED, SUBTITLE_TIMEOUT,
    )

    fun isCancelled(raw: String?): Boolean = raw == CANCELLED

    fun isInterrupted(raw: String?): Boolean = raw in interrupted ||
        raw?.let { "中断" in it || "后台" in it } == true

}

/** 把数据库中的已知错误/状态值映射为展示文本；未知文本保持原样。 */
internal fun persistedErrorText(raw: String?): TextRef? {
    if (raw.isNullOrBlank()) return null
    return when (raw) {
        PersistedErrors.CANCELLED -> TextRef.Res(R.string.status_cancelled)
        PersistedErrors.JA_VERIFY -> TextRef.Res(R.string.download_status_waiting_verification)
        PersistedErrors.JA_POST_REMOVED,
        PersistedErrors.JA_POST_REMOVED_LEGACY -> TextRef.Res(R.string.error_work_removed)
        PersistedErrors.LIBRARY_REQUIRED -> TextRef.Res(R.string.download_error_library_required)
        PersistedErrors.AUDIO_DOWNLOAD_INTERRUPTED -> TextRef.Res(R.string.download_error_interrupted)
        PersistedErrors.AUDIO_DOWNLOAD_START_FAILED -> TextRef.Res(R.string.download_error_start_failed)
        PersistedErrors.AUDIO_DOWNLOAD_TIMEOUT -> TextRef.Res(R.string.download_error_timeout)
        PersistedErrors.SUBTITLE_INTERRUPTED -> TextRef.Res(R.string.subtitle_error_interrupted)
        PersistedErrors.SUBTITLE_START_FAILED -> TextRef.Res(R.string.subtitle_error_start_failed)
        PersistedErrors.SUBTITLE_TIMEOUT -> TextRef.Res(R.string.subtitle_error_timeout)
        PersistedErrors.JA_WORK_FILE_MISSING -> TextRef.Res(R.string.ja_error_work_file_missing)
        PersistedErrors.JA_ENTRY_CHANGED -> TextRef.Res(R.string.ja_error_entry_changed)
        PersistedErrors.DLSITE_ARCHIVE_INTERRUPTED -> TextRef.Res(R.string.dlsite_archive_error_interrupted)
        PersistedErrors.DLSITE_ARCHIVE_FAILED -> TextRef.Res(R.string.dlsite_archive_error_failed)
        PersistedErrors.TRANSLATE_SAVE_FAILED -> TextRef.Res(R.string.translate_publication_save_failed)
        PersistedErrors.TRANSLATE_FAILED -> TextRef.Res(R.string.translate_status_failed)
        else -> TextRef.Raw(raw)
    }
}
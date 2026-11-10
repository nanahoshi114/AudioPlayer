package com.nanahoshi.audioplayer.ui.importing

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.data.*
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.youtube.YouTubeVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun importCandidate(clip: ClipData): ClipboardImportCandidate? {
    val timestamp = if (Build.VERSION.SDK_INT >= 26) clip.description.timestamp else 0
    for (index in 0 until minOf(clip.itemCount, 10)) {
        val item = clip.getItemAt(index)
        // 不用 coerceToText，避免自动访问剪贴板中的 content: URI 或 Intent。
        val text = item.text?.take(MAX_IMPORT_CLIPBOARD_LENGTH)?.toString()
        val target = text?.let(::clipboardImportTarget)
            ?: item.uri?.takeIf { it.scheme == "https" || it.scheme == "http" }?.toString()?.let(::clipboardImportTarget) ?: continue
        return ClipboardImportCandidate(target, timestamp)
    }
    return null
}

class ClipboardImportViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    private val preferences = app.getSharedPreferences("clipboard_import", Context.MODE_PRIVATE)
    internal val controller = ClipboardImportController(viewModelScope, object : ClipboardImportBackend {
        override suspend fun preview(target: ClipboardTarget): ClipboardImportPreview = withContext(Dispatchers.IO) {
            when (target.source) {
                ClipboardSource.BILIBILI -> {
                    val details = graph.bilibili.videoDetails(target.id)
                    val parts = details.parts.filter { it.cid > 0 }.distinctBy { it.cid }
                    check(parts.isNotEmpty())
                    val saved = parts.all { graph.db.tracks().findByBvid(it.bvid, it.cid) != null }
                    ClipboardImportPreview(target, details.video.title, details.video.coverUrl, saved, parts, biliVideo = details.video)
                }
                ClipboardSource.ASMR -> {
                    val saved = graph.library.folderBySource(target.id)
                    if (saved != null) ClipboardImportPreview(target, saved.name, saved.coverUri, alreadySaved = true)
                    else {
                        val info = graph.asmr.preview(target.id)
                        ClipboardImportPreview(target, info.title, info.coverUrl)
                    }
                }
                ClipboardSource.YOUTUBE -> {
                    val saved = graph.db.tracks().findYouTube(target.id)
                    val video = saved?.let { YouTubeVideo(target.id, it.title, it.artist.orEmpty(),
                        it.coverUri ?: "https://i.ytimg.com/vi/${target.id}/hqdefault.jpg", it.durationMs) }
                        ?: graph.youtube.videoInfo(target.id)
                    ClipboardImportPreview(target, video.title, video.coverUrl, saved != null, youtubeVideo = video)
                }
            }
        }
        override suspend fun save(preview: ClipboardImportPreview, selectedParts: List<BiliPart>): String? {
            when (preview.target.source) {
                ClipboardSource.BILIBILI -> {
                    val details = com.nanahoshi.audioplayer.bilibili.BiliVideoDetails(checkNotNull(preview.biliVideo), preview.parts)
                    val requests = graph.biliImportRequests.choose(listOf(details)) ?: return null
                    requests.forEach { graph.library.importBili(it) }
                    return "已加入音频库"
                }
                ClipboardSource.ASMR -> graph.library.ensureAsmrWork(graph.asmr.loadWork(preview.target.id))
                ClipboardSource.YOUTUBE -> graph.library.saveYouTube(checkNotNull(preview.youtubeVideo))
            }
            return "已加入音频库"
        }
    }, lastHandled = { preferences.getString("last_handled", null) },
        rememberHandled = { preferences.edit().putString("last_handled", it).apply() }, message = { graph.messages.value = it })

    fun inspectClipboard() {
        if (controller.state?.saving == true) return
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        // 某些系统会拒绝读取；保持当前页面，不记录剪贴板或异常原文。
        val candidate = runCatching { clipboard.primaryClip?.let(::importCandidate) }.getOrNull() ?: return
        controller.offer(candidate)
    }
}

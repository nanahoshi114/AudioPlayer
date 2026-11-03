package com.nanahoshi.audioplayer.data

import androidx.compose.runtime.*
import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.youtube.YouTubeVideo
import kotlinx.coroutines.*

internal data class ClipboardImportPreview(
    val target: ClipboardTarget, val title: String, val cover: String?, val alreadySaved: Boolean = false,
    val parts: List<BiliPart> = emptyList(), val youtubeVideo: YouTubeVideo? = null,
    val biliVideo: com.nanahoshi.audioplayer.bilibili.BiliVideoHit? = null,
)
internal data class ClipboardImportState(
    val candidate: ClipboardImportCandidate, val preview: ClipboardImportPreview? = null,
    val selected: Set<Long> = emptySet(), val loading: Boolean = true, val saving: Boolean = false, val error: String? = null,
) {
    val canImport get() = !loading && !saving && preview != null && (!preview.alreadySaved || preview.target.source == ClipboardSource.BILIBILI) &&
        (preview.target.source != ClipboardSource.BILIBILI || preview.parts.any { it.cid in selected })
}
internal interface ClipboardImportBackend {
    suspend fun preview(target: ClipboardTarget): ClipboardImportPreview
    suspend fun save(preview: ClipboardImportPreview, selectedParts: List<BiliPart>): String?
}

/** 所有状态更新在主线程；测试注入后端和作用域，不读 Android 剪贴板或 org.json。 */
internal class ClipboardImportController(
    private val scope: CoroutineScope,
    private val backend: ClipboardImportBackend,
    private val lastHandled: () -> String?,
    private val rememberHandled: (String) -> Unit,
    private val message: (String) -> Unit,
) {
    var state by mutableStateOf<ClipboardImportState?>(null); private set
    private var job: Job? = null
    private var generation = 0L

    fun offer(candidate: ClipboardImportCandidate) {
        if (state?.saving == true || candidate.fingerprint == lastHandled()) return
        generation++; job?.cancel()
        rememberHandled(candidate.fingerprint)
        state = ClipboardImportState(candidate)
        load()
    }
    fun dismiss() {
        if (state?.saving == true) return
        generation++; job?.cancel(); state = null
    }
    fun load() {
        val current = state ?: return
        if (current.saving) return
        generation++; job?.cancel()
        val token = generation
        state = current.copy(loading = true, error = null)
        job = scope.launch {
            try {
                val preview = backend.preview(current.candidate.target)
                if (token != generation) return@launch
                state = current.copy(preview = preview, loading = false, error = null, selected = preview.parts.map { it.cid }.toSet())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (token == generation) state = current.copy(loading = false, error = "读取标题和封面失败，请检查网络后重试")
            }
        }
    }
    fun togglePart(cid: Long) {
        val current = state ?: return
        if (current.loading || current.saving) return
        state = current.copy(selected = if (cid in current.selected) current.selected - cid else current.selected + cid)
    }
    fun toggleAllParts() {
        val current = state ?: return
        if (current.loading || current.saving) return
        val all = current.preview?.parts?.map { it.cid }?.toSet() ?: return
        state = current.copy(selected = if (current.selected == all) emptySet() else all)
    }
    fun confirm() {
        val current = state?.takeIf { it.canImport } ?: return
        val preview = current.preview ?: return
        val token = generation
        state = current.copy(saving = true, error = null)
        job = scope.launch {
            try {
                val result = backend.save(preview, preview.parts.filter { it.cid in current.selected })
                if (token == generation) {
                    if (result == null) state = current.copy(saving = false, error = null)
                    else { state = null; message(result) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (token == generation) state = current.copy(error = "导入失败，请重试")
            }
        }
    }
}

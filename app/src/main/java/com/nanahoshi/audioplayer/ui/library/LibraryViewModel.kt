package com.nanahoshi.audioplayer.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.bilibili.BilibiliException
import com.nanahoshi.audioplayer.data.FolderChoice
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.graph
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

data class PendingBiliImport(
    val parts: List<BiliPart>,
    val selected: Set<Int>,
    val play: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    val currentFolderId = MutableStateFlow<Long?>(null)
    val currentFolder = currentFolderId.flatMapLatest { id ->
        if (id == null) kotlinx.coroutines.flow.flowOf(null) else graph.library.observeFolder(id)
    }
    val folders = currentFolderId.flatMapLatest { graph.library.observeChildFolders(it) }
    val tracks = currentFolderId.flatMapLatest { graph.library.observeTracksIn(it) }
    val busy = MutableStateFlow(false)
    val pendingBili = MutableStateFlow<PendingBiliImport?>(null)
    val selectedTrackIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedFolderIds = MutableStateFlow<Set<Long>>(emptySet())

    fun openFolder(folder: FolderEntity) {
        currentFolderId.value = folder.id
        clearSelection()
    }

    fun up() {
        val id = currentFolderId.value ?: return
        viewModelScope.launch {
            currentFolderId.value = graph.library.folder(id)?.parentId
            clearSelection()
        }
    }

    fun importFile(uri: Uri, play: Boolean) = launch {
        val track = graph.library.importFile(uri, currentFolderId.value)
        if (play) graph.player.playTrack(track) else graph.messages.value = "已导入"
    }

    fun importFolder(uri: Uri, keepStructure: Boolean) = launch {
        val count = graph.library.importTree(uri, currentFolderId.value, keepStructure)
        graph.messages.value = if (count == 0) "这个文件夹里没有音频" else "已导入 $count 首"
    }

    fun createFolder(name: String) = launch {
        graph.library.createFolder(currentFolderId.value, name)
    }

    fun lookupBvid(input: String, play: Boolean) = launch {
        val parts = graph.library.lookupBvid(input)
        if (parts.size == 1) {
            finishBili(parts, play)
        } else {
            pendingBili.value = PendingBiliImport(parts, parts.indices.toSet(), play)
        }
    }

    fun togglePart(index: Int) {
        val current = pendingBili.value ?: return
        val selected = current.selected.toMutableSet()
        if (!selected.add(index)) selected.remove(index)
        pendingBili.value = current.copy(selected = selected)
    }

    fun confirmBili() {
        val current = pendingBili.value ?: return
        val chosen = current.parts.filterIndexed { index, _ -> index in current.selected }
        pendingBili.value = null
        if (chosen.isEmpty()) {
            graph.messages.value = "请至少选择一段"
            return
        }
        launch { finishBili(chosen, current.play) }
    }

    fun dismissBili() {
        pendingBili.value = null
    }

    fun deleteTrack(id: Long) {
        graph.player.deleteTrack(id)
    }

    fun deleteFolder(id: Long) {
        graph.player.removeFromLibrary(emptyList(), listOf(id))
    }

    fun deleteSelection() {
        val tracks = selectedTrackIds.value.toList()
        val folders = selectedFolderIds.value.toList()
        clearSelection()
        graph.player.removeFromLibrary(tracks, folders)
    }

    fun beginSelection(trackId: Long? = null, folderId: Long? = null) {
        if (selectedTrackIds.value.isEmpty() && selectedFolderIds.value.isEmpty()) {
            selectedTrackIds.value = listOfNotNull(trackId).toSet()
            selectedFolderIds.value = listOfNotNull(folderId).toSet()
        } else if (trackId != null) {
            toggleTrack(trackId)
        } else if (folderId != null) {
            toggleFolder(folderId)
        }
    }

    fun toggleTrack(id: Long) {
        val current = selectedTrackIds.value
        selectedTrackIds.value = if (id in current) current - id else current + id
    }

    fun toggleFolder(id: Long) {
        val current = selectedFolderIds.value
        selectedFolderIds.value = if (id in current) current - id else current + id
    }

    fun clearSelection() {
        selectedTrackIds.value = emptySet()
        selectedFolderIds.value = emptySet()
    }

    fun playNextSelection() = launch {
        val tracks = selectedTracks()
        clearSelection()
        if (tracks.isEmpty()) {
            graph.messages.value = "没有可加入的音频"
            return@launch
        }
        graph.player.playNext(tracks)
    }

    fun appendSelection() = launch {
        val tracks = selectedTracks()
        clearSelection()
        if (tracks.isEmpty()) {
            graph.messages.value = "没有可加入的音频"
            return@launch
        }
        graph.player.append(tracks)
    }

    fun play(track: TrackEntity) {
        graph.player.playTrack(track)
    }

    fun playNext(track: TrackEntity) {
        graph.player.playNext(listOf(track))
    }

    fun append(track: TrackEntity) {
        graph.player.append(listOf(track))
    }

    fun moveSelection(targetFolderId: Long?) = launch {
        graph.library.moveItems(
            selectedTrackIds.value.toList(),
            selectedFolderIds.value.toList(),
            targetFolderId,
        )
        clearSelection()
        graph.messages.value = "已移动"
    }

    fun loadMoveChoices(onReady: (List<FolderChoice>) -> Unit) {
        viewModelScope.launch {
            onReady(graph.library.moveChoices(selectedFolderIds.value))
        }
    }

    private suspend fun finishBili(parts: List<BiliPart>, play: Boolean) {
        val tracks = graph.library.importParts(parts, currentFolderId.value)
        if (play) {
            graph.player.playTracks(tracks)
        } else {
            graph.messages.value = "已导入 ${tracks.size} 首"
        }
    }

    private suspend fun selectedTracks(): List<TrackEntity> =
        graph.library.orderedSelection(
            currentFolderId.value,
            selectedFolderIds.value,
            selectedTrackIds.value,
        )

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try {
                block()
            } catch (error: BilibiliException) {
                graph.messages.value = error.message
            } catch (error: Exception) {
                graph.messages.value = error.message ?: "操作失败"
            } finally {
                busy.value = false
            }
        }
    }
}

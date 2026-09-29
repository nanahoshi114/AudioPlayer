package com.nanahoshi.audioplayer.ui.playlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nanahoshi.audioplayer.graph
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    val playlists = graph.library.observePlaylists()
    private val pickerFolderId = MutableStateFlow<Long?>(null)
    val pickerFolders = pickerFolderId.flatMapLatest { graph.library.observeChildFolders(it) }
    val pickerTracks = pickerFolderId.flatMapLatest { graph.library.observeTracksIn(it) }
    val pickerFolder = pickerFolderId.flatMapLatest { id ->
        if (id == null) kotlinx.coroutines.flow.flowOf(null) else graph.library.observeFolder(id)
    }

    fun playlist(id: Long) = graph.library.observePlaylist(id)

    fun playlistTracks(id: Long) = graph.library.observePlaylistTracks(id)

    fun create(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { graph.library.createPlaylist(name) }
    }

    fun rename(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { graph.library.renamePlaylist(id, name) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { graph.library.deletePlaylist(id) }
    }

    fun openPickerFolder(id: Long) {
        pickerFolderId.value = id
    }

    fun upPicker() {
        val id = pickerFolderId.value ?: return
        viewModelScope.launch {
            pickerFolderId.value = graph.library.folder(id)?.parentId
        }
    }

    fun resetPicker() {
        pickerFolderId.value = null
    }

    fun addTracks(playlistId: Long, trackIds: List<Long>) {
        viewModelScope.launch { graph.library.addTracksToPlaylist(playlistId, trackIds) }
    }

    fun removeItem(playlistId: Long, itemId: Long) {
        viewModelScope.launch { graph.library.removePlaylistItem(playlistId, itemId) }
    }

    fun move(playlistId: Long, from: Int, to: Int) {
        viewModelScope.launch { graph.library.movePlaylistItem(playlistId, from, to) }
    }

    fun playReplacing(playlistId: Long) {
        viewModelScope.launch {
            graph.player.replaceAndPlay(graph.library.playlistTracks(playlistId))
        }
    }

    fun appendToQueue(playlistId: Long) {
        viewModelScope.launch {
            graph.player.append(graph.library.playlistTracks(playlistId))
        }
    }
}

package com.nanahoshi.audioplayer.ui.playlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nanahoshi.audioplayer.graph
import kotlinx.coroutines.launch

class PlaylistViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    val playlists = graph.library.observePlaylists()
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

    fun playFrom(playlistId: Long, index: Int) {
        viewModelScope.launch {
            val tracks = graph.library.playlistTracks(playlistId)
            if (tracks.isEmpty()) return@launch
            graph.player.replaceAndPlay(tracks, index)
        }
    }

    fun appendToQueue(playlistId: Long) {
        viewModelScope.launch {
            graph.player.append(graph.library.playlistTracks(playlistId))
        }
    }
}

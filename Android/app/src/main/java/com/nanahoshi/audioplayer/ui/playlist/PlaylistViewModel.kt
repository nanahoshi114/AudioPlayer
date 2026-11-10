package com.nanahoshi.audioplayer.ui.playlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nanahoshi.audioplayer.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

class PlaylistViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    val playlists = graph.db.playlists().observeAll()
    internal val coverFolders = graph.library.observeAllFolders().map { folders -> folders.associateBy { it.id } }
    val collectionSources = graph.biliCollections.sources.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)
    fun collection(row: com.nanahoshi.audioplayer.data.db.BiliCollectionEntity) = graph.biliCollections.decode(row)
    fun playlist(id: Long) = graph.library.observePlaylist(id)

    fun playlistTracks(id: Long) = graph.library.observePlaylistTracks(id)

    fun create(name: String) {
        if (name.isBlank()) return
        edit { graph.library.createPlaylist(name) }
    }

    fun rename(id: Long, name: String) {
        if (name.isBlank()) return
        edit { graph.library.renamePlaylist(id, name) }
    }

    fun delete(id: Long) = edit { graph.library.deletePlaylist(id) }

    fun removeItem(playlistId: Long, itemId: Long) = edit {
        graph.library.removePlaylistItem(playlistId, itemId)
    }

    fun move(playlistId: Long, from: Int, to: Int) = edit {
        graph.library.movePlaylistItem(playlistId, from, to)
    }

    /** 在线列表的编辑限制由仓储层抛出，这里转成提示而不是崩溃。 */
    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.messages.value = failure.message?.takeIf { it.isNotBlank() } ?: "操作失败，请重试"
            }
        }
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

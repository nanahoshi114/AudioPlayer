package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.data.db.QueuedTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SubtitleSelection(val queueItemId: Long = -1, val chinese: Boolean = true)

internal data class CurrentSubtitleState(
    val queueItemId: Long = -1,
    val loaded: LoadedSubtitles? = null,
) {
    fun forItem(id: Long): LoadedSubtitles? = loaded.takeIf { queueItemId == id }
}

internal class CurrentSubtitles(
    scope: CoroutineScope,
    queue: Flow<List<QueuedTrack>>,
    changes: Flow<Unit>,
    load: suspend (QueuedTrack) -> LoadedSubtitles,
) {
    private val _selection = MutableStateFlow(SubtitleSelection())
    val selection = _selection.asStateFlow()
    private val revision = MutableStateFlow(0L)

    private val currentItem = combine(
        selection.map { it.queueItemId },
        queue,
    ) { id, rows ->
        // Reordering the queue does not change the source of the current subtitles.
        id to rows.firstOrNull { it.queueItemId == id }?.copy(position = 0)
    }.distinctUntilChanged()

    val state = channelFlow {
        var pending: Job? = null
        combine(currentItem, changes, revision) { item, _, _ -> item }.collect { (id, row) ->
            // Start the next item without waiting for a previous blocking file or session read.
            pending?.cancel()
            send(CurrentSubtitleState(id))
            pending = launch {
                val loaded = row?.let { load(it) } ?: LoadedSubtitles(emptyList(), false, false)
                ensureActive()
                if (selection.value.queueItemId == id) send(CurrentSubtitleState(id, loaded))
            }
        }
    }.stateIn(
        scope,
        // Give the overlay time to take over when the activity stops collecting.
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 750, replayExpirationMillis = 0),
        CurrentSubtitleState(),
    )

    fun select(queueItemId: Long) {
        _selection.update { if (it.queueItemId == queueItemId) it else SubtitleSelection(queueItemId) }
    }

    fun showChinese(queueItemId: Long, chinese: Boolean) {
        _selection.update { if (it.queueItemId == queueItemId) it.copy(chinese = chinese) else it }
    }

    fun refresh() {
        revision.update { it + 1 }
    }
}

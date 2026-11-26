package com.nanahoshi.audioplayer.ui.youtube

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.resolve
import com.nanahoshi.audioplayer.text.resolveUserError
import com.nanahoshi.audioplayer.ui.components.AccountCard
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.ConfirmActionDialog
import com.nanahoshi.audioplayer.ui.components.CoverImage
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.ErrorState
import com.nanahoshi.audioplayer.ui.components.ResetOnNewSearch
import com.nanahoshi.audioplayer.ui.components.RoundedProgress
import com.nanahoshi.audioplayer.ui.components.SearchField
import com.nanahoshi.audioplayer.ui.components.SecretTextField
import com.nanahoshi.audioplayer.ui.components.SelectionAction
import com.nanahoshi.audioplayer.ui.components.SelectionState
import com.nanahoshi.audioplayer.ui.components.SelectionTopBar
import com.nanahoshi.audioplayer.ui.components.TopBarOverride
import com.nanahoshi.audioplayer.ui.components.formatDuration
import com.nanahoshi.audioplayer.youtube.YouTubeVideo
import com.nanahoshi.audioplayer.youtube.normalizeYouTubeCookie
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

internal data class YouTubeVideoState(
    val track: TrackEntity? = null,
    val downloaded: Boolean = false,
    val downloading: Boolean = false,
)

class YouTubeViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    var query by mutableStateOf("")
    var videos by mutableStateOf<List<YouTubeVideo>>(emptyList()); private set
    private val selection = SelectionState<String>()
    val selected: Set<String> get() = selection.ids
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var partial by mutableStateOf(false); private set
    val tracks = graph.db.tracks().observeAll()
    val downloads = graph.downloads.observe()
    internal val videoStates: StateFlow<Map<String, YouTubeVideoState>> = combine(tracks, downloads) { allTracks, allJobs ->
        val stored = allTracks.filter { it.source == TrackSource.YOUTUBE && !it.youtubeVideoId.isNullOrBlank() }
            .associateBy { it.youtubeVideoId!! }
        val active = allJobs.filter {
            it.source == TrackSource.YOUTUBE.name &&
                (it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING)
        }.mapNotNullTo(mutableSetOf()) { it.workKey }
        (stored.keys + active).associateWith { videoId ->
            val track = stored[videoId]
            val downloaded = track?.localUri?.let { it.startsWith("/") && File(it).isFile } == true
            YouTubeVideoState(track = track, downloaded = downloaded, downloading = videoId in active)
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    private var request: Job? = null
    private var saving = false
    var searchRevision by mutableStateOf(0L); private set
    private var generation = 0L

    init {
        viewModelScope.launch {
            var revision = graph.credentials.youtubeSession.value
            graph.credentials.youtubeSession.collect { next ->
                if (next != revision) {
                    revision = next
                    cancelSearch()
                    videos = emptyList(); selection.clear(); error = null; partial = false
                }
            }
        }
    }

    fun cancelSearch() { generation++; request?.cancel(); request = null; loading = false }

    fun search(listId: String? = null) {
        searchRevision++
        cancelSearch()
        val token = generation
        loading = true; error = null; partial = false; selection.clear(); videos = emptyList()
        request = viewModelScope.launch {
            try {
                val result = if (listId != null) graph.youtube.playlist(listId) else graph.youtube.lookup(query)
                if (token == generation) { videos = result.videos; partial = result.partial }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (token == generation) error = getApplication<Application>().resolveUserError(failure, R.string.youtube_error_read_failed)
            } finally {
                if (token == generation) loading = false
            }
        }
    }

    fun toggle(id: String) = selection.toggle(id)
    fun clearSelection() = selection.clear()
    fun selectAll() = selection.toggleAll(videos.map { it.id })

    private fun action(onFinally: () -> Unit = {}, block: suspend () -> Unit) {
        graph.launchGuarded(viewModelScope, R.string.youtube_error_operation_failed, onFinally = onFinally, block = block)
    }

    fun play(video: YouTubeVideo) = action {
        val track = graph.db.tracks().findYouTube(video.id)
        if (track != null) graph.player.replaceAndPlay(listOf(track))
        else graph.player.replaceAndPlayRemote(
            listOf(
                RemotePlayable(
                    video.title, video.author, video.coverUrl, video.watchUrl, TrackSource.YOUTUBE,
                    "https://www.youtube.com/", video.durationMs, video.id, "audio",
                ),
            ),
        )
    }

    fun saveSelection() {
        if (saving) return
        saving = true
        action(onFinally = { saving = false }) {
            val targets = videos.filter { it.id in selected }
            var existing = 0
            for (video in targets) {
                if (graph.db.tracks().findYouTube(video.id) != null) existing++
            }
            targets.forEach { graph.library.saveYouTube(it) }
            // 只移除本次快照中已保存的条目，保留用户在这期间新勾选的内容。
            selection.removeAll(targets.map { it.id })
            graph.show(
                when {
                    targets.isEmpty() -> TextRef.Res(R.string.youtube_no_videos_to_add)
                    existing == targets.size -> TextRef.Res(R.string.youtube_selected_in_library)
                    existing == 0 -> TextRef.Res(R.string.youtube_added_count, listOf(targets.size))
                    else -> TextRef.Res(
                        R.string.youtube_added_partial,
                        listOf(targets.size - existing, existing),
                    )
                },
            )
        }
    }

    fun save(video: YouTubeVideo) = action {
        graph.library.saveYouTube(video)
        graph.show(R.string.youtube_added_library)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YouTubeBrowser(
    playlistId: String? = null,
    onBack: (() -> Unit)? = null,
    topBarOwnerKey: String? = null,
    viewModel: YouTubeViewModel = viewModel(key = "youtube:${playlistId ?: "search"}"),
) {
    val videoStates by viewModel.videoStates.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    ResetOnNewSearch(listState, viewModel.searchRevision)
    LaunchedEffect(playlistId) { if (playlistId != null) viewModel.search(playlistId) }
    val selecting = viewModel.selected.isNotEmpty()
    BackHandler(selecting) { viewModel.clearSelection() }
    val selectionBar: (@Composable () -> Unit)? = if (selecting) {
        {
            val allSelected = viewModel.selected.size == viewModel.videos.size
            SelectionTopBar(
                count = viewModel.selected.size,
                onClose = viewModel::clearSelection,
                actions = listOf(
                    SelectionAction(
                        stringResource(if (allSelected) R.string.youtube_deselect_all else R.string.youtube_select_all_loaded),
                        if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                        viewModel::selectAll,
                    ),
                    SelectionAction(stringResource(R.string.common_add_to_library), Icons.Default.LibraryAdd, viewModel::saveSelection),
                ),
            )
        }
    } else {
        null
    }
    val overrideOwner = topBarOwnerKey?.takeIf { playlistId == null }
    if (overrideOwner != null && selectionBar != null) TopBarOverride(overrideOwner, selectionBar)
    Column(Modifier.fillMaxSize()) {
        if (overrideOwner == null) {
            if (selectionBar != null) selectionBar()
            else if (onBack != null) AppTopBar(
                title = stringResource(if (playlistId == "LL") R.string.youtube_liked_title else R.string.youtube_watch_later_title),
                onBack = onBack,
            )
        }
        if (playlistId == null) {
            SearchField(
                value = viewModel.query,
                onValueChange = { viewModel.query = it },
                placeholder = stringResource(R.string.youtube_search_hint),
                onSearch = { viewModel.search() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (viewModel.loading) {
                Text(stringResource(R.string.youtube_loading), Modifier.weight(1f))
                TextButton(onClick = { viewModel.cancelSearch() }) { Text(stringResource(R.string.common_stop)) }
            } else {
                Text(stringResource(R.string.youtube_video_count, viewModel.videos.size), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { viewModel.search(playlistId) }) {
                    Text(stringResource(if (playlistId == null) R.string.youtube_action_search else R.string.youtube_action_refresh))
                }
            }
            if (!selecting && viewModel.videos.isNotEmpty()) TextButton(onClick = viewModel::selectAll) {
                Text(stringResource(R.string.youtube_select_all_loaded))
            }
        }
        if (viewModel.loading) RoundedProgress(progress = null)
        val errorText = viewModel.error
        if (viewModel.partial) Text(stringResource(R.string.youtube_partial), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        when {
            !viewModel.loading && viewModel.videos.isEmpty() && errorText != null -> ErrorState(
                message = errorText,
                onRetry = { viewModel.search(playlistId) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            !viewModel.loading && viewModel.videos.isEmpty() -> EmptyState(
                title = stringResource(
                    if (playlistId == null) R.string.youtube_empty_search_title
                    else R.string.youtube_empty_playlist_title,
                ),
                message = stringResource(
                    if (playlistId == null) R.string.youtube_empty_search_message
                    else R.string.youtube_empty_playlist_message,
                ),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (errorText != null) {
                    item(key = "error") {
                        ErrorState(errorText, onRetry = { viewModel.search(playlistId) })
                    }
                }
                items(viewModel.videos, key = { it.id }) { video ->
                    val status = videoStates[video.id]
                    val track = status?.track
                    val downloaded = status?.downloaded == true
                    val downloading = status?.downloading == true
                    var menu by remember { mutableStateOf(false) }
                    ListItem(
                        headlineContent = { Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                Text(listOf(video.author, formatDuration(video.durationMs)).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    stringResource(
                                        when {
                                            downloaded -> R.string.youtube_downloaded
                                            downloading -> R.string.youtube_downloading
                                            track != null -> R.string.youtube_in_library
                                            else -> R.string.youtube_not_in_library
                                        },
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        },
                        leadingContent = {
                            if (viewModel.selected.isNotEmpty()) Checkbox(video.id in viewModel.selected, { viewModel.toggle(video.id) })
                            else CoverImage(video.coverUrl, shape = MaterialTheme.shapes.small)
                        },
                        trailingContent = {
                            if (viewModel.selected.isEmpty()) {
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.a11y_video_actions)) }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(if (track != null) R.string.youtube_in_library else R.string.youtube_add_to_library)) },
                                            enabled = track == null,
                                            onClick = { menu = false; viewModel.save(video) },
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.combinedClickable(
                            onClick = { if (viewModel.selected.isNotEmpty()) viewModel.toggle(video.id) else viewModel.play(video) },
                            onLongClick = { viewModel.toggle(video.id) },
                        ),
                    )
                }
            }
        }
    }
}

class YouTubeAccountViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    var saved by mutableStateOf(!graph.credentials.youtubeCookie().isNullOrBlank()); private set
    var status by mutableStateOf(
        app.getString(if (saved) R.string.youtube_saved_login else R.string.youtube_no_login),
    ); private set
    private var job: Job? = null
    fun savedCookie(): String = graph.credentials.youtubeCookie().orEmpty()
    fun save(raw: String): Boolean {
        val cookie = normalizeYouTubeCookie(raw)
        if (cookie.isBlank() || !cookie.contains('=')) {
            status = getApplication<Application>().getString(R.string.youtube_cookie_hint)
            return false
        }
        job?.cancel()
        graph.credentials.saveYouTubeCookie(cookie); graph.youtube.resetSession(); saved = true
        status = getApplication<Application>().getString(R.string.youtube_saved_confirming)
        job = viewModelScope.launch {
            try { status = getApplication<Application>().resolve(graph.youtube.accountStatus()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = getApplication<Application>().getString(R.string.youtube_saved_offline) }
        }
        return true
    }
    fun clear() {
        job?.cancel(); graph.credentials.saveYouTubeCookie(""); graph.youtube.resetSession()
        saved = false; status = getApplication<Application>().getString(R.string.youtube_logged_out)
    }
}

@Composable
fun YouTubeAccountCard(onOpenList: (String) -> Unit, viewModel: YouTubeAccountViewModel = viewModel()) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    AccountCard(title = "YouTube", loggedIn = viewModel.saved) {
        if (viewModel.status.isNotBlank()) {
            Text(
                viewModel.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row {
            TextButton(onClick = { editing = true }) { Text(stringResource(if (viewModel.saved) R.string.youtube_update_cookie else R.string.youtube_save_cookie)) }
            TextButton(onClick = { confirmClear = true }, enabled = viewModel.saved) { Text(stringResource(R.string.youtube_logout)) }
        }
        Row {
            TextButton(onClick = { onOpenList("LL") }) { Text(stringResource(R.string.youtube_liked_title)) }
            TextButton(onClick = { onOpenList("WL") }) { Text(stringResource(R.string.youtube_watch_later_title)) }
        }
        Text(stringResource(R.string.youtube_cookie_help), style = MaterialTheme.typography.bodySmall)
    }
    if (editing) {
        var draft by remember { mutableStateOf(viewModel.savedCookie()) }
        var showSecret by rememberSaveable { mutableStateOf(false) }
        val normalized = normalizeYouTubeCookie(draft)
        val invalid = draft.isNotBlank() && (normalized.isBlank() || !normalized.contains('='))
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.youtube_login_info_title)) },
            text = {
                SecretTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = stringResource(R.string.youtube_cookie_label),
                    visible = showSecret,
                    onToggleVisible = { showSecret = !showSecret },
                    singleLine = false,
                    isError = invalid,
                    supportingText = {
                        Text(
                            stringResource(if (invalid) R.string.youtube_cookie_invalid else R.string.youtube_cookie_hint),
                            color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { if (viewModel.save(draft)) editing = false },
                    enabled = draft.isNotBlank() && !invalid,
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (confirmClear) {
        ConfirmActionDialog(
            title = stringResource(R.string.youtube_logout_title),
            message = stringResource(R.string.youtube_logout_message),
            confirmLabel = stringResource(R.string.youtube_logout),
            onConfirm = { confirmClear = false; viewModel.clear() },
            onDismiss = { confirmClear = false },
        )
    }
}

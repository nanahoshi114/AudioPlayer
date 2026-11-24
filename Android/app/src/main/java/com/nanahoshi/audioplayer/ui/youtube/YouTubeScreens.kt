package com.nanahoshi.audioplayer.ui.youtube

import com.nanahoshi.audioplayer.text.resolveUserError
import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.resolve
import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.components.AccountCard
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.ConfirmActionDialog
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.SearchField
import com.nanahoshi.audioplayer.ui.components.SelectionAction
import com.nanahoshi.audioplayer.ui.components.SelectionTopBar
import com.nanahoshi.audioplayer.ui.components.TopBarOverride
import com.nanahoshi.audioplayer.ui.formatDuration
import com.nanahoshi.audioplayer.youtube.*
import kotlinx.coroutines.*

class YouTubeViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    var query by mutableStateOf("")
    var videos by mutableStateOf<List<YouTubeVideo>>(emptyList()); private set
    var selected by mutableStateOf<Set<String>>(emptySet()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var partial by mutableStateOf(false); private set
    val tracks = graph.db.tracks().observeAll()
    val downloads = graph.downloads.observe()
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
                    videos = emptyList(); selected = emptySet(); error = null; partial = false
                }
            }
        }
    }
    fun cancelSearch() { generation++; request?.cancel(); request = null; loading = false }
    fun search(listId: String? = null) {
        searchRevision++
        cancelSearch()
        val token = generation
        loading = true; error = null; partial = false; selected = emptySet(); videos = emptyList()
        request = viewModelScope.launch {
            try {
                val result = if (listId != null) graph.youtube.playlist(listId) else graph.youtube.lookup(query)
                if (token == generation) { videos = result.videos; partial = result.partial }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == generation) error = getApplication<Application>().resolveUserError(failure, R.string.youtube_error_read_failed)
            } finally { if (token == generation) loading = false }
        }
    }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    fun clearSelection() { selected = emptySet() }
    fun selectAll() { selected = if (selected.size == videos.size) emptySet() else videos.map { it.id }.toSet() }
    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { graph.show(R.string.youtube_error_operation_failed) }
        }
    }
    fun play(video: YouTubeVideo) = action {
        val track = graph.db.tracks().findYouTube(video.id)
        if (track != null) graph.player.replaceAndPlay(listOf(track)) else graph.player.replaceAndPlayRemote(listOf(
            RemotePlayable(video.title, video.author, video.coverUrl, video.watchUrl, TrackSource.YOUTUBE,
                "https://www.youtube.com/", video.durationMs, video.id, "audio"),
        ))
    }
    fun saveSelection() {
        if (saving) return
        saving = true
        action {
            try {
                val targets = videos.filter { it.id in selected }
                var existing = 0
                for (video in targets) {
                    if (graph.db.tracks().findYouTube(video.id) != null) existing++
                }
                targets.forEach { graph.library.saveYouTube(it) }
                // 只移除本次快照中已保存的条目，保留用户在这期间新勾选的内容。
                selected = selected - targets.map { it.id }.toSet()
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
            } finally {
                saving = false
            }
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
    val tracks by viewModel.tracks.collectAsStateWithLifecycle(emptyList())
    val jobs by viewModel.downloads.collectAsStateWithLifecycle(emptyList())
    val stored = tracks.filter { it.source == TrackSource.YOUTUBE }.associateBy { it.youtubeVideoId }
    val active = jobs.filter { it.source == TrackSource.YOUTUBE.name && it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING) }.map { it.workKey }.toSet()
    val listState = rememberLazyListState()
    com.nanahoshi.audioplayer.ui.components.ResetOnNewSearch(listState, viewModel.searchRevision)
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
                TextButton(onClick = { viewModel.search(playlistId) }) { Text(stringResource(if (playlistId == null) R.string.youtube_action_search else R.string.youtube_action_refresh)) }
            }
            if (!selecting && viewModel.videos.isNotEmpty()) TextButton(onClick = viewModel::selectAll) {
                Text(stringResource(R.string.youtube_select_all_loaded))
            }
        }
        if (viewModel.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        viewModel.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (viewModel.partial) Text(stringResource(R.string.youtube_partial), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        if (!viewModel.loading && viewModel.videos.isEmpty() && viewModel.error == null) {
            EmptyState(
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
        } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(viewModel.videos, key = { it.id }) { video ->
                val track = stored[video.id]
                val downloaded = track?.localUri?.let { it.startsWith("/") && java.io.File(it).isFile } == true
                val downloading = video.id in active
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
                                style = MaterialTheme.typography.labelSmall,
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
                                    DropdownMenuItem(text = { Text(stringResource(if (track != null) R.string.youtube_in_library else R.string.youtube_add_to_library)) }, enabled = track == null,
                                        onClick = { menu = false; viewModel.save(video) })
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
    var busy by mutableStateOf(false); private set
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
            busy = true
            try { status = getApplication<Application>().resolve(graph.youtube.accountStatus()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = getApplication<Application>().getString(R.string.youtube_saved_offline) }
            finally { busy = false }
        }
        return true
    }
    fun clear() {
        job?.cancel(); graph.credentials.saveYouTubeCookie(""); graph.youtube.resetSession()
        saved = false; busy = false; status = getApplication<Application>().getString(R.string.youtube_logged_out)
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
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(R.string.youtube_login_info_title)) },
            text = {
                OutlinedTextField(
                    draft,
                    { draft = it },
                    label = { Text(stringResource(R.string.youtube_cookie_label)) },
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    isError = invalid,
                    supportingText = {
                        Text(
                            stringResource(if (invalid) R.string.youtube_cookie_invalid else R.string.youtube_cookie_hint),
                            color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { showSecret = !showSecret }) {
                            Icon(
                                if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(
                                    if (showSecret) R.string.a11y_hide_password else R.string.a11y_show_password,
                                ),
                            )
                        }
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

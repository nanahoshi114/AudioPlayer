package com.nanahoshi.audioplayer.ui.bilibili

import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.text.TextRef
import androidx.compose.ui.res.stringResource
import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanahoshi.audioplayer.data.LocalRemovalPlan
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.canonicalPath
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.ui.components.*
import com.nanahoshi.audioplayer.ui.library.PlaylistPickDialog
import com.nanahoshi.audioplayer.ui.library.TrackRow
import com.nanahoshi.audioplayer.ui.library.deleteDownloadedNotice
import com.nanahoshi.audioplayer.ui.library.hasTargets
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

class BiliVideoViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    var folder by mutableStateOf<FolderEntity?>(null); private set
    var tracks by mutableStateOf<List<TrackEntity>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    var selected by mutableStateOf<Set<Long>>(emptySet()); private set
    val playlists = graph.library.observePlaylists()
    private var observer: Job? = null

    fun open(id: Long) {
        observer?.cancel()
        observer = viewModelScope.launch {
            combine(graph.library.observeFolder(id), graph.library.observeTracksIn(id)) { root, items -> root to items }
                .collect { (root, items) ->
                    folder = root?.takeIf { it.biliBvid != null }
                    tracks = items.filter { it.bvid == folder?.biliBvid }.sortedWith(compareBy({ it.page ?: Int.MAX_VALUE }, { it.id }))
                    selected = selected.intersect(tracks.map { it.id }.toSet())
                    loaded = true
                }
        }
    }
    fun toggle(id: Long) { selected = if (id in selected) selected - id else selected + id }
    fun clear() { selected = emptySet() }
    fun all() { selected = if (selected.size == tracks.size) emptySet() else tracks.map { it.id }.toSet() }
    private fun chosen() = tracks.filter { it.id in selected }
    fun play(track: TrackEntity) { graph.player.replaceAndPlay(tracks, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)) }
    fun playSelection() { graph.player.replaceAndPlay(chosen()); clear() }
    fun next(items: List<TrackEntity> = chosen()) { graph.player.playNext(items); clear() }
    fun append(items: List<TrackEntity> = chosen()) { graph.player.append(items); clear() }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { graph.showError(failure, R.string.bilibili_operation_failed) }
    } }
    fun download(items: List<TrackEntity> = chosen()) = action {
        val added = graph.downloads.enqueue(items)
        clear()
        graph.show(
            if (added > 0) TextRef.Res(R.string.bilibili_added_download_count, listOf(added))
            else TextRef.Res(R.string.bilibili_already_local_or_downloading),
        )
    }
    internal data class PendingDelete(
        val plan: LocalRemovalPlan,
        val audioCount: Int,
        val associationCount: Int,
        val generatedSubtitleCount: Int,
        val customSubtitleCount: Int,
    )
    internal var pendingDelete by mutableStateOf<PendingDelete?>(null); private set

    fun deleteDownloads(items: List<TrackEntity> = chosen()) = action {
        val ids = items.map { it.id }.toSet()
        if (ids.isEmpty()) return@action
        val plan = graph.library.localRemovalPlan(ids, emptySet(), emptySet(), TrackSource.BILIBILI)
        if (!plan.hasTargets) return@action
        val downloadGroups = plan.audioDownloads.groupBy { it.localUri?.let(::canonicalPath) ?: "id:${it.id}" }
        val audioCount = downloadGroups.size
        val associationCount = plan.audioAssociations.distinctBy { it.id }.size
        val generatedSubtitleCount = downloadGroups.values.count { group ->
            group.any { graph.subtitles.hasRemovableSubtitlesForTrack(it) }
        }
        val customSubtitleCount = plan.customSubtitlePaths.distinct().size
        if (generatedSubtitleCount > 0 || customSubtitleCount > 0 || (audioCount > 0 && associationCount > 0)) {
            pendingDelete = PendingDelete(
                plan = plan,
                audioCount = audioCount,
                associationCount = associationCount,
                generatedSubtitleCount = generatedSubtitleCount,
                customSubtitleCount = customSubtitleCount,
            )
        } else {
            val result = graph.downloads.deleteLocalTargets(plan, deleteSubtitles = true, graph.subtitleBindings)
            clear()
            graph.show(deleteDownloadedNotice(result))
        }
    }
    fun confirmDelete(deleteSubtitles: Boolean) {
        val pending = pendingDelete ?: return
        pendingDelete = null
        action {
            val result = graph.downloads.deleteLocalTargets(pending.plan, deleteSubtitles, graph.subtitleBindings)
            clear()
            graph.show(deleteDownloadedNotice(result))
        }
    }
    fun cancelDelete() { pendingDelete = null }
    fun addToPlaylist(id: Long) = action { graph.library.addTracksToPlaylist(id, chosen().map { it.id }); clear() }
    fun remove() { folder?.let { graph.player.removeFromLibrary(emptyList(), listOf(it.id)) } }
}

@Composable
fun BiliVideoScreen(
    folderId: Long,
    onBack: () -> Unit,
    onSearchUploader: (String) -> Unit = {},
    viewModel: BiliVideoViewModel = viewModel(),
) {
    LaunchedEffect(folderId) { viewModel.open(folderId) }
    LaunchedEffect(viewModel.loaded, viewModel.folder) { if (viewModel.loaded && viewModel.folder == null) onBack() }
    val selecting = viewModel.selected.isNotEmpty()
    BackHandler(selecting) { viewModel.clear() }
    var menu by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var pickingPlaylist by remember { mutableStateOf(false) }
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(emptyList())
    val state = rememberLazyListState()
    val pageState = rememberScrollState()
    val title = stringResource(R.string.bilibili_video_title)
    val folder = viewModel.folder
    if (folder == null) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(title, onBack = onBack)
            LoadingState(stringResource(R.string.bilibili_reading_video))
        }
    } else {
        ImmersiveWorkScaffold(
            listState = state,
            pageState = pageState,
            selectionBar = if (selecting) {
                {
                    SelectionTopBar(
                        count = viewModel.selected.size,
                        onClose = viewModel::clear,
                        actions = listOf(
                            SelectionAction(stringResource(R.string.bilibili_play_selected), Icons.Default.PlayArrow, viewModel::playSelection),
                            SelectionAction(stringResource(R.string.library_play_next), Icons.Default.SkipNext, { viewModel.next() }),
                            SelectionAction(stringResource(R.string.library_add_queue), Icons.AutoMirrored.Filled.PlaylistAdd, { viewModel.append() }),
                            SelectionAction(stringResource(R.string.bilibili_download_track), Icons.Default.Download, { viewModel.download() }),
                            SelectionAction(stringResource(R.string.library_add_playlist), Icons.AutoMirrored.Filled.QueueMusic, { pickingPlaylist = true }),
                            SelectionAction(stringResource(R.string.bilibili_delete_downloads), Icons.Default.Delete, { viewModel.deleteDownloads() }, destructive = true),
                        ),
                    )
                }
            } else {
                null
            },
            topBar = { containerAlpha ->
                ImmersiveTopBar(
                    title = title,
                    containerAlpha = containerAlpha,
                    onBack = onBack,
                    actions = {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.a11y_video_actions))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_remove_from_library)) },
                                onClick = {
                                    menu = false
                                    removing = true
                                },
                            )
                        }
                    },
                )
            },
            header = { topBarInset, heroMeasureModifier ->
                BiliVideoHeroHeader(
                    title = folder.name,
                    bvid = folder.biliBvid.orEmpty(),
                    uploader = viewModel.tracks.firstOrNull()?.artist,
                    partCount = viewModel.tracks.size,
                    totalDurationText = formatDuration(viewModel.tracks.sumOf { it.durationMs }),
                    coverUrl = folder.coverUri,
                    topBarInset = topBarInset,
                    onSearchUploader = onSearchUploader,
                    modifier = heroMeasureModifier,
                )
                FilledTonalButton(
                    onClick = { viewModel.download(viewModel.tracks) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.bilibili_download_all_tracks))
                }
            },
            controls = {
                SectionHeader(stringResource(R.string.bilibili_parts)) {
                    val allSelected = selecting && viewModel.selected.size == viewModel.tracks.size
                    TextButton(onClick = viewModel::all) {
                        Text(stringResource(if (allSelected) R.string.bilibili_deselect_all else R.string.bilibili_select_all))
                    }
                }
            },
        ) {
            items(viewModel.tracks, key = { it.id }) { track ->
                TrackRow(
                    track = track.copy(title = "P${track.page ?: 1} · ${track.title.removePrefix(folder.name + " - ")}"),
                    hasSubtitle = false,
                    selected = track.id in viewModel.selected,
                    selecting = selecting,
                    onClick = { if (selecting) viewModel.toggle(track.id) else viewModel.play(track) },
                    onLongClick = { viewModel.toggle(track.id) },
                    onAppend = { viewModel.append(listOf(track)) },
                    onPlayNext = { viewModel.next(listOf(track)) },
                    onDownload = { viewModel.download(listOf(track)) },
                    onDeleteDownloaded = { viewModel.deleteDownloads(listOf(track)) },
                    onDelete = null,
                )
            }
        }
    }
    if (pickingPlaylist) PlaylistPickDialog(playlists,
        onPick = { pickingPlaylist = false; viewModel.addToPlaylist(it) }, onDismiss = { pickingPlaylist = false })
    if (removing) {
        ConfirmActionDialog(
            title = stringResource(R.string.library_remove_from_library),
            message = stringResource(R.string.bilibili_remove_video_message),
            confirmLabel = stringResource(R.string.common_remove),
            onConfirm = { removing = false; viewModel.remove() },
            onDismiss = { removing = false },
        )
    }
    viewModel.pendingDelete?.let { pending ->
        com.nanahoshi.audioplayer.ui.components.DeleteAudioDialog(
            itemCount = pending.audioCount,
            associationCount = pending.associationCount,
            subtitleCount = pending.generatedSubtitleCount,
            customSubtitleCount = pending.customSubtitleCount,
            onConfirm = viewModel::confirmDelete,
            onCancel = viewModel::cancelDelete,
        )
    }
}

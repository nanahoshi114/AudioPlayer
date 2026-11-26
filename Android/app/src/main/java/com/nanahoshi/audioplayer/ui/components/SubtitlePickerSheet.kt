package com.nanahoshi.audioplayer.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanahoshi.audioplayer.AppGraph
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.BindExternalResult
import com.nanahoshi.audioplayer.data.LocalAudio
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleBindingEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleKind
import com.nanahoshi.audioplayer.data.isSubtitleFile
import com.nanahoshi.audioplayer.playback.SubtitleMatchTier
import com.nanahoshi.audioplayer.playback.WorkSubtitleCandidate
import com.nanahoshi.audioplayer.playback.WorkSubtitleScope
import com.nanahoshi.audioplayer.ui.library.OpenPersistableDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface SubtitlePickerActions {
    fun observeBinding(trackId: Long): Flow<TrackSubtitleBindingEntity?>
    suspend fun loadCandidates(track: TrackEntity): List<WorkSubtitleCandidate>
    suspend fun hasGenerated(track: TrackEntity): Boolean
    suspend fun bindLibraryFile(trackId: Long, fileId: Long, displayName: String? = null)
    suspend fun bindExternalUri(trackId: Long, uri: Uri, displayName: String): BindExternalResult
    suspend fun importAndBindCopy(trackId: Long, uri: Uri, displayName: String): Boolean
    suspend fun bindGenerated(trackId: Long)
    suspend fun bindNone(trackId: Long)
    suspend fun clearBinding(trackId: Long)
    /** 串行写入时间轴偏移；不得在弹层自己的 scope 里调用 setOffsetMs。 */
    val offsetWriter: SubtitleOffsetWriter
    fun showMessage(@StringRes id: Int)
}

internal fun AppGraph.subtitlePickerActions(scope: CoroutineScope): SubtitlePickerActions =
    object : SubtitlePickerActions {
        override fun observeBinding(trackId: Long) = db.trackSubtitles().observeForTrack(trackId)

        override suspend fun loadCandidates(track: TrackEntity): List<WorkSubtitleCandidate> =
            subtitleBindings.listWorkSubtitleCandidates(track)

        override suspend fun hasGenerated(track: TrackEntity): Boolean =
            subtitles.hasGeneratedForTrack(track)

        override suspend fun bindLibraryFile(trackId: Long, fileId: Long, displayName: String?) {
            offsetWriter.clear(trackId)
            subtitleBindings.bindLibraryFile(trackId, fileId, displayName)
        }

        override suspend fun bindExternalUri(trackId: Long, uri: Uri, displayName: String): BindExternalResult =
            subtitleBindings.bindExternalUri(trackId, uri, displayName).also {
                if (it == BindExternalResult.Bound) offsetWriter.clear(trackId)
            }

        override suspend fun importAndBindCopy(trackId: Long, uri: Uri, displayName: String): Boolean =
            subtitleBindings.importAndBindCopy(trackId, uri, displayName).also { imported ->
                if (imported) offsetWriter.clear(trackId)
            }

        override suspend fun bindGenerated(trackId: Long) {
            offsetWriter.clear(trackId)
            subtitleBindings.bindGenerated(trackId)
        }

        override suspend fun bindNone(trackId: Long) {
            offsetWriter.clear(trackId)
            subtitleBindings.bindNone(trackId)
        }

        override suspend fun clearBinding(trackId: Long) {
            offsetWriter.clear(trackId)
            subtitleBindings.clearBinding(trackId)
        }

        // 长生命周期写入器：关闭字幕弹层不会取消尚未落盘的偏移。
        override val offsetWriter = SubtitleOffsetWriter(
            scope,
            persist = { trackId, offsetMs -> subtitleBindings.setOffsetMs(trackId, offsetMs) },
            onError = { show(R.string.library_operation_failed) },
        )

        override fun showMessage(id: Int) = show(id)
    }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TrackSubtitlePickerSheet(
    track: TrackEntity?,
    trackTitle: String,
    actions: SubtitlePickerActions,
    onDismiss: () -> Unit,
    loading: Boolean = false,
) {
    val libraryTrack = track?.takeIf { it.id > 0L }
    var busy by remember(libraryTrack?.id) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !busy },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
    ) {
        ModalSheetHeader(
            title = stringResource(R.string.subtitle_picker_title),
            subtitle = trackTitle,
            closeEnabled = !busy,
            onDismiss = onDismiss,
        )
        if (loading) {
            LoadingState(
                stringResource(R.string.library_file_loading),
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp)
                    .navigationBarsPadding(),
            )
            return@ModalBottomSheet
        }
        if (libraryTrack == null) {
            EmptyState(
                title = stringResource(R.string.subtitle_picker_library_required),
                actionLabel = stringResource(R.string.common_close),
                onAction = onDismiss,
                modifier = Modifier.navigationBarsPadding(),
            )
            return@ModalBottomSheet
        }

        val binding by actions.observeBinding(libraryTrack.id).collectAsStateWithLifecycle(initialValue = null)
        var candidates by remember(libraryTrack.id) { mutableStateOf<List<WorkSubtitleCandidate>?>(null) }
        var hasGenerated by remember(libraryTrack.id) { mutableStateOf(false) }
        var sourceFilter by remember(libraryTrack.id) { mutableStateOf<TrackSource?>(null) }
        var pendingExternalChoice by remember(libraryTrack.id) { mutableStateOf<Pair<Uri, String>?>(null) }
        var needsCopyPrompt by remember(libraryTrack.id) { mutableStateOf<Pair<Uri, String>?>(null) }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        LaunchedEffect(libraryTrack.id) {
            try {
                hasGenerated = actions.hasGenerated(libraryTrack)
                candidates = actions.loadCandidates(libraryTrack)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                candidates = emptyList()
                actions.showMessage(R.string.library_operation_failed)
            }
        }

        fun runAction(closeAfter: Boolean = true, block: suspend () -> Boolean) {
            if (busy) return
            busy = true
            scope.launch {
                try {
                    if (block() && closeAfter) onDismiss()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    actions.showMessage(R.string.library_operation_failed)
                } finally {
                    busy = false
                }
            }
        }

        val openDocument = rememberLauncherForActivityResult(OpenPersistableDocument()) { uri ->
            if (uri == null || busy) return@rememberLauncherForActivityResult
            busy = true
            scope.launch {
                try {
                    val displayName = withContext(Dispatchers.IO) { LocalAudio.displayName(context, uri) }
                    if (!isSubtitleFile(displayName)) {
                        actions.showMessage(R.string.subtitle_picker_unsupported_format)
                    } else {
                        pendingExternalChoice = uri to displayName
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    actions.showMessage(R.string.subtitle_picker_import_failed)
                } finally {
                    busy = false
                }
            }
        }

        val allCandidates = candidates.orEmpty()
        val availableSources = remember(allCandidates) { allCandidates.mapNotNull { it.source }.distinct() }
        val filteredCandidates = remember(allCandidates, sourceFilter) {
            val activeFilter = sourceFilter
            if (activeFilter == null) allCandidates else allCandidates.filter { it.source == activeFilter }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            val currentBinding = binding
            if (currentBinding != null && currentBinding.kind != TrackSubtitleKind.NONE) {
                item(key = "offset") {
                    val settledOffsetMs = currentBinding.offsetMs ?: 0L
                    val pendingOffsets by actions.offsetWriter.pendingOffsets
                        .collectAsStateWithLifecycle()
                    // 用写入器持有的乐观值渲染，避免连续点击时从旧落盘值反复回弹。
                    LaunchedEffect(libraryTrack.id, settledOffsetMs) {
                        actions.offsetWriter.sync(libraryTrack.id, settledOffsetMs)
                    }
                    SubtitleOffsetCard(
                        offsetMs = pendingOffsets[libraryTrack.id] ?: settledOffsetMs,
                        enabled = !busy,
                        onDelta = { delta ->
                            actions.offsetWriter.step(libraryTrack.id, settledOffsetMs, delta)
                        },
                        onReset = {
                            actions.offsetWriter.reset(libraryTrack.id, settledOffsetMs)
                        },
                    )
                }
            }

            item(key = "auto") {
                val selected = currentBinding == null
                PickerOptionRow(
                    icon = Icons.Default.Tune,
                    title = stringResource(R.string.subtitle_picker_auto),
                    summary = stringResource(R.string.subtitle_picker_auto_summary),
                    selected = selected,
                    enabled = !busy,
                    onClick = {
                        runAction {
                            actions.clearBinding(libraryTrack.id)
                            true
                        }
                    },
                )
            }

            if (hasGenerated || currentBinding?.kind == TrackSubtitleKind.GENERATED) {
                item(key = "generated") {
                    val selected = currentBinding?.kind == TrackSubtitleKind.GENERATED
                    PickerOptionRow(
                        icon = Icons.Default.Translate,
                        title = stringResource(R.string.subtitle_picker_generated),
                        summary = stringResource(R.string.subtitle_picker_generated_summary),
                        selected = selected,
                        enabled = !busy,
                        onClick = {
                            runAction {
                                actions.bindGenerated(libraryTrack.id)
                                true
                            }
                        },
                    )
                }
            }

            item(key = "external") {
                val isExternal = currentBinding?.kind == TrackSubtitleKind.EXTERNAL_URI
                val isAppCopy = currentBinding?.kind == TrackSubtitleKind.APP_FILE
                val externalSelected = isExternal || isAppCopy
                ListItem(
                    headlineContent = { Text(stringResource(R.string.subtitle_picker_external_pick)) },
                    supportingContent = if (externalSelected) {
                        {
                            Column {
                                Text(
                                    stringResource(
                                        if (isExternal) R.string.subtitle_picker_external_bound
                                        else R.string.subtitle_picker_app_copy_bound,
                                        currentBinding?.displayName.orEmpty(),
                                    ),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(4.dp))
                                LabelBadge(
                                    stringResource(
                                        if (isExternal) R.string.subtitle_picker_badge_external
                                        else R.string.subtitle_picker_badge_app_copy,
                                    ),
                                    MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    } else null,
                    leadingContent = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                    trailingContent = if (externalSelected) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null,
                    colors = ListItemDefaults.colors(
                        containerColor = if (externalSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable(enabled = !busy) { openDocument.launch(arrayOf("*/*")) },
                )
            }

            item(key = "none") {
                val selected = currentBinding?.kind == TrackSubtitleKind.NONE
                PickerOptionRow(
                    icon = Icons.Default.Block,
                    title = stringResource(R.string.subtitle_picker_none),
                    summary = stringResource(R.string.subtitle_picker_none_summary),
                    selected = selected,
                    enabled = !busy,
                    onClick = {
                        runAction {
                            actions.bindNone(libraryTrack.id)
                            true
                        }
                    },
                )
            }

            item(key = "work-header") {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                SectionHeader(
                    title = stringResource(R.string.subtitle_picker_work_section),
                    containerColor = Color.Transparent,
                )
                if (availableSources.size > 1) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = sourceFilter == null,
                            onClick = { sourceFilter = null },
                            label = {
                                Text(
                                    stringResource(R.string.subtitle_picker_source_all),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        )
                        availableSources.forEach { src ->
                            FilterChip(
                                selected = sourceFilter == src,
                                onClick = { sourceFilter = if (sourceFilter == src) null else src },
                                label = {
                                    Text(
                                        sourceDisplayLabel(src),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            )
                        }
                    }
                }
            }

            when {
                candidates == null -> item(key = "work-loading") {
                    LoadingState(
                        stringResource(R.string.library_file_loading),
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                    )
                }
                filteredCandidates.isEmpty() -> item(key = "work-empty") {
                    Text(
                        stringResource(R.string.subtitle_picker_work_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                else -> items(filteredCandidates, key = { "cand-${it.file.id}" }) { candidate ->
                    val selected = currentBinding?.kind == TrackSubtitleKind.LIBRARY_FILE &&
                        currentBinding.fileId == candidate.file.id
                    ListItem(
                        headlineContent = {
                            Text(
                                candidate.file.name,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Column {
                                if (candidate.relativePath != candidate.file.name) {
                                    Text(
                                        candidate.relativePath,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    candidate.tier?.let { tier ->
                                        LabelBadge(matchTierLabel(tier), MaterialTheme.colorScheme.primary)
                                    }
                                    candidate.source?.let { src ->
                                        SourceBadge(src)
                                    }
                                    if (candidate.scope == WorkSubtitleScope.CROSS_SOURCE) {
                                        LabelBadge(
                                            stringResource(R.string.subtitle_picker_badge_cross_source),
                                            MaterialTheme.colorScheme.secondary,
                                        )
                                    }
                                }
                            }
                        },
                        leadingContent = { Icon(Icons.Default.Subtitles, contentDescription = null) },
                        trailingContent = if (selected) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null,
                        colors = ListItemDefaults.colors(
                            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable(enabled = !busy) {
                                runAction {
                                    actions.bindLibraryFile(libraryTrack.id, candidate.file.id, candidate.file.name)
                                    true
                                }
                            },
                    )
                }
            }
        }

        pendingExternalChoice?.let { (uri, displayName) ->
            AlertDialog(
                onDismissRequest = { if (!busy) pendingExternalChoice = null },
                title = { Text(displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                text = { Text(stringResource(R.string.subtitle_picker_external_mode_message)) },
                confirmButton = {},
                dismissButton = {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.End,
                    ) {
                        TextButton(
                            enabled = !busy,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            onClick = {
                                pendingExternalChoice = null
                                runAction(closeAfter = true) {
                                    when (actions.bindExternalUri(libraryTrack.id, uri, displayName)) {
                                        BindExternalResult.Bound -> true
                                        BindExternalResult.NeedsAppCopy -> {
                                            needsCopyPrompt = uri to displayName
                                            false
                                        }
                                    }
                                }
                            },
                        ) { Text(stringResource(R.string.subtitle_picker_link_original)) }
                        TextButton(
                            enabled = !busy,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            onClick = {
                                pendingExternalChoice = null
                                runAction(closeAfter = true) {
                                    val ok = actions.importAndBindCopy(libraryTrack.id, uri, displayName)
                                    if (!ok) actions.showMessage(R.string.subtitle_picker_import_failed)
                                    ok
                                }
                            },
                        ) { Text(stringResource(R.string.subtitle_picker_import_copy_to_app)) }
                        TextButton(
                            enabled = !busy,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            onClick = { pendingExternalChoice = null },
                        ) { Text(stringResource(R.string.common_cancel)) }
                    }
                },
            )
        }

        needsCopyPrompt?.let { (uri, displayName) ->
            ConfirmActionDialog(
                title = stringResource(R.string.subtitle_picker_needs_copy_title),
                message = stringResource(R.string.subtitle_picker_needs_copy_message),
                confirmLabel = stringResource(R.string.subtitle_picker_import_copy),
                destructive = false,
                enabled = !busy,
                onConfirm = {
                    needsCopyPrompt = null
                    runAction(closeAfter = true) {
                        val ok = actions.importAndBindCopy(libraryTrack.id, uri, displayName)
                        if (!ok) actions.showMessage(R.string.subtitle_picker_import_failed)
                        ok
                    }
                },
                onDismiss = { needsCopyPrompt = null },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FileToTrackSubtitleSheet(
    file: LibraryFileEntity,
    loadTracks: suspend () -> List<Pair<TrackEntity, TrackSubtitleBindingEntity?>>,
    onBindTrack: suspend (TrackEntity) -> Unit,
    onDismiss: () -> Unit,
    onError: () -> Unit = {},
) {
    var rows by remember(file.id) { mutableStateOf<List<Pair<TrackEntity, TrackSubtitleBindingEntity?>>?>(null) }
    var loadFailed by remember(file.id) { mutableStateOf(false) }
    var reloadKey by remember(file.id) { mutableStateOf(0) }
    var busy by remember(file.id) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !busy },
    )
    val scope = rememberCoroutineScope()

    LaunchedEffect(file.id, reloadKey) {
        rows = null
        loadFailed = false
        try {
            rows = loadTracks()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
    ) {
        ModalSheetHeader(
            title = stringResource(R.string.subtitle_file_bind_title),
            subtitle = file.name,
            closeEnabled = !busy,
            onDismiss = onDismiss,
        )
        val loadedRows = rows
        when {
            loadFailed -> ErrorState(
                message = stringResource(R.string.library_operation_failed),
                onRetry = { reloadKey++ },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            )
            loadedRows == null -> LoadingState(
                stringResource(R.string.library_file_loading),
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp)
                    .navigationBarsPadding(),
            )
            loadedRows.isEmpty() -> EmptyState(
                title = stringResource(R.string.subtitle_file_bind_empty),
                modifier = Modifier.navigationBarsPadding(),
            )
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(loadedRows, key = { (track, _) -> track.id }) { (track, existing) ->
                    val isCurrent = existing?.kind == TrackSubtitleKind.LIBRARY_FILE && existing.fileId == file.id
                    val hasOther = existing != null && existing.kind != TrackSubtitleKind.NONE && !isCurrent
                    ListItem(
                        headlineContent = {
                            Text(
                                track.title,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                SourceBadge(track.source)
                                if (isCurrent) {
                                    LabelBadge(
                                        stringResource(R.string.subtitle_file_bind_current),
                                        MaterialTheme.colorScheme.primary,
                                    )
                                } else if (hasOther) {
                                    LabelBadge(
                                        stringResource(R.string.subtitle_file_bind_other),
                                        MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                        },
                        leadingContent = { Icon(Icons.Default.Description, contentDescription = null) },
                        trailingContent = if (isCurrent) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null,
                        colors = ListItemDefaults.colors(
                            containerColor = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable(enabled = !busy) {
                                busy = true
                                scope.launch {
                                    try {
                                        onBindTrack(track)
                                        onDismiss()
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        // 保持表单打开以便用户重试，并明确提示失败。
                                        onError()
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtitleOffsetCard(
    offsetMs: Long,
    enabled: Boolean,
    onDelta: (Long) -> Unit,
    onReset: () -> Unit,
) {
    val label = if (offsetMs == 0L) {
        stringResource(R.string.subtitle_picker_offset_zero)
    } else {
        stringResource(R.string.subtitle_picker_offset_value, offsetMs / 1000f)
    }
    val minusDesc = stringResource(R.string.a11y_subtitle_offset_minus)
    val plusDesc = stringResource(R.string.a11y_subtitle_offset_plus)
    val resetDesc = stringResource(R.string.a11y_subtitle_offset_reset)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.subtitle_picker_offset_title),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(
                    onClick = { onDelta(-500L) },
                    enabled = enabled,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = minusDesc },
                ) {
                    Text(
                        stringResource(R.string.subtitle_picker_offset_minus),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = { onDelta(500L) },
                    enabled = enabled,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = plusDesc },
                ) {
                    Text(
                        stringResource(R.string.subtitle_picker_offset_plus),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = onReset,
                    enabled = enabled && offsetMs != 0L,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = resetDesc },
                ) {
                    Text(
                        stringResource(R.string.subtitle_picker_offset_reset),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null) }
        } else null,
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun matchTierLabel(tier: SubtitleMatchTier): String = when (tier) {
    SubtitleMatchTier.EXACT -> stringResource(R.string.subtitle_picker_match_exact)
    SubtitleMatchTier.NORMALIZED -> stringResource(R.string.subtitle_picker_match_normalized)
    SubtitleMatchTier.TRACK_NUMBER -> stringResource(R.string.subtitle_picker_match_number)
}

@Composable
private fun sourceDisplayLabel(source: TrackSource): String = when (source) {
    TrackSource.YOUTUBE -> "YouTube"
    TrackSource.BILIBILI -> stringResource(R.string.library_badge_bilibili)
    TrackSource.JAPANESE_ASMR -> "Japanese ASMR"
    TrackSource.ASMR -> "asmr.one"
    TrackSource.DLSITE -> "DLsite"
    TrackSource.LOCAL -> stringResource(R.string.online_source_local)
}

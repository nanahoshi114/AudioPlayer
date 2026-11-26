package com.nanahoshi.audioplayer.ui.player

import com.nanahoshi.audioplayer.text.asString
import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.lazy.items
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.matches
import com.nanahoshi.audioplayer.data.operation
import com.nanahoshi.audioplayer.data.SubtitleTask
import com.nanahoshi.audioplayer.playback.SubtitleLanguageDelete
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.playback.PlayerUiState
import com.nanahoshi.audioplayer.data.TaskKey
import com.nanahoshi.audioplayer.data.TaskKind
import com.nanahoshi.audioplayer.data.TaskProgressSnapshot
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.data.fraction
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.playback.SubtitleCue
import com.nanahoshi.audioplayer.playback.hasTranslation
import com.nanahoshi.audioplayer.playback.indexAt
import com.nanahoshi.audioplayer.playback.line
import com.nanahoshi.audioplayer.playback.playbackProgress
import com.nanahoshi.audioplayer.playback.playbackSeekPosition
import com.nanahoshi.audioplayer.playback.textAt
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.ConfirmActionDialog
import com.nanahoshi.audioplayer.ui.components.CoverImage
import com.nanahoshi.audioplayer.ui.components.DeleteSubtitleLanguageDialog
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.InfoNoticeDialog
import com.nanahoshi.audioplayer.ui.components.ModalSheetHeader
import com.nanahoshi.audioplayer.ui.components.RoundedProgress
import com.nanahoshi.audioplayer.ui.components.SegmentedTabBar
import com.nanahoshi.audioplayer.ui.components.SourceBadge
import com.nanahoshi.audioplayer.ui.components.formatDuration
import com.nanahoshi.audioplayer.ui.components.pressScale
import com.nanahoshi.audioplayer.ui.components.rememberLocalNetworkAccess
import com.nanahoshi.audioplayer.ui.theme.LocalDarkTheme
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import com.nanahoshi.audioplayer.ui.theme.Motion
import com.nanahoshi.audioplayer.ui.theme.SubtitleLarge
import com.nanahoshi.audioplayer.ui.theme.SubtitleReader
import com.nanahoshi.audioplayer.ui.theme.pressSpec
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubtitleReader(
    mediaId: Long,
    cues: List<SubtitleCue>,
    positionMs: Long,
    chinese: Boolean,
    translating: Boolean,
    canDelete: Boolean,
    onChinese: () -> Unit,
    onJapanese: () -> Unit,
    onTranslate: () -> Unit,
    onStop: () -> Unit,
    polishing: Boolean,
    onPolish: () -> Unit,
    onStopPolish: () -> Unit,
    onDelete: () -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    canTranslate: Boolean,
    whisperJob: WhisperJobEntity? = null,
    whisperProgress: TaskProgressSnapshot? = null,
    onStopWhisper: () -> Unit = {},
    canSelectSubtitle: Boolean = false,
    hasManualBinding: Boolean = false,
    disabledByUser: Boolean = false,
    canRegenerate: Boolean = false,
    onSelectSubtitle: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onClearBinding: () -> Unit = {},
    canDeleteChinese: Boolean = false,
    canDeleteOriginal: Boolean = false,
    onDeleteChinese: () -> Unit = {},
    onDeleteOriginal: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val listState = key(mediaId) { rememberLazyListState() }
    val follow = remember(mediaId) { SubtitleFollowState() }
    val active = cues.indexAt(positionMs)
    val anchor = follow.anchorIndex(cues, positionMs)
    val autoTranslating = whisperJob?.let { TranslateMark.isActive(it.translateStatus) } == true
    var placed by remember(mediaId) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val reduce = LocalReduceMotion.current
    val scrolling = remember(follow) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y != 0f) follow.onUserScroll()
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(anchor, cues, follow.following, mediaId) {
        if (!follow.following || anchor < 0) return@LaunchedEffect
        listState.centerOn(anchor, animated = placed && !reduce)
        placed = true
    }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = stringResource(R.string.player_subtitles),
            onBack = onBack,
            actions = {
                when {
                    polishing -> TextButton(onClick = onStopPolish) { Text(stringResource(R.string.common_stop)) }
                    translating -> TextButton(onClick = onStop) { Text(stringResource(R.string.common_stop)) }
                    whisperJob != null || autoTranslating -> TextButton(onClick = onStopWhisper) { Text(stringResource(R.string.common_stop)) }
                    cues.hasTranslation() -> TextButton(onClick = onPolish) { Text(stringResource(R.string.player_polish)) }
                    cues.isNotEmpty() && canTranslate -> TextButton(onClick = onTranslate) { Text(stringResource(R.string.player_translate)) }
                }
                val regenerateAllowed = canRegenerate && !disabledByUser
                val hasMenu = canSelectSubtitle || regenerateAllowed || hasManualBinding ||
                    canDeleteChinese || canDeleteOriginal || canDelete
                if (hasMenu) {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.a11y_subtitle_actions))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (canSelectSubtitle) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.player_switch_subtitle)) },
                                onClick = {
                                    menu = false
                                    onSelectSubtitle()
                                },
                            )
                        }
                        if (regenerateAllowed) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.player_regenerate_subtitle)) },
                                onClick = {
                                    menu = false
                                    onRegenerate()
                                },
                            )
                        }
                        if (hasManualBinding) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.player_clear_subtitle_binding)) },
                                onClick = {
                                    menu = false
                                    onClearBinding()
                                },
                            )
                        }
                        if (canDeleteChinese) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.player_delete_chinese_subtitle), color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menu = false
                                    onDeleteChinese()
                                },
                            )
                        }
                        if (canDeleteOriginal) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.player_delete_original_subtitle), color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menu = false
                                    onDeleteOriginal()
                                },
                            )
                        }
                        if (canDelete) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.player_delete_generated_subtitle), color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menu = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            },
        )
        if (cues.hasTranslation()) {
            val chineseLabel = stringResource(R.string.player_chinese)
            val japaneseLabel = stringResource(R.string.player_japanese)
            SegmentedTabBar(
                options = listOf(true, false),
                selected = chinese,
                label = { if (it) chineseLabel else japaneseLabel },
                onSelect = { if (it) onChinese() else onJapanese() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (cues.isEmpty()) {
            if (whisperJob != null) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    WhisperProgress(
                        job = whisperJob,
                        progress = whisperProgress,
                        onStop = onStopWhisper,
                    )
                }
            } else if (disabledByUser) {
                EmptyState(
                    title = stringResource(R.string.player_subtitles),
                    message = stringResource(R.string.player_subtitle_disabled_notice),
                )
            } else {
                EmptyState(
                    title = stringResource(R.string.player_subtitle_empty_title),
                    message = stringResource(R.string.player_subtitle_empty_message),
                )
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().nestedScroll(scrolling),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 72.dp),
            ) {
                itemsIndexed(cues, key = { index, cue -> "$index-${cue.startMs}" }) { index, cue ->
                    val current = index == active
                    val color by animateColorAsState(
                        targetValue = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        animationSpec = if (reduce) snap() else tween(Motion.medium, easing = Motion.enter),
                        label = "cueColor",
                    )
                    Text(
                        cue.line(chinese),
                        color = color,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        style = SubtitleReader,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSeek(cue.startMs) }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }
            if (!follow.following) {
                FilledTonalButton(
                    onClick = follow::resume,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                ) { Text(stringResource(R.string.player_back_to_current)) }
            }
            }
        }
    }
}

private suspend fun LazyListState.centerOn(index: Int, animated: Boolean) {
    val viewport = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(0)
    val sample = layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
    val offset = if (viewport > 0 && sample > 0) -((viewport - sample) / 2) else 0
    if (animated) animateScrollToItem(index, offset) else scrollToItem(index, offset)
}

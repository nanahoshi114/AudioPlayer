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

@Composable
internal fun CueLine(text: String) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            if (reduce) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                fadeIn(tween(Motion.short, easing = Motion.enter)) togetherWith
                    fadeOut(tween(Motion.short, easing = Motion.exit))
            }
        },
        label = "subtitleLine",
    ) { line ->
        Text(
            line,
            style = SubtitleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth(),
        )
    }
}

@Composable
internal fun WhisperProgress(
    job: WhisperJobEntity,
    progress: TaskProgressSnapshot?,
    onStop: () -> Unit,
) {
    val translating = TranslateMark.isActive(job.translateStatus)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(
                if (translating) R.string.player_translating_subtitles else R.string.player_transcribing_subtitles,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        RoundedProgress(
            progress = progress?.fraction(),
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(onClick = onStop, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.common_stop))
        }
    }
}

@Composable
internal fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .padding(horizontal = 10.dp)
            .pressScale(interaction)
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(interactionSource = interaction, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = stringResource(if (playing) R.string.a11y_pause else R.string.a11y_play),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(36.dp),
        )
    }
}

@Composable
internal fun PlayerCollectionAction(
    row: com.nanahoshi.audioplayer.data.db.QueuedTrack?,
    link: com.nanahoshi.audioplayer.playback.PlayingBiliCollectionLink?,
    onOpen: (com.nanahoshi.audioplayer.bilibili.BiliCollection) -> Unit,
) {
    val collection = com.nanahoshi.audioplayer.playback.playingBiliCollection(row, link) ?: return
    SecondaryAction(
        icon = { Icon(Icons.Default.CollectionsBookmark, contentDescription = null) },
        label = stringResource(R.string.player_collection_label, collection.title),
        onClick = { onOpen(collection) },
        modifier = Modifier.widthIn(max = 240.dp),
        maxLines = 2,
    )
}

@Composable
internal fun SecondaryAction(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .widthIn(min = 48.dp)
            .pressScale(interaction)
            .clip(MaterialTheme.shapes.medium)
            .heightIn(min = 48.dp)
            .clickable(interactionSource = interaction, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 2.dp),
            maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun SeekBar(state: PlayerUiState, onSeek: (Long) -> Unit) {
    val duration = state.durationMs
    val canScrub = duration > 0L
    var dragValue by remember(state.queueItemId, canScrub) { mutableStateOf<Float?>(null) }
    val previewPosition = dragValue?.let { playbackSeekPosition(it, duration) }
    Column {
        Slider(
            value = dragValue ?: playbackProgress(state.positionMs, duration),
            enabled = canScrub,
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { playbackSeekPosition(it, duration) }?.let(onSeek)
                dragValue = null
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatDuration(previewPosition ?: state.positionMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (canScrub) formatDuration(duration) else stringResource(R.string.player_duration_unknown),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

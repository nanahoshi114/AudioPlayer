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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SleepTimerDialog(onDismiss: () -> Unit, onStart: (Long) -> Unit) {
    var minutes by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf("") }
    val input = validateSleepTimerInput(minutes, seconds)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_sleep_timer)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    sleepTimerPresets.forEach { preset ->
                        AssistChip(
                            onClick = {
                                minutes = preset.toString()
                                seconds = ""
                            },
                            label = { Text(stringResource(R.string.player_timer_preset_minutes, preset)) },
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { minutes = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.player_timer_minutes)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = seconds,
                        onValueChange = { seconds = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.player_timer_seconds)) },
                        singleLine = true,
                        isError = input.secondsOutOfRange,
                        supportingText = if (input.secondsOutOfRange) {
                            { Text(stringResource(R.string.player_timer_seconds_error)) }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.valid,
                onClick = { onStart(input.totalMs) },
            ) { Text(stringResource(R.string.player_start_timer)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

private val sleepTimerPresets = listOf(15, 30, 60)

/** 字幕选择弹层的目标曲目解析结果；Loading 期间不闪示「未入库曲目」。 */
internal sealed interface SubtitlePickerResolution {
    data object Loading : SubtitlePickerResolution
    data object Unlisted : SubtitlePickerResolution
    data class Ready(val track: TrackEntity) : SubtitlePickerResolution
}

internal enum class SubtitleDeleteMode { ALL_GENERATED, CHINESE_ONLY, ORIGINAL_ONLY }

internal data class PendingSubtitleDelete(
    val row: com.nanahoshi.audioplayer.data.db.QueuedTrack,
    val mode: SubtitleDeleteMode,
    val generatedFingerprint: String?,
    val overlayOutputName: String?,
)

@Composable
internal fun WhisperPromptDialog(viewModel: PlayerViewModel) {
    when (val prompt = viewModel.whisperPrompt) {
        WhisperPrompt.NeedSettings -> InfoNoticeDialog(
            message = stringResource(R.string.player_whisper_setup_help),
            confirmLabel = stringResource(R.string.player_got_it),
            onDismiss = viewModel::dismissWhisperPrompt,
        )
        is WhisperPrompt.NeedModelDownload -> InfoNoticeDialog(
            message = com.nanahoshi.audioplayer.data.missingOfflineWhisperModelMessage(prompt.model).asString(),
            confirmLabel = stringResource(R.string.player_got_it),
            onDismiss = viewModel::dismissWhisperPrompt,
        )
        is WhisperPrompt.NeedDownload -> ConfirmActionDialog(
            message = stringResource(R.string.player_download_for_subtitle),
            confirmLabel = stringResource(R.string.common_download),
            destructive = false,
            onConfirm = viewModel::confirmWhisperDownload,
            onDismiss = viewModel::dismissWhisperPrompt,
        )
        null -> Unit
    }
    if (viewModel.translatePrompt) {
        InfoNoticeDialog(
            message = stringResource(R.string.player_translate_setup_help),
            confirmLabel = stringResource(R.string.player_got_it),
            onDismiss = viewModel::dismissTranslatePrompt,
        )
    }
}

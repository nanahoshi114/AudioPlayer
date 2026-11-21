package com.nanahoshi.audioplayer.ui.settings

import com.nanahoshi.audioplayer.text.asString
import com.nanahoshi.audioplayer.data.whisperModelLabelRes
import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.ConfirmActionDialog
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.data.SENSE_VOICE_MODEL
import com.nanahoshi.audioplayer.data.ModelDownloadSource
import com.nanahoshi.audioplayer.data.OfflineModelDownloadService
import com.nanahoshi.audioplayer.data.OfflineWhisperModel
import com.nanahoshi.audioplayer.data.OfflineWhisperModelState
import com.nanahoshi.audioplayer.data.PARAKEET_MODEL
import com.nanahoshi.audioplayer.data.TranslatePlatform
import com.nanahoshi.audioplayer.data.WHISPER_MODEL_QWEN
import com.nanahoshi.audioplayer.data.WHISPER_MODEL_QWEN3
import com.nanahoshi.audioplayer.data.isCloudWhisperModel
import com.nanahoshi.audioplayer.data.isOnDeviceWhisperModel
import com.nanahoshi.audioplayer.data.whisperModelLabel
import com.nanahoshi.audioplayer.graph
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelSettingsScreen(
    onBack: () -> Unit,
    viewModel: ModelSettingsViewModel = viewModel(),
) {
    var whisperHelp by remember { mutableStateOf(false) }
    var translateHelp by remember { mutableStateOf(false) }
    if (whisperHelp) {
        WhisperHelpDialog(onDismiss = { whisperHelp = false })
    }
    if (translateHelp) {
        TranslateHelpDialog(onDismiss = { translateHelp = false })
    }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.settings_whisper_translate_title), onBack = onBack)
        LazyColumn(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "whisper") {
                Row(
                    Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_whisper_section), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { whisperHelp = true }) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = stringResource(R.string.a11y_whisper_help))
                    }
                }
                WhisperModelField(
                    model = viewModel.whisperModel,
                    onModel = viewModel::selectWhisperModel,
                )
                val offlineModel = OfflineWhisperModel.fromId(viewModel.whisperModel)
                if (offlineModel != null) {
                    val manager = viewModel.offlineModel(offlineModel)
                    val state by manager.state.collectAsState()
                    OfflineModelPanel(
                        model = offlineModel,
                        state = state,
                        onDownload = { viewModel.downloadOfflineModel(offlineModel, it) },
                        onCancel = manager::cancelDownload,
                        onDelete = { viewModel.deleteOfflineModel(offlineModel) },
                    )
                    Button(
                        onClick = viewModel::saveWhisper,
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(stringResource(R.string.common_save)) }
                } else {
                    if (!isCloudWhisperModel(viewModel.whisperModel)) {
                        OutlinedTextField(
                            value = viewModel.whisperBase,
                            onValueChange = { viewModel.whisperBase = it },
                            label = { Text(stringResource(R.string.settings_address_label)) },
                            singleLine = true,
                            supportingText = whisperAddressHint(viewModel.whisperModel),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = viewModel.whisperToken,
                        onValueChange = { viewModel.whisperToken = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = viewModel::saveWhisper) { Text(stringResource(R.string.common_save)) }
                        TextButton(onClick = viewModel::clearWhisper) { Text(stringResource(R.string.settings_clear)) }
                    }
                    SaveStatus(viewModel.whisperModified, viewModel.whisperSaved)
                }
            }
            item(key = "translate") {
                Row(
                    Modifier.padding(top = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_translate_section), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { translateHelp = true }) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = stringResource(R.string.a11y_translate_help))
                    }
                }
                TranslatePlatformField(
                    platform = viewModel.translatePlatform,
                    onPlatform = viewModel::selectTranslatePlatform,
                )
                OutlinedTextField(
                    value = viewModel.translateToken,
                    onValueChange = { viewModel.translateToken = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = viewModel.translateModel,
                    onValueChange = { viewModel.translateModel = it },
                    label = { Text(stringResource(R.string.settings_model_name_optional)) },
                    supportingText = { Text(stringResource(R.string.settings_model_default_hint, viewModel.translatePlatform.defaultModel)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_deep_thinking), modifier = Modifier.weight(1f))
                    Switch(
                        checked = viewModel.translateThinking,
                        onCheckedChange = viewModel::setThinking,
                    )
                }
                if (viewModel.translateThinking) {
                    Text(stringResource(R.string.settings_thinking_effort), modifier = Modifier.padding(top = 4.dp))
                    viewModel.translatePlatform.efforts.forEach { effort ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = effort.value == viewModel.translateEffort,
                                    onClick = { viewModel.setEffort(effort.value) },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = effort.value == viewModel.translateEffort,
                                onClick = null,
                            )
                            Text(stringResource(effort.labelRes), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_auto_polish), modifier = Modifier.weight(1f))
                    Switch(
                        checked = viewModel.translateAutoPolish,
                        onCheckedChange = viewModel::setAutoPolish,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_translate_tuning), modifier = Modifier.weight(1f))
                    Switch(
                        checked = viewModel.translateAutoCorrect,
                        onCheckedChange = viewModel::setAutoCorrect,
                    )
                }
                Text(
                    stringResource(R.string.settings_translate_tuning_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = viewModel::saveTranslate) { Text(stringResource(R.string.common_save)) }
                    TextButton(onClick = viewModel::clearTranslate) { Text(stringResource(R.string.settings_clear)) }
                }
                SaveStatus(viewModel.translateModified, viewModel.translateSaved)
            }
        }
    }
}

@Composable
private fun SaveStatus(modified: Boolean, configured: Boolean) {
    Text(
        when {
            modified -> stringResource(R.string.settings_unsaved)
            configured -> stringResource(R.string.settings_saved)
            else -> stringResource(R.string.settings_unconfigured)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (modified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

class ModelSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    internal fun offlineModel(model: OfflineWhisperModel) = graph.offlineWhisperModels.getValue(model)
    var whisperModel by mutableStateOf(graph.credentials.resolvedWhisperModel())
    var whisperBase by mutableStateOf(graph.credentials.whisperBaseUrl(whisperModel).orEmpty())
    var whisperToken by mutableStateOf(graph.credentials.whisperToken(whisperModel).orEmpty())
    var whisperSaved by mutableStateOf(graph.credentials.hasWhisper())
        private set
    var translatePlatform by mutableStateOf(graph.credentials.translatePlatform())
    var translateToken by mutableStateOf(graph.credentials.translateToken(translatePlatform).orEmpty())
    var translateModel by mutableStateOf(graph.credentials.translateModelInput(translatePlatform))
    var translateSaved by mutableStateOf(graph.credentials.translateToken(translatePlatform) != null)
        private set
    var translateThinking by mutableStateOf(graph.credentials.translateThinking())
        private set
    var translateEffort by mutableStateOf(graph.credentials.translateEffort(translatePlatform))
        private set
    var translateAutoPolish by mutableStateOf(graph.credentials.translateAutoPolish())
        private set
    var translateAutoCorrect by mutableStateOf(graph.credentials.translateAutoCorrect())
        private set

    private var whisperBaseline by mutableStateOf(currentWhisperInput())
    private var translateBaseline by mutableStateOf(currentTranslateInput())
    val whisperModified: Boolean
        get() = whisperBaseline.isModified(whisperBase, whisperToken)
    val translateModified: Boolean
        get() = translateBaseline.isModified(translateToken, translateModel)

    private fun currentWhisperInput() = SettingsInputBaseline(whisperBase, whisperToken)
    private fun currentTranslateInput() = SettingsInputBaseline(translateToken, translateModel)

    fun selectWhisperModel(id: String) {
        whisperModel = id
        if (isOnDeviceWhisperModel(id)) {
            whisperBase = ""
            whisperToken = ""
            whisperSaved = graph.whisper.missingOfflineModel(id) == null
        } else {
            whisperBase = graph.credentials.whisperBaseUrl(id).orEmpty()
            whisperToken = graph.credentials.whisperToken(id).orEmpty()
            whisperSaved = graph.credentials.hasWhisper(id)
        }
        whisperBaseline = currentWhisperInput()
    }

    fun saveWhisper() {
        graph.credentials.saveWhisper(whisperBase, whisperToken, whisperModel)
        whisperBase = graph.credentials.whisperBaseUrl(whisperModel).orEmpty()
        whisperToken = graph.credentials.whisperToken(whisperModel).orEmpty()
        whisperModel = graph.credentials.resolvedWhisperModel()
        whisperSaved = if (isOnDeviceWhisperModel(whisperModel)) {
            graph.whisper.missingOfflineModel(whisperModel) == null
        } else {
            graph.credentials.hasWhisper(whisperModel)
        }
        whisperBaseline = currentWhisperInput()
    }

    internal fun deleteOfflineModel(model: OfflineWhisperModel) {
        viewModelScope.launch {
            try {
                if (!graph.whisper.deleteOfflineModel(model)) {
                    graph.show(R.string.settings_model_in_use, whisperModelLabel(getApplication<Application>(), model.id))
                }
            } catch (error: com.nanahoshi.audioplayer.data.WhisperException) {
                graph.show(error.userText)
            }
        }
    }

    internal fun downloadOfflineModel(model: OfflineWhisperModel, source: ModelDownloadSource) {
        if (!OfflineModelDownloadService.start(getApplication(), model, source)) {
            graph.show(R.string.settings_model_start_failed)
        }
    }

    fun clearWhisper() {
        graph.credentials.clearWhisper(whisperModel)
        whisperBase = ""
        whisperToken = ""
        whisperSaved = false
        whisperBaseline = currentWhisperInput()
    }

    fun selectTranslatePlatform(platform: TranslatePlatform) {
        translatePlatform = platform
        translateToken = graph.credentials.translateToken(platform).orEmpty()
        translateModel = graph.credentials.translateModelInput(platform)
        translateEffort = graph.credentials.translateEffort(platform)
        translateSaved = graph.credentials.translateToken(platform) != null
        translateBaseline = currentTranslateInput()
    }

    fun saveTranslate() {
        graph.credentials.saveTranslate(translatePlatform, translateToken, translateModel)
        translatePlatform = graph.credentials.translatePlatform()
        translateToken = graph.credentials.translateToken(translatePlatform).orEmpty()
        translateModel = graph.credentials.translateModelInput(translatePlatform)
        translateSaved = graph.credentials.hasTranslate()
        translateBaseline = currentTranslateInput()
    }

    fun clearTranslate() {
        graph.credentials.clearTranslate(translatePlatform)
        translateToken = ""
        translateModel = ""
        translateSaved = false
        translateBaseline = currentTranslateInput()
    }

    fun setThinking(enabled: Boolean) {
        graph.credentials.saveTranslateThinking(enabled)
        translateThinking = graph.credentials.translateThinking()
    }

    fun setEffort(value: String) {
        graph.credentials.saveTranslateEffort(translatePlatform, value)
        translateEffort = graph.credentials.translateEffort(translatePlatform)
    }

    fun setAutoPolish(enabled: Boolean) {
        graph.credentials.saveTranslateAutoPolish(enabled)
        translateAutoPolish = graph.credentials.translateAutoPolish()
    }

    fun setAutoCorrect(enabled: Boolean) {
        graph.credentials.saveTranslateAutoCorrect(enabled)
        translateAutoCorrect = graph.credentials.translateAutoCorrect()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhisperModelField(model: String, onModel: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        OutlinedTextField(
            value = stringResource(whisperModelLabelRes(model) ?: R.string.settings_model_sensevoice),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.settings_model_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            listOf(
                WHISPER_MODEL_QWEN,
                WHISPER_MODEL_QWEN3,
                SENSE_VOICE_MODEL,
                PARAKEET_MODEL,
            ).forEach { id ->
                DropdownMenuItem(
                    text = { Text(stringResource(whisperModelLabelRes(id) ?: R.string.settings_model_sensevoice)) },
                    onClick = {
                        onModel(id)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslatePlatformField(platform: TranslatePlatform, onPlatform: (TranslatePlatform) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        OutlinedTextField(
            value = stringResource(platform.labelRes),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.settings_platform_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            TranslatePlatform.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(stringResource(item.labelRes)) },
                    onClick = {
                        onPlatform(item)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

private fun whisperAddressHint(model: String): (@Composable () -> Unit)? = when (model) {
    WHISPER_MODEL_QWEN3 -> {
        { Text(stringResource(R.string.settings_base_required)) }
    }
    else -> null
}

@Composable
private fun OfflineModelPanel(
    model: OfflineWhisperModel,
    state: OfflineWhisperModelState,
    onDownload: (ModelDownloadSource) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    var sourceDialog by rememberSaveable(model.id) { mutableStateOf(false) }
    if (sourceDialog) {
        ModelDownloadDialog(
            model = model,
            onDismiss = { sourceDialog = false },
            onDownload = {
                sourceDialog = false
                onDownload(it)
            },
        )
    }
    when (state) {
        OfflineWhisperModelState.NotDownloaded -> Column {
            Text(
                stringResource(R.string.settings_model_not_downloaded, model.installedMiB),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { sourceDialog = true }, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.settings_download_model))
            }
        }
        is OfflineWhisperModelState.Downloading -> Column {
            val fraction = if (state.bytesTotal > 0L) {
                (state.bytesDone.toFloat() / state.bytesTotal).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                "${state.source.label} · ${(fraction * 100).toInt()}% · ${state.bytesDone / 1024 / 1024} / ${state.bytesTotal / 1024 / 1024} MiB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onCancel, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.settings_cancel_download))
            }
        }
        OfflineWhisperModelState.Ready -> {
            var confirmDelete by rememberSaveable(model.id) { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_model_downloaded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.settings_delete_model)) }
            }
            if (confirmDelete) {
                ConfirmActionDialog(
                    title = stringResource(R.string.settings_delete_model_title),
                    message = stringResource(R.string.settings_delete_model_message, stringResource(whisperModelLabelRes(model.id) ?: R.string.settings_model_sensevoice)),
                    confirmLabel = stringResource(R.string.common_delete),
                    onConfirm = { confirmDelete = false; onDelete() },
                    onDismiss = { confirmDelete = false },
                )
            }
        }
        is OfflineWhisperModelState.Failed -> Column {
            Text(
                stringResource(R.string.settings_download_failed, state.message.asString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = { sourceDialog = true }, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.common_retry))
            }
        }
    }
}

@Composable
private fun ModelDownloadDialog(
    model: OfflineWhisperModel,
    onDismiss: () -> Unit,
    onDownload: (ModelDownloadSource) -> Unit,
) {
    var source by rememberSaveable(model.id) { mutableStateOf(ModelDownloadSource.GitHub) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_choose_model_source)) },
        text = {
            Column {
                ModelDownloadSource.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = source == option,
                            onClick = { source = option },
                            role = Role.RadioButton,
                        ).padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = source == option, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(option.label)
                            Text(
                                stringResource(R.string.settings_model_size_summary, model.downloadMiB(option), model.installedMiB),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDownload(source) }) { Text(stringResource(R.string.common_download)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun WhisperHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_whisper_section)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.settings_whisper_help_intro))
                Text(
                    stringResource(R.string.settings_whisper_help_qwen_audio),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    stringResource(R.string.settings_whisper_help_qwen3),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    stringResource(R.string.settings_whisper_help_offline),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    stringResource(R.string.settings_whisper_help_usage),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun TranslateHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_translate_section)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.settings_translate_help_intro))
                Text(
                    stringResource(R.string.settings_translate_help_thinking),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    stringResource(R.string.settings_translate_help_usage),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

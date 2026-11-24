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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    var confirmClearWhisper by remember { mutableStateOf(false) }
    var confirmClearTranslate by remember { mutableStateOf(false) }
    var showWhisperToken by rememberSaveable { mutableStateOf(false) }
    var showTranslateToken by rememberSaveable { mutableStateOf(false) }
    // 切换模型或平台后重新隐藏口令，避免复用上一项的可视状态。
    LaunchedEffect(viewModel.whisperModel) { showWhisperToken = false }
    LaunchedEffect(viewModel.translatePlatform) { showTranslateToken = false }
    if (whisperHelp) {
        WhisperHelpDialog(onDismiss = { whisperHelp = false })
    }
    if (translateHelp) {
        TranslateHelpDialog(onDismiss = { translateHelp = false })
    }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.settings_whisper_translate_title), onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "whisper") {
                SettingsCard(
                    title = stringResource(R.string.settings_whisper_card_title),
                    helpDescription = stringResource(R.string.a11y_whisper_help),
                    onHelp = { whisperHelp = true },
                ) {
                    WhisperModelField(
                        model = viewModel.whisperModel,
                        onModel = viewModel::selectWhisperModel,
                    )
                    val offlineModel = OfflineWhisperModel.fromId(viewModel.whisperModel)
                    if (offlineModel != null) {
                        val manager = viewModel.offlineModel(offlineModel)
                        val state by manager.state.collectAsStateWithLifecycle()
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
                        SaveStatus(viewModel.whisperModified, state == OfflineWhisperModelState.Ready)
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
                            label = { Text(stringResource(R.string.settings_api_key_label)) },
                            singleLine = true,
                            visualTransformation = if (showWhisperToken) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                SecretVisibilityIcon(visible = showWhisperToken, onToggle = { showWhisperToken = !showWhisperToken })
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        FlowRow(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = viewModel::saveWhisper) { Text(stringResource(R.string.common_save)) }
                            TextButton(onClick = { confirmClearWhisper = true }) { Text(stringResource(R.string.settings_clear)) }
                        }
                        SaveStatus(viewModel.whisperModified, viewModel.whisperSaved)
                    }
                }
            }
            item(key = "translate-config") {
                SettingsCard(
                    title = stringResource(R.string.settings_translate_card_title),
                    helpDescription = stringResource(R.string.a11y_translate_help),
                    onHelp = { translateHelp = true },
                ) {
                    TranslatePlatformField(
                        platform = viewModel.translatePlatform,
                        onPlatform = viewModel::selectTranslatePlatform,
                    )
                    OutlinedTextField(
                        value = viewModel.translateToken,
                        onValueChange = { viewModel.translateToken = it },
                        label = { Text(stringResource(R.string.settings_api_key_label)) },
                        singleLine = true,
                        visualTransformation = if (showTranslateToken) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            SecretVisibilityIcon(visible = showTranslateToken, onToggle = { showTranslateToken = !showTranslateToken })
                        },
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
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = viewModel::saveTranslate) { Text(stringResource(R.string.common_save)) }
                        TextButton(onClick = { confirmClearTranslate = true }) { Text(stringResource(R.string.settings_clear)) }
                    }
                    SaveStatus(viewModel.translateModified, viewModel.translateSaved)
                }
            }
            item(key = "translate-behavior") {
                SettingsCard(title = stringResource(R.string.settings_translate_behavior_title)) {
                    Text(
                        stringResource(
                            R.string.settings_translate_behavior_platform,
                            stringResource(viewModel.activeTranslatePlatform.labelRes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = viewModel.translateThinking,
                                role = Role.Switch,
                                onValueChange = viewModel::setThinking,
                            )
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.settings_deep_thinking), modifier = Modifier.weight(1f))
                        Switch(
                            checked = viewModel.translateThinking,
                            onCheckedChange = null,
                        )
                    }
                    if (viewModel.translateThinking) {
                        Text(stringResource(R.string.settings_thinking_effort), modifier = Modifier.padding(top = 4.dp))
                        viewModel.activeTranslatePlatform.efforts.forEach { effort ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
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
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = viewModel.translateAutoPolish,
                                role = Role.Switch,
                                onValueChange = viewModel::setAutoPolish,
                            )
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.settings_auto_polish), modifier = Modifier.weight(1f))
                        Switch(
                            checked = viewModel.translateAutoPolish,
                            onCheckedChange = null,
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = viewModel.translateAutoCorrect,
                                role = Role.Switch,
                                onValueChange = viewModel::setAutoCorrect,
                            )
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.settings_translate_tuning), modifier = Modifier.weight(1f))
                        Switch(
                            checked = viewModel.translateAutoCorrect,
                            onCheckedChange = null,
                        )
                    }
                    Text(
                        stringResource(R.string.settings_translate_tuning_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (confirmClearWhisper) {
        val model = stringResource(whisperModelLabelRes(viewModel.whisperModel) ?: R.string.settings_model_sensevoice)
        val address = viewModel.savedWhisperBase()
        ConfirmActionDialog(
            title = stringResource(R.string.settings_clear_title),
            message = if (address.isBlank()) {
                stringResource(R.string.settings_clear_whisper_message_no_address, model)
            } else {
                stringResource(R.string.settings_clear_whisper_message, model, address)
            },
            confirmLabel = stringResource(R.string.settings_clear),
            onConfirm = {
                confirmClearWhisper = false
                viewModel.clearWhisper()
            },
            onDismiss = { confirmClearWhisper = false },
        )
    }
    if (confirmClearTranslate) {
        ConfirmActionDialog(
            title = stringResource(R.string.settings_clear_title),
            message = stringResource(
                R.string.settings_clear_translate_message,
                stringResource(viewModel.translatePlatform.labelRes),
                viewModel.savedTranslateModel(),
            ),
            confirmLabel = stringResource(R.string.settings_clear),
            onConfirm = {
                confirmClearTranslate = false
                viewModel.clearTranslate()
            },
            onDismiss = { confirmClearTranslate = false },
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    helpDescription: String? = null,
    onHelp: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (onHelp != null && helpDescription != null) {
                    IconButton(onClick = onHelp) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = helpDescription)
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun SecretVisibilityIcon(visible: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            contentDescription = stringResource(
                if (visible) R.string.a11y_hide_password else R.string.a11y_show_password,
            ),
        )
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
    private val credentials = graph.credentials
    internal fun offlineModel(model: OfflineWhisperModel) = graph.offlineWhisperModels.getValue(model)
    var whisperModel by mutableStateOf(credentials.resolvedWhisperModel())
    var whisperBase by mutableStateOf(storedWhisperBase(whisperModel))
    var whisperToken by mutableStateOf(storedWhisperToken(whisperModel))
    var whisperSaved by mutableStateOf(credentials.hasWhisper(whisperModel))
        private set
    var translatePlatform by mutableStateOf(credentials.translatePlatform())
    var activeTranslatePlatform by mutableStateOf(credentials.translatePlatform())
        private set
    var translateToken by mutableStateOf(credentials.translateToken(translatePlatform).orEmpty())
    var translateModel by mutableStateOf(credentials.translateModelInput(translatePlatform))
    var translateSaved by mutableStateOf(credentials.hasTranslate())
        private set
    var translateThinking by mutableStateOf(graph.credentials.translateThinking())
        private set
    var translateEffort by mutableStateOf(graph.credentials.translateEffort(credentials.translatePlatform()))
        private set
    var translateAutoPolish by mutableStateOf(graph.credentials.translateAutoPolish())
        private set
    var translateAutoCorrect by mutableStateOf(graph.credentials.translateAutoCorrect())
        private set

    private var whisperBaseline by mutableStateOf(persistedWhisperBaseline())
    private var translateBaseline by mutableStateOf(persistedTranslateBaseline())
    private data class WhisperDraft(val base: String, val token: String)
    private data class TranslateDraft(val token: String, val model: String)
    // 切换模型/平台但尚未保存时保留各自的编辑内容，避免下一次切换丢失输入。
    private val whisperDrafts = mutableMapOf<String, WhisperDraft>()
    private val translateDrafts = mutableMapOf<TranslatePlatform, TranslateDraft>()
    val whisperModified: Boolean
        get() = whisperBaseline.isModified(whisperModel, whisperBase, whisperToken)
    val translateModified: Boolean
        get() = translateBaseline.isModified(translatePlatform.name, translateToken, translateModel)

    private fun storedWhisperBase(model: String) =
        if (isOnDeviceWhisperModel(model)) "" else credentials.whisperBaseUrl(model).orEmpty()

    private fun storedWhisperToken(model: String) =
        if (isOnDeviceWhisperModel(model)) "" else credentials.whisperToken(model).orEmpty()

    private fun persistedWhisperBaseline(): SettingsInputBaseline {
        val model = credentials.resolvedWhisperModel()
        return SettingsInputBaseline(model, storedWhisperBase(model), storedWhisperToken(model))
    }

    private fun persistedTranslateBaseline(): SettingsInputBaseline {
        val platform = credentials.translatePlatform()
        return SettingsInputBaseline(platform.name, credentials.translateToken(platform).orEmpty(), credentials.translateModelInput(platform))
    }

    fun selectWhisperModel(id: String) {
        if (id == whisperModel) return
        whisperDrafts[whisperModel] = WhisperDraft(whisperBase, whisperToken)
        applyWhisperModel(id)
    }

    private fun applyWhisperModel(id: String) {
        whisperModel = id
        val draft = whisperDrafts[id]
        whisperBase = draft?.base ?: storedWhisperBase(id)
        whisperToken = draft?.token ?: storedWhisperToken(id)
        whisperSaved = credentials.hasWhisper(id)
        whisperBaseline = SettingsInputBaseline(id, storedWhisperBase(id), storedWhisperToken(id))
    }

    fun saveWhisper() {
        credentials.saveWhisper(whisperBase, whisperToken, whisperModel)
        whisperDrafts.remove(whisperModel)
        applyWhisperModel(credentials.resolvedWhisperModel())
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
        credentials.clearWhisper(whisperModel)
        whisperDrafts.remove(whisperModel)
        applyWhisperModel(whisperModel)
    }

    fun selectTranslatePlatform(platform: TranslatePlatform) {
        if (platform == translatePlatform) return
        translateDrafts[translatePlatform] = TranslateDraft(translateToken, translateModel)
        applyTranslatePlatform(platform)
    }

    private fun applyTranslatePlatform(platform: TranslatePlatform) {
        translatePlatform = platform
        val draft = translateDrafts[platform]
        translateToken = draft?.token ?: credentials.translateToken(platform).orEmpty()
        translateModel = draft?.model ?: credentials.translateModelInput(platform)
        translateSaved = translateToken.isNotEmpty()
        translateBaseline = SettingsInputBaseline(
            platform.name, credentials.translateToken(platform).orEmpty(), credentials.translateModelInput(platform),
        )
    }

    /** 清除确认里明确列出会清掉的自定义地址与模型名。 */
    fun savedWhisperBase(): String =
        if (isOnDeviceWhisperModel(whisperModel)) "" else credentials.whisperBaseUrl(whisperModel).orEmpty()

    fun savedTranslateModel(): String = credentials.translateModel(translatePlatform)

    fun saveTranslate() {
        credentials.saveTranslate(translatePlatform, translateToken, translateModel)
        translateDrafts.remove(translatePlatform)
        applyTranslatePlatform(credentials.translatePlatform())
        activeTranslatePlatform = credentials.translatePlatform()
        translateEffort = credentials.translateEffort(activeTranslatePlatform)
    }

    fun clearTranslate() {
        credentials.clearTranslate(translatePlatform)
        translateDrafts.remove(translatePlatform)
        applyTranslatePlatform(translatePlatform)
    }

    fun setThinking(enabled: Boolean) {
        graph.credentials.saveTranslateThinking(enabled)
        translateThinking = graph.credentials.translateThinking()
    }

    fun setEffort(value: String) {
        graph.credentials.saveTranslateEffort(activeTranslatePlatform, value)
        translateEffort = graph.credentials.translateEffort(activeTranslatePlatform)
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

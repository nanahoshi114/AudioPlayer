package com.nanahoshi.audioplayer.ui.settings

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.data.SENSE_VOICE_MODEL
import com.nanahoshi.audioplayer.data.SenseVoiceModelState
import com.nanahoshi.audioplayer.data.TranslatePlatform
import com.nanahoshi.audioplayer.data.WHISPER_MODEL_QWEN
import com.nanahoshi.audioplayer.data.WHISPER_MODEL_QWEN3
import com.nanahoshi.audioplayer.data.isCloudWhisperModel
import com.nanahoshi.audioplayer.data.isOnDeviceWhisperModel
import com.nanahoshi.audioplayer.data.whisperModelLabel
import com.nanahoshi.audioplayer.graph

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
        AppTopBar(title = "听写和翻译", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "whisper") {
                Row(
                    Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("听写", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { whisperHelp = true }) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "听写说明")
                    }
                }
                WhisperModelField(
                    model = viewModel.whisperModel,
                    onModel = viewModel::selectWhisperModel,
                )
                if (isOnDeviceWhisperModel(viewModel.whisperModel)) {
                    val senseVoiceState by viewModel.senseVoice.state.collectAsState()
                    SenseVoiceModelPanel(
                        state = senseVoiceState,
                        onDownload = viewModel::downloadSenseVoice,
                        onCancel = viewModel::cancelSenseVoiceDownload,
                        onDelete = viewModel::deleteSenseVoice,
                    )
                    Button(
                        onClick = viewModel::saveWhisper,
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("保存") }
                } else {
                    if (!isCloudWhisperModel(viewModel.whisperModel)) {
                        OutlinedTextField(
                            value = viewModel.whisperBase,
                            onValueChange = { viewModel.whisperBase = it },
                            label = { Text("地址") },
                            singleLine = true,
                            supportingText = whisperAddressHint(viewModel.whisperModel),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = viewModel.whisperToken,
                        onValueChange = { viewModel.whisperToken = it },
                        label = { Text(if (isCloudWhisperModel(viewModel.whisperModel)) "API Key" else "口令") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = viewModel::saveWhisper) { Text("保存") }
                        TextButton(onClick = viewModel::clearWhisper) { Text("清除") }
                    }
                    SaveStatus(viewModel.whisperModified, viewModel.whisperSaved)
                }
            }
            item(key = "translate") {
                Row(
                    Modifier.padding(top = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("翻译", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { translateHelp = true }) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "翻译说明")
                    }
                }
                TranslatePlatformField(
                    platform = viewModel.translatePlatform,
                    onPlatform = viewModel::selectTranslatePlatform,
                )
                OutlinedTextField(
                    value = viewModel.translateToken,
                    onValueChange = { viewModel.translateToken = it },
                    label = { Text("口令") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = viewModel.translateModel,
                    onValueChange = { viewModel.translateModel = it },
                    placeholder = {
                        Text(
                            viewModel.translatePlatform.defaultModel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    supportingText = { Text("模型名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("深度思考", modifier = Modifier.weight(1f))
                    Switch(
                        checked = viewModel.translateThinking,
                        onCheckedChange = viewModel::setThinking,
                    )
                }
                if (viewModel.translateThinking) {
                    Text("思考强度", modifier = Modifier.padding(top = 4.dp))
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
                            Text(effort.label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("翻译后自动润色", modifier = Modifier.weight(1f))
                    Switch(
                        checked = viewModel.translateAutoPolish,
                        onCheckedChange = viewModel::setAutoPolish,
                    )
                }
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = viewModel::saveTranslate) { Text("保存") }
                    TextButton(onClick = viewModel::clearTranslate) { Text("清除") }
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
            modified -> "有未保存的修改"
            configured -> "已保存"
            else -> "未配置完成"
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (modified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

class ModelSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    val senseVoice = graph.senseVoice
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
            whisperSaved = graph.senseVoice.isReady()
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
            graph.senseVoice.isReady()
        } else {
            graph.credentials.hasWhisper(whisperModel)
        }
        whisperBaseline = currentWhisperInput()
    }

    fun downloadSenseVoice() = graph.senseVoice.download()

    fun cancelSenseVoiceDownload() = graph.senseVoice.cancelDownload()

    fun deleteSenseVoice() = graph.senseVoice.deleteModel()

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
            value = whisperModelLabel(model),
            onValueChange = {},
            readOnly = true,
            label = { Text("模型") },
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
            ).forEach { id ->
                DropdownMenuItem(
                    text = { Text(whisperModelLabel(id)) },
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
            value = platform.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("平台") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            TranslatePlatform.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
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
        { Text("需要填写服务地址") }
    }
    else -> null
}

@Composable
private fun SenseVoiceModelPanel(
    state: SenseVoiceModelState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    when (state) {
        SenseVoiceModelState.NotDownloaded -> Column {
            Text(
                "模型未下载。下载约 160MB，占用约 230MB，从 GitHub 下载到手机后完全离线运行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onDownload, modifier = Modifier.padding(top = 8.dp)) {
                Text("下载模型")
            }
        }
        is SenseVoiceModelState.Downloading -> Column {
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
                "${(fraction * 100).toInt()}%　${state.bytesDone / 1024 / 1024}MB / ${state.bytesTotal / 1024 / 1024}MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onCancel, modifier = Modifier.padding(top = 4.dp)) {
                Text("取消下载")
            }
        }
        SenseVoiceModelState.Ready -> Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "模型已下载，在本机运行，不需要填写地址和口令。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDelete) { Text("删除模型") }
        }
        is SenseVoiceModelState.Failed -> Column {
            Text(
                "下载失败：${state.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onDownload, modifier = Modifier.padding(top = 8.dp)) {
                Text("重试")
            }
        }
    }
}

@Composable
private fun WhisperHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("听写") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("选一个听写模型，填写对应内容后点「保存」。")
                Text(
                    "qwen-audio-3.1-asr：线上模型，只填 API Key，地址固定不用管。",
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Qwen3-ASR：自己部署的服务，填地址和口令（服务不校验口令时随便填一个）。地址在局域网时，首次访问要允许本地网络。",
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "SenseVoiceSmall：完全在本机运行，不填地址和口令，先点「下载模型」（约 160MB），下载后可离线使用。",
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "用法：播放时点「生成字幕」只生成日文字幕，「生成字幕和翻译」会接着翻成中文。同一首用同一模型不会重复生成；换模型或地址会重新生成。",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun TranslateHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("翻译") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("选一个平台，填入口令后点「保存」。模型名可以不填：千问AI平台默认 qwen3.8-flash，DeepSeek 默认 deepseek-flash。")
                Text(
                    "深度思考一般不用打开；想要更细的译文时再打开，并选择思考强度。",
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "用法：在字幕全文里点「生成翻译」，已有译文时可以点「润色」。打开「翻译后自动润色」后，每次翻译完成会自动润色一遍。润色失败会保留原来的译文。",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

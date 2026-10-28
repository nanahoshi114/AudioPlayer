package com.nanahoshi.audioplayer.ui.settings

import android.app.Application
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.nanahoshi.audioplayer.data.TranslatePlatform
import com.nanahoshi.audioplayer.data.WHISPER_MODEL_1
import com.nanahoshi.audioplayer.data.WHISPER_MODEL_JOBS
import com.nanahoshi.audioplayer.data.WHISPER_MODEL_LOCAL
import com.nanahoshi.audioplayer.data.WHISPER_MODEL_QWEN
import com.nanahoshi.audioplayer.data.WHISPER_MODEL_QWEN3
import com.nanahoshi.audioplayer.data.isCloudWhisperModel
import com.nanahoshi.audioplayer.data.whisperModelLabel
import com.nanahoshi.audioplayer.graph

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
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("听写和翻译", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
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
                Button(
                    onClick = viewModel::saveWhisper,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("保存") }
                TextButton(onClick = viewModel::clearWhisper) { Text("清除") }
                if (viewModel.whisperSaved) {
                    Text("已保存", modifier = Modifier.padding(top = 8.dp))
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
                Button(
                    onClick = viewModel::saveTranslate,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("保存") }
                TextButton(onClick = viewModel::clearTranslate) { Text("清除") }
                if (viewModel.translateSaved) {
                    Text("已保存", modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

class ModelSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
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

    fun selectWhisperModel(id: String) {
        whisperModel = id
        whisperBase = graph.credentials.whisperBaseUrl(id).orEmpty()
        whisperToken = graph.credentials.whisperToken(id).orEmpty()
        whisperSaved = graph.credentials.hasWhisper(id)
    }

    fun saveWhisper() {
        graph.credentials.saveWhisper(whisperBase, whisperToken, whisperModel)
        whisperBase = graph.credentials.whisperBaseUrl(whisperModel).orEmpty()
        whisperToken = graph.credentials.whisperToken(whisperModel).orEmpty()
        whisperModel = graph.credentials.resolvedWhisperModel()
        whisperSaved = graph.credentials.hasWhisper(whisperModel)
    }

    fun clearWhisper() {
        graph.credentials.clearWhisper(whisperModel)
        whisperBase = ""
        whisperToken = ""
        whisperSaved = false
    }

    fun selectTranslatePlatform(platform: TranslatePlatform) {
        translatePlatform = platform
        translateToken = graph.credentials.translateToken(platform).orEmpty()
        translateModel = graph.credentials.translateModelInput(platform)
        translateEffort = graph.credentials.translateEffort(platform)
        translateSaved = graph.credentials.translateToken(platform) != null
    }

    fun saveTranslate() {
        graph.credentials.saveTranslate(translatePlatform, translateToken, translateModel)
        translatePlatform = graph.credentials.translatePlatform()
        translateToken = graph.credentials.translateToken(translatePlatform).orEmpty()
        translateModel = graph.credentials.translateModelInput(translatePlatform)
        translateSaved = graph.credentials.hasTranslate()
    }

    fun clearTranslate() {
        graph.credentials.clearTranslate(translatePlatform)
        translateToken = ""
        translateModel = ""
        translateSaved = false
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
                WHISPER_MODEL_1,
                WHISPER_MODEL_LOCAL,
                WHISPER_MODEL_QWEN,
                WHISPER_MODEL_QWEN3,
                WHISPER_MODEL_JOBS,
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
    WHISPER_MODEL_LOCAL, WHISPER_MODEL_QWEN3 -> {
        { Text("需要填写服务地址") }
    }
    else -> null
}

@Composable
private fun WhisperHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("听写") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("whisper-1 和 qwen-audio-3.1-asr 是线上模型，只填写 API Key，不显示地址。whisper-1 使用 https://api.openai.com，qwen-audio-3.1-asr 使用 https://dashscope.aliyuncs.com。whisper（本地部署）、Qwen3-ASR 和本机任务接口要填写地址和口令。连到局域网时，系统会询问是否允许访问本地网络，需要允许。")
                Text(
                    "whisper-1：模型名固定为 whisper-1，语言为日语，并要求返回每个分段的开始时间、结束时间和文本。单次请求最长等待 10 分钟。文件超过 25MB 时，会先询问是否仍要发送；确认后仍会上传。",
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "whisper（本地部署）：地址填写本机服务基址，不能留空。基址若不以 /v1 结尾，请求时会补上。请求内容与 whisper-1 相同，模型名仍发送 whisper-1。文件超过 25MB 时直接发送。服务不检查口令时，填写任意非空内容即可。",
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "qwen-audio-3.1-asr：不论时长，都先上传到临时存储，再转写。语言提示为日语。超过临时上传上限时不会上传。",
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Qwen3-ASR：本地部署的开源模型，与 qwen-audio-3.1-asr 分开。地址填写本机服务基址，不能留空；基址若不以 /v1 结尾，请求时会补上。应用先读取服务上已加载的模型名，再把音频提交到音频转写接口，语言为日语。服务返回每个分段的开始和结束时间时，按段生成字幕。只返回整段文字时，整段文字作为一条字幕，覆盖整首的时长。没有读出时长、也没有分段时间时，记为「没有分段时间」。口令会放在请求里；服务不检查口令时，填写任意非空内容即可。",
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "本机任务接口：按 docs/whisper-api.md。应用将音频提交到 POST /jobs，用 GET /jobs/{id} 查询进度，用 GET /jobs/{id}/cues 读取字幕。",
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
                Text("只支持通用大模型。在「千问AI平台」和「DeepSeek」里选一个，填写口令。模型名不填时，千问AI平台用 qwen3.8-flash，DeepSeek 用 deepseek-flash。")
                Text(
                    "千问AI平台的请求发到 https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions。DeepSeek 发到 https://api.deepseek.com/chat/completions。",
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "深度思考默认关闭。打开后可以选择思考强度。千问AI平台有低、中、高；DeepSeek 有高、最高。关闭时单次最长等待 3 分钟，打开后最长等待 10 分钟。",
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "打开「翻译后自动润色」后，每次翻译完成会再发给模型通顺一遍。字幕全文页里，已有译文时也可以手动「润色」。润色失败会保留原来的译文。",
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "日文按每批最多 40 句发送。每一行必须是「编号|译文」，编号从 1 连续排到本批句数，不能缺行、空行或附加说明。任一批格式不符，整次翻译失败，已完成的批次不会保存。润色同样按批发送，任一批格式不符则整次润色失败。",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

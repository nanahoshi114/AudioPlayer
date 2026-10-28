package com.nanahoshi.audioplayer.ui.settings

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.data.formatCacheBytes
import com.nanahoshi.audioplayer.data.whisperModelLabel
import com.nanahoshi.audioplayer.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    onOpenStored: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenCache: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val limit by viewModel.limitBytes.collectAsStateWithLifecycle()
    LaunchedEffect(limit) { viewModel.refreshUsage() }
    val used = viewModel.usedBytes
    val cacheLine = if (used == null) {
        "正在统计…"
    } else {
        "已用 ${formatCacheBytes(used)} / 上限 ${formatCacheBytes(limit)}"
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "stored") {
            SettingsRow(
                icon = { Icon(Icons.Default.DownloadDone, contentDescription = null) },
                title = "下载管理",
                summary = "已下载的音频和字幕，可搜索、导出、删除",
                onClick = onOpenStored,
            )
        }
        item(key = "models") {
            SettingsRow(
                icon = { Icon(Icons.Default.Translate, contentDescription = null) },
                title = "听写和翻译",
                summary = "${viewModel.whisperLabel()}，${viewModel.translateLabel()}",
                onClick = onOpenModels,
            )
        }
        item(key = "cache") {
            SettingsRow(
                icon = { Icon(Icons.Default.CleaningServices, contentDescription = null) },
                title = "清理缓存",
                summary = cacheLine,
                onClick = onOpenCache,
            )
        }
    }
}

@Composable
private fun SettingsRow(
    icon: @Composable () -> Unit,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(summary) },
        leadingContent = icon,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    val limitBytes = graph.cache.limitBytes
    var usedBytes by mutableStateOf<Long?>(null)
        private set

    fun whisperLabel(): String = whisperModelLabel(graph.credentials.resolvedWhisperModel())

    fun translateLabel(): String = if (graph.credentials.hasTranslate()) "翻译已配置" else "翻译未配置"

    fun refreshUsage() {
        viewModelScope.launch {
            usedBytes = withContext(Dispatchers.IO) { graph.cache.usage() }
        }
    }
}

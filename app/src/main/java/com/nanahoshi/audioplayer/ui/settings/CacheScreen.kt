package com.nanahoshi.audioplayer.ui.settings

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.data.CACHE_LIMIT_OPTIONS
import com.nanahoshi.audioplayer.data.cacheLimitLabel
import com.nanahoshi.audioplayer.data.formatCacheBytes
import com.nanahoshi.audioplayer.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CacheScreen(
    onBack: () -> Unit,
    viewModel: CacheViewModel = viewModel(),
) {
    val limit by viewModel.limitBytes.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refresh() }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("清理缓存", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "当前占用 ${formatCacheBytes(viewModel.usedBytes)}",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        ListItem(
            headlineContent = { Text("缓存上限") },
            supportingContent = { Text(cacheLimitLabel(limit)) },
            modifier = Modifier.clickable { picking = true },
        )
        Button(
            onClick = viewModel::clear,
            enabled = !viewModel.clearing,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text("清理缓存") }
        if (viewModel.cleared) {
            Text("已清理", modifier = Modifier.padding(horizontal = 16.dp))
        }
        Text(
            "只清理封面图片等临时缓存。下载的音频、B 站缓存音轨和字幕请到「下载管理」处理。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        )
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("缓存上限") },
            text = {
                Column {
                    CACHE_LIMIT_OPTIONS.forEach { bytes ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = bytes == limit,
                                    onClick = {
                                        viewModel.setLimit(bytes)
                                        picking = false
                                    },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = bytes == limit, onClick = null)
                            Text(cacheLimitLabel(bytes), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picking = false }) { Text("取消") }
            },
        )
    }
}

class CacheViewModel(app: Application) : AndroidViewModel(app) {
    private val cache = app.graph().cache
    val limitBytes = cache.limitBytes
    var usedBytes by mutableStateOf(0L)
        private set
    var clearing by mutableStateOf(false)
        private set
    var cleared by mutableStateOf(false)
        private set

    fun refresh() {
        viewModelScope.launch {
            usedBytes = withContext(Dispatchers.IO) { cache.usage() }
        }
    }

    fun setLimit(bytes: Long) {
        viewModelScope.launch {
            cleared = false
            cache.setLimit(bytes)
            usedBytes = withContext(Dispatchers.IO) { cache.usage() }
        }
    }

    fun clear() {
        viewModelScope.launch {
            clearing = true
            cleared = false
            cache.clearAll()
            usedBytes = withContext(Dispatchers.IO) { cache.usage() }
            clearing = false
            cleared = true
        }
    }
}

package com.nanahoshi.audioplayer.ui.settings

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.CACHE_LIMIT_OPTIONS
import com.nanahoshi.audioplayer.data.cacheLimitLabel
import com.nanahoshi.audioplayer.data.formatCacheBytes
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import kotlinx.coroutines.CancellationException
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
    val used = viewModel.usedBytes
    val usageText = if (used == null) {
        stringResource(R.string.settings_cache_stats_loading)
    } else {
        stringResource(R.string.cache_usage, formatCacheBytes(used))
    }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.cache_title), onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                usageText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            SettingsGroup(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.cache_limit_title)) },
                    supportingContent = { Text(stringResource(R.string.cache_limit_summary, cacheLimitLabel(limit))) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp).clickable { picking = true },
                )
            }
            Button(
                onClick = viewModel::clear,
                enabled = !viewModel.clearing,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.settings_clear_cache)) }
            if (viewModel.cleared) {
                Text(stringResource(R.string.cache_cleared), modifier = Modifier.padding(horizontal = 16.dp))
            }
            Text(
                stringResource(R.string.cache_note),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
    }
    if (picking) {
        SettingsRadioDialog(
            title = stringResource(R.string.cache_limit_title),
            message = stringResource(R.string.cache_dialog_message),
            options = CACHE_LIMIT_OPTIONS.toList(),
            selected = limit,
            optionTitle = { cacheLimitLabel(it) },
            onSelect = { bytes ->
                viewModel.setLimit(bytes)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

class CacheViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    private val cache = graph.cache
    val limitBytes = cache.limitBytes
    var usedBytes by mutableStateOf<Long?>(null)
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
            try {
                cache.clearAll()
                usedBytes = withContext(Dispatchers.IO) { cache.usage() }
                cleared = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.showError(failure, R.string.cache_clear_failed)
            } finally {
                clearing = false
            }
        }
    }
}

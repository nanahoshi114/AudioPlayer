package com.nanahoshi.audioplayer.ui.settings

import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.nanahoshi.audioplayer.ui.components.AppTopBar
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
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.cache_title), onBack = onBack)
        Text(
            stringResource(R.string.cache_usage, formatCacheBytes(viewModel.usedBytes)),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.cache_limit_title)) },
            supportingContent = { Text(stringResource(R.string.cache_limit_summary, cacheLimitLabel(limit))) },
            modifier = Modifier.clickable { picking = true },
        )
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
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.cache_limit_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.cache_dialog_message),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
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
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.common_close)) }
            },
        )
    }
}

class CacheViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    private val cache = graph.cache
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

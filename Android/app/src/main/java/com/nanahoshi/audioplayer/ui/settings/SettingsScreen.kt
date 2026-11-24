package com.nanahoshi.audioplayer.ui.settings

import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.data.formatCacheBytes
import com.nanahoshi.audioplayer.data.whisperModelLabel
import com.nanahoshi.audioplayer.data.DOWNLOAD_CONCURRENCY_OPTIONS
import com.nanahoshi.audioplayer.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    onOpenStored: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenCache: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val limit by viewModel.limitBytes.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val desktop by viewModel.subtitleDisplay.desktop.collectAsStateWithLifecycle()
    val crossSourceFallback by viewModel.subtitleDisplay.crossSourceFallback.collectAsStateWithLifecycle()
    val downloadConcurrency by viewModel.downloadPreferences.maxConcurrentDownloads.collectAsStateWithLifecycle()
    var choosingDownloadConcurrency by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.subtitleDisplay.enableDesktop(Settings.canDrawOverlays(context))
    }
    LaunchedEffect(limit) { viewModel.refreshUsage() }
    val used = viewModel.usedBytes
    val cacheLine = if (used == null) {
        stringResource(R.string.settings_cache_stats_loading)
    } else {
        stringResource(R.string.settings_cache_usage, formatCacheBytes(used), formatCacheBytes(limit))
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "appearance-title") {
            Text(stringResource(R.string.settings_section_appearance), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        }
        item(key = "appearance") {
            SettingsGroup {
                SettingsRow(
                    icon = { Icon(Icons.Default.Palette, contentDescription = null) },
                    title = stringResource(R.string.settings_theme_color_title),
                    summary = appearanceSummary(appearance),
                    onClick = onOpenAppearance,
                )
            }
        }
        item(key = "storage-title") {
            Text(stringResource(R.string.settings_section_storage), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        }
        item(key = "storage") {
            SettingsGroup {
                SettingsRow(
                    icon = { Icon(Icons.Default.DownloadDone, contentDescription = null) },
                    title = stringResource(R.string.settings_download_management_title),
                    summary = stringResource(R.string.settings_download_management_summary),
                    onClick = onOpenStored,
                )
                HorizontalDivider()
                SettingsRow(
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    title = stringResource(R.string.settings_concurrent_downloads),
                    summary = stringResource(R.string.settings_concurrent_downloads_summary, downloadConcurrency),
                    onClick = { choosingDownloadConcurrency = true },
                )
                HorizontalDivider()
                SettingsRow(
                    icon = { Icon(Icons.Default.CleaningServices, contentDescription = null) },
                    title = stringResource(R.string.settings_clear_cache),
                    summary = cacheLine,
                    onClick = onOpenCache,
                )
            }
        }
        item(key = "subtitle-title") {
            Text(stringResource(R.string.settings_section_subtitles), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        }
        item(key = "models") {
            SettingsGroup {
                SettingsRow(
                    icon = { Icon(Icons.Default.Translate, contentDescription = null) },
                    title = stringResource(R.string.settings_whisper_translate_title),
                    summary = "${viewModel.whisperLabel()}，${viewModel.translateLabel()}",
                    onClick = onOpenModels,
                )
                HorizontalDivider()
                SubtitleSwitch(
                    icon = { Icon(Icons.Default.Subtitles, contentDescription = null) },
                    title = stringResource(R.string.settings_desktop_subtitles_title),
                    summary = stringResource(R.string.settings_desktop_subtitles_summary),
                    checked = desktop.enabled,
                ) { enabled ->
                    if (!enabled || Settings.canDrawOverlays(context)) {
                        viewModel.subtitleDisplay.enableDesktop(enabled)
                    } else {
                        try {
                            overlayPermission.launch(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", context.packageName, null)),
                            )
                        } catch (_: ActivityNotFoundException) {
                            viewModel.reportOverlaySettingsUnavailable()
                        } catch (_: SecurityException) {
                            viewModel.reportOverlaySettingsUnavailable()
                        }
                    }
                }
                if (desktop.enabled || desktop.locked) {
                    HorizontalDivider()
                    SubtitleSwitch(
                        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        title = stringResource(R.string.settings_lock_desktop_subtitles),
                        summary = stringResource(R.string.settings_lock_desktop_subtitles_summary),
                        checked = desktop.locked,
                        onChange = viewModel.subtitleDisplay::lockDesktop,
                    )
                }
                HorizontalDivider()
                SubtitleSwitch(
                    icon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
                    title = stringResource(R.string.settings_cross_source_subtitles_title),
                    summary = stringResource(R.string.settings_cross_source_subtitles_summary),
                    checked = crossSourceFallback,
                    onChange = viewModel.subtitleDisplay::setCrossSourceFallback,
                )
            }
        }
        item(key = "app-title") {
            Text(stringResource(R.string.settings_section_app), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        }
        item(key = "about") {
            SettingsGroup {
                SettingsRow(icon = { Icon(Icons.Default.Info, contentDescription = null) }, title = stringResource(R.string.settings_about_title),
                    summary = stringResource(
                        R.string.settings_about_summary,
                        viewModel.version.display.ifBlank { stringResource(R.string.about_unknown_version) },
                    ), onClick = onOpenAbout)
            }
        }
    }
    if (choosingDownloadConcurrency) {
        AlertDialog(
            onDismissRequest = { choosingDownloadConcurrency = false },
            title = { Text(stringResource(R.string.settings_concurrent_downloads)) },
            text = {
                Column {
                    DOWNLOAD_CONCURRENCY_OPTIONS.forEach { count ->
                        Row(
                            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).selectable(
                                selected = count == downloadConcurrency,
                                role = Role.RadioButton,
                                onClick = {
                                    viewModel.downloadPreferences.setMaxConcurrentDownloads(count)
                                    choosingDownloadConcurrency = false
                                },
                            ).padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = count == downloadConcurrency, onClick = null)
                            Text(stringResource(R.string.settings_concurrency_option, count), Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingDownloadConcurrency = false }) { Text(stringResource(R.string.common_close)) } },
        )
    }
}

@Composable
private fun SubtitleSwitch(
    icon: @Composable () -> Unit,
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(summary) },
        leadingContent = icon,
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(content = { content() })
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
        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    val limitBytes = graph.cache.limitBytes
    val appearance = graph.appearance.state
    val downloadPreferences = graph.downloads.preferences
    internal val subtitleDisplay = graph.subtitleDisplay
    internal val version = installedAppVersion(app)
    var usedBytes by mutableStateOf<Long?>(null)
        private set

    fun whisperLabel(): String = whisperModelLabel(getApplication<Application>(), graph.credentials.resolvedWhisperModel())

    fun translateLabel(): String = getApplication<Application>().getString(
        if (graph.credentials.hasTranslate()) R.string.settings_translate_configured
        else R.string.settings_translate_unconfigured,
    )

    internal fun reportOverlaySettingsUnavailable() {
        graph.show(R.string.settings_overlay_permission_failed)
    }

    fun refreshUsage() {
        viewModelScope.launch {
            usedBytes = withContext(Dispatchers.IO) { graph.cache.usage() }
        }
    }
}

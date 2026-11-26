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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
            SettingsSectionTitle(
                title = stringResource(R.string.settings_section_appearance),
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
        }
        item(key = "appearance") {
            SettingsGroup {
                SettingsNavigationRow(
                    icon = { Icon(Icons.Default.Palette, contentDescription = null) },
                    title = stringResource(R.string.settings_theme_color_title),
                    summary = appearanceSummary(appearance),
                    onClick = onOpenAppearance,
                )
            }
        }
        item(key = "storage-title") {
            SettingsSectionTitle(
                title = stringResource(R.string.settings_section_storage),
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
            )
        }
        item(key = "storage") {
            SettingsGroup {
                SettingsNavigationRow(
                    icon = { Icon(Icons.Default.DownloadDone, contentDescription = null) },
                    title = stringResource(R.string.settings_download_management_title),
                    summary = stringResource(R.string.settings_download_management_summary),
                    onClick = onOpenStored,
                )
                HorizontalDivider()
                SettingsNavigationRow(
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    title = stringResource(R.string.settings_concurrent_downloads),
                    summary = stringResource(R.string.settings_concurrent_downloads_summary, downloadConcurrency),
                    onClick = { choosingDownloadConcurrency = true },
                )
                HorizontalDivider()
                SettingsNavigationRow(
                    icon = { Icon(Icons.Default.CleaningServices, contentDescription = null) },
                    title = stringResource(R.string.settings_clear_cache),
                    summary = cacheLine,
                    onClick = onOpenCache,
                )
            }
        }
        item(key = "subtitle-title") {
            SettingsSectionTitle(
                title = stringResource(R.string.settings_section_subtitles),
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
            )
        }
        item(key = "models") {
            SettingsGroup {
                SettingsNavigationRow(
                    icon = { Icon(Icons.Default.Translate, contentDescription = null) },
                    title = stringResource(R.string.settings_whisper_translate_title),
                    summary = "${viewModel.whisperLabel()}，${viewModel.translateLabel()}",
                    onClick = onOpenModels,
                )
                HorizontalDivider()
                SettingsSwitchRow(
                    icon = { Icon(Icons.Default.Subtitles, contentDescription = null) },
                    title = stringResource(R.string.settings_desktop_subtitles_title),
                    summary = stringResource(R.string.settings_desktop_subtitles_summary),
                    checked = desktop.enabled,
                    onCheckedChange = { enabled ->
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
                    },
                )
                if (desktop.enabled || desktop.locked) {
                    HorizontalDivider()
                    SettingsSwitchRow(
                        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        title = stringResource(R.string.settings_lock_desktop_subtitles),
                        summary = stringResource(R.string.settings_lock_desktop_subtitles_summary),
                        checked = desktop.locked,
                        onCheckedChange = viewModel.subtitleDisplay::lockDesktop,
                    )
                }
                HorizontalDivider()
                SettingsSwitchRow(
                    icon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
                    title = stringResource(R.string.settings_cross_source_subtitles_title),
                    summary = stringResource(R.string.settings_cross_source_subtitles_summary),
                    checked = crossSourceFallback,
                    onCheckedChange = viewModel.subtitleDisplay::setCrossSourceFallback,
                )
            }
        }
        item(key = "app-title") {
            SettingsSectionTitle(
                title = stringResource(R.string.settings_section_app),
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
            )
        }
        item(key = "about") {
            SettingsGroup {
                SettingsNavigationRow(
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    title = stringResource(R.string.settings_about_title),
                    summary = stringResource(
                        R.string.settings_about_summary,
                        viewModel.version.display.ifBlank { stringResource(R.string.about_unknown_version) },
                    ),
                    onClick = onOpenAbout,
                )
            }
        }
    }
    if (choosingDownloadConcurrency) {
        SettingsRadioDialog(
            title = stringResource(R.string.settings_concurrent_downloads),
            options = DOWNLOAD_CONCURRENCY_OPTIONS.toList(),
            selected = downloadConcurrency,
            optionTitle = { count -> stringResource(R.string.settings_concurrency_option, count) },
            onSelect = { count ->
                viewModel.downloadPreferences.setMaxConcurrentDownloads(count)
                choosingDownloadConcurrency = false
            },
            onDismiss = { choosingDownloadConcurrency = false },
        )
    }
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

package com.nanahoshi.audioplayer.ui.settings

import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import android.app.Application
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.data.AccentMode
import com.nanahoshi.audioplayer.data.Appearance
import com.nanahoshi.audioplayer.data.ThemeMode
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.SectionHeader
import com.nanahoshi.audioplayer.ui.components.SegmentedTabBar
import com.nanahoshi.audioplayer.ui.theme.AccentPresets
import com.nanahoshi.audioplayer.ui.theme.accentPreset

private val supportsSystemAccent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun appearanceSummary(appearance: Appearance): String {
    val mode = stringResource(appearance.themeMode.labelRes)
    val accent = when (appearance.accentMode) {
        AccentMode.SYSTEM -> if (supportsSystemAccent) stringResource(R.string.appearance_system_color)
        else stringResource(accentPreset("indigo").nameRes)
        AccentMode.COVER -> stringResource(R.string.appearance_follow_cover)
        AccentMode.PRESET -> stringResource(accentPreset(appearance.presetId).nameRes)
    }
    return "$mode，$accent"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    viewModel: AppearanceViewModel = viewModel(),
) {
    val appearance by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.appearance_title), onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.appearance_theme_mode))
            SegmentedTabBar(
                options = ThemeMode.entries,
                selected = appearance.themeMode,
                label = { stringResource(it.labelRes) },
                onSelect = viewModel::setThemeMode,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SectionHeader(stringResource(R.string.appearance_theme_color))
            SettingsGroup(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                AccentRow(
                    title = stringResource(R.string.appearance_system_accent),
                    summary = stringResource(
                        if (supportsSystemAccent) R.string.appearance_system_accent_supported
                        else R.string.appearance_system_accent_unsupported,
                    ),
                    selected = appearance.accentMode == AccentMode.SYSTEM,
                    enabled = supportsSystemAccent,
                    onClick = viewModel::useSystemAccent,
                )
                HorizontalDivider()
                AccentRow(
                    title = stringResource(R.string.appearance_cover_accent),
                    summary = stringResource(R.string.appearance_cover_accent_summary),
                    selected = appearance.accentMode == AccentMode.COVER,
                    enabled = true,
                    onClick = viewModel::useCoverAccent,
                )
            }
            Text(
                stringResource(R.string.appearance_custom_color),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccentPresets.forEach { preset ->
                    Swatch(
                        color = preset.color,
                        name = stringResource(preset.nameRes),
                        selected = appearance.accentMode == AccentMode.PRESET && appearance.presetId == preset.id,
                        onClick = { viewModel.usePreset(preset.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AccentRow(
    title: String,
    summary: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    )
}

@Composable
private fun Swatch(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 4.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                )
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

class AppearanceViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.graph().appearance
    val state = prefs.state

    fun setThemeMode(mode: ThemeMode) = prefs.setThemeMode(mode)
    fun useSystemAccent() = prefs.useSystemAccent()
    fun useCoverAccent() = prefs.useCoverAccent()
    fun usePreset(id: String) = prefs.usePreset(id)
}

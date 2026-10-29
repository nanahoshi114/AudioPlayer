package com.nanahoshi.audioplayer.ui.settings

import android.app.Application
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
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
import com.nanahoshi.audioplayer.ui.theme.AccentPresets
import com.nanahoshi.audioplayer.ui.theme.accentPreset

private val supportsSystemAccent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

fun appearanceSummary(appearance: Appearance): String {
    val mode = appearance.themeMode.label
    val accent = when (appearance.accentMode) {
        AccentMode.SYSTEM -> if (supportsSystemAccent) "系统取色" else accentPreset("indigo").name
        AccentMode.COVER -> "跟随封面"
        AccentMode.PRESET -> accentPreset(appearance.presetId).name
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
        AppTopBar(title = "外观", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionHeader("主题模式")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                val modes = ThemeMode.entries
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = appearance.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    ) { Text(mode.label) }
                }
            }
            SectionHeader("主题色")
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column {
                    AccentRow(
                        title = "跟随系统取色",
                        summary = if (supportsSystemAccent) "使用系统壁纸生成的颜色" else "需要 Android 12 及以上，当前使用默认靛蓝",
                        selected = appearance.accentMode == AccentMode.SYSTEM,
                        enabled = supportsSystemAccent,
                        onClick = viewModel::useSystemAccent,
                    )
                    HorizontalDivider()
                    AccentRow(
                        title = "跟随当前播放封面",
                        summary = "播放时随封面变化；封面没有明显颜色时保持上一次的颜色",
                        selected = appearance.accentMode == AccentMode.COVER,
                        enabled = true,
                        onClick = viewModel::useCoverAccent,
                    )
                }
            }
            Text(
                "自选颜色",
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
                        name = preset.name,
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
        modifier = Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    )
}

@Composable
private fun Swatch(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.size(width = 56.dp, height = 76.dp)) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(role = Role.RadioButton, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "已选择",
                    tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                )
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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

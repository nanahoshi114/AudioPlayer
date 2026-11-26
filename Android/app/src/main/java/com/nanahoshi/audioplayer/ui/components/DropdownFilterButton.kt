package com.nanahoshi.audioplayer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nanahoshi.audioplayer.R

@Composable
internal fun <T> DropdownFilterButton(
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    buttonLabel: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        if (icon != null) {
            IconButton(onClick = { expanded = true }, enabled = enabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = iconContentDescription)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        } else {
            TextButton(onClick = { expanded = true }, enabled = enabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(buttonLabel ?: optionLabel(selected))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    trailingIcon = {
                        if (option == selected) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.a11y_selected))
                        }
                    },
                )
            }
        }
    }
}

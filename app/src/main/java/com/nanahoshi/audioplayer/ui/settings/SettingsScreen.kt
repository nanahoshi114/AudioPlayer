package com.nanahoshi.audioplayer.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.graph

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val credentials = app.graph().credentials
    val saved = credentials.cookie().orEmpty()

    fun save(value: String) = credentials.saveCookie(value)

    fun clear() = credentials.clearCookie()
}

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    var text by remember { mutableStateOf(viewModel.saved) }
    var savedHint by remember { mutableStateOf(viewModel.saved.isNotBlank()) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text("B 站登录信息", style = MaterialTheme.typography.titleLarge)
        Text(
            "只保存在这台手机里，用来播放你的账号能听的声音，包括需要登录或大会员的视频。可以粘贴 SESSDATA，或整段 Cookie。不会上传到别处。",
            modifier = Modifier.padding(vertical = 12.dp),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("SESSDATA 或 Cookie") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                viewModel.save(text)
                savedHint = text.isNotBlank()
            },
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("保存") }
        TextButton(onClick = {
            viewModel.clear()
            text = ""
            savedHint = false
        }) { Text("清除") }
        if (savedHint) {
            Text("已保存登录信息", modifier = Modifier.padding(top = 8.dp))
        }
    }
}

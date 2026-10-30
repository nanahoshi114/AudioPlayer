package com.nanahoshi.audioplayer.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 本地网络授权。Android 17 起访问局域网、本机或 `.local` 地址要在请求前申请
 * `ACCESS_LOCAL_NETWORK`；其他版本不需要。
 */
internal class LocalNetworkAccess(val run: (Boolean, () -> Unit) -> Unit)

@Composable
internal fun rememberLocalNetworkAccess(onDenied: () -> Unit): LocalNetworkAccess {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = pending
        pending = null
        if (granted) {
            next?.invoke()
            return@rememberLauncherForActivityResult
        }
        onDenied()
        val activity = context.findActivity()
        val permanent = activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        )
        if (permanent) {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        }
    }
    return LocalNetworkAccess { needed, action ->
        val allowed = Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        ) == PackageManager.PERMISSION_GRANTED
        if (!needed || allowed) {
            action()
        } else {
            pending = action
            launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

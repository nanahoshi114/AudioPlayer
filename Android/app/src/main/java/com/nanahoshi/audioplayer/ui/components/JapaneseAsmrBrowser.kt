package com.nanahoshi.audioplayer.ui.components

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.graph

@StringRes
internal val JAPANESE_ASMR_VERIFY_ACTION = R.string.ja_verify_action
@StringRes
internal val JAPANESE_ASMR_VERIFY_HINT = R.string.ja_verify_hint

internal fun Context.openJapaneseAsmrInBrowser(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (_: ActivityNotFoundException) {
        (applicationContext as Application).graph().show(R.string.online_no_browser)
    }
}

@Composable
internal fun JapaneseAsmrVerificationState(url: String, onVerify: (String) -> Unit) {
    EmptyState(
        title = stringResource(R.string.ja_verify_browser_title),
        message = stringResource(JAPANESE_ASMR_VERIFY_HINT),
        actionLabel = stringResource(JAPANESE_ASMR_VERIFY_ACTION),
        onAction = { onVerify(url) },
    )
}

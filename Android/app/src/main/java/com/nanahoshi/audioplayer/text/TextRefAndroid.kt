package com.nanahoshi.audioplayer.text

import androidx.annotation.StringRes
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** 递归解析嵌套的 [TextRef]，参数中的文本也先解析成字符串。 */
fun Context.resolve(text: TextRef): String = when (text) {
    is TextRef.Raw -> text.value
    is TextRef.Res -> if (text.args.isEmpty()) getString(text.id) else getString(
        text.id,
        *text.args.map { if (it is TextRef) resolve(it) else it }.toTypedArray(),
    )
    is TextRef.Joined -> text.parts.joinToString(text.separator) { resolve(it) }
}

@Composable
fun TextRef.asString(): String = LocalContext.current.resolve(this)

fun Context.resolveNoUrl(text: TextRef, @StringRes fallback: Int): String =
    resolveNoUrl(text, TextRef.Res(fallback))

internal fun Context.resolveNoUrl(text: TextRef, fallback: TextRef): String =
    resolve(text).takeUnless { "://" in it || "res:#" in it } ?: resolve(fallback)

internal fun Context.resolveUserError(error: Throwable, @StringRes fallback: Int): String =
    resolveNoUrl(error.userTextOr(fallback), fallback)

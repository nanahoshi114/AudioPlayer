package com.nanahoshi.audioplayer.text

import java.io.IOException
import androidx.annotation.StringRes

/** 所有会把原因展示给用户的异常实现此接口。 */
interface UserFacingError {
    val userText: TextRef
}

fun Throwable.userTextOr(fallback: TextRef): TextRef =
    (this as? UserFacingError)?.userText ?: fallback

fun Throwable.userTextOr(@StringRes fallback: Int, vararg args: Any): TextRef =
    userTextOr(TextRef.Res(fallback, args.toList()))

/** 旧代码会用 `"://"` 过滤可能带临时媒体地址的异常；迁移期保留这一保护。 */
fun Throwable.userTextOrNoUrl(@StringRes fallback: Int, vararg args: Any): TextRef {
    val text = userTextOr(fallback, *args)
    return if (text is TextRef.Raw && "://" in text.value) {
        TextRef.Res(fallback, args.toList())
    } else {
        text
    }
}

/**
 * 替代 `error(...)` / `check(...)` / `require(...)` 的用户可见异常。
 *
 * 必须继续继承 [IllegalStateException]，Service 的现有清理 catch 依赖该类型。
 */
class UserFacingException(
    override val userText: TextRef,
    cause: Throwable? = null,
) : IllegalStateException(userText.debugString(), cause), UserFacingError

class UserFacingIOException(
    override val userText: TextRef,
    cause: Throwable? = null,
) : IOException(userText.debugString(), cause), UserFacingError

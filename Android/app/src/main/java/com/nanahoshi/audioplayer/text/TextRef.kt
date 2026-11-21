package com.nanahoshi.audioplayer.text

import androidx.annotation.StringRes

/**
 * 数据层产生的用户可见文本。
 *
 * 纯数据类只携带资源 ID 和参数，不持有 Context；在 Compose、ViewModel、
 * Service 或 Repository 的边界再解析为实际字符串。
 */
sealed interface TextRef {
    data class Raw(val value: String) : TextRef
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : TextRef
    data class Joined(val parts: List<TextRef>, val separator: String = " · ") : TextRef

    /** 仅用于日志和测试，不能直接展示给用户。 */
    fun debugString(): String = when (this) {
        is Raw -> value
        is Res -> buildString {
            append("res:#").append(id)
            if (args.isNotEmpty()) append(args)
        }
        is Joined -> parts.joinToString(separator) { it.debugString() }
    }
}

/** 供按文案内容分支的旧代码过渡使用；新代码应优先判断异常类型。 */
fun TextRef.resIdOrNull(): Int? = (this as? TextRef.Res)?.id
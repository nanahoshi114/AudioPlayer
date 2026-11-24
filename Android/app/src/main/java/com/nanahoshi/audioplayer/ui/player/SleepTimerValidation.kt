package com.nanahoshi.audioplayer.ui.player

internal data class SleepTimerInput(
    val totalMs: Long,
    val secondsOutOfRange: Boolean,
    /** 数值乘法相加超出 Long 范围；与单纯的错误输入区分。 */
    val overflow: Boolean = false,
    /** 非空输入无法解析为 Long（如超长数字串）。 */
    val invalidInput: Boolean = false,
) {
    val valid: Boolean get() = totalMs > 0L && !secondsOutOfRange && !overflow && !invalidInput
}

/** 解析睡眠定时输入；秒数超过 59 时保留原值并标记无效，不静默截断。 */
internal fun validateSleepTimerInput(minutes: String, seconds: String): SleepTimerInput {
    val minuteText = minutes.trim()
    val secondText = seconds.trim()
    val minuteValue = minuteText.toLongOrNull()
    val secondValue = secondText.toLongOrNull()
    val invalidInput = (minuteText.isNotEmpty() && minuteValue == null) ||
        (secondText.isNotEmpty() && secondValue == null)
    val minute = minuteValue?.coerceAtLeast(0L) ?: 0L
    val second = secondValue?.coerceAtLeast(0L) ?: 0L
    val secondsOutOfRange = second > 59L
    val minuteMs = minute * 60_000L
    // 先确认分钟换算本身没有溢出，再确认加上秒数不会溢出，避免回绕成负数或 0。
    val overflow = !invalidInput && (
        minute > Long.MAX_VALUE / 60_000L ||
            second > (Long.MAX_VALUE - minuteMs) / 1_000L
        )
    return SleepTimerInput(
        totalMs = if (invalidInput || overflow) 0L else minuteMs + second * 1_000L,
        secondsOutOfRange = secondsOutOfRange,
        overflow = overflow,
        invalidInput = invalidInput,
    )
}
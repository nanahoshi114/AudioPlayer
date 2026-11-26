package com.nanahoshi.audioplayer.data

import java.util.Locale

/**
 * 统一人类可读的字节大小。后台任务、通知和存储界面共用同一套单位，
 * 避免出现 `KiB` 与 `KB` 混用。
 */
object ByteSizeFormatter {
    private const val KIB = 1024.0
    private const val MIB = KIB * 1024
    private const val GIB = MIB * 1024

    fun format(bytes: Long?): String {
        if (bytes == null) return "—"
        val value = bytes.coerceAtLeast(0).toDouble()
        return when {
            value >= GIB -> String.format(Locale.ROOT, "%.2f GiB", value / GIB)
            value >= MIB -> String.format(Locale.ROOT, "%.1f MiB", value / MIB)
            value >= KIB -> String.format(Locale.ROOT, "%.1f KiB", value / KIB)
            else -> "${value.toLong()} B"
        }
    }

    /** 安装包和模型面板使用整数 MiB，四舍五入到最近的整数。 */
    fun wholeMiB(bytes: Long): Long = (bytes.coerceAtLeast(0) + 512 * 1024) / (1024 * 1024)
}
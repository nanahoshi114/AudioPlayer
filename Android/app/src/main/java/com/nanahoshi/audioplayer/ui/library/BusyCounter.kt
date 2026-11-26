package com.nanahoshi.audioplayer.ui.library

import kotlinx.coroutines.flow.MutableStateFlow

/** 多个后台操作共用 busy；全部结束才复位，避免互相覆盖。只在主线程调用。 */
internal class BusyCounter {
    val busy = MutableStateFlow(false)
    private var count = 0

    fun begin() {
        count += 1
        busy.value = true
    }

    fun end() {
        if (count > 0) count -= 1
        busy.value = count > 0
    }
}

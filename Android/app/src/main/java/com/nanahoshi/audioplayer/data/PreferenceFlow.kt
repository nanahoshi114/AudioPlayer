package com.nanahoshi.audioplayer.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 偏好文件统一使用 application context，避免设置页或服务持有的 Context
 * 被 SharedPreferences 间接延长生命周期。
 */
internal fun appPreferences(context: Context, name: String): SharedPreferences =
    context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

/** 内存中的响应式偏好值：写入磁盘后同步更新 UI，不再各自维护 MutableStateFlow。 */
internal class PreferenceFlow<T>(initial: T) {
    private val mutable = MutableStateFlow(initial)
    val state: StateFlow<T> = mutable.asStateFlow()
    val value: T get() = mutable.value

    fun set(value: T) {
        mutable.value = value
    }
}
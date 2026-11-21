package com.nanahoshi.audioplayer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val DEFAULT_DOWNLOAD_CONCURRENCY = 3
internal val DOWNLOAD_CONCURRENCY_OPTIONS = 1..5

internal fun validDownloadConcurrency(value: Int): Int =
    value.takeIf { it in DOWNLOAD_CONCURRENCY_OPTIONS } ?: DEFAULT_DOWNLOAD_CONCURRENCY

class DownloadPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    private val count = MutableStateFlow(validDownloadConcurrency(prefs.getInt("concurrency", DEFAULT_DOWNLOAD_CONCURRENCY)))
    val maxConcurrentDownloads = count.asStateFlow()

    fun setMaxConcurrentDownloads(value: Int) {
        val selected = validDownloadConcurrency(value)
        prefs.edit().putInt("concurrency", selected).apply()
        count.value = selected
    }
}

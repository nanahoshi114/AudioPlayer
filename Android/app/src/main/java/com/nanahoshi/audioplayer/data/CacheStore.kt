package com.nanahoshi.audioplayer.data

import android.content.Context
import android.os.SystemClock
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import okio.Path.Companion.toOkioPath

internal const val DEFAULT_CACHE_LIMIT_BYTES = 200L * 1024L * 1024L

internal val CACHE_LIMIT_OPTIONS = longArrayOf(
    100L * 1024L * 1024L,
    DEFAULT_CACHE_LIMIT_BYTES,
    500L * 1024L * 1024L,
    1024L * 1024L * 1024L,
    2L * 1024L * 1024L * 1024L,
)

private const val IMAGE_TRIM_GAP_MS = 30_000L

internal data class CacheFileInfo(
    val name: String,
    val modifiedAt: Long,
    val sizeBytes: Long,
)

internal fun isCacheJournal(name: String): Boolean = name.startsWith("journal")

internal fun cacheFilesToRemove(files: List<CacheFileInfo>, limitBytes: Long): List<Int> {
    val total = files.sumOf { it.sizeBytes }
    if (total <= limitBytes) return emptyList()
    var remaining = total
    val order = files.indices
        .filter { !isCacheJournal(files[it].name) }
        .sortedWith(compareBy({ files[it].modifiedAt }, { files[it].name }, { it }))
    val victims = ArrayList<Int>()
    for (index in order) {
        if (remaining <= limitBytes) break
        victims += index
        remaining -= files[index].sizeBytes
    }
    return victims
}

internal fun formatCacheBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "${oneDecimal(kb)} KB"
    val mb = kb / 1024.0
    if (mb < 1024.0) return "${oneDecimal(mb)} MB"
    return "${oneDecimal(mb / 1024.0)} GB"
}

internal fun cacheLimitLabel(bytes: Long): String = when (bytes) {
    100L * 1024L * 1024L -> "100MB"
    DEFAULT_CACHE_LIMIT_BYTES -> "200MB"
    500L * 1024L * 1024L -> "500MB"
    1024L * 1024L * 1024L -> "1GB"
    2L * 1024L * 1024L * 1024L -> "2GB"
    else -> formatCacheBytes(bytes)
}

private fun oneDecimal(value: Double): String {
    val tenths = (value * 10).toLong()
    val whole = tenths / 10
    val fraction = tenths % 10
    return if (fraction == 0L) "$whole" else "$whole.$fraction"
}

class CacheStore(
    context: Context,
    private val imageLoader: () -> ImageLoader,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val limitState = MutableStateFlow(readLimit())
    val limitBytes: StateFlow<Long> = limitState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val lastImageTrim = AtomicLong(0L)

    fun usage(): Long = directorySize(appContext.cacheDir)

    suspend fun setLimit(bytes: Long) = withContext(Dispatchers.IO) {
        prefs.edit().putLong(KEY_LIMIT, bytes).apply()
        limitState.value = bytes
        gate.withLock { trimLocked() }
    }

    @OptIn(ExperimentalCoilApi::class)
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        gate.withLock {
            val loader = imageLoader()
            loader.memoryCache?.clear()
            val disk = loader.diskCache
            disk?.clear()
            val keep = disk?.directory
            appContext.cacheDir.listFiles()?.forEach { child ->
                if (keep != null && child.toOkioPath() == keep) return@forEach
                child.deleteRecursively()
            }
        }
    }

    fun trimOnStart() {
        scope.launch { gate.withLock { trimLocked() } }
    }

    fun onImageLoaded() {
        val now = SystemClock.elapsedRealtime()
        val previous = lastImageTrim.get()
        if (now - previous < IMAGE_TRIM_GAP_MS) return
        if (!lastImageTrim.compareAndSet(previous, now)) return
        scope.launch { gate.withLock { trimLocked() } }
    }

    private fun trimLocked() {
        val root = appContext.cacheDir
        if (!root.exists()) return
        val files = root.walkTopDown().filter { it.isFile }.toList()
        val infos = files.map { CacheFileInfo(it.name, it.lastModified(), it.length()) }
        for (index in cacheFilesToRemove(infos, limitState.value)) {
            files[index].delete()
        }
    }

    private fun readLimit(): Long = prefs.getLong(KEY_LIMIT, DEFAULT_CACHE_LIMIT_BYTES)

    private companion object {
        const val PREFS = "cache"
        const val KEY_LIMIT = "limitBytes"
    }
}

private fun directorySize(root: File): Long {
    if (!root.exists()) return 0L
    var total = 0L
    root.walkTopDown().forEach { file ->
        if (file.isFile) total += file.length()
    }
    return total
}

package com.nanahoshi.audioplayer.data

import android.net.Uri
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 听写暂存文件、重试快照与 content URI 落盘。 */
internal class WhisperStaging(private val center: WhisperCenter) {
    private val stagedRoot = File(center.appContext.filesDir, WHISPER_STAGING_DIRECTORY)
    private val staging = ConcurrentHashMap<String, Int>()
    private val stagingLocks = ConcurrentHashMap<String, Mutex>()

    fun stagedFilePath(contentUri: String): String =
        com.nanahoshi.audioplayer.data.stagedFilePath(center.appContext.filesDir, contentUri)

    suspend fun materialize(path: String?): File? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("content:")) return stageContent(path)
        val file = when {
            path.startsWith("/") -> File(path)
            path.startsWith("file:") -> Uri.parse(path).path?.let(::File)
            else -> null
        }
        return file?.takeIf { it.isFile }
    }

    suspend fun stageContent(path: String): File? {
        val key = stagedFilePath(path)
        val dest = File(key)
        holdStaged(key)
        return try {
            stagingLock(key).withLock {
                val ready = dest.isFile && dest.length() > 0L
                val shared = (staging[key] ?: 0) > 1
                if (ready && (shared || stagedInUse(dest))) return@withLock dest
                val input = center.appContext.contentResolver.openInputStream(Uri.parse(path))
                if (input == null) {
                    releaseStaged(key)
                    return@withLock null
                }
                stagedRoot.mkdirs()
                val temp = File.createTempFile("stage-", ".tmp", stagedRoot)
                holdStaged(temp.absolutePath)
                try {
                    input.use { source ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    if (!temp.isFile || temp.length() <= 0L) {
                        releaseStaged(key)
                        return@withLock null
                    }
                    if (!temp.renameTo(dest)) {
                        throw IOException("Failed to rename staged audio")
                    }
                    dest
                } finally {
                    releaseStaged(temp.absolutePath)
                    temp.delete()
                }
            }
        } catch (cancelled: CancellationException) {
            releaseStaged(key)
            throw cancelled
        } catch (error: Exception) {
            releaseStaged(key)
            throw error
        }
    }

    suspend fun snapshotForRetry(source: File, jobId: Long): File? {
        if (!source.isFile) return null
        stagedRoot.mkdirs()
        val temp = File.createTempFile("retry-$jobId-", ".tmp", stagedRoot)
        holdStaged(temp.absolutePath)
        val ext = source.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        val target = File(stagedRoot, "retry-$jobId-${UUID.randomUUID()}$ext")
        return try {
            try {
                source.inputStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            } catch (_: java.io.FileNotFoundException) {
                return null
            }
            if (!temp.isFile) return null
            holdStaged(target.absolutePath)
            if (!temp.renameTo(target)) {
                releaseStaged(target.absolutePath)
                throw IOException("Failed to create retry snapshot")
            }
            target
        } finally {
            releaseStaged(temp.absolutePath)
            temp.delete()
        }
    }

    fun stagingLock(key: String): Mutex = stagingLocks.computeIfAbsent(key) { Mutex() }

    suspend fun restoreLocalCopy(job: WhisperJobEntity): File? {
        val trackId = job.trackId ?: return null
        if (trackId <= 0L) return null
        val track = center.db.tracks().getById(trackId) ?: return null
        return materialize(track.localUri)
    }

    suspend fun sweepStaged() {
        val kept = buildSet {
            addAll(center.db.whisper().activeLocalPaths())
            addAll(staging.keys)
        }
        val files = stagedRoot.listFiles() ?: return
        for (file in files) {
            if (shouldDeleteStagedCopy(file, stagedRoot, kept)) file.delete()
        }
    }

    private suspend fun stagedInUse(dest: File): Boolean {
        val canonical = runCatching { dest.canonicalPath }.getOrDefault(dest.absolutePath)
        return center.db.whisper().activeLocalPaths().any { raw ->
            raw == dest.absolutePath || raw == canonical ||
                runCatching { File(raw).canonicalPath }.getOrNull() == canonical
        }
    }

    fun isStagedPath(path: String): Boolean {
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        val root = runCatching { stagedRoot.canonicalFile }.getOrNull() ?: return false
        return file.parentFile == root
    }

    fun holdStaged(path: String) {
        staging.compute(path) { _, count -> (count ?: 0) + 1 }
    }

    fun releaseStaged(path: String) {
        staging.compute(path) { _, count ->
            val next = (count ?: 0) - 1
            if (next <= 0) null else next
        }
    }

    fun isFile(path: String?): Boolean =
        !path.isNullOrBlank() && path.startsWith("/") && File(path).isFile
}

internal fun shouldDeleteStagedCopy(file: File, root: File, kept: Set<String>): Boolean {
    val path = runCatching { file.canonicalFile }.getOrNull() ?: return false
    val base = runCatching { root.canonicalFile }.getOrNull() ?: return false
    if (!path.isFile) return false
    if (path.parentFile != base) return false
    return kept.none { raw ->
        val other = runCatching { File(raw).canonicalFile }.getOrNull() ?: return@none false
        other == path
    }
}
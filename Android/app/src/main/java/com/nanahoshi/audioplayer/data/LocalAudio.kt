package com.nanahoshi.audioplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.nanahoshi.audioplayer.playback.SubtitleCandidateEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

object LocalAudio {
    val extensions = setOf("mp3", "flac", "m4a", "wav", "aac", "ogg", "opus", "wma", "alac")

    /** content: 关联是否仍可读取；失效或无权限时返回 false，供播放回退和下载判断共用。 */
    fun contentUriReadable(context: Context, uri: Uri): Boolean =
        runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true }
            .getOrDefault(false)

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index).orEmpty()
                }
            }
        return uri.lastPathSegment.orEmpty()
    }

    fun readMetadata(context: Context, uri: Uri): LocalMetadata {
        val fallbackName = displayName(context, uri).substringBeforeLast('.')
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: fallbackName.ifBlank { "未命名音频" }
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val cover = retriever.embeddedPicture?.let { bytes ->
                val file = File(context.filesDir, "covers/${uri.toString().hashCode()}.jpg")
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                file.absolutePath
            }
            return LocalMetadata(title, artist, duration, cover)
        } catch (_: Exception) {
            return LocalMetadata(fallbackName.ifBlank { "未命名音频" }, null, 0L, null)
        } finally {
            retriever.release()
        }
    }

    fun readTree(context: Context, treeUri: Uri): AudioNode? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        return audioNode(root)
    }

    private fun audioNode(file: DocumentFile): AudioNode? {
        if (!file.isDirectory) return null
        val files = mutableListOf<Uri>()
        val children = mutableListOf<AudioNode>()
        file.listFiles().forEach { child ->
            if (child.isDirectory) {
                audioNode(child)?.let(children::add)
            } else if (isAudio(child.name)) {
                files.add(child.uri)
            }
        }
        if (files.isEmpty() && children.isEmpty()) return null
        return AudioNode(file.name?.ifBlank { null } ?: "文件夹", files, children)
    }

    /**
     * 递归遍历 SAF 目录树，对每个文件调用 [visit]（文件、相对根目录的路径前缀）。
     * 遍历期间响应协程取消；无名称的条目被跳过。
     */
    private suspend fun walkFiles(root: DocumentFile, visit: (file: DocumentFile, name: String, prefix: String) -> Unit) {
        val cancellation = currentCoroutineContext()
        fun walk(dir: DocumentFile, prefix: String) {
            for (child in dir.listFiles()) {
                cancellation.ensureActive()
                val name = child.name ?: continue
                if (child.isDirectory) walk(child, "$prefix$name/")
                else if (child.isFile) visit(child, name, prefix)
            }
        }
        walk(root, "")
    }

    /**
     * 在 IO 线程递归扫描用户选择的外部文件夹，返回（文件夹名，按相对路径排序的字幕文件）。
     * 无法打开该目录时返回空名称与空列表。
     */
    suspend fun scanSubtitleTree(context: Context, treeUri: Uri): Pair<String, List<SubtitleCandidateEntry>> =
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext "" to emptyList()
            val result = mutableListOf<SubtitleCandidateEntry>()
            walkFiles(root) { file, name, prefix ->
                if (isSubtitleFile(name)) {
                    result += SubtitleCandidateEntry(
                        name = name,
                        uri = file.uri.toString(),
                        relativePath = "$prefix$name",
                    )
                }
            }
            root.name.orEmpty() to result.sortedBy { it.relativePath.lowercase() }
        }

    fun hasStoredCopy(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        if (uri.startsWith("content:", ignoreCase = true)) return true
        if (uri.startsWith("file:", ignoreCase = true)) {
            val file = Uri.parse(uri).path?.let(::File) ?: return false
            return file.isFile && file.length() > 0L
        }
        return uri.startsWith("/") && File(uri).let { it.isFile && it.length() > 0L }
    }

    fun hasStoredCopy(context: Context, uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        if (uri.startsWith("content:", ignoreCase = true)) {
            return contentUriReadable(context, Uri.parse(uri))
        }
        return hasStoredCopy(uri)
    }

    fun readDurationMs(path: String): Long? = probeAudio(path)?.durationMs

    fun readDurationMs(context: Context, uri: Uri): Long? = probe {
        it.setDataSource(context, uri)
    }?.durationMs

    /** 一次探测同时读取是否有音轨与时长；失败返回 null。 */
    fun probeAudio(path: String): AudioProbe? = probe { it.setDataSource(path) }

    private inline fun probe(setUp: (MediaMetadataRetriever) -> Unit): AudioProbe? {
        val retriever = MediaMetadataRetriever()
        return try {
            setUp(retriever)
            AudioProbe(
                hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it >= 0L },
            )
        } catch (_: RuntimeException) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun isAudio(name: String?): Boolean {
        val lower = name?.lowercase().orEmpty()
        return extensions.any { lower.endsWith(".$it") }
    }
}

data class AudioProbe(val hasAudio: Boolean, val durationMs: Long?)

data class AudioNode(
    val name: String,
    val files: List<Uri>,
    val children: List<AudioNode>,
) {
    fun allFiles(): List<Uri> = files + children.flatMap { it.allFiles() }
}

data class LocalMetadata(
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val coverPath: String?,
)

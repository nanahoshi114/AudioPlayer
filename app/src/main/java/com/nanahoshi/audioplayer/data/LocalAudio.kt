package com.nanahoshi.audioplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File

object LocalAudio {
    val extensions = setOf("mp3", "flac", "m4a", "wav", "aac", "ogg", "opus", "wma", "alac")

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

    fun isAudio(name: String?): Boolean {
        val lower = name?.lowercase().orEmpty()
        return extensions.any { lower.endsWith(".$it") }
    }
}

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

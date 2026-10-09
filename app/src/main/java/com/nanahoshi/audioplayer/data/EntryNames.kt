package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.asmr.AsmrNode
import com.nanahoshi.audioplayer.data.db.FileKind

private val audioExtensions = setOf("mp3", "flac", "wav", "m4a", "ogg", "aac")
private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")
private val textExtensions = setOf("txt", "lrc", "srt")
private val subtitleExtensions = setOf("lrc", "vtt", "srt")

fun isSubtitleFile(name: String): Boolean = fileExtension(name) in subtitleExtensions

fun fileExtension(name: String): String = name.substringAfterLast('.', "").lowercase()

fun scopedFileHash(source: TrackSource, raw: String): String = when (source) {
    TrackSource.DLSITE -> "dlsite:" + raw.removePrefix("dlsite:")
    TrackSource.ASMR -> "asmr:" + raw.removePrefix("asmr:")
    else -> raw
}

fun rawFileKey(hash: String?): String? {
    if (hash.isNullOrBlank()) return null
    return when {
        hash.startsWith("dlsite:") -> hash.removePrefix("dlsite:")
        hash.startsWith("asmr:") -> hash.removePrefix("asmr:")
        else -> hash
    }
}

fun isAsmrAudio(type: String, name: String): Boolean =
    type.equals("audio", ignoreCase = true) || fileExtension(name) in audioExtensions

fun AsmrNode.isContainer(): Boolean =
    type.equals("folder", ignoreCase = true) || (streamUrl == null && children.isNotEmpty())

fun AsmrNode.storageKey(parentPath: String): String {
    val name = sanitizeEntryName(title)
    val here = if (parentPath.isEmpty()) name else "$parentPath/$name"
    return hash.ifBlank { here }
}

fun previewKind(name: String): FileKind = when (fileExtension(name)) {
    in imageExtensions -> FileKind.IMAGE
    in textExtensions -> FileKind.TEXT
    else -> FileKind.OTHER
}

fun sanitizeEntryName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(120)
        .ifBlank { "未命名" }

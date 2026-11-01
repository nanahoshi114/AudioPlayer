package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.asmr.AsmrNode
import com.nanahoshi.audioplayer.dlsite.PlayEntry
import java.text.Normalizer

internal data class WorkFile(
    val name: String,
    val storageKey: String,
    val streamUrl: String?,
    val audio: Boolean,
)

internal data class WorkLevel(
    val files: List<WorkFile>,
    val folders: List<WorkFolder>,
)

internal data class WorkFolder(
    val name: String,
    val level: WorkLevel,
)

internal data class LocalMatchFile(
    val name: String,
    val trackId: Long?,
    val libraryFileId: Long?,
)

internal data class LocalLevel(
    val files: List<LocalMatchFile>,
    val folders: List<LocalMatchFolder>,
)

internal data class LocalMatchFolder(
    val id: Long,
    val name: String,
    val level: LocalLevel,
)

internal data class FolderFitReady(
    val tracks: Map<Long, WorkFile>,
    val libraryFiles: Map<Long, WorkFile>,
    val folderIds: List<Long>,
)

internal sealed class FolderFit {
    data class Ready(val assignment: FolderFitReady) : FolderFit()
    data object Empty : FolderFit()
    data class Extra(val path: String) : FolderFit()
}

internal enum class CatalogSide {
    Matched,
    Missed,
    Unreachable,
    NotPurchased,
}

internal fun recognizeNotice(dlsite: CatalogSide, asmrMatched: Boolean, extraPath: String? = null): String {
    val base = when {
        dlsite == CatalogSide.Matched -> "DLsite匹配成功"
        asmrMatched -> "asmr.one匹配成功"
        dlsite == CatalogSide.Unreachable -> "DLsite未连接且asmr.one匹配失败"
        else -> "DLsite/asmr.one均匹配失败"
    }
    val extra = extraPath?.trim().orEmpty()
    if (extra.isEmpty() || dlsite == CatalogSide.Matched || asmrMatched) return base
    return "$base：多出来的内容：$extra"
}

internal fun asmrMatchTree(nodes: List<AsmrNode>, parentPath: String = ""): WorkLevel {
    val files = mutableListOf<WorkFile>()
    val folders = mutableListOf<WorkFolder>()
    for (node in nodes) {
        if (node.isContainer()) {
            val name = sanitizeEntryName(node.title)
            val here = if (parentPath.isEmpty()) name else "$parentPath/$name"
            folders += WorkFolder(node.title, asmrMatchTree(node.children, here))
        } else {
            files += WorkFile(
                name = node.title,
                storageKey = node.storageKey(parentPath),
                streamUrl = node.streamUrl,
                audio = isAsmrAudio(node.type, node.title),
            )
        }
    }
    return WorkLevel(files, folders)
}

internal fun dlsiteMatchTree(nodes: List<PlayEntry>, parentPath: String = ""): WorkLevel {
    val files = mutableListOf<WorkFile>()
    val folders = mutableListOf<WorkFolder>()
    for (node in nodes) {
        val name = sanitizeEntryName(node.title)
        val here = if (parentPath.isEmpty()) name else "$parentPath/$name"
        if (node.children.isNotEmpty()) {
            folders += WorkFolder(node.title, dlsiteMatchTree(node.children, here))
        } else {
            val audio = node.audio
            files += WorkFile(
                name = node.title,
                storageKey = audio?.fileKey?.takeIf { it.isNotBlank() } ?: here,
                streamUrl = audio?.remoteUrl ?: node.preview?.url,
                audio = audio != null,
            )
        }
    }
    return WorkLevel(files, folders)
}

internal fun fitLocalFolder(local: LocalLevel, work: WorkLevel): FolderFit {
    if (!local.hasMatchableFile()) return FolderFit.Empty
    val tracks = linkedMapOf<Long, WorkFile>()
    val libraryFiles = linkedMapOf<Long, WorkFile>()
    val folderIds = mutableListOf<Long>()
    val extra = matchLevel(local, work, "", tracks, libraryFiles, folderIds)
    if (extra != null) return FolderFit.Extra(extra)
    return FolderFit.Ready(FolderFitReady(tracks, libraryFiles, folderIds))
}

private fun matchLevel(
    local: LocalLevel,
    work: WorkLevel,
    prefix: String,
    tracks: MutableMap<Long, WorkFile>,
    libraryFiles: MutableMap<Long, WorkFile>,
    folderIds: MutableList<Long>,
): String? {
    val workFiles = work.files.groupBy { matchKey(it.name) }.mapValues { (_, files) -> files.toMutableList() }
    for (file in local.files) {
        val bucket = workFiles[matchKey(file.name)]
        val remote = bucket?.removeFirstOrNull()
            ?: return joinPath(prefix, file.name)
        val trackId = file.trackId
        val libraryFileId = file.libraryFileId
        if (trackId != null) tracks[trackId] = remote
        if (libraryFileId != null) libraryFiles[libraryFileId] = remote
    }
    val workFolders = work.folders.groupBy { matchKey(it.name) }.mapValues { (_, folders) -> folders.toMutableList() }
    for (folder in local.folders) {
        val bucket = workFolders[matchKey(folder.name)]
        val remote = bucket?.removeFirstOrNull()
            ?: return joinPath(prefix, folder.name)
        folderIds += folder.id
        val nested = matchLevel(folder.level, remote.level, joinPath(prefix, folder.name), tracks, libraryFiles, folderIds)
        if (nested != null) return nested
    }
    return null
}

internal fun LocalLevel.hasMatchableFile(): Boolean =
    files.isNotEmpty() || folders.any { it.level.hasMatchableFile() }

internal fun matchKey(name: String): String = sanitizeEntryName(foldWidth(name)).lowercase()

private fun foldWidth(name: String): String = buildString(name.length) {
    var index = 0
    while (index < name.length) {
        val code = name[index].code
        if (code == 0x3000 || code in 0xFF01..0xFF5E || code in 0xFF61..0xFF9F) {
            val start = index
            index++
            while (index < name.length) {
                val next = name[index].code
                if (next != 0x3000 && next !in 0xFF01..0xFF5E && next !in 0xFF61..0xFF9F) break
                index++
            }
            append(Normalizer.normalize(name.substring(start, index), Normalizer.Form.NFKC))
        } else {
            append(name[index])
            index++
        }
    }
}

private fun joinPath(prefix: String, name: String): String =
    if (prefix.isEmpty()) name else "$prefix/$name"

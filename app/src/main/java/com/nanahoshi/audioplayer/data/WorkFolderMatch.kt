package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.asmr.AsmrNode

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

internal fun fitLocalFolder(local: LocalLevel, work: WorkLevel): FolderFit {
    if (!local.hasFile()) return FolderFit.Empty
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

private fun LocalLevel.hasFile(): Boolean =
    files.isNotEmpty() || folders.any { it.level.hasFile() }

internal fun matchKey(name: String): String = sanitizeEntryName(name).lowercase()

private fun joinPath(prefix: String, name: String): String =
    if (prefix.isEmpty()) name else "$prefix/$name"

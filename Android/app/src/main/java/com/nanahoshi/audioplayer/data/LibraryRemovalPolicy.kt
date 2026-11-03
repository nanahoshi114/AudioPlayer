package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.data.db.FolderEntity

class LibraryRemovalRejected : IllegalStateException("作品内部的内容不能单独移除，请移除整部作品")

class LibraryRemovalPolicy(folders: List<FolderEntity>) {
    private val byId = folders.associateBy { it.id }

    private fun ancestors(id: Long?): Sequence<FolderEntity> = sequence {
        var current = id
        val visited = mutableSetOf<Long>()
        while (current != null && visited.add(current)) {
            val folder = byId[current] ?: break
            yield(folder)
            current = folder.parentId
        }
    }

    fun canRemoveEntry(parentId: Long?): Boolean = ancestors(parentId).none { (!it.asmrSourceId.isNullOrBlank() || it.biliBvid != null) }

    fun canRemoveFolder(id: Long): Boolean {
        val root = ancestors(id).firstOrNull { (!it.asmrSourceId.isNullOrBlank() || it.biliBvid != null) }
        return root == null || root.id == id
    }

    fun allows(entryParents: List<Long?>, folderIds: Set<Long>): Boolean {
        val topFolders = folderIds.filter { id -> ancestors(byId[id]?.parentId).none { it.id in folderIds } }
        if (topFolders.any { !canRemoveFolder(it) }) return false
        return entryParents.all { parent -> ancestors(parent).any { it.id in folderIds } || canRemoveEntry(parent) }
    }
}

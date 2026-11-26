package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingError

class LibraryRemovalRejected(
    override val userText: TextRef = TextRef.Res(R.string.library_removal_rejected),
) : IllegalStateException(userText.debugString()), UserFacingError

class LibraryRemovalPolicy(folders: List<FolderEntity>) {
    private val byId = folders.associateBy { it.id }

    private fun ancestors(id: Long?): Sequence<FolderEntity> = FolderTree.ancestorChain(id, byId)

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

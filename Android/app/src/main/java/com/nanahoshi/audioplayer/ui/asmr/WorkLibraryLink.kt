package com.nanahoshi.audioplayer.ui.asmr

import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.dlsite.DlsiteClient

internal data class WorkLibraryLink(
    val rawKey: String,
    val localRootId: Long?,
    val originalRootId: Long?,
    val libraryFolderId: Long?,
) {
    val saved: Boolean get() = libraryFolderId != null
}

internal fun resolveWorkLibraryLink(
    rawKey: String,
    folders: List<FolderEntity>,
    originalRootFolderId: Long?,
): WorkLibraryLink {
    val canonical = DlsiteClient.canonicalWorkno(rawKey)
    fun FolderEntity.matchesWork(): Boolean =
        !asmrSourceId.isNullOrBlank() && DlsiteClient.canonicalWorkno(asmrSourceId) == canonical

    val localRoot = folders.firstOrNull { it.asmrSourceId == rawKey }
        ?: folders.asSequence().filter { it.matchesWork() }
            .minWithOrNull(compareBy(FolderEntity::position, FolderEntity::name))
    val originalRoot = folders.firstOrNull { it.id == originalRootFolderId }
    return WorkLibraryLink(
        rawKey = rawKey,
        localRootId = localRoot?.id,
        originalRootId = originalRoot?.id,
        libraryFolderId = localRoot?.id ?: originalRoot?.takeIf { it.matchesWork() }?.id,
    )
}

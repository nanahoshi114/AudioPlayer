package com.nanahoshi.audioplayer.ui.playlist

import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.PlaylistTrack
import com.nanahoshi.audioplayer.ui.library.workRootOf

internal fun playlistCoverUris(rows: List<PlaylistTrack>, folders: Map<Long, FolderEntity>): List<String?> = buildList {
    val selectedWorks = mutableListOf<String>()
    for (row in rows) {
        val work = row.coverWork(folders)
        if (work in selectedWorks) continue
        selectedWorks += work
        add(row.coverUri)
        if (size == 3) break
    }
}

private fun PlaylistTrack.coverWork(folders: Map<Long, FolderEntity>): String = when (source) {
    TrackSource.BILIBILI -> bvid?.takeIf { it.isNotBlank() }?.let { "bili:$it" }
    TrackSource.YOUTUBE -> youtubeVideoId?.takeIf { it.isNotBlank() }?.let { "youtube:$it" }
    else -> workRootOf(folderId, folders)?.takeIf { !it.asmrSourceId.isNullOrBlank() }?.let { "work:${it.id}" }
} ?: "track:$trackId"

package com.nanahoshi.audioplayer.ui.playlist

import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.PlaylistTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistCoversTest {
    @Test
    fun tracksFromTheSameWorkShareOneSlotAcrossSourcesAndSubfolders() {
        val root = FolderEntity(id = 1, name = "作品", position = 0, asmrSourceId = "RJ012345")
        val subfolder = FolderEntity(id = 2, parentId = root.id, name = "音频", position = 0)
        val otherWork = root.copy(id = 3, asmrSourceId = "BJ012345")
        val folders = listOf(root, subfolder, otherWork).associateBy { it.id }
        val rows = listOf(
            track(1, "first", TrackSource.ASMR, folderId = root.id),
            track(2, "dlsite", TrackSource.DLSITE, folderId = subfolder.id),
            track(3, "third track", TrackSource.ASMR, folderId = subfolder.id),
            track(4, "first", TrackSource.DLSITE, folderId = otherWork.id),
            track(5, "third cover"),
            track(6, "fourth cover"),
        )

        assertEquals(listOf("first", "first", "third cover"), playlistCoverUris(rows, folders))
    }

    @Test
    fun biliPartsShareOneSlotButDifferentVideosCanShowTheSameImage() {
        val rows = listOf(
            track(1, "shared", TrackSource.BILIBILI, bvid = "BV1x"),
            track(2, "other part", TrackSource.BILIBILI, bvid = "BV1x"),
            track(3, "shared", TrackSource.BILIBILI, bvid = "BV1X"),
            track(4, "third", TrackSource.BILIBILI, bvid = "BV2x"),
        )

        assertEquals(listOf("shared", "shared", "third"), playlistCoverUris(rows, emptyMap()))
    }

    @Test
    fun localTracksWithoutWorkIdentityRemainSeparateEvenWithDefaultCovers() {
        val folder = FolderEntity(id = 1, name = "本地音频", position = 0)
        val rows = listOf(
            track(1, null, folderId = folder.id),
            track(2, null, folderId = folder.id),
            track(3, "third", folderId = folder.id),
        )

        assertEquals(listOf(null, null, "third"), playlistCoverUris(rows, mapOf(folder.id to folder)))
    }

    private fun track(
        id: Long,
        cover: String?,
        source: TrackSource = TrackSource.LOCAL,
        folderId: Long? = null,
        bvid: String? = null,
    ) = PlaylistTrack(
        itemId = id,
        position = id.toInt(),
        trackId = id,
        source = source,
        title = "音轨 $id",
        artist = null,
        durationMs = 0,
        coverUri = cover,
        localUri = null,
        bvid = bvid,
        cid = id.takeIf { bvid != null },
        page = id.toInt().takeIf { bvid != null },
        folderId = folderId,
    )
}

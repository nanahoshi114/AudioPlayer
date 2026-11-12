package com.nanahoshi.audioplayer.playback

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.media3.common.MimeTypes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MediaItemFactoryAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun biliTrack(localUri: String?) = TrackEntity(
        id = 7L, source = TrackSource.BILIBILI, title = "音轨", artist = null, durationMs = 1000L,
        coverUri = null, localUri = localUri, bvid = "BV1234567890", cid = 101L, page = 1, addedAt = 0L,
    )

    @Test fun deadContentUriFallsBackToOnlineBilibili() = runBlocking {
        val item = biliTrack("content://media/external/audio/media/999999999").toPlayableItem(1L, context)
        assertEquals("bilibili", item.localConfiguration?.uri?.scheme)
        assertEquals(MimeTypes.APPLICATION_MPD, item.localConfiguration?.mimeType)
    }

    @Test fun readableContentUriIsKept() = runBlocking {
        val file = File(context.filesDir, "media-item-factory-test.m4a").apply { writeText("fixture") }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val item = biliTrack(uri.toString()).toPlayableItem(1L, context)
            assertEquals(uri, item.localConfiguration?.uri)
        } finally {
            file.delete()
        }
    }

    @Test fun deadFileUriFallsBackToOnlineBilibili() = runBlocking {
        val item = biliTrack("file:///nonexistent/audio-player-factory-test.m4a").toPlayableItem(1L, context)
        assertEquals("bilibili", item.localConfiguration?.uri?.scheme)
        assertEquals(MimeTypes.APPLICATION_MPD, item.localConfiguration?.mimeType)
    }

    @Test fun twoPhaseSplitMatchesTheConvenienceWrapper() = runBlocking {
        for (localUri in listOf(
            "content://media/external/audio/media/999999999",
            "file:///nonexistent/audio-player-factory-test.m4a",
            null,
        )) {
            val track = biliTrack(localUri)
            val cached = track.biliLocalCache(context)
            assertEquals(track.toPlayableItem(1L, context), track.toPlayableItem(1L, cached))
        }
    }

    @Test fun explicitCacheControlsBilibiliFallback() {
        val track = biliTrack("content://media/external/audio/media/999999999")
        val noCache: Uri? = null
        val online = track.toPlayableItem(1L, noCache)
        assertEquals("bilibili", online.localConfiguration?.uri?.scheme)
        assertEquals(MimeTypes.APPLICATION_MPD, online.localConfiguration?.mimeType)
        val cached = Uri.parse("content://media/external/audio/media/123")
        val local = track.toPlayableItem(1L, cached)
        assertEquals(cached, local.localConfiguration?.uri)
        assertNull(local.localConfiguration?.mimeType)
    }

    @Test fun localTrackContentUriPassesThroughUnchecked() {
        val dead = "content://media/external/audio/media/999999999"
        val track = TrackEntity(
            id = 8L, source = TrackSource.LOCAL, title = "本地", artist = null, durationMs = 1000L,
            coverUri = null, localUri = dead, bvid = null, cid = null, page = null, addedAt = 0L,
        )
        val cached: Uri? = null
        assertEquals(Uri.parse(dead), track.toPlayableItem(1L, cached).localConfiguration?.uri)
    }

    @Test fun remoteQueueRowMatchesRemotePlayable() {
        val row = QueuedTrack(
            queueItemId = 5L, position = 0, trackId = null, source = TrackSource.BILIBILI,
            title = "视频", artist = null, durationMs = 1000L, coverUri = null, localUri = null,
            bvid = null, cid = null, page = null, remoteUrl = "https://www.bilibili.com/video/BV1234567890",
            referer = null, workno = "BV1234567890", fileKey = "101",
        )
        val cached: Uri? = null
        val expected = RemotePlayable(
            title = row.title, artist = row.artist, coverUri = row.coverUri, remoteUrl = row.remoteUrl!!,
            source = row.source, referer = row.referer.orEmpty(), durationMs = row.durationMs,
            workno = row.workno, fileKey = row.fileKey,
        ).toMediaItem(row.queueItemId)
        assertEquals(expected, row.toMediaItem(cached))
    }
}

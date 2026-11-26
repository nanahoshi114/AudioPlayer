package com.nanahoshi.audioplayer.playback

import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.TrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlaybackRecoveryAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun downloadedBilibiliTrackFallsBackToDashAfterLocalCopyDeletion() = runBlocking {
        val file = File(context.filesDir, "bilibili/BV1234567890/42.m4s").apply {
            parentFile?.mkdirs()
            writeText("audio")
        }
        val track = TrackEntity(
            id = 7L, source = TrackSource.BILIBILI, title = "音轨", artist = null, durationMs = 1000L,
            coverUri = null, localUri = file.absolutePath, bvid = "BV1234567890", cid = 42L, page = 1, addedAt = 0L,
        )
        try {
            val local = track.toPlayableItem(1L, context)
            assertEquals(Uri.fromFile(file), local.localConfiguration?.uri)
            assertNull(local.localConfiguration?.mimeType)

            file.delete()
            val online = track.toPlayableItem(1L, context)
            assertEquals("bilibili", online.localConfiguration?.uri?.scheme)
            assertEquals("BV1234567890", online.localConfiguration?.uri?.getQueryParameter("bvid"))
            assertEquals("42", online.localConfiguration?.uri?.getQueryParameter("cid"))
            assertEquals(MimeTypes.APPLICATION_MPD, online.localConfiguration?.mimeType)
        } finally {
            file.parentFile?.deleteRecursively()
        }
    }
}
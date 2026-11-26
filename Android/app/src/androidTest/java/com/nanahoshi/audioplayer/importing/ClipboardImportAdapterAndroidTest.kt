package com.nanahoshi.audioplayer.importing

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanahoshi.audioplayer.data.ClipboardSource
import com.nanahoshi.audioplayer.data.ClipboardTarget
import com.nanahoshi.audioplayer.ui.importing.importCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardImportAdapterAndroidTest {
    @Test fun adapterAcceptsTextAndWebUrisWithoutOpeningContentOrIntents() {
        val target = ClipboardTarget(ClipboardSource.BILIBILI, "BV1234567890")
        val clip = ClipData.newPlainText("fixture", "ordinary text")
        clip.addItem(ClipData.Item(target.id))
        assertEquals(target, importCandidate(clip)!!.target)
        fun uriClip(value: String) = ClipData(ClipDescription("fixture", arrayOf("text/uri-list")), ClipData.Item(Uri.parse(value)))
        assertEquals(ClipboardSource.YOUTUBE, importCandidate(uriClip("https://youtu.be/jNQXAC9IVRw"))!!.target.source)
        assertEquals(
            ClipboardTarget(ClipboardSource.JAPANESE_ASMR, "135575"),
            importCandidate(uriClip("https://www.japaneseasmr.com/135575/?share=1"))?.target,
        )
        val combined = ClipData(ClipDescription("fixture", arrayOf("text/plain")),
            ClipData.Item("description", null, null, Uri.parse("https://youtu.be/jNQXAC9IVRw")))
        assertEquals(ClipboardSource.YOUTUBE, importCandidate(combined)!!.target.source)
        assertNull(importCandidate(uriClip("content://example.org/RJ123456")))
        assertNull(importCandidate(ClipData.newIntent("fixture", Intent(Intent.ACTION_VIEW, Uri.parse("https://youtu.be/jNQXAC9IVRw")))))
        val bare = ClipData.newPlainText("fixture", "参考 123456")
        assertEquals("RJ123456", importCandidate(bare)?.target?.id)
        assertTrue(checkNotNull(importCandidate(bare)).requiresLookupConfirmation)
        bare.addItem(ClipData.Item("作品 RJ01234567"))
        assertEquals("RJ01234567", importCandidate(bare)?.target?.id)
        assertFalse(checkNotNull(importCandidate(bare)).requiresLookupConfirmation)
        assertNull(importCandidate(uriClip("https://example.org/123456")))
    }
}
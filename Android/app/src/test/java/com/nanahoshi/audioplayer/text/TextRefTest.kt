package com.nanahoshi.audioplayer.text

import com.nanahoshi.audioplayer.playback.playbackErrorDetail
import org.junit.Assert.assertTrue
import com.nanahoshi.audioplayer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextRefTest {
    @Test
    fun rawDebugStringIsTheValue() {
        assertEquals("原因", TextRef.Raw("原因").debugString())
    }

    @Test
    fun resDebugStringUsesResourceId() {
        val text = TextRef.Res(R.string.common_back)
        assertEquals("res:#${R.string.common_back}", text.debugString())
        assertEquals(R.string.common_back, text.resIdOrNull())
    }

    @Test
    fun joinedDebugStringUsesSeparator() {
        val text = TextRef.Joined(
            listOf(TextRef.Raw("第一段"), TextRef.Raw("第二段")),
            separator = "；",
        )
        assertEquals("第一段；第二段", text.debugString())
        assertNull(text.resIdOrNull())
    }

    @Test
    fun resDebugStringIncludesArguments() {
        val text = TextRef.Res(R.string.common_selected_count, listOf(3))
        assertEquals("res:#${R.string.common_selected_count}[3]", text.debugString())
    }

    @Test
    fun userFacingExceptionPreservesLocalizedReason() {
        val error: Throwable = UserFacingException(TextRef.Res(R.string.library_folder_not_found))
        assertEquals(TextRef.Res(R.string.library_folder_not_found), error.userTextOr(R.string.library_operation_failed))
        assertEquals(R.string.library_folder_not_found, error.userTextOr(R.string.library_operation_failed).resIdOrNull())
    }

    @Test
    fun userFacingExceptionKeepsSentinelMessageForCallers() {
        val error: Throwable = UserFacingException(TextRef.Raw(PersistedErrors.JA_POST_REMOVED))
        assertEquals(PersistedErrors.JA_POST_REMOVED, error.message)
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun plainIllegalStateExceptionFallsBackWithoutLeakingMessage() {
        val error: Throwable = IllegalStateException("internal detail")
        assertEquals(TextRef.Res(R.string.library_operation_failed), error.userTextOr(R.string.library_operation_failed))
    }

    @Test
    fun persistedInterruptionRecognizesCurrentAndHistoricalMessages() {
        assertTrue(PersistedErrors.isInterrupted(PersistedErrors.DLSITE_ARCHIVE_INTERRUPTED))
        assertTrue(PersistedErrors.isInterrupted(PersistedErrors.SUBTITLE_TIMEOUT))
        assertTrue(PersistedErrors.isInterrupted("后台任务中断，请重试"))
        assertTrue(PersistedErrors.isCancelled(PersistedErrors.CANCELLED))
        assertEquals(false, PersistedErrors.isInterrupted(PersistedErrors.CANCELLED))
    }

    @Test
    fun playbackFiltersRenderedArgumentsAndUsesLastSafeReason() {
        val wrapped = UserFacingIOException(
            TextRef.Res(R.string.player_error_playback, listOf(TextRef.Raw("https://example.test/private"))),
            IllegalStateException("androidx.media3 fixture", IllegalStateException("safe reason")),
        )
        val resolve: (TextRef) -> String = {
            if (it is TextRef.Res) (it.args.single() as TextRef.Raw).value else (it as TextRef.Raw).value
        }
        assertEquals("safe reason", playbackErrorDetail(wrapped, resolve))
        assertNull(playbackErrorDetail(UserFacingIOException(wrapped.userText), resolve))
    }
}

package com.nanahoshi.audioplayer.ui.components

import com.nanahoshi.audioplayer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteAudioDialogSpecTest {
    @Test
    fun mixedAudioAndAssociationsOfferBothSubtitleChoices() {
        val spec = deleteAudioDialogSpec(itemCount = 1, subtitleCount = 1, associationCount = 2)
        assertEquals(R.string.delete_dialog_title_mixed, spec.titleRes)
        assertEquals(R.string.delete_dialog_confirm_all, spec.confirmRes)
        assertTrue(spec.mixed)
        assertTrue(spec.showKeepSubtitles)
        assertFalse(spec.subtitleFilesOnly)
        assertFalse(spec.handledGeneratedWithFiles)
    }

    @Test
    fun subtitleFilesOnlyHidesKeepSubtitles() {
        val spec = deleteAudioDialogSpec(itemCount = 0, subtitleCount = 0, workSubtitleCount = 2)
        assertEquals(R.string.delete_dialog_title_subtitles_only, spec.titleRes)
        assertEquals(R.string.delete_dialog_confirm_delete, spec.confirmRes)
        assertTrue(spec.subtitleFilesOnly)
        assertFalse(spec.showKeepSubtitles)
        assertTrue(spec.hasWorkOrCustomSubtitles)
    }

    @Test
    fun generatedSubtitlesOnFilesShortCircuitTheGenericSubtitleNote() {
        val spec = deleteAudioDialogSpec(itemCount = 1, subtitleCount = 1)
        assertEquals(R.string.delete_dialog_title_subtitles, spec.titleRes)
        assertEquals(R.string.delete_dialog_confirm_all, spec.confirmRes)
        assertTrue(spec.handledGeneratedWithFiles)
        assertTrue(spec.showKeepSubtitles)
    }

    @Test
    fun associationOnlyIsAnUnlinkAction() {
        val spec = deleteAudioDialogSpec(itemCount = 0, subtitleCount = 0, associationCount = 2)
        assertEquals(R.string.delete_dialog_title_unlink, spec.titleRes)
        assertEquals(R.string.library_action_unlink, spec.confirmRes)
        assertTrue(spec.mixed)
        assertFalse(spec.hasDeletes)
        assertFalse(spec.showKeepSubtitles)
    }

    @Test
    fun audioAndOtherFilesWithoutSubtitlesIsAPlainDelete() {
        val spec = deleteAudioDialogSpec(itemCount = 1, subtitleCount = 0, otherFileCount = 2)
        assertEquals(R.string.delete_dialog_title_files, spec.titleRes)
        assertEquals(R.string.delete_dialog_confirm_delete, spec.confirmRes)
        assertTrue(spec.hasDeletes)
        assertFalse(spec.hasAnySubtitles)
        assertFalse(spec.showKeepSubtitles)
    }

    @Test
    fun workAndCustomSubtitlesCountAsContentRequiringAChoice() {
        val spec = deleteAudioDialogSpec(
            itemCount = 1, subtitleCount = 1, otherFileCount = 2, workSubtitleCount = 1, customSubtitleCount = 1,
        )
        assertEquals(R.string.delete_dialog_title_files_and_subtitles, spec.titleRes)
        assertEquals(R.string.delete_dialog_confirm_all, spec.confirmRes)
        assertTrue(spec.hasWorkOrCustomSubtitles)
        assertTrue(spec.showKeepSubtitles)
    }
}
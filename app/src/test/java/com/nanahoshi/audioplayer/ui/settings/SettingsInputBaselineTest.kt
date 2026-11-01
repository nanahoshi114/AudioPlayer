package com.nanahoshi.audioplayer.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsInputBaselineTest {
    @Test
    fun detectsOnlyMeaningfulInputChanges() {
        val baseline = SettingsInputBaseline("address", "token")
        assertFalse(baseline.isModified("address", "token"))
        assertTrue(baseline.isModified("address/", "token"))
        assertTrue(baseline.isModified("address", "new-token"))
    }
}

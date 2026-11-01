package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class WhisperStagingTest {
    @Test
    fun onlyUnreferencedFilesDirectlyInsideTheStageAreDeleted() {
        val root = createTempDirectory(prefix = "whisper-src").toFile()
        val outside = File(root.parentFile, "outside-whisper-src")
        try {
            val keep = File(root, "keep")
            val drop = File(root, "drop")
            val nested = File(root, "sub/nope")
            val folder = File(root, "folder")
            keep.writeText("keep")
            drop.writeText("drop")
            nested.parentFile?.mkdirs()
            nested.writeText("nested")
            folder.mkdirs()
            outside.writeText("outside")

            val kept = setOf(File(root, "./keep").path)
            assertFalse(shouldDeleteStagedCopy(keep, root, kept))
            assertTrue(shouldDeleteStagedCopy(drop, root, kept))
            assertFalse(shouldDeleteStagedCopy(nested, root, kept))
            assertFalse(shouldDeleteStagedCopy(folder, root, kept))
            assertFalse(shouldDeleteStagedCopy(outside, root, kept))
            assertFalse(shouldDeleteStagedCopy(File(root, "missing"), root, emptySet()))
            assertTrue(shouldDeleteStagedCopy(drop, root, emptySet()))
        } finally {
            root.deleteRecursively()
            outside.delete()
        }
    }
}

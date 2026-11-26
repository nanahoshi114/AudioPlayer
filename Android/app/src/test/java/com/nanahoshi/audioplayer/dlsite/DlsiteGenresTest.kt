package com.nanahoshi.audioplayer.dlsite

import com.nanahoshi.audioplayer.ui.asmr.PieceKind
import com.nanahoshi.audioplayer.ui.asmr.SearchPiece
import com.nanahoshi.audioplayer.ui.asmr.searchPieceForTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DlsiteGenresTest {
    @Test
    fun allGenresHaveUniqueIdsAndNames() {
        val genres = DlsiteGenres.all
        assertTrue(genres.isNotEmpty())
        assertEquals(genres.size, genres.map { it.id }.toSet().size)
        assertEquals(genres.size, genres.map { it.name }.toSet().size)
        assertTrue(genres.all { it.id.isNotBlank() && it.name.isNotBlank() })
    }

    @Test
    fun noGenresContainJapaneseKana() {
        val kanaRegex = Regex("[\\u3040-\\u30ff]")
        val untranslated = DlsiteGenres.all.filter { kanaRegex.containsMatchIn(it.name) }
        assertTrue("Unexpected Japanese kana labels: $untranslated", untranslated.isEmpty())
    }

    @Test
    fun localizedGenresPreserveLookupSemantics() {
        assertEquals(SearchPiece(PieceKind.TAG, "同居", "031"), searchPieceForTag("同居"))
        assertEquals(SearchPiece(PieceKind.TAG, "按摩", "659"), searchPieceForTag("按摩"))
        assertEquals(SearchPiece(PieceKind.TAG, "陪睡", "636"), searchPieceForTag("陪睡"))
        assertEquals(SearchPiece(PieceKind.TAG, "接吻", "637"), searchPieceForTag("接吻"))
        assertFalse(DlsiteGenres.all.any { it.id == "438" })
    }
}

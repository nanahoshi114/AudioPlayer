package com.nanahoshi.audioplayer.ui.asmr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSearchNavigationTest {
    @Test
    fun tagMatchesGenreWithoutChangingTheSearchName() {
        val piece = searchPieceForTag(" \$asmr\$ ")
        assertEquals(SearchPiece(PieceKind.TAG, "asmr", "497"), piece)
        assertEquals("\$tag:asmr\$", piece?.token())
        assertEquals(SearchPiece(PieceKind.TAG, "VTuber", "513"), searchPieceForTag("VTuber"))
    }

    @Test
    fun tagCanMatchOnePartOfACompoundGenre() {
        assertEquals(SearchPiece(PieceKind.TAG, "平淡", "057"), searchPieceForTag("平淡"))
    }

    @Test
    fun unknownTagsKeepTheirNameAndBlankTagsAreIgnored() {
        assertEquals(SearchPiece(PieceKind.TAG, "custom tag"), searchPieceForTag(" \$custom tag\$ "))
        assertNull(searchPieceForTag(" \$ \$ "))
    }

    @Test
    fun circleAndVoiceActorRemoveTokenDelimitersAndWhitespace() {
        assertEquals(SearchPiece(PieceKind.CIRCLE, "Circle Name"), searchPieceForCircle(" \$Circle Name\$ "))
        assertEquals(SearchPiece(PieceKind.VA, "Voice Actor"), searchPieceForVa(" \$Voice Actor\$ "))
        assertNull(searchPieceForCircle(" \$ "))
        assertNull(searchPieceForVa(" \$ "))
    }

    @Test
    fun dlsiteIsSkippedOnlyForQueriesMadeOfUnmappedTags() {
        val unmapped = SearchPiece(PieceKind.TAG, "custom tag")
        val tags = listOf(unmapped, SearchPiece(PieceKind.TAG, "another tag"))
        assertTrue(shouldSkipDlsiteSearch("", listOf(unmapped)))
        assertTrue(shouldSkipDlsiteSearch("  ", tags))
        assertFalse(shouldSkipDlsiteSearch("", emptyList()))
        assertFalse(shouldSkipDlsiteSearch("keyword", tags))
        assertFalse(shouldSkipDlsiteSearch("", tags + SearchPiece(PieceKind.TAG, "ASMR", "497")))
        assertFalse(shouldSkipDlsiteSearch("", tags + SearchPiece(PieceKind.CIRCLE, "Circle Name")))
        assertFalse(shouldSkipDlsiteSearch("", tags + SearchPiece(PieceKind.VA, "Voice Actor")))
    }
}

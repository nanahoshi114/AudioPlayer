package com.nanahoshi.audioplayer.ui.asmr

import com.nanahoshi.audioplayer.dlsite.DlsiteGenres

internal sealed interface PendingOnlineSearch {
    val id: Long

    data class WorkPiece(override val id: Long, val piece: SearchPiece) : PendingOnlineSearch
    data class BiliUploader(override val id: Long, val name: String) : PendingOnlineSearch
}

internal fun searchPieceForTag(tag: String): SearchPiece? {
    val cleaned = tag.replace("$", "").trim()
    if (cleaned.isBlank()) return null

    val genre = DlsiteGenres.all.firstOrNull { it.name.equals(cleaned, ignoreCase = true) }
        ?: DlsiteGenres.all.singleOrNull { candidate ->
            candidate.name.split('/').any { it.trim().equals(cleaned, ignoreCase = true) }
        }
    return SearchPiece(PieceKind.TAG, cleaned, genre?.id)
}

internal fun searchPieceForCircle(circle: String): SearchPiece? {
    val cleaned = circle.replace("$", "").trim()
    return cleaned.takeIf { it.isNotBlank() }?.let { SearchPiece(PieceKind.CIRCLE, it) }
}

internal fun searchPieceForVa(va: String): SearchPiece? {
    val cleaned = va.replace("$", "").trim()
    return cleaned.takeIf { it.isNotBlank() }?.let { SearchPiece(PieceKind.VA, it) }
}

internal fun shouldSkipDlsiteSearch(text: String, pieces: List<SearchPiece>): Boolean =
    text.isBlank() && pieces.isNotEmpty() && pieces.all { it.kind == PieceKind.TAG && it.genreId == null }

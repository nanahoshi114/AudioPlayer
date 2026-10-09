package com.nanahoshi.audioplayer.data

import android.content.Context
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class LibrarySort {
    NAME,
    ADDED,
}

class LibraryPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val sortState = MutableStateFlow(read())
    val sort: StateFlow<LibrarySort> = sortState

    fun toggle() {
        val next = if (sortState.value == LibrarySort.NAME) LibrarySort.ADDED else LibrarySort.NAME
        prefs.edit().putString(KEY, next.name).apply()
        sortState.value = next
    }

    private fun read(): LibrarySort =
        if (prefs.getString(KEY, LibrarySort.ADDED.name) == LibrarySort.NAME.name) {
            LibrarySort.NAME
        } else {
            LibrarySort.ADDED
        }

    private companion object {
        const val KEY = "sort"
    }
}

@JvmName("sortedFoldersForLibrary")
fun List<FolderEntity>.sortedForLibrary(sort: LibrarySort): List<FolderEntity> = when (sort) {
    LibrarySort.NAME -> sortedBy { it.name.lowercase() }
    LibrarySort.ADDED -> sortedByDescending { it.addedAt }
}

@JvmName("sortedTracksForLibrary")
fun List<TrackEntity>.sortedForLibrary(sort: LibrarySort): List<TrackEntity> = when (sort) {
    LibrarySort.NAME -> sortedBy { it.title.lowercase() }
    LibrarySort.ADDED -> sortedByDescending { it.addedAt }
}

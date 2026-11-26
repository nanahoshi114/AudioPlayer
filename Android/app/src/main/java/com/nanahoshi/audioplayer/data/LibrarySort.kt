package com.nanahoshi.audioplayer.data

import android.content.Context
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import kotlinx.coroutines.flow.StateFlow

enum class LibrarySort {
    NAME,
    ADDED,
}

class LibraryPreferences(context: Context) {
    private val prefs = appPreferences(context, "library")
    private val sortFlow = PreferenceFlow(read())
    val sort: StateFlow<LibrarySort> = sortFlow.state
    private val subtitleFilesFlow = PreferenceFlow(prefs.getBoolean(SHOW_SUBTITLE_FILES, false))
    val showSubtitleFiles: StateFlow<Boolean> = subtitleFilesFlow.state

    fun setShowSubtitleFiles(enabled: Boolean) {
        prefs.edit().putBoolean(SHOW_SUBTITLE_FILES, enabled).apply()
        subtitleFilesFlow.set(enabled)
    }

    fun toggleSubtitleFiles() = setShowSubtitleFiles(!subtitleFilesFlow.value)

    fun toggle() {
        val next = if (sortFlow.value == LibrarySort.NAME) LibrarySort.ADDED else LibrarySort.NAME
        prefs.edit().putString(KEY, next.name).apply()
        sortFlow.set(next)
    }

    private fun read(): LibrarySort =
        if (prefs.getString(KEY, LibrarySort.ADDED.name) == LibrarySort.NAME.name) {
            LibrarySort.NAME
        } else {
            LibrarySort.ADDED
        }

    private companion object {
        const val KEY = "sort"
        const val SHOW_SUBTITLE_FILES = "show_subtitle_files"
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
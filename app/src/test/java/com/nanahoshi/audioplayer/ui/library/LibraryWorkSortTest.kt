package com.nanahoshi.audioplayer.ui.library

import com.nanahoshi.audioplayer.data.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryWorkSortTest {
    @Test
    fun workAlwaysSortsByName() {
        assertEquals(LibrarySort.NAME, effectiveLibrarySort(LibrarySort.ADDED, 7L))
        assertEquals(LibrarySort.NAME, effectiveLibrarySort(LibrarySort.NAME, 7L))
    }

    @Test
    fun libraryKeepsGlobalSort() {
        assertEquals(LibrarySort.ADDED, effectiveLibrarySort(LibrarySort.ADDED, null))
        assertEquals(LibrarySort.NAME, effectiveLibrarySort(LibrarySort.NAME, null))
    }
}

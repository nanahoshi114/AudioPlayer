package com.nanahoshi.audioplayer.ui.settings

/** Holds only an in-memory baseline. Deliberately has no credential-bearing toString(). */
internal class SettingsInputBaseline(vararg values: String) {
    private val saved = values.toList()
    fun isModified(vararg current: String): Boolean = saved != current.toList()
}

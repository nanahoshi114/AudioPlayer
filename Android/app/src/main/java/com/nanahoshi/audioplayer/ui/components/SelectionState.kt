package com.nanahoshi.audioplayer.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 通用多选集合状态：同时提供 Compose Snapshot `ids` 与协程 `flow`，
 * 便于常规 ViewModel 与 `combine(...)` 响应式流水线共用。
 */
internal class SelectionState<K> {
    private val _flow = MutableStateFlow<Set<K>>(emptySet())
    val flow: StateFlow<Set<K>> = _flow.asStateFlow()

    var ids by mutableStateOf<Set<K>>(emptySet())
        private set

    val isEmpty: Boolean get() = ids.isEmpty()
    val isNotEmpty: Boolean get() = ids.isNotEmpty()
    val size: Int get() = ids.size

    operator fun contains(id: K): Boolean = id in ids

    fun toggle(id: K) {
        replace(if (id in ids) ids - id else ids + id)
    }

    fun select(id: K) {
        replace(ids + id)
    }

    fun clear() {
        if (ids.isNotEmpty()) replace(emptySet())
    }

    fun toggleAll(candidates: Collection<K>) {
        val target = candidates.toSet()
        if (target.isEmpty()) return
        val next = if (target.all { it in ids }) ids - target else ids + target
        replace(next)
    }

    fun retain(valid: Set<K>) {
        val next = ids.intersect(valid)
        if (next != ids) replace(next)
    }

    fun removeAll(removed: Collection<K>) {
        val next = ids - removed.toSet()
        if (next != ids) replace(next)
    }

    fun replace(next: Set<K>) {
        ids = next
        _flow.value = next
    }
}

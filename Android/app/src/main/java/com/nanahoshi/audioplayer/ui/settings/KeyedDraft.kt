package com.nanahoshi.audioplayer.ui.settings

/**
 * 按模型/平台键缓存尚未保存的表单草稿，切回时恢复之前的编辑，保存或清除时丢弃对应草稿。
 */
internal class KeyedDraft<K, V> {
    private val drafts = mutableMapOf<K, V>()

    fun stash(key: K, value: V) {
        drafts[key] = value
    }

    fun restoreOr(key: K, persisted: () -> V): V = drafts[key] ?: persisted()

    fun clear(key: K) {
        drafts.remove(key)
    }

    fun hasDraft(key: K): Boolean = key in drafts
}

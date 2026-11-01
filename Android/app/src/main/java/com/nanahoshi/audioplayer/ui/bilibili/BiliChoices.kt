package com.nanahoshi.audioplayer.ui.bilibili

import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.bilibili.BiliVideoHit

internal enum class BiliAction(val label: String) { PLAY("播放"), SAVE("保存到音频库"), DOWNLOAD("下载") }
internal data class BiliPartGroup(val video: BiliVideoHit, val parts: List<BiliPart>)
internal data class BiliPartChoice(
    val action: BiliAction, val groups: List<BiliPartGroup>, val failed: List<String>, val selected: Set<Pair<String, Long>>,
) {
    val parts get() = groups.flatMap { it.parts }.distinctBy { it.bvid to it.cid }
    val chosen get() = parts.filter { (it.bvid to it.cid) in selected }
    fun toggle(part: BiliPart): BiliPartChoice {
        val key = part.bvid to part.cid
        return copy(selected = if (action == BiliAction.PLAY) setOf(key)
            else if (key in selected) selected - key else selected + key)
    }
    fun toggleAll(): BiliPartChoice {
        if (action == BiliAction.PLAY) return this
        val all = parts.map { it.bvid to it.cid }.toSet()
        return copy(selected = if (selected == all) emptySet() else all)
    }
    companion object {
        fun create(action: BiliAction, groups: List<BiliPartGroup>, failed: List<String>): BiliPartChoice {
            val parts = groups.flatMap { it.parts }.distinctBy { it.bvid to it.cid }
            return BiliPartChoice(action, groups, failed,
                (if (action == BiliAction.PLAY) parts.take(1) else parts).map { it.bvid to it.cid }.toSet())
        }
    }
}

package com.nanahoshi.audioplayer.ui.bilibili

import com.nanahoshi.audioplayer.bilibili.BiliVideoHit
import kotlin.random.Random

enum class CollectionSort(val label: String) {
    FORWARD("正序"), REVERSE("倒序"), RANDOM("随机")
}

internal fun orderCollectionVideos(
    videos: List<BiliVideoHit>,
    sort: CollectionSort,
    randomSeed: Long,
): List<BiliVideoHit> = when (sort) {
    CollectionSort.FORWARD -> videos
    CollectionSort.REVERSE -> videos.asReversed()
    // 按稳定 BV 生成随机位置，分页、刷新或元数据变化不打乱已有视频的相对顺序。
    CollectionSort.RANDOM -> videos.sortedWith(
        compareBy<BiliVideoHit> { Random(randomSeed xor it.bvid.hashCode().toLong()).nextLong() }
            .thenBy { it.bvid }
    )
}

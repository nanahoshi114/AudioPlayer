package com.nanahoshi.audioplayer.ui.bilibili

import androidx.annotation.StringRes
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.bilibili.BiliVideoHit
import kotlin.random.Random

enum class CollectionSort(@param:StringRes val labelRes: Int) {
    FORWARD(R.string.bilibili_sort_forward),
    REVERSE(R.string.bilibili_sort_reverse),
    RANDOM(R.string.bilibili_sort_random),
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

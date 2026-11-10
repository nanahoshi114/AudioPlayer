package com.nanahoshi.audioplayer.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteDownloadNoticeTest {
    @Test
    fun reportsThreeQueuedPartsAsDownloadTasks() {
        assertEquals("已加入 3 个下载任务", favoriteDownloadNotice(3, 0))
        assertEquals("已加入 3 个下载任务，1 个视频处理失败", favoriteDownloadNotice(3, 1))
    }

    @Test
    fun distinguishesFailuresFromAlreadyAvailableParts() {
        assertEquals("1 个视频处理失败", favoriteDownloadNotice(0, 1))
        assertEquals("已在本地或正在下载", favoriteDownloadNotice(0, 0))
    }
}

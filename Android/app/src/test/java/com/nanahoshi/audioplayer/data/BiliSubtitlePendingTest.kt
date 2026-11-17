package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.bilibili.BiliSubtitle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliSubtitlePendingTest {
    @Test fun pendingClearsOnlyWhenSubtitleIsSettledOrTargetGone() {
        val ready = BiliSubtitle.Ready("{}", "zh")
        // 写盘成功才清；写盘失败保留待下次接手。
        assertTrue(shouldClearPendingBiliSubtitle(ready, targetReady = true, alreadyCached = false, writeSucceeded = true))
        assertFalse(shouldClearPendingBiliSubtitle(ready, targetReady = true, alreadyCached = false, writeSucceeded = false))
        assertFalse(shouldClearPendingBiliSubtitle(ready, targetReady = true, alreadyCached = false, writeSucceeded = null))
        // 确无字幕清除。
        assertTrue(shouldClearPendingBiliSubtitle(BiliSubtitle.Missing, targetReady = true, alreadyCached = false, writeSucceeded = null))
        // 需登录、读取失败、超时保留。
        assertFalse(shouldClearPendingBiliSubtitle(BiliSubtitle.NeedsLogin, targetReady = true, alreadyCached = false, writeSucceeded = null))
        assertFalse(shouldClearPendingBiliSubtitle(BiliSubtitle.Failed, targetReady = true, alreadyCached = false, writeSucceeded = null))
        assertFalse(shouldClearPendingBiliSubtitle(null, targetReady = true, alreadyCached = false, writeSucceeded = null))
        // 目标已不在或播放时已补存清除。
        assertTrue(shouldClearPendingBiliSubtitle(null, targetReady = false, alreadyCached = false, writeSucceeded = null))
        assertTrue(shouldClearPendingBiliSubtitle(BiliSubtitle.Failed, targetReady = true, alreadyCached = true, writeSucceeded = null))
    }
}

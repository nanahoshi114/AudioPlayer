package com.nanahoshi.audioplayer.ui.bilibili

import com.nanahoshi.audioplayer.bilibili.*
import org.junit.Assert.*
import org.junit.Test

class BiliChoicesTest {
    private val first=BiliPart("BV1234567890",42,1,"one","UP",1000,"")
    private val second=first.copy(cid=43,page=2,title="two")
    private val other=first.copy(bvid="BV0987654321",title="other")
    private fun group(parts: List<BiliPart>)=BiliPartGroup(BiliVideoHit(parts.first().bvid,"video","UP","",2000),parts)

    @Test fun playAlwaysChoosesOnePartAndDoesNotSelectWholeResult() {
        val choice=BiliPartChoice.create(BiliAction.PLAY,listOf(group(listOf(first,second))),emptyList())
        assertEquals(listOf(first),choice.chosen)
        assertEquals(listOf(second),choice.toggle(second).chosen)
        assertEquals(listOf(second),choice.toggle(second).toggle(second).chosen)
        assertEquals(choice,choice.toggleAll())
    }
    @Test fun batchSaveAndDownloadSelectAcrossVideosByBothBvAndCid() {
        for (action in listOf(BiliAction.SAVE,BiliAction.DOWNLOAD)) {
            val choice=BiliPartChoice.create(action,listOf(group(listOf(first,second)),group(listOf(other))),listOf("failed"))
            assertEquals(listOf(first,second,other),choice.chosen)
            assertEquals(listOf(first,other),choice.toggle(second).chosen)
            assertTrue(choice.toggleAll().chosen.isEmpty())
            assertEquals(choice.chosen,choice.toggleAll().toggleAll().chosen)
            assertEquals(listOf("failed"),choice.toggle(first).failed)
        }
    }
    @Test fun failedOnlyAndDuplicatePartResultsCannotCreateExtraActions() {
        assertTrue(BiliPartChoice.create(BiliAction.DOWNLOAD,emptyList(),listOf("failure")).chosen.isEmpty())
        val choice=BiliPartChoice.create(BiliAction.SAVE,listOf(group(listOf(first,first))),emptyList())
        assertEquals(listOf(first),choice.chosen)
    }
}

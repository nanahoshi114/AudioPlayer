package com.nanahoshi.audioplayer.dlsite

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DlsiteProductCardsTest {
    private fun hit(workno: String, title: String = workno) = DlsiteHit(workno, title, "", null)

    @Test
    fun batchesDistinctIdsOnceBeforeFallingBackToMissingOnes() = runBlocking {
        val batches = mutableListOf<List<String>>()
        val singles = mutableListOf<String>()
        val result = resolveProductCards(
            worknos = listOf("RJ000123", " RJ000124 ", "RJ000125", "RJ000123", ""),
            fetchBatch = { ids ->
                batches += ids
                listOf(hit("RJ000123", "一"), hit("RJ000124", "二"))
            },
            fetchSingle = { id ->
                singles += id
                if (id == "RJ000125") hit("RJ000125", "三") else null
            },
        )
        assertEquals(listOf(listOf("RJ000123", "RJ000124", "RJ000125")), batches)
        assertEquals(listOf("RJ000125"), singles)
        assertEquals(listOf("RJ000123", "RJ000124", "RJ000125"), result.map { it.workno })
        assertEquals(listOf("一", "二", "三"), result.map { it.title })
    }

    @Test
    fun batchFailureFallsBackToEveryIdWithoutFailingThePage() = runBlocking {
        val singles = mutableListOf<String>()
        val result = resolveProductCards(
            worknos = listOf("RJ1", "RJ2"),
            fetchBatch = { throw IOException("network unavailable") },
            fetchSingle = { id ->
                singles += id
                hit(id)
            },
        )
        assertEquals(listOf("RJ1", "RJ2"), singles)
        assertEquals(listOf("RJ1", "RJ2"), result.map { it.workno })
    }

    @Test
    fun canonicalIdentityMatchesBatchResultsAndIgnoresUnrequestedHits() = runBlocking {
        val singles = mutableListOf<String>()
        val result = resolveProductCards(
            worknos = listOf("RJ123"),
            fetchBatch = { listOf(hit("RJ0000123", "批量"), hit("RJ999", "无关")) },
            fetchSingle = { id ->
                singles += id
                null
            },
        )
        assertEquals("批量", result.single().title)
        assertTrue(singles.isEmpty())
    }

    @Test
    fun largeSetsAreSplitIntoBoundedBatchRequests() = runBlocking {
        val batches = mutableListOf<List<String>>()
        val result = resolveProductCards(
            worknos = (1..45).map { "RJ$it" },
            fetchBatch = { ids ->
                batches += ids
                ids.map { hit(it) }
            },
            fetchSingle = { null },
            batchSize = 20,
        )
        assertEquals(listOf(20, 20, 5), batches.map { it.size })
        assertEquals(45, result.size)
    }

    @Test
    fun singleLookupFailureIsTreatedAsMissing() = runBlocking {
        val result = resolveProductCards(
            worknos = listOf("RJ1"),
            fetchBatch = { emptyList() },
            fetchSingle = { throw IOException("down") },
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun productCardsReadLocalizedFieldsAndFallBackToProductId() {
        val json = """
            [{"product_id":"RJ01111111",
              "work_name":{"zh_CN":"中文标题","ja_JP":"日本語"},
              "maker_name":"社团",
              "creaters":{"voice_by":[{"name":{"zh_CN":"声优"}}]},
              "genres":[{"name":"标签"}],
              "regist_date":"2024-01-02 03:04:05",
              "work_type":"SOU",
              "image_main":{"url":"//img.dlsite.jp/a.jpg"}}]
        """.trimIndent()
        val card = DlsiteClient.parseProductCards(json).single()
        assertEquals("RJ01111111", card.workno)
        assertEquals("中文标题", card.title)
        assertEquals("社团", card.circle)
        assertEquals(listOf("声优"), card.vas)
        assertEquals(listOf("标签"), card.tags)
        assertEquals("2024-01-02", card.release)
        assertEquals("SOU", card.workType)
        assertEquals("https://img.dlsite.jp/a.jpg", card.coverUrl)
    }
}
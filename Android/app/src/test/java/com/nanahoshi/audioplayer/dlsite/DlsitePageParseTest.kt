package com.nanahoshi.audioplayer.dlsite

import com.nanahoshi.audioplayer.data.toHttps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DlsitePageParseTest {
    @Test
    fun protocolRelativeUrlsBecomeHttps() {
        assertEquals(
            "https://img.dlsite.jp/modpub/images2/work/doujin/RJ01015000/RJ01014447_img_smp1.jpg",
            "//img.dlsite.jp/modpub/images2/work/doujin/RJ01015000/RJ01014447_img_smp1.jpg".toHttps(),
        )
    }

    @Test
    fun padsShortWorknoForChobit() {
        assertEquals("RJ01014447", DlsiteClient.worknoCandidates("RJ1014447")[1])
    }

    @Test
    fun readsChobitSampleTracks() {
        val html = """
            <ol>
              <li data-title="03 sample"
                            data-src="//file.chobit.cc/contents/2301/track_001.m4a"
                            data-playtime="10:11"
              ></li>
            </ol>
        """.trimIndent()
        val tracks = DlsiteClient.parseChobit(html, "https://chobit.cc/embed/4sho1/1f419puv", null)
        assertEquals(1, tracks.size)
        assertEquals("03 sample", tracks[0].title)
        assertEquals("https://file.chobit.cc/contents/2301/track_001.m4a", tracks[0].remoteUrl)
        assertEquals(611_000L, tracks[0].durationMs)
        assertTrue(tracks[0].referer.startsWith("https://chobit.cc/"))
    }

    @Test
    fun searchCardsKeepVoiceWorksAndTheirNames() {
        val html = """
            <div class="work_category type_SOU" data-list_item_product_id="RJ01714767">
              <div class="work_thumb"><img data-src="//img.dlsite.jp/cover.jpg" alt="封面"></div>
              <dt class="work_name"><a href="/work">本当のタイトル</a></dt>
              <dd class="maker_name"><a href="/circle">サークル</a></dd>
              <dd class="author"><a href="/va">声优A</a><a href="/va">声优B</a></dd>
            </div>
            <div class="work_category type_RPG" data-list_item_product_id="RJ01000001">
              <dt class="work_name"><a title="某游戏">某游戏</a></dt>
            </div>
            <div class="work_category type_MNG" data-list_item_product_id="RJ01000002">
              <dt class="work_name"><a title="某漫画">某漫画</a></dt>
            </div>
            <div class="work_category type_SOU" data-list_item_product_id="RJ02000003">
              <dt class="work_name">
                <div class="work_name_head"><span class="icon">R</span></div>
                <a href="/nested">嵌套标题</a>
              </dt>
              <dd class="maker_name"><a href="/circle">嵌套社团</a></dd>
            </div>
            <div class="search_result_img_box_inner" data-list_item_product_id="RJ01014447">
              <div class="work_thumb_inner"><img src="https://img.dlsite.jp/old.jpg"></div>
              <dt class="work_name"><a title="旧版标题" href="/old">旧版标题</a></dt>
              <dd class="maker_name"><a>旧社团</a></dd>
            </div>
        """.trimIndent()
        val cards = DlsiteClient.parseSearchCards(html)
        assertEquals(listOf("RJ01714767", "RJ02000003", "RJ01014447"), cards.map { it.workno })
        assertEquals("本当のタイトル", cards[0].title)
        assertEquals("サークル", cards[0].circle)
        assertEquals(listOf("声优A", "声优B"), cards[0].vas)
        assertEquals("https://img.dlsite.jp/cover.jpg", cards[0].coverUrl)
        assertEquals("旧版标题", cards[2].title)
        assertEquals("旧社团", cards[2].circle)
        assertEquals("嵌套标题", cards[1].title)
        assertEquals("嵌套社团", cards[1].circle)
    }

    @Test
    fun keepsPlayAccessCookiesWithTheLoginCookie() {
        val header = DlsiteClient.playAccessCookie(
            "dl_session=abc",
            listOf("CloudFront-Policy=policy", "CloudFront-Signature=sign"),
        )
        assertTrue(header.contains("dl_session=abc"))
        assertTrue(header.contains("CloudFront-Policy=policy"))
        assertTrue(header.contains("CloudFront-Signature=sign"))
    }
}

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

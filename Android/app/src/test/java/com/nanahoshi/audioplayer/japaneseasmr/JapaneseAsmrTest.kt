package com.nanahoshi.audioplayer.japaneseasmr

import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.JapaneseAsmrMediaEntity
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class JapaneseAsmrTest {
    @Test fun oldQueueAssociationPrefersExactKeyAndKeepsAmbiguousPaths() {
        fun media(key: String, path: String) = JapaneseAsmrMediaEntity(
            postId = "1", mediaKey = key, trackId = null, kind = JaMediaKind.DIRECT.name,
            title = key, durationMs = 0, chapters = null, pathHash = path,
        )
        fun queued(id: Long, key: String, path: String?) = QueuedTrack(
            queueItemId = id, position = id.toInt(), trackId = null, source = TrackSource.JAPANESE_ASMR,
            title = key, artist = null, durationMs = 0, coverUri = null, localUri = null,
            bvid = null, cid = null, page = null, remoteUrl = null, referer = null, workno = "1",
            fileKey = key, mediaPathHash = path,
        )
        val exact = media("t:exact", "different")
        val unique = media("t:unique", "unique")
        val overlap = listOf(media("t:one", "shared"), media("t:two", "shared"))
        val matches = matchOldJaQueue(listOf(exact, unique) + overlap, listOf(
            queued(1, "t:exact", "shared"), queued(2, "p:unique", null),
            queued(3, "t:alias", "shared"), queued(4, "t:unknown", null),
        ))
        assertEquals(OldJaQueueMatch(setOf("t:exact")), matches[1L])
        assertEquals(OldJaQueueMatch(setOf("t:unique")), matches[2L])
        assertEquals(OldJaQueueMatch(setOf("t:one", "t:two"), ambiguous = true), matches[3L])
        assertEquals(OldJaQueueMatch(emptySet()), matches[4L])
    }

    private val search = """
        <article class="entry-preview">
          <h2 class="entry-title"><a href="/135575/">癒し RJ01258546</a></h2>
          RJ01258546
          <img data-src="//img.example/cover.jpg" src="/placeholder.png">
          <time datetime="2026-01-03">1月3日</time>
        </article>
        <article class="entry-preview">
          <h2 class="entry-title"><a href="/123908/">第二篇 RJ01258546</a></h2>
        </article>
        <article class="entry-preview"><h2 class="entry-title"><a href="/31/">RJ111111 RJ222222</a></h2></article>
        <nav class="navigation pagination"><a class="next page-numbers" href="/search/test/page/2/">Next</a></nav>
    """.trimIndent()

    private fun article(players: String) = """
        <h1 class="page-title">ささやき</h1>
        <div class="entry-content">
          <p>RJ Code: RJ01258546</p>
          <p id="voice_actors">CV: A、B</p>
          <img data-src="//img.example/cover.jpg">
        </div>
        <div class="cleanPlayer">$players</div>
        <div class="audio_main"><audio src="https://files.example/duplicate.mp3"></audio></div>
        <table id="plyr-chapter-playlist">
         <tr><th>章</th></tr>
         <tr><td class="chapter_list start_time"><a data-value="83">1:23</a></td>
         <td class="chapter_list chapter_title"><a data-value="83">次の章</a></td></tr>
         <tr><td class="chapter_list start_time"><a data-value="0">0:00</a></td>
         <td class="chapter_list chapter_title">最初</td></tr>
        </table>
        <table id="basic-chapter-playlist">
         <tr><td class="chapter_list start_time"><a data-value="3">0:03</a></td>
         <td class="chapter_list chapter_title">duplicate</td></tr>
        </table>
    """.trimIndent()

    @Test fun searchKeepsSameRjArticlesAndUsesNextUrl() {
        val page = parseJaSearch(search, JapaneseAsmrClient.BASE)
        assertEquals(listOf("135575", "123908"), page.posts.map { it.postId })
        assertTrue(page.posts.all { it.rj == "RJ01258546" })
        assertEquals("https://img.example/cover.jpg", page.posts.first().coverUri)
        assertEquals("2026-01-03", page.posts.first().publishedAt)
        assertEquals("https://japaneseasmr.com/search/test/page/2/", page.next)
        assertNull(parseJaSearch("", JapaneseAsmrClient.BASE).next)
    }

    @Test fun pathMatchingPreservesIdentityAcrossPartialPlayerMetadataInBothDirections() {
        val rich = parseJaArticle(article(
            """<audio title="Voice" descr="Details" src="https://audio.example/voice.mp3?old=1"></audio>"""
        ), "1")
        val sparse = parseJaArticle(article(
            """<audio src="https://other.example/voice.mp3?new=1"></audio>"""
        ).replace("第二篇", ""), "1")
        val before = rich.media.single()
        val after = sparse.media.single()
        assertTrue(before.hasTitle)
        assertTrue(before.hasDescription)
        assertTrue(!after.hasTitle)
        assertNotEquals(before.key, after.key)
        assertEquals(before.pathHash, after.pathHash)
        assertEquals(JaMatchResult.MatchedByPath(after), matchJaMedia(sparse.media, mapOf(before.key to before.pathHash))[before.key])
        assertEquals(JaMatchResult.MatchedByPath(before), matchJaMedia(rich.media, mapOf(after.key to after.pathHash))[after.key])
    }

    @Test fun exactKeysReserveCandidatesBeforePathsAndProtectAmbiguousOrUnknownOldRows() {
        fun media(key: String, path: String) =
            JaMediaRef(key, key, listOf(JaCandidate("https://example.invalid/$path", null)), emptyList(), path)
        val sameKey = media("t:keep", "new-path")
        val renamed = media("t:renamed", "stable-path")
        val other = media("t:new", "old-path")
        val reserved = media("t:other", "stolen-path")
        val parsed = listOf(sameKey, renamed, other, reserved, media("t:one", "dup"), media("t:two", "dup"))
        val matches = matchJaMedia(parsed, linkedMapOf(
            "t:keep" to "old-path", "t:previous" to "stable-path", "t:stolen" to "stolen-path",
            "t:other" to "different", "t:unknown" to null, "t:gone" to "missing", "t:ambiguous" to "dup",
        ))
        assertEquals(JaMatchResult.MatchedByKey(sameKey), matches["t:keep"])
        assertEquals(JaMatchResult.MatchedByPath(renamed), matches["t:previous"])
        assertEquals(JaMatchResult.Ambiguous, matches["t:stolen"])
        assertEquals(JaMatchResult.MatchedByKey(reserved), matches["t:other"])
        assertEquals(JaMatchResult.InsufficientIdentity, matches["t:unknown"])
        assertEquals(JaMatchResult.Gone, matches["t:gone"])
        assertEquals(JaMatchResult.Ambiguous, matches["t:ambiguous"])
    }

    @Test fun incompletePlayerAndDuplicateKeysCannotAuthorizeCleanup() {
        val duplicate = parseJaArticle(article("""
            <audio src="https://audio.example/same.m4a?a=1"></audio>
            <audio src="https://audio.example/same.m4a?a=2"></audio>
        """.trimIndent()), "1")
        val key = duplicate.media.single().key
        assertTrue(!duplicate.complete)
        assertEquals(setOf(key), duplicate.duplicateKeys)
        assertEquals(JaMatchResult.Ambiguous, matchJaMedia(duplicate.media,
            mapOf(key to duplicate.media.single().pathHash), ambiguous = duplicate.duplicateKeys)[key])
        val missing = parseJaArticle(article("""
            <audio src="https://audio.example/known.m4a"></audio>
            <audio></audio>
        """.trimIndent()), "1")
        assertTrue(!missing.complete)
    }

    @Test fun samePlayerGroupsFormatsAndSingleTrackGetsChapters() {
        val parsed = parseJaArticle(article("""
            <video title="Track 1" descr=" 説明  文 ">
                <source src="https://files.example/a.mp3?token=x" type="audio/mpeg">
                <source src="https://files.example/a.m4a" type="audio/mp4">
            </video>
        """), "135575")
        assertEquals(listOf("A", "B"), parsed.voiceActors)
        val media = parsed.media.single()
        assertEquals(JaMediaKind.DIRECT, media.kind)
        assertEquals(2, media.candidates.size)
        assertTrue(media.key.startsWith("t:"))
        assertEquals(listOf(JaChapter(0, "最初"), JaChapter(83_000, "次の章")), media.chapters)
        val multiple = parseJaArticle(article("""
            <audio title="Track 1" descr="same"><source src="https://files.example/a.mp3?t=x"></audio>
            <audio title="Track 1" descr="same"><source src="https://files.example/b.m3u8?t=x"></audio>
        """), "135575")
        assertEquals(listOf(JaMediaKind.DIRECT, JaMediaKind.HLS), multiple.media.map { it.kind })
        assertTrue(multiple.media.all { it.chapters.isEmpty() && it.key.startsWith("p:") })
        assertNotEquals(multiple.media.first().key, multiple.media.last().key)
    }

    @Test fun mediaKeySurvivesAddressRefreshAndRejectsAmbiguousRj() {
        fun key(address: String) = parseJaArticle(article("<audio src='$address'></audio>"), "1").media.single().key
        assertEquals(key("https://one.example/track.mp3?q=first#fragment"), key("https://two.example/track.mp3?q=second"))
        val failure = runCatching { parseJaArticle(article("").replace("RJ01258546", "RJ01258546 RJ111111"), "1") }.exceptionOrNull()
        assertEquals(JaFailure.IDENTITY, (failure as JapaneseAsmrException).failure)
    }

    @Test fun listingCooldownDoesNotDelayDetailAndDetailsCachePerPost() = runBlocking {
        var now = 1L
        val requests = mutableListOf<Pair<String, Long>>()
        val client = JapaneseAsmrClient(
            now = { now }, wait = { now += it },
            pageClient = OkHttpClient.Builder().addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                requests += path to now
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body((if (path == "/135575/") article("<audio src='/track.mp3'></audio>") else search).toResponseBody())
                    .build()
            }.build(),
        )
        client.search("日/本?#")
        client.article("135575", "RJ01258546")
        client.article("135575", "RJ01258546")
        client.search("RJ01258546")
        assertEquals(listOf(1L, 1L, 10_001L), requests.map { it.second })
        assertTrue(requests.first().first.contains("%2F"))
        assertTrue(requests.first().first.contains("%3F%23"))
        val failure = runCatching { client.article("135575", "RJ1258546") }.exceptionOrNull()
        assertEquals(JaFailure.IDENTITY, (failure as JapaneseAsmrException).failure)
    }

    @Test fun siteFailuresAreDifferentFromEmptySearch() = runBlocking {
        for ((code, type) in listOf(403 to JaFailure.VERIFY, 500 to JaFailure.SERVER, 404 to JaFailure.MISSING, 0 to JaFailure.CONNECTION)) {
            val client = JapaneseAsmrClient(pageClient = OkHttpClient.Builder().addInterceptor { chain ->
                if (code == 0) throw IOException()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test")
                    .header("cf-mitigated", if (code == 403) "challenge" else "none")
                    .body("".toResponseBody()).build()
            }.build())
            val searchFailure = runCatching { client.search("a/b#") }.exceptionOrNull() as JapaneseAsmrException
            assertEquals(type, searchFailure.failure)
            if (type == JaFailure.VERIFY) {
                assertEquals("https://japaneseasmr.com/search/a%2Fb%23/", searchFailure.verificationPage)
                val articleFailure = runCatching { client.article("135575") }.exceptionOrNull() as JapaneseAsmrException
                assertEquals("https://japaneseasmr.com/135575/", articleFailure.verificationPage)
            } else {
                assertNull(searchFailure.verificationPage)
            }
        }
    }

    @Test fun verifiedPageFeedsNativePlaybackWithoutRefetchingTheArticle() = runBlocking {
        var now = 0L
        var pageRequests = 0
        val client = JapaneseAsmrClient(now = { now }, pageClient = OkHttpClient.Builder().addInterceptor { chain ->
            pageRequests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Challenge")
                .header("cf-mitigated", "challenge").body("".toResponseBody()).build()
        }.build())
        val post = "135575"
        val page = JapaneseAsmrClient.postUrl(post)
        val blocked = runCatching { client.article(post) }.exceptionOrNull() as JapaneseAsmrException
        assertEquals(page, blocked.verificationPage)
        val html = article("<audio title='fixture' src='/media/file.mp3'></audio>")
        val verified = client.rememberVerifiedArticle(page, html, "RJ01258546")
        assertEquals("https://japaneseasmr.com/media/file.mp3", verified.media.single().candidates.single().url)
        now += 600_000L
        assertEquals(verified, client.article(post, "RJ01258546"))
        assertEquals(1, pageRequests)

        client.invalidate(post)
        assertEquals(JaFailure.VERIFY, (runCatching { client.article(post) }.exceptionOrNull() as JapaneseAsmrException).failure)
        assertEquals(2, pageRequests)
    }

    @Test fun verifiedPageMustBeARealMatchingArticleWithPublicAudio() = runBlocking {
        val client = JapaneseAsmrClient()
        val html = article("<audio src='https://audio.example/fixture.mp3'></audio>")
        val page = JapaneseAsmrClient.postUrl("135575")
        for (address in listOf("http://japaneseasmr.com/135575/", "https://example.org/135575/",
            "https://japaneseasmr.com:8443/135575/", "https://japaneseasmr.com/search/test/")) {
            assertTrue(runCatching { client.rememberVerifiedArticle(address, html, null) }.isFailure)
        }
        assertTrue(runCatching { client.rememberVerifiedArticle(page, html, "RJ000001") }.isFailure)
        assertTrue(runCatching { client.rememberVerifiedArticle(page, article(""), null) }.isFailure)
        assertNull(client.cachedArticle("135575"))
    }

    @Test fun hlsRewritesAllManifestAddressesAndResumeDependsOnFullManifest() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nsub/list.m3u8"
        val addr = "https://audio.example/path/index.m3u8"
        val child = rewriteJaPlaylist(master, addr).decodeToString().lineSequence().last()
        val childUrl = restoreJaMediaUrl(child.substringAfter('?'))!!
        assertEquals("https://audio.example/path/sub/list.m3u8", childUrl)
        val media = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:4
            #EXTINF:1.04,
            ../first.ts?auth=a%2Cb
            #EXTINF:2.01,
            http://audio.example/second.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val parsed = parseJaPlaylist(media, childUrl)
        assertEquals(3050, parsed.durationMs.toInt())
        assertEquals(4, parsed.sequence.toInt())
        assertNotEquals(parsed.fingerprint, parseJaPlaylist(media.replace("a%2Cb", "next"), childUrl).fingerprint)
        val attribute = """#EXT-X-KEY:METHOD=AES-128,URI="/key.bin""""
        val rewritten = rewriteJaPlaylist(media.replace("#EXTINF:1.04,", "$attribute\n#EXTINF:1.04,"), childUrl).decodeToString()
        assertTrue(rewritten.contains("URI=\"jamedia://"))
        assertTrue(rewritten.contains("jamedia://"))
        assertNotNull(runCatching { parseJaPlaylist(master, addr) }.exceptionOrNull())
        assertNotNull(runCatching { parseJaPlaylist(media.replace("#EXTINF:1.04,", "$attribute\n#EXTINF:1.04,"), childUrl) }.exceptionOrNull())
    }
}

package com.nanahoshi.audioplayer.bilibili

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanahoshi.audioplayer.data.CredentialStore
import com.nanahoshi.audioplayer.withIsolatedApp
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BilibiliSessionAndroidTest {
    private fun jsonResponse(request: okhttp3.Request, json: String): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .header("Content-Type", "application/json").body(json.toResponseBody("application/json".toMediaType()))
            .build()

    @Test
    fun cookieInfo412EntersTenMinuteFailureCooldown() = runBlocking {
        withIsolatedApp { app ->
            val credentials = CredentialStore(app)
            credentials.saveBiliSession("SESSDATA=fixture; bili_jct=jct", "refresh-token")
            var requests = 0
            val client = BilibiliClient(credentials, OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                jsonResponse(chain.request(), """{"code":-412,"data":null,"message":"blocked"}""")
            }.build())

            client.maybeRefreshSession()
            assertEquals(1, requests)
            assertTrue(credentials.biliRefreshFailed())
            assertTrue(credentials.biliRefreshFailure.value)

            // 冷却期内不再请求 cookie/info。
            client.maybeRefreshSession()
            assertEquals(1, requests)
            assertTrue(credentials.biliRefreshFailed())
        }
    }

    @Test
    fun pendingOldTokenIsRetriedAndClearedAfterConfirmation() = runBlocking {
        withIsolatedApp { app ->
            val credentials = CredentialStore(app)
            credentials.saveBiliSession("SESSDATA=old; bili_jct=jct", "old-refresh")
            val generation = credentials.biliSession.value
            assertTrue(credentials.commitRefreshedBiliSession(
                generation, "SESSDATA=new; bili_jct=jct", "new-refresh", "old-refresh",
            ))
            credentials.markBiliRefreshChecked()
            // 待作废旧 token 必须跨进程/实例留存，启动后才能补发确认。
            assertEquals("old-refresh", CredentialStore(app).pendingBiliRevokeToken())
            val paths = mutableListOf<String>()
            val bodies = mutableListOf<String>()
            val client = BilibiliClient(credentials, OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                paths += request.url.encodedPath
                val buffer = okio.Buffer().also { request.body?.writeTo(it) }
                bodies += buffer.readUtf8()
                jsonResponse(request, """{"code":0,"data":null,"message":"ok"}""")
            }.build())

            client.maybeRefreshSession()

            assertEquals(listOf("/x/passport-login/web/confirm/refresh"), paths)
            assertTrue(bodies.single().contains("refresh_token=old-refresh"))
            assertNull(credentials.pendingBiliRevokeToken())
            assertEquals("new-refresh", credentials.biliRefreshToken())
        }
    }

    @Test
    fun accountSwitchDuringRefreshCannotOverwriteTheCurrentSession() = runBlocking {
        withIsolatedApp { app ->
            val credentials = CredentialStore(app)
            credentials.saveBiliSession("SESSDATA=old; bili_jct=jct", "old-refresh")
            val generation = credentials.biliSession.value
            var cleared = false
            var refreshed = false
            val client = BilibiliClient(credentials, OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                when {
                    request.url.encodedPath.endsWith("/cookie/info") -> {
                        // 请求返回前用户退出登录或切换账号：generation 已前进。
                        if (!cleared) {
                            credentials.clearCookie()
                            cleared = true
                        }
                        jsonResponse(request, """{"code":0,"data":{"refresh":true,"timestamp":123},"message":"ok"}""")
                    }
                    request.url.encodedPath.startsWith("/correspond/") -> {
                        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                            .body(
                                """<div id="1-name">refresh-csrf</div>""".toResponseBody("text/html".toMediaType()),
                            ).build()
                    }
                    request.url.encodedPath.endsWith("/cookie/refresh") -> {
                        refreshed = true
                        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                            .header("Content-Type", "application/json")
                            .header("Set-Cookie", "SESSDATA=new%2C1; Path=/; Domain=bilibili.com")
                            .body("""{"code":0,"data":{"refresh_token":"new-refresh"},"message":"ok"}"""
                                .toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    else -> jsonResponse(request, """{"code":0,"data":null,"message":"ok"}""")
                }
            }.build())

            client.maybeRefreshSession()

            assertTrue(cleared)
            assertTrue("刷新请求确实发出，但结果因账号切换被丢弃", refreshed)
            assertNull(credentials.cookie())
            assertNull(credentials.biliRefreshToken())
            assertNull(credentials.pendingBiliRevokeToken())
            assertTrue(credentials.biliSession.value > generation)
        }
    }
}
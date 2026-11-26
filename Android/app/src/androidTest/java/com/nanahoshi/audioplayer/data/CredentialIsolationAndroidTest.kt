package com.nanahoshi.audioplayer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.text.TextRef
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CredentialIsolationAndroidTest {
    @Test
    fun whisperRejectsAStaleJobEndpointBeforeSendingTheSavedKey() = runBlocking {
        withIsolatedApp { app ->
            val credentials = CredentialStore(app)
            credentials.saveWhisper("https://old.example/v1", "secret-old-key", WHISPER_MODEL_QWEN3)
            val file = File(app.cacheDir, "fixture-${UUID.randomUUID()}.mp3").apply { writeText("audio") }
            try {
                // 任务排队后用户改了地址：不能在旧地址携带新密钥发请求。
                credentials.saveWhisper("https://new.example/v1", "secret-new-key", WHISPER_MODEL_QWEN3)
                val calls = InflightCalls()
                val client = WhisperClient(credentials, calls)
                val failure = runCatching {
                    client.transcribeQwen3(
                        file = file,
                        base = "https://old.example/v1",
                        durationMs = 1000,
                        ticket = calls.prepare("whisper-fixture"),
                    )
                }.exceptionOrNull()
                assertTrue(failure is WhisperException)
                assertEquals(
                    TextRef.Res(R.string.whisper_error_endpoint_changed),
                    (failure as WhisperException).userText,
                )
                assertFalse(failure.message.orEmpty().contains("secret-new-key"))
                assertFalse(failure.message.orEmpty().contains("secret-old-key"))
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun translateUsesAnImmutableConfigSnapshotWithoutCrossPlatformKeyLeakage() = runBlocking {
        withIsolatedApp { app ->
            val credentials = CredentialStore(app)
            credentials.saveTranslate(TranslatePlatform.QWEN, "key-qwen", "")
            val qwenConfig = credentials.translateConfig()
            credentials.saveTranslate(TranslatePlatform.DEEPSEEK, "key-deepseek", "")
            val deepseekConfig = credentials.translateConfig()

            val seen = mutableListOf<Pair<String, String?>>()
            var failNext = false
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                seen += request.url.host to request.header("Authorization")
                if (failNext) {
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(500).message("fixture")
                        .body(" ".toResponseBody("text/plain".toMediaType())).build()
                } else {
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                        .body(
                            """{"choices":[{"message":{"content":"1|你好"},"finish_reason":"stop"}]}"""
                                .toResponseBody("application/json".toMediaType()),
                        ).build()
                }
            }.build()
            val calls = InflightCalls()
            val client = TranslateClient(credentials, calls)
            replaceClient(client, "http", http)
            replaceClient(client, "thinkingHttp", http)

            val first = client.translate(listOf("hello"), calls.prepare("translate-a"), qwenConfig)
            assertEquals(listOf("你好"), first.lines)
            // 切到另一平台后继续用旧快照：仍访问原平台地址与原密钥。
            val second = client.translate(listOf("hello"), calls.prepare("translate-a"), qwenConfig)
            assertEquals(listOf("你好"), second.lines)
            val third = client.translate(listOf("hello"), calls.prepare("translate-b"), deepseekConfig)
            assertEquals(listOf("你好"), third.lines)

            assertEquals(
                listOf(
                    TranslatePlatform.QWEN.baseUrl.substringAfter("://").substringBefore('/') to "Bearer key-qwen",
                    TranslatePlatform.QWEN.baseUrl.substringAfter("://").substringBefore('/') to "Bearer key-qwen",
                    TranslatePlatform.DEEPSEEK.baseUrl.substringAfter("://").substringBefore('/') to "Bearer key-deepseek",
                ),
                seen,
            )

            failNext = true
            val failed = client.translate(listOf("hello"), calls.prepare("translate-b"), deepseekConfig)
            assertNotNull(failed.error)
            assertFalse(failed.toString().contains("key-deepseek"))
            assertFalse(failed.toString().contains("key-qwen"))
            assertFalse(failed.raw.orEmpty().contains("key-deepseek"))
        }
    }

    private fun replaceClient(target: TranslateClient, field: String, value: OkHttpClient) {
        TranslateClient::class.java.getDeclaredField(field).apply { isAccessible = true }.set(target, value)
    }
}
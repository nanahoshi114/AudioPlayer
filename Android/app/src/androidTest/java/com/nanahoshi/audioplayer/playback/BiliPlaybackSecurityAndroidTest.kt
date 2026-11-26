package com.nanahoshi.audioplayer.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.CredentialStore
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.text.UserFacingIOException
import com.nanahoshi.audioplayer.youtube.YouTubeClient
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiliPlaybackSecurityAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val youtube = YouTubeClient({ null }, { _, _, _ -> error("unexpected solver") })

    private fun source(
        fallback: DataSource,
        http: OkHttpClient,
        db: AppDatabase,
        credentials: CredentialStore,
    ) = BiliPlaybackDataSource(
        fallback = fallback,
        asmrHttp = fallback,
        httpClient = http,
        api = BilibiliClient(credentials),
        asmr = AsmrClient(),
        dlsite = DlsiteClient(credentials),
        db = db,
        youtube = youtube,
        filesDir = context.cacheDir,
    )

    private fun reply(request: okhttp3.Request, code: Int, body: ByteArray = ByteArray(0), headerPair: Pair<String, String>? = null) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody("application/octet-stream".toMediaType()))
            .apply { headerPair?.let { (name, value) -> header(name, value) } }
            .build()

    @Test
    fun dlsiteTrialWithoutFileKeyNeverSendsTheSavedLoginCookie() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val credentials = CredentialStore(context)
            credentials.saveDlsiteCookie("session=very-secret")
            val dlsite = DlsiteClient(credentials)
            assertNotNull("前置条件：已保存的 DLsite 登录 Cookie 可用", dlsite.playCookie())
            val seen = mutableListOf<String?>()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                seen += chain.request().header("Cookie")
                reply(chain.request(), 200, ByteArray(16))
            }.build()
            val fallback = NoopDataSource()
            val dataSource = source(fallback, http, db, credentials)
            try {
                dataSource.open(
                    DataSpec(
                        Uri.parse(
                            "remote://audio?url=https://media.example/trial&referer=https%3A%2F%2Fplay.dlsite.com%2F" +
                                "&source=DLSITE&workno=RJ123&file=",
                        ),
                    ),
                )
                dataSource.read(ByteArray(16), 0, 16)
            } finally {
                dataSource.close()
            }
            assertEquals(listOf<String?>(null), seen)
        } finally {
            db.close()
        }
    }

    @Test
    fun unreadableLocalCopyClosesTheFallbackBeforeFallingBack() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val credentials = CredentialStore(context)
            val trackId = db.tracks().insert(
                TrackEntity(
                    source = TrackSource.ASMR, title = "voice", artist = null, durationMs = 1000,
                    coverUri = null, localUri = "content://missing/voice.mp3", bvid = null, cid = null,
                    page = null, addedAt = 0,
                ),
            )
            val fallback = NoopDataSource(failOnOpen = true)
            val dataSource = source(fallback, OkHttpClient.Builder().build(), db, credentials)
            val failure = runCatching {
                dataSource.open(DataSpec(Uri.parse("asmr://audio?trackId=$trackId")))
            }.exceptionOrNull()
            assertTrue(failure is UserFacingIOException)
            assertEquals(1, fallback.opens)
            assertEquals("本地副本打不开时必须关闭 fallback，避免泄漏读取器", 1, fallback.closes)
        } finally {
            db.close()
        }
    }

    @Test
    fun unsatisfiedRangeAtExactEndReturnsEofWithoutThrowing() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val credentials = CredentialStore(context)
            val total = 4096L
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                reply(chain.request(), 416, headerPair = "Content-Range" to "bytes */$total")
            }.build()
            val dataSource = source(NoopDataSource(), http, db, credentials)
            val uri = Uri.parse("remote://audio?url=https://media.example/file&source=ASMR&referer=x")
            val opened = dataSource.open(
                DataSpec.Builder().setUri(uri).setPosition(total).build(),
            )
            assertEquals(0L, opened)
            assertEquals(C.RESULT_END_OF_INPUT, dataSource.read(ByteArray(8), 0, 8))
            dataSource.close()
        } finally {
            db.close()
        }
    }

    @Test
    fun rangePastTheEndIsReportedAsOutOfRange() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val credentials = CredentialStore(context)
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                reply(chain.request(), 416, headerPair = "Content-Range" to "bytes */4096")
            }.build()
            val dataSource = source(NoopDataSource(), http, db, credentials)
            val uri = Uri.parse("remote://audio?url=https://media.example/file&source=ASMR&referer=x")
            val failure = runCatching {
                dataSource.open(DataSpec.Builder().setUri(uri).setPosition(4097).build())
            }.exceptionOrNull()
            if (failure !is DataSourceException) fail("expected DataSourceException, got $failure")
            assertEquals(
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                (failure as DataSourceException).reason,
            )
            dataSource.close()
        } finally {
            db.close()
        }
    }

    private class NoopDataSource(private val failOnOpen: Boolean = false) : DataSource {
        var opens = 0
        var closes = 0

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            opens++
            if (failOnOpen) throw java.io.IOException("missing local copy")
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = null

        override fun close() {
            closes++
        }

        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
    }
}
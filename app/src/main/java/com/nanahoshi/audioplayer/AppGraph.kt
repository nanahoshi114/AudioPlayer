package com.nanahoshi.audioplayer

import android.app.Application
import coil.EventListener
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.dlsite.RemotePreview
import com.nanahoshi.audioplayer.data.AppearancePreferences
import com.nanahoshi.audioplayer.data.CacheStore
import com.nanahoshi.audioplayer.data.CredentialStore
import com.nanahoshi.audioplayer.data.DEFAULT_CACHE_LIMIT_BYTES
import com.nanahoshi.audioplayer.data.DownloadCenter
import com.nanahoshi.audioplayer.data.LibraryPreferences
import com.nanahoshi.audioplayer.data.TranslateCenter
import com.nanahoshi.audioplayer.data.WhisperCenter
import com.nanahoshi.audioplayer.data.LibraryRepository
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.playback.PlayerClient
import com.nanahoshi.audioplayer.playback.SubtitleSource
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient

class AppGraph(app: Application) {
    val messages = MutableStateFlow<String?>(null)
    val remotePreview = MutableStateFlow<RemotePreview?>(null)
    val db = AppDatabase.create(app)
    val credentials = CredentialStore(app)
    val bilibili = BilibiliClient(credentials)
    val asmr = AsmrClient()
    val dlsite = DlsiteClient(credentials)
    val librarySort = LibraryPreferences(app)
    val appearance = AppearancePreferences(app)
    val cache = CacheStore(app) { app.imageLoader }
    val library = LibraryRepository(app, db, bilibili, asmr, dlsite)
    val downloads = DownloadCenter(app, db, asmr, dlsite, bilibili)
    val subtitles = SubtitleSource(app, db, bilibili, asmr)
    val translator = TranslateCenter(app, db, credentials, subtitles)
    val whisper = WhisperCenter(app, db, credentials, downloads, translator)
    val player = PlayerClient(app, library)
}

class AudioPlayerApplication : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.cache.trimOnStart()
    }

    override fun newImageLoader(): ImageLoader {
        val limit = if (::graph.isInitialized) graph.cache.limitBytes.value else DEFAULT_CACHE_LIMIT_BYTES
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val updated = when {
                host.contains("hdslb") || host.contains("bilibili") ->
                    request.newBuilder().header("Referer", "https://www.bilibili.com").build()
                host.contains("dlsite") -> {
                    if (request.header("Referer") != null) {
                        request
                    } else {
                        request.newBuilder()
                            .header("User-Agent", DlsiteClient.USER_AGENT)
                            .header("Referer", DlsiteClient.STORE_REFERER)
                            .build()
                    }
                }
                else -> request.newBuilder()
                    .header("User-Agent", AsmrClient.USER_AGENT)
                    .header("Referer", AsmrClient.REFERER)
                    .build()
            }
            chain.proceed(updated)
        }.build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .diskCache(
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(limit.coerceAtLeast(1L))
                    .build(),
            )
            .eventListener(object : EventListener {
                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    if (::graph.isInitialized) graph.cache.onImageLoaded()
                }
            })
            .build()
    }
}

fun Application.graph(): AppGraph = (this as AudioPlayerApplication).graph

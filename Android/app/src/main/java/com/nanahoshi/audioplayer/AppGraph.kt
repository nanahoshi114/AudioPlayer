package com.nanahoshi.audioplayer

import com.nanahoshi.audioplayer.youtube.WebViewYouTubeSolver
import com.nanahoshi.audioplayer.youtube.YouTubeClient

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
import com.nanahoshi.audioplayer.data.SenseVoiceModelManager
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.playback.CurrentSubtitles
import com.nanahoshi.audioplayer.playback.PlayerClient
import com.nanahoshi.audioplayer.playback.SubtitleDisplayPreferences
import com.nanahoshi.audioplayer.playback.SubtitleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient

class AppGraph(app: Application) {
    val messages = MutableStateFlow<String?>(null)
    val remotePreview = MutableStateFlow<RemotePreview?>(null)
    val db = AppDatabase.create(app)
    val credentials = CredentialStore(app)
    val bilibili = BilibiliClient(credentials)
    val youtube = YouTubeClient(
        { credentials.youtubeCookie() }, WebViewYouTubeSolver(app),
        diagnostics = { event ->
            android.util.Log.w("YouTubeTransfer", "stage=${event.stage} error=${event.errorTypes} http=${event.responseCode} bytes=${event.bytesRead}/${event.bytesTotal} elapsedMs=${event.elapsedMs}")
        },
    )
    val asmr = AsmrClient()
    val dlsite = DlsiteClient(credentials)
    val librarySort = LibraryPreferences(app)
    val appearance = AppearancePreferences(app)
    val cache = CacheStore(app) { app.imageLoader }
    val library = LibraryRepository(app, db, bilibili, asmr, dlsite)
    val biliCollections = com.nanahoshi.audioplayer.data.BiliCollectionRepository(app, db, bilibili)
    val biliImportRequests = com.nanahoshi.audioplayer.data.BiliImportRequests(
        { library.biliLibrary.mode(it) },
        { messages.value = "请先完成当前的入库选择" },
    )
    val dlsiteDownloadRequests = com.nanahoshi.audioplayer.data.DlsiteDownloadRequests()
    val downloads = DownloadCenter(app, db, asmr, dlsite, bilibili, youtube, library) { messages.value = it }
    val subtitles = SubtitleSource(app, db, bilibili, asmr)
    val translator = TranslateCenter(app, db, credentials, subtitles)
    val senseVoice = SenseVoiceModelManager(app)
    val whisper = WhisperCenter(app, db, credentials, downloads, translator, senseVoice)
    internal val subtitleDisplay = SubtitleDisplayPreferences(app)
    internal val currentSubtitles = CurrentSubtitles(
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        library.observeQueue(),
        combine(
            whisper.observe().map { jobs ->
                jobs.filter { it.status == DownloadStatus.DONE }.map { it.id to it.translatedAt }
            }.distinctUntilChanged(),
            subtitles.revision,
            translator.updates,
        ) { _, _, _ -> Unit },
        subtitles::load,
    )
    val player = PlayerClient(app, library) { messages.value = it }
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
                host == "i.ytimg.com" || host.endsWith(".ytimg.com") -> request.newBuilder()
                    .header("User-Agent", YouTubeClient.WEB_AGENT)
                    .header("Referer", "https://www.youtube.com/").build()
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

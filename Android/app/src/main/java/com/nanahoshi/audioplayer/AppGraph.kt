package com.nanahoshi.audioplayer

import com.nanahoshi.audioplayer.text.resolveUserError
import com.nanahoshi.audioplayer.youtube.WebViewYouTubeSolver
import com.nanahoshi.audioplayer.youtube.YouTubeClient

import android.app.Application
import androidx.annotation.StringRes
import androidx.room.withTransaction
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
import com.nanahoshi.audioplayer.data.OfflineWhisperModel
import com.nanahoshi.audioplayer.data.OfflineWhisperModelManager
import com.nanahoshi.audioplayer.data.SUBTITLE_INTERRUPTED
import com.nanahoshi.audioplayer.data.StoredCatalog
import com.nanahoshi.audioplayer.data.SubtitleBindingStore
import com.nanahoshi.audioplayer.data.SubtitleTaskRequests
import com.nanahoshi.audioplayer.data.SubtitleTaskService
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.playback.CurrentSubtitles
import com.nanahoshi.audioplayer.playback.PlayerClient
import com.nanahoshi.audioplayer.playback.SubtitleDisplayPreferences
import com.nanahoshi.audioplayer.playback.SubtitleSource
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.resolve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class AppGraph(app: Application) {
    private val appContext = app.applicationContext
    val messages = MutableStateFlow<String?>(null)

    /** 资源化提示入口；解析后仍走现有的 Snackbar 通道。 */
    fun show(@StringRes id: Int, vararg args: Any) {
        messages.value = appContext.getString(id, *args)
    }

    fun show(text: TextRef) {
        messages.value = appContext.resolve(text)
    }

    internal fun showError(error: Throwable, @StringRes fallback: Int) {
        messages.value = appContext.resolveUserError(error, fallback)
    }

    /** 迁移期保留原字符串，不改变现有行为。 */
    fun showRaw(text: String?) {
        messages.value = text
    }
    val remotePreview = MutableStateFlow<RemotePreview?>(null)
    val db = AppDatabase.create(app)
    val taskProgress = com.nanahoshi.audioplayer.data.TaskProgressCenter(db)
    val credentials = CredentialStore(app)
    val bilibili = BilibiliClient(credentials)
    val youtube = YouTubeClient(
        { credentials.youtubeCookie() }, WebViewYouTubeSolver(app),
        diagnostics = { event ->
            android.util.Log.w("YouTubeTransfer", "stage=${event.stage} error=${event.errorTypes} http=${event.responseCode} bytes=${event.bytesRead}/${event.bytesTotal} elapsedMs=${event.elapsedMs}")
        },
    )
    val asmr = AsmrClient()
    internal val jaRefreshRequests = com.nanahoshi.audioplayer.japaneseasmr.JaRefreshRequests()
    val japaneseAsmr = com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient()
    val dlsite = DlsiteClient(credentials)
    val librarySort = LibraryPreferences(app)
    val appearance = AppearancePreferences(app)
    val cache = CacheStore(app) { app.imageLoader }
    val library = LibraryRepository(app, db, bilibili, asmr, dlsite, japaneseAsmr)
    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            db.japaneseAsmr().covers().forEach(japaneseAsmr::registerImage)
        }
    }
    val biliCollections = com.nanahoshi.audioplayer.data.BiliCollectionRepository(app, db, bilibili)
    val biliImportRequests = com.nanahoshi.audioplayer.data.BiliImportRequests(
        { library.biliLibrary.mode(it) },
        { show(R.string.bili_import_choice_pending) },
    )
    val dlsiteDownloadRequests = com.nanahoshi.audioplayer.data.DlsiteDownloadRequests()
    internal val subtitleDisplay = SubtitleDisplayPreferences(app)
    val subtitles = SubtitleSource(app, db, bilibili, asmr, dlsite) { subtitleDisplay.crossSourceFallback.value }
    val subtitleBindings = SubtitleBindingStore(app, db, subtitles)
    init {
        library.onBindingsDetached = { detached, invalidate ->
            if (detached.isNotEmpty()) subtitleBindings.cleanupDetached(detached)
            else if (invalidate) subtitles.invalidate()
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                subtitleBindings.cleanupOrphans()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("SubtitleBindings", "Could not clean up unused subtitle copies", error)
            }
        }
    }
    val storedCatalog = StoredCatalog(app, db, subtitles, subtitleBindings)
    val downloads = DownloadCenter(app, db, asmr, dlsite, bilibili, youtube, library, subtitles,
        progress = taskProgress, subtitleBindings = subtitleBindings) { messages.value = it }
    internal val subtitleTaskRequests = SubtitleTaskRequests { SubtitleTaskService.start(app, it) }
    private val subtitleRecovery = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        // 恢复失败不得使启动崩溃或让 recovery.join() 重抛；两段各自捕获，恢复失败不跳过中断登记。
        try {
            subtitles.recoverPublications()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("SubtitleRecovery", "recover publications failed", error)
        }
        try {
            val automatic = db.whisper().pendingWork().filter { it.translateStatus == com.nanahoshi.audioplayer.data.db.TranslateMark.RUNNING }
            val independent = db.translations().active()
            db.withTransaction {
                db.whisper().failInterruptedTranslations(SUBTITLE_INTERRUPTED)
                db.translations().failInterrupted(SUBTITLE_INTERRUPTED)
            }
            automatic.forEach { taskProgress.interrupted(com.nanahoshi.audioplayer.data.TaskKey(com.nanahoshi.audioplayer.data.TaskKind.WHISPER, it.id), com.nanahoshi.audioplayer.data.ProgressPart.TRANSLATION) }
            independent.filter { db.translations().get(it.id)?.status == DownloadStatus.FAILED }.forEach {
                taskProgress.interrupted(com.nanahoshi.audioplayer.data.TaskKey(com.nanahoshi.audioplayer.data.TaskKind.TRANSLATION, it.id), com.nanahoshi.audioplayer.data.ProgressPart.TRANSLATION)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("SubtitleRecovery", "fail interrupted failed", error)
        }
    }
    val translator = TranslateCenter(app, db, credentials, subtitles, subtitleTaskRequests, subtitleRecovery, progress = taskProgress) { messages.value = it }
    internal val offlineWhisperModels = OfflineWhisperModel.entries.associateWith {
        OfflineWhisperModelManager(app.filesDir, it)
    }
    val whisper = WhisperCenter(
        app, db, credentials, downloads, translator, subtitles, offlineWhisperModels, subtitleTaskRequests, subtitleRecovery,
        subtitleBindings = subtitleBindings,
    )
    internal val currentSubtitles = CurrentSubtitles(
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        library.observeQueue(),
        combine(
            whisper.observe().map { jobs ->
                jobs.filter { it.status == DownloadStatus.DONE }.map { it.id to it.translatedAt }
            }.distinctUntilChanged(),
            subtitles.revision,
            translator.updates,
            subtitleDisplay.crossSourceFallback,
            db.invalidationTracker.createFlow(
                "track_subtitles", "library_files", "dlsite_original_entries", "japaneseasmr_download_entries",
            ),
        ) { _, _, _, _, _ -> Unit },
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
                ::graph.isInitialized && graph.japaneseAsmr.handlesImage(host) -> request.newBuilder()
                    .header("User-Agent", com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient.USER_AGENT)
                    .header("Referer", com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient.REFERER).build()
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

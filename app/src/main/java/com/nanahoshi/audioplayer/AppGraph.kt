package com.nanahoshi.audioplayer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.CredentialStore
import com.nanahoshi.audioplayer.data.LibraryRepository
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.playback.PlayerClient
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient

class AppGraph(app: Application) {
    val messages = MutableStateFlow<String?>(null)
    val db = AppDatabase.create(app)
    val credentials = CredentialStore(app)
    val bilibili = BilibiliClient(credentials)
    val library = LibraryRepository(app, db, bilibili)
    val player = PlayerClient(app, library)
}

class AudioPlayerApplication : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val updated = if (host.contains("hdslb") || host.contains("bilibili")) {
                request.newBuilder().header("Referer", "https://www.bilibili.com").build()
            } else {
                request
            }
            chain.proceed(updated)
        }.build()
        return ImageLoader.Builder(this).okHttpClient(client).build()
    }
}

fun Application.graph(): AppGraph = (this as AudioPlayerApplication).graph

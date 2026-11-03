package com.nanahoshi.audioplayer.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import com.nanahoshi.audioplayer.data.db.FileKind
import com.nanahoshi.audioplayer.dlsite.RemotePreview
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExternalFileAndroidTest {
    @Test fun cacheClearPreservesProtectedFilesUntilTheyAreReleased() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "external-test-${UUID.randomUUID()}")
        val prefs = "external-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(prefs, mode)
        }
        val imageLoader = ImageLoader.Builder(context).build()
        val cache = CacheStore(context) { imageLoader }
        val directory = File(root, "external-open/active").apply { mkdirs() }
        val protectedFile = File(directory, "book.pdf").apply { writeText("%PDF-fixture") }
        val ordinary = File(root, "old.tmp").apply { writeText("obsolete") }
        try {
            cache.protect(directory)
            cache.clearAll()
            assertTrue(protectedFile.isFile)
            assertFalse(ordinary.exists())
            cache.release(directory)
            cache.clearAll()
            assertEquals(0, root.walkTopDown().count { it.isFile })
        } finally {
            cache.release(directory); imageLoader.shutdown(); root.deleteRecursively(); app.deleteSharedPreferences(prefs)
        }
    }
    @Test fun existingPrivateFileOpensThroughContentUriWithoutDownloadingOrDeletingIt() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.filesDir, "external-uri-test-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(directory, "book.pdf").apply { writeText("%PDF-fixture") }
        val imageLoader = ImageLoader.Builder(app).build()
        val cache = CacheStore(app) { imageLoader }
        val http = OkHttpClient.Builder().addInterceptor { error("local files must not issue a request") }.build()
        val session = ExternalFileSession(app, cache, ExternalFileDownloader(http) { _, _ -> error("must not sign") })
        try {
            val result = session.prepare(RemotePreview("book.pdf", "", FileKind.OTHER, "", false), source.absolutePath) { _, _ -> }
            assertEquals("content", result.uri.scheme)
            assertEquals("application/pdf", result.mime)
            assertEquals("%PDF-fixture", app.contentResolver.openInputStream(result.uri)!!.bufferedReader().use { it.readText() })
            session.close()
            assertTrue(source.isFile)
        } finally { session.close(); imageLoader.shutdown(); directory.deleteRecursively() }
    }

}

package com.nanahoshi.audioplayer.youtube

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YouTubeAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun bundledSolverWorksInWebViewWithoutNavigating() = runBlocking {
        val player = """(function(){
            function make(url, key, sig) {
                var map = new Map(); map.set('alr','yes'); map.set('s',sig);
                return { __proto__: {
                    set(k,v) {map.set(k,v);}, get(k) {return map.get(k);}, clone() {return this;},
                    transform() {if(map.get('s')) map.set('s','sig_'+map.get('s')); if(map.get('n')) map.set('n','n_'+map.get('n'));}
                }};
            }
        }).call(this);"""
        val raw = WebViewYouTubeSolver(context).solve(player, listOf("test"), listOf("test"))
        val results = solvedResults(raw, true, true)
        assertEquals("sig_test", results.sig["test"])
        assertEquals("n_test", results.n["test"])
    }

    @Test fun migrationKeepsOldDataAndMatchesFreshInstall() = runBlocking {
        val name = "youtube-migration-test.db"
        val freshName = "youtube-fresh-test.db"
        context.deleteDatabase(name); context.deleteDatabase(freshName)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val schema = instrumentation.context.assets.open("schema-v12.sql").bufferedReader().use { it.readText() }
                .lineSequence().filterNot { it.startsWith("--") }.joinToString("\n")
            schema.split(';').filter { it.isNotBlank() }.forEach { old.execSQL(it) }
            old.execSQL("INSERT INTO folders(id,name,position,addedAt) VALUES(1,'folder',0,0)")
            for ((index, source) in listOf("LOCAL", "BILIBILI", "ASMR", "DLSITE").withIndex()) {
                old.execSQL("INSERT INTO tracks(id,source,title,durationMs,addedAt,folderId) VALUES(?,?,'old',1000,0,1)", arrayOf<Any>(index+1,source))
            }
            old.execSQL("INSERT INTO playlists(id,name,createdAt,updatedAt) VALUES(1,'list',0,0)")
            old.execSQL("INSERT INTO playlist_items(playlistId,trackId,position) VALUES(1,1,0)")
            old.execSQL("INSERT INTO queue_items(trackId,position) VALUES(1,0)")
            old.execSQL("INSERT INTO playback_state(id,queueItemId,positionMs) VALUES(0,1,1234)")
            old.execSQL("INSERT INTO whisper_jobs(title,fingerprint,trackId,status,progress,model) VALUES('old','fixture',1,'DONE',1,'SenseVoiceSmall')")
            old.version = 12
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17).build()
        val fresh = Room.databaseBuilder(context, AppDatabase::class.java, freshName).build()
        try {
            assertEquals(4, migrated.tracks().inFolder(1).size)
            assertEquals(1, migrated.playlists().items(1).size)
            assertEquals(1, migrated.queue().snapshot().size)
            assertEquals(1234L, migrated.queue().playbackState()!!.positionMs)
            assertEquals("fixture", migrated.whisper().done().single().fingerprint)
            assertEquals(17, migrated.openHelper.writableDatabase.version)
            fun structure(db: AppDatabase): List<String> {
                val result = mutableListOf<String>()
                val database = db.openHelper.writableDatabase
                for (pragma in listOf("table_info(tracks)", "index_list(tracks)")) {
                    database.query("PRAGMA $pragma").use { rows ->
                        while (rows.moveToNext()) {
                            // cid/seq 仅反映创建顺序；比较列、类型、默认值、唯一性和索引名。
                            result += (1 until rows.columnCount).joinToString("|") { rows.getString(it).orEmpty() }
                        }
                    }
                }
                return result.sorted()
            }
            assertEquals(structure(fresh), structure(migrated))
            val video = TrackEntity(source = TrackSource.YOUTUBE, title = "new", artist = "author", durationMs = 10,
                coverUri = null, localUri = null, bvid = null, cid = null, page = null, addedAt = 0, youtubeVideoId = "jNQXAC9IVRw")
            val trackId = migrated.tracks().insert(video)
            migrated.playlists().insertItem(PlaylistItemEntity(playlistId = 1, trackId = trackId, position = 1))
            migrated.queue().insert(QueueItemEntity(trackId = trackId, position = 1))
            migrated.queue().insert(QueueItemEntity(position = 2, source = "YOUTUBE", workno = "jNQXAC9IVRw", remoteUrl = "https://www.youtube.com/watch?v=jNQXAC9IVRw"))
            assertEquals("jNQXAC9IVRw", migrated.playlists().items(1).last().trackId.let { migrated.tracks().getById(it) }!!.youtubeVideoId)
            assertEquals(listOf(null, "jNQXAC9IVRw", "jNQXAC9IVRw"), migrated.queue().snapshot().map { it.youtubeVideoId })
            try { migrated.tracks().insert(video); fail("duplicate video id") } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        } finally {
            migrated.close(); fresh.close(); context.deleteDatabase(name); context.deleteDatabase(freshName)
        }
    }
    @Test fun completedDownloadReusesSavedTrackAndCancelledJobCannotFinish() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = java.io.File.createTempFile("youtube-transaction", ".m4a", context.cacheDir).apply { writeBytes(ByteArray(2048)) }
        try {
            val credentials = com.nanahoshi.audioplayer.data.CredentialStore(context)
            val library = com.nanahoshi.audioplayer.data.LibraryRepository(context, db,
                com.nanahoshi.audioplayer.bilibili.BilibiliClient(credentials), com.nanahoshi.audioplayer.asmr.AsmrClient(),
                com.nanahoshi.audioplayer.dlsite.DlsiteClient(credentials))
            val video = YouTubeVideo("jNQXAC9IVRw", "fixture")
            val saved = library.saveYouTube(video)
            val jobId = db.downloads().insert(DownloadEntity(trackId = 0, title = video.title, bytesDone = 0, bytesTotal = 0,
                status = DownloadStatus.RUNNING, error = null, source = "YOUTUBE", workKey = video.id))
            val finished = library.saveYouTube(video, file.path, jobId)
            assertEquals(saved.id, finished.id)
            assertEquals(1, db.tracks().inFolder(null).size)
            assertEquals(file.path, db.tracks().getById(saved.id)!!.localUri)
            assertEquals(saved.id, db.downloads().get(jobId)!!.trackId)
            assertEquals(DownloadStatus.DONE, db.downloads().get(jobId)!!.status)
            val cancelled = db.downloads().insert(DownloadEntity(trackId = 0, title = "cancelled", bytesDone = 0, bytesTotal = 0,
                status = DownloadStatus.RUNNING, error = null, source = "YOUTUBE", workKey = "BaW_jenozKc"))
            assertEquals(1, db.downloads().cancelYouTube(cancelled))
            assertEquals(0, db.downloads().startQueued(cancelled))
            try { library.saveYouTube(YouTubeVideo("BaW_jenozKc", "cancelled"), file.path, cancelled); fail() }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertNull(db.tracks().findYouTube("BaW_jenozKc"))
            assertEquals(1, db.tracks().inFolder(null).size)
        } finally { db.close(); file.delete() }
    }

}

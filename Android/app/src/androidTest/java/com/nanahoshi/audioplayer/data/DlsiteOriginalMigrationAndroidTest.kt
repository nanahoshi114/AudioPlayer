package com.nanahoshi.audioplayer.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DlsiteOriginalEntryEntity
import com.nanahoshi.audioplayer.data.db.DlsiteOriginalWorkEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DlsiteOriginalMigrationAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun migration14To16PreservesDataAndMatchesFreshSchema() = runBlocking {
        val name = "dlsite-original-migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val schema = instrumentation.context.assets.open("schema-v12.sql").bufferedReader().use { it.readText() }
                .lineSequence().filterNot { it.startsWith("--") }.joinToString("\n")
            schema.split(';').filter { it.isNotBlank() }.forEach { old.execSQL(it) }
            old.execSQL("ALTER TABLE tracks ADD COLUMN youtubeVideoId TEXT")
            old.execSQL("CREATE UNIQUE INDEX index_tracks_youtubeVideoId ON tracks (youtubeVideoId)")
            old.execSQL("ALTER TABLE downloads ADD COLUMN biliImportPart TEXT")
            old.execSQL("INSERT INTO folders(id,name,position,asmrWorkId,asmrSourceId) VALUES(1,'work',0,123456,'RJ123456')")
            old.execSQL("INSERT INTO tracks(id,source,title,durationMs,addedAt,folderId,asmrWorkId,fileHash,localUri,remoteUrl) VALUES(1,'DLSITE','voice.wav',1000,0,1,123456,'dlsite:play.mp3','content://fixture/voice','https://example.invalid/play.mp3')")
            old.execSQL("INSERT INTO library_files(id,folderId,name,kind,remoteUrl,addedAt,asmrWorkId,fileHash) VALUES(1,1,'readme.txt','TEXT','https://example.invalid/readme.txt',0,123456,'dlsite:readme.txt')")
            old.execSQL("INSERT INTO downloads(id,trackId,title,bytesDone,bytesTotal,status,source,workKey,localPath) VALUES(1,1,'voice',2048,2048,'DONE','DLSITE','RJ123456','/fixture/old.mp3')")
            old.execSQL("INSERT INTO whisper_jobs(id,title,fingerprint,trackId,status,progress,skipTranslate,model) VALUES(1,'voice','fixture',1,'DONE',1,1,'SenseVoiceSmall')")
            old.execSQL("INSERT INTO playlists(id,name,createdAt,updatedAt) VALUES(1,'list',0,0)")
            old.execSQL("INSERT INTO playlist_items(playlistId,trackId,position) VALUES(1,1,0)")
            old.execSQL("INSERT INTO queue_items(trackId,position) VALUES(1,0)")
            old.execSQL("INSERT INTO playback_state(id,queueItemId,positionMs) VALUES(0,1,100)")
            old.version = 14
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16).build()
        val fresh = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val track = migrated.tracks().getById(1)!!
            assertEquals("dlsite:play.mp3", track.fileHash)
            assertEquals("content://fixture/voice", track.localUri)
            assertEquals(1L, migrated.playlists().items(1).single().trackId)
            assertEquals(1L, migrated.queue().snapshot().single().trackId)
            assertEquals(100L, migrated.queue().playbackState()!!.positionMs)
            val download = migrated.downloads().get(1)!!
            assertEquals("/fixture/old.mp3", download.localPath)
            assertEquals(2048L, download.bytesDone)
            assertNull(download.kind)
            assertNull(download.phase)
            assertNull(migrated.files().get(1)!!.localUri)
            val whisper = migrated.whisper().get(1)!!
            assertEquals("fixture", whisper.fingerprint)
            assertEquals(true, whisper.skipTranslate)
            assertEquals("SenseVoiceSmall", whisper.model)
            assertNull(whisper.awaitDownloadId)
            assertNull(whisper.awaitFileKey)
            fun structure(db: AppDatabase): List<String> {
                val sql = db.openHelper.writableDatabase
                assertEquals(16, sql.version)
                return buildList {
                    for (table in listOf("folders", "bili_import_intents", "downloads", "library_files", "whisper_jobs", "dlsite_original_works", "dlsite_original_entries")) {
                        for (pragma in listOf("table_info", "index_list")) {
                            sql.query("PRAGMA $pragma($table)").use { rows ->
                                while (rows.moveToNext()) add("$table|$pragma|" +
                                    (1 until rows.columnCount).joinToString("|") { rows.getString(it).orEmpty() })
                            }
                        }
                    }
                }.sorted()
            }
            assertEquals(structure(fresh), structure(migrated))
            assertTrue(migrated.dlsiteOriginals().works().isEmpty())
            assertNull(migrated.folders().get(1)!!.biliBvid)
            assertTrue(migrated.biliImports().all().isEmpty())
            migrated.dlsiteOriginals().upsertWork(DlsiteOriginalWorkEntity("RJ123456", 1, "/fixture/original", "generation"))
            migrated.dlsiteOriginals().upsertEntries(listOf(DlsiteOriginalEntryEntity(
                "RJ123456", "voice.wav", "AUDIO", 4096, "/fixture/original/voice.wav", "play.mp3", trackId = 1,
            )))
            migrated.folders().delete(1)
            assertNull(migrated.tracks().getById(1))
            assertEquals("generation", migrated.dlsiteOriginals().work("RJ123456")!!.generation)
            assertEquals("voice.wav", migrated.dlsiteOriginals().entries("RJ123456").single().relativePath)
        } finally {
            migrated.close()
            fresh.close()
            context.deleteDatabase(name)
        }
    }
}

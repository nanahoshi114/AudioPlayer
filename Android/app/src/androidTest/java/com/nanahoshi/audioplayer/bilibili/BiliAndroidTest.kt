package com.nanahoshi.audioplayer.bilibili

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.data.*
import com.nanahoshi.audioplayer.data.db.*
import com.nanahoshi.audioplayer.playback.toMediaItem
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BiliAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val part = BiliPart("BV1234567890",42,1,"fixture","UP",1000,"https://i0.hdslb.com/test.jpg")
    private fun library(db: AppDatabase): LibraryRepository {
        val credentials = CredentialStore(context)
        return LibraryRepository(context,db,BilibiliClient(credentials),com.nanahoshi.audioplayer.asmr.AsmrClient(),
            com.nanahoshi.audioplayer.dlsite.DlsiteClient(credentials))
    }
    private fun job(metadata: String? = null, status: DownloadStatus = DownloadStatus.RUNNING) = DownloadEntity(
        trackId=0,title=part.title,bytesDone=0,bytesTotal=0,status=status,error=null,
        source="BILIBILI",workKey=part.bvid,fileKey=part.cid.toString(),biliImportPart=metadata,
    )

    @Test fun migration13To14KeepsAllAssociationsAndMatchesFreshSchema() = runBlocking {
        val name="bili-migration-test.db";context.deleteDatabase(name)
        context.getDatabasePath(name).parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null).use { old ->
            val schema=instrumentation.context.assets.open("schema-v12.sql").bufferedReader().use { it.readText() }
                .lineSequence().filterNot { it.startsWith("--") }.joinToString("\n")
            schema.split(';').filter { it.isNotBlank() }.forEach { old.execSQL(it) }
            old.execSQL("ALTER TABLE tracks ADD COLUMN youtubeVideoId TEXT")
            old.execSQL("CREATE UNIQUE INDEX index_tracks_youtubeVideoId ON tracks (youtubeVideoId)")
            old.execSQL("INSERT INTO folders(id,name,position,addedAt,asmrSourceId,asmrWorkId) VALUES(1,'work',0,0,'123',123)")
            for ((index,source) in listOf("LOCAL","BILIBILI","ASMR","DLSITE","YOUTUBE").withIndex()) {
                old.execSQL("INSERT INTO tracks(id,source,title,durationMs,addedAt,folderId) VALUES(?,?,'old',1000,0,1)",arrayOf<Any>(index+1,source))
            }
            old.execSQL("UPDATE tracks SET youtubeVideoId='jNQXAC9IVRw' WHERE id=5")
            old.execSQL("INSERT INTO playlists(id,name,createdAt,updatedAt) VALUES(1,'list',0,0)")
            old.execSQL("INSERT INTO playlist_items(playlistId,trackId,position) VALUES(1,2,0)")
            old.execSQL("INSERT INTO queue_items(trackId,position) VALUES(2,0)")
            old.execSQL("INSERT INTO playback_state(id,queueItemId,positionMs) VALUES(0,1,1234)")
            old.execSQL("INSERT INTO downloads(trackId,title,bytesDone,bytesTotal,status) VALUES(2,'old',100,100,'DONE')")
            old.execSQL("INSERT INTO whisper_jobs(title,fingerprint,trackId,status,progress) VALUES('old','fixture',2,'DONE',1)")
            old.execSQL("INSERT INTO translation_jobs(title,trackId,status) VALUES('old',2,'DONE')")
            old.version=13
        }
        val migrated=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(AppDatabase.MIGRATION_13_14,AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17, AppDatabase.MIGRATION_17_18, AppDatabase.MIGRATION_18_19).build()
        val fresh=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            assertEquals(5,migrated.tracks().inFolder(1).size)
            assertEquals("123",migrated.folders().get(1)!!.asmrSourceId)
            assertEquals(2L,migrated.playlists().items(1).single().trackId)
            assertEquals(2L,migrated.queue().snapshot().single().trackId)
            assertEquals(1234L,migrated.queue().playbackState()!!.positionMs)
            assertEquals("fixture",migrated.whisper().done().single().fingerprint)
            assertNull(migrated.downloads().get(1)!!.biliImportPart)
            assertEquals("jNQXAC9IVRw",migrated.tracks().getById(5)!!.youtubeVideoId)
            fun structure(db: AppDatabase): List<String> {
                val result=mutableListOf<String>();val sql=db.openHelper.writableDatabase
                assertEquals(19,sql.version)
                for (table in listOf("folders","tracks","downloads","queue_items","playlists","playlist_items","whisper_jobs","translation_jobs")) {
                    sql.query("PRAGMA table_info($table)").use { rows ->
                        while(rows.moveToNext()) result += table+"|"+(1 until rows.columnCount).joinToString("|") { rows.getString(it).orEmpty() }
                    }
                }
                return result.sorted()
            }
            assertEquals(structure(fresh),structure(migrated))
            migrated.openHelper.readableDatabase.query("SELECT trackId FROM translation_jobs").use { assertTrue(it.moveToFirst());assertEquals(2L,it.getLong(0)) }
        } finally { migrated.close();fresh.close();context.deleteDatabase(name) }
    }

    @Test fun completingADownloadRequiresTheExactSavedPart() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File.createTempFile("bili-complete", ".m4s", context.cacheDir).apply { writeBytes(ByteArray(2048)) }
        try {
            val repo = library(db)
            val folder = db.folders().insert(FolderEntity(name = "folder", position = 0))
            val other = repo.importParts(listOf(part.copy(cid = 43)), folder).single()
            val id = db.downloads().insert(job().copy(trackId = other.id))
            assertTrue(runCatching { repo.completeBiliDownload(id, part.bvid, part.cid, file.path) }.isFailure)
            assertNull(db.tracks().findByBvid(part.bvid, part.cid))

            val saved = repo.importParts(listOf(part), folder).single()
            db.downloads().attachTrack(id, saved.id)
            repo.completeBiliDownload(id, part.bvid, part.cid, file.path)
            assertEquals(saved.id, db.downloads().get(id)!!.trackId)
            assertEquals(DownloadStatus.DONE, db.downloads().get(id)!!.status)
            assertEquals(true, db.downloads().get(id)!!.pendingBiliSubtitle)
            assertEquals(folder, db.tracks().getById(saved.id)!!.folderId)
            assertEquals(file.path, db.tracks().getById(saved.id)!!.localUri)
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test fun remoteQueueKeepsIdentityAndBuildsBiliManifestWithoutSavingTrack() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            val remote=RemotePlayable(part.title,part.artist,part.coverUrl,"https://www.bilibili.com/video/${part.bvid}",
                TrackSource.BILIBILI,"https://www.bilibili.com/",part.durationMs,part.bvid,part.cid.toString())
            library(db).replaceRemoteQueue(listOf(remote))
            val row=db.queue().snapshot().single();assertNull(row.trackId)
            assertEquals(part.bvid,row.workno);assertEquals("42",row.fileKey)
            val item=row.toMediaItem(context)
            assertEquals("bilibili",item.localConfiguration!!.uri.scheme)
            assertEquals(part.bvid,item.localConfiguration!!.uri.getQueryParameter("bvid"))
            assertEquals("42",item.localConfiguration!!.uri.getQueryParameter("cid"))
            assertEquals(androidx.media3.common.MimeTypes.APPLICATION_MPD,item.localConfiguration!!.mimeType)
            assertTrue(db.tracks().inFolder(null).isEmpty())
        } finally { db.close() }
    }
}

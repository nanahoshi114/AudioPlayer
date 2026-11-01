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
        val migrated=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(AppDatabase.MIGRATION_13_14).build()
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
                assertEquals(14,sql.version)
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

    @Test fun enqueueDeduplicatesAndUpgradesOldCacheTaskWithoutCreatingTracks() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val root=File(context.cacheDir,"bili-enqueue-test").apply { deleteRecursively();mkdirs() }
        try {
            val store=BiliDownloads(root,db,library(db))
            assertEquals(1,store.enqueue(listOf(part,part)))
            val old=db.downloads().nextQueued()!!;assertNull(old.biliImportPart)
            assertEquals(0,store.enqueue(listOf(part),true))
            assertEquals(part,biliImportPart(db.downloads().get(old.id)!!.biliImportPart!!,part.bvid,part.cid))
            assertTrue(db.tracks().inFolder(null).isEmpty())
            db.downloads().fail(old.id,"fixture failed")
            assertEquals(1,store.enqueue(listOf(part),true))
            assertTrue(db.tracks().inFolder(null).isEmpty())
        } finally { db.close();root.deleteRecursively() }
    }

    @Test fun completionReusesTrackAndFolderAndReadsLatestIntent() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val file=File.createTempFile("bili-complete", ".m4s",context.cacheDir).apply { writeBytes(ByteArray(2048)) }
        try {
            val repo=library(db);val folder=db.folders().insert(FolderEntity(name="folder",position=0))
            val saved=repo.importParts(listOf(part),folder).single()
            val id=db.downloads().insert(job())
            db.downloads().setBiliImportPart(id,part.importMetadata())
            repo.completeBiliDownload(id,part.bvid,part.cid,file.path)
            assertEquals(saved.id,db.downloads().get(id)!!.trackId)
            assertEquals(DownloadStatus.DONE,db.downloads().get(id)!!.status)
            assertEquals(folder,db.tracks().getById(saved.id)!!.folderId)
            assertEquals(file.path,db.tracks().getById(saved.id)!!.localUri)
            assertEquals(1,db.tracks().inFolder(folder).size)
            val other=part.copy(cid=43)
            val failed=db.downloads().insert(job(other.importMetadata(),DownloadStatus.FAILED).copy(fileKey="43"))
            try { repo.completeBiliDownload(failed,other.bvid,other.cid,file.path);fail() } catch (_: IllegalStateException) { }
            assertNull(db.tracks().findByBvid(other.bvid,other.cid))
        } finally { db.close();file.delete() }
    }

    @Test fun oldCacheDoesNotAutoImportButSearchReusesFileAndSavesIt() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val root=File(context.cacheDir,"bili-cache-import-test").apply { deleteRecursively();mkdirs() }
        val file=biliAudioFile(root,part.bvid,part.cid)!!.apply { parentFile!!.mkdirs();writeBytes(ByteArray(2048)) }
        try {
            val repo=library(db);val id=db.downloads().insert(job())
            repo.completeBiliDownload(id,part.bvid,part.cid,file.path)
            assertTrue(db.tracks().inFolder(null).isEmpty())
            assertEquals(0,BiliDownloads(root,db,repo).enqueue(listOf(part),true))
            val saved=db.tracks().inFolder(null).single()
            assertEquals(file.path,saved.localUri);assertEquals(saved.id,db.downloads().get(id)!!.trackId)
            assertEquals(0,BiliDownloads(root,db,repo).enqueue(listOf(part),true))
            assertEquals(1,db.tracks().inFolder(null).size)
            file.writeBytes(ByteArray(0))
            assertEquals(1,BiliDownloads(root,db,repo).enqueue(listOf(part),true))
        } finally { db.close();root.deleteRecursively() }
    }

    @Test fun retryAfterDatabaseReopenStillImportsOnlyOnSuccessfulCompletion() = runBlocking {
        val name="bili-retry-test.db";context.deleteDatabase(name)
        val file=File.createTempFile("bili-retry", ".m4s",context.cacheDir).apply { writeBytes(ByteArray(2048)) }
        var db=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
        try {
            val id=db.downloads().insert(job(part.importMetadata(),DownloadStatus.FAILED))
            assertNull(db.tracks().findByBvid(part.bvid,part.cid))
            db.close();db=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
            assertEquals(part,biliImportPart(db.downloads().get(id)!!.biliImportPart!!,part.bvid,part.cid))
            db.downloads().requeue(id);assertEquals(1,db.downloads().startQueued(id))
            library(db).completeBiliDownload(id,part.bvid,part.cid,file.path)
            val saved=db.tracks().inFolder(null).single()
            assertEquals(part.title,saved.title);assertEquals(part.artist,saved.artist)
            assertEquals(part.bvid,saved.bvid);assertEquals(part.cid,saved.cid)
            assertEquals(saved.id,db.downloads().get(id)!!.trackId)
            assertEquals(DownloadStatus.DONE,db.downloads().get(id)!!.status)
        } finally { db.close();context.deleteDatabase(name);file.delete() }
    }

    @Test fun remoteQueueKeepsIdentityAndBuildsBiliManifestWithoutSavingTrack() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            val remote=RemotePlayable(part.title,part.artist,part.coverUrl,"https://www.bilibili.com/video/${part.bvid}",
                TrackSource.BILIBILI,"https://www.bilibili.com/",part.durationMs,part.bvid,part.cid.toString())
            library(db).replaceRemoteQueue(listOf(remote))
            val row=db.queue().snapshot().single();assertNull(row.trackId)
            assertEquals(part.bvid,row.workno);assertEquals("42",row.fileKey)
            val item=row.toMediaItem()
            assertEquals("bilibili",item.localConfiguration!!.uri.scheme)
            assertEquals(part.bvid,item.localConfiguration!!.uri.getQueryParameter("bvid"))
            assertEquals("42",item.localConfiguration!!.uri.getQueryParameter("cid"))
            assertEquals(androidx.media3.common.MimeTypes.APPLICATION_MPD,item.localConfiguration!!.mimeType)
            assertTrue(db.tracks().inFolder(null).isEmpty())
        } finally { db.close() }
    }
}

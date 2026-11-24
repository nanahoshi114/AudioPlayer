package com.nanahoshi.audioplayer.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.SubtitlePublicationEntity
import com.nanahoshi.audioplayer.data.db.WhisperDao
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhisperDaoAndroidTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun requeueWithFileOnlyTouchesFailedAndUnpublishedRows() = runBlocking {
        val dao = db.whisper()
        val failed = insert(dao, DownloadStatus.FAILED)
        val running = insert(dao, DownloadStatus.RUNNING)
        val queued = insert(dao, DownloadStatus.QUEUED)
        val done = insert(dao, DownloadStatus.DONE)

        assertEquals(1, requeueWithFile(dao, failed))
        val updated = checkNotNull(dao.get(failed))
        assertEquals(DownloadStatus.QUEUED, updated.status)
        assertEquals("Requeued", updated.title)
        assertEquals(0f, updated.progress, 0f)
        assertNull(updated.error)

        for (id in listOf(running, queued, done)) {
            assertEquals(0, requeueWithFile(dao, id))
            assertEquals(originalStatus(id, failed, running, queued, done), dao.get(id)?.status)
        }

        val published = insert(dao, DownloadStatus.FAILED)
        db.translations().stagePublication(
            SubtitlePublicationEntity(
                filename = "published.srt", payload = "{}", whisperJobId = published,
                translationJobId = null, translatedAt = 0,
            ),
        )
        assertEquals(0, requeueWithFile(dao, published))
        assertEquals(DownloadStatus.FAILED, dao.get(published)?.status)
    }

    @Test
    fun requeueForDownloadOnlyTouchesFailedAndUnpublishedRows() = runBlocking {
        val dao = db.whisper()
        val failed = insert(dao, DownloadStatus.FAILED)
        val running = insert(dao, DownloadStatus.RUNNING)
        val queued = insert(dao, DownloadStatus.QUEUED)
        val done = insert(dao, DownloadStatus.DONE)

        assertEquals(1, dao.requeueForDownload(failed))
        assertEquals(DownloadStatus.QUEUED, dao.get(failed)?.status)
        assertEquals("", dao.get(failed)?.fingerprint)

        for (id in listOf(running, queued, done)) {
            assertEquals(0, dao.requeueForDownload(id))
        }
        assertEquals(DownloadStatus.RUNNING, dao.get(running)?.status)
        assertEquals(DownloadStatus.QUEUED, dao.get(queued)?.status)
        assertEquals(DownloadStatus.DONE, dao.get(done)?.status)

        val published = insert(dao, DownloadStatus.FAILED)
        db.translations().stagePublication(
            SubtitlePublicationEntity(
                filename = "published.srt", payload = "{}", whisperJobId = published,
                translationJobId = null, translatedAt = 0,
            ),
        )
        assertEquals(0, dao.requeueForDownload(published))
        assertEquals(DownloadStatus.FAILED, dao.get(published)?.status)
    }

    private suspend fun insert(dao: WhisperDao, status: DownloadStatus): Long = dao.insert(
        WhisperJobEntity(
            title = "Job", fingerprint = "fp", trackId = null, localPath = null, remoteJobId = null,
            status = status, progress = 0f, error = null, awaitRemoteUrl = null,
        ),
    )

    private suspend fun requeueWithFile(dao: WhisperDao, id: Long): Int = dao.requeueWithFile(
        id = id,
        title = "Requeued",
        fingerprint = "new-fp",
        trackId = null,
        path = "/tmp/requeued.m4a",
        url = null,
        endpoint = null,
        skipTranslate = null,
        model = null,
    )

    private fun originalStatus(id: Long, failed: Long, running: Long, queued: Long, done: Long) = when (id) {
        failed -> DownloadStatus.FAILED
        running -> DownloadStatus.RUNNING
        queued -> DownloadStatus.QUEUED
        else -> DownloadStatus.DONE
    }
}
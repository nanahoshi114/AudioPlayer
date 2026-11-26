package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.text.persistedErrorText
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.R
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal const val AUDIO_DOWNLOAD_START_FAILED = PersistedErrors.AUDIO_DOWNLOAD_START_FAILED
internal const val AUDIO_DOWNLOAD_INTERRUPTED = PersistedErrors.AUDIO_DOWNLOAD_INTERRUPTED
internal const val AUDIO_DOWNLOAD_TIMEOUT = PersistedErrors.AUDIO_DOWNLOAD_TIMEOUT

internal class AudioDownloadStopped(
    val persistedError: String,
) : CancellationException(persistedError), UserFacingError {
    override val userText = persistedErrorText(persistedError) ?: TextRef.Raw(persistedError)
}

internal class AudioDownloadService : DataSyncForegroundService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val downloads get() = application.graph().downloads
    private val notifications by lazy { getSystemService(android.app.NotificationManager::class.java) }
    private var worker: Job? = null
    private val preparing = mutableMapOf<Int, Job>()
    private val triedSubtitles = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private var generation = 0L
    @Volatile private var activeIds = emptyList<Long>()
    private var latestStartId = 0

    override val channelId get() = CHANNEL_ID
    override val channelNameRes get() = R.string.channel_audio_downloads
    override val notificationId get() = NOTIFICATION_ID

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val requests = downloads.requests
        val request = requests.take(intent?.getLongExtra(EXTRA_REQUEST, 0L) ?: 0L)
        if (stopping || !promote()) {
            request?.cancel(AudioDownloadStopped(AUDIO_DOWNLOAD_START_FAILED))
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (request != null) {
            val preparation = request.launchIn(scope)
            preparing[startId] = preparation
            preparation.invokeOnCompletion {
                scope.launch {
                    preparing.remove(startId)
                    wakeQueue()
                }
            }
            preparation.start()
        } else {
            wakeQueue()
        }
        return START_NOT_STICKY
    }

    private fun wakeQueue() {
        if (stopping) return
        generation++
        if (worker != null) return
        worker = scope.launch(start = CoroutineStart.LAZY) {
            try {
                do {
                    val observed = generation
                    downloads.drainQueue(triedSubtitles) { activeIds = it }
                } while (observed != generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                downloads.failAfterServiceStop(activeIds, AUDIO_DOWNLOAD_INTERRUPTED)
            } finally {
                worker = null
                if (preparing.isEmpty() && !stopping) stopSelfResult(latestStartId)
            }
        }
        worker?.start()
    }

    private fun promote(): Boolean {
        if (isForeground) return true
        if (!enterForeground(notification(AudioProgressNotification()).build())) return false
        scope.launch {
            combine(
                downloads.observeActive().map { rows -> rows.map { it.copy(bytesDone = 0, bytesTotal = 0, progressSummary = null) } }.distinctUntilChanged(),
                application.graph().taskProgress.snapshots,
            ) { jobs, snapshots -> audioNotificationState(this@AudioDownloadService, jobs, snapshots) }
                .distinctUntilChanged().collect { state ->
                    notifications.notify(NOTIFICATION_ID, notification(state).build())
                }
        }
        return true
    }

    private fun notification(state: AudioProgressNotification): NotificationCompat.Builder =
        DataSyncNotifications.progressBuilder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notification_audio_title))
            .setContentText(state.title)
            .setContentIntent(DataSyncNotifications.openApp(this, NOTIFICATION_ID, OPEN_DOWNLOADS))
            .setProgress(100, state.percent ?: 0, state.percent == null)
            .setSubText(state.detail)

    override fun onTimeout(startId: Int, fgsType: Int) {
        markStopping()
        val reason = AudioDownloadStopped(AUDIO_DOWNLOAD_TIMEOUT)
        scope.cancel(reason)
        downloads.requests.stopPending(reason) { stopSelf() }
    }

    override fun onDestroy() {
        markStopping()
        scope.cancel(AudioDownloadStopped(AUDIO_DOWNLOAD_INTERRUPTED))
        exitForeground()
        super.onDestroy()
    }

    companion object {
        internal const val OPEN_DOWNLOADS = "com.nanahoshi.audioplayer.OPEN_AUDIO_DOWNLOADS"
        private const val EXTRA_REQUEST = "request"
        private const val CHANNEL_ID = "audio_downloads"
        private const val NOTIFICATION_ID = 0x4155

        internal fun updateJaVerificationNotification(context: Context, count: Int) {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            val notificationId = NOTIFICATION_ID + 1
            if (count == 0) {
                manager.cancel(notificationId)
                return
            }
            DataSyncNotifications.ensureChannel(context, CHANNEL_ID, R.string.channel_audio_downloads)
            val notice = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(context.getString(R.string.notification_ja_work_verification_title))
                .setContentText(context.getString(R.string.notification_ja_work_verification_message, count))
                .setContentIntent(DataSyncNotifications.openApp(context, notificationId, OPEN_DOWNLOADS))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
            try {
                manager.notify(notificationId, notice)
            } catch (_: SecurityException) {
                // 系统未授予通知权限时仍可在下载页继续。
            }
        }

        fun start(context: Context, id: Long = 0L): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, AudioDownloadService::class.java).putExtra(EXTRA_REQUEST, id))
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
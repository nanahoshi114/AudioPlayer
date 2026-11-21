package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.text.persistedErrorText
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nanahoshi.audioplayer.MainActivity
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
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
import java.util.concurrent.TimeUnit

internal const val AUDIO_DOWNLOAD_START_FAILED = PersistedErrors.AUDIO_DOWNLOAD_START_FAILED
internal const val AUDIO_DOWNLOAD_INTERRUPTED = PersistedErrors.AUDIO_DOWNLOAD_INTERRUPTED
internal const val AUDIO_DOWNLOAD_TIMEOUT = PersistedErrors.AUDIO_DOWNLOAD_TIMEOUT

internal class AudioDownloadStopped(
    val persistedError: String,
) : CancellationException(persistedError), UserFacingError {
    override val userText = persistedErrorText(persistedError) ?: TextRef.Raw(persistedError)
}

internal class AudioDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val downloads get() = application.graph().downloads
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private var worker: Job? = null
    private val preparing = mutableMapOf<Int, Job>()
    private val triedSubtitles = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private var stopping = false
    private var generation = 0L
    @Volatile private var activeIds = emptyList<Long>()
    private var foreground = false
    private var latestStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val requests = downloads.requests
        val request = requests.take(intent?.getLongExtra(EXTRA_REQUEST, 0L) ?: 0L)
        if (stopping || (!foreground && !promote())) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_audio_downloads), NotificationManager.IMPORTANCE_LOW),
            )
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(AudioProgressNotification()).build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (_: IllegalStateException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
        foreground = true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:AudioDownload")
            .apply {
                setReferenceCounted(false)
                acquire(TimeUnit.HOURS.toMillis(6))
            }
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

    private fun notification(state: AudioProgressNotification): NotificationCompat.Builder {
        val openDownloads = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java)
                .setAction(OPEN_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notification_audio_title))
            .setContentText(state.title)
            .setContentIntent(openDownloads)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, state.percent ?: 0, state.percent == null)
        builder.setSubText(state.detail)
        return builder
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopping = true
        val reason = AudioDownloadStopped(AUDIO_DOWNLOAD_TIMEOUT)
        scope.cancel(reason)
        downloads.requests.stopPending(reason) { stopSelf() }
    }

    override fun onDestroy() {
        stopping = true
        scope.cancel(AudioDownloadStopped(AUDIO_DOWNLOAD_INTERRUPTED))
        wakeLock?.let { if (it.isHeld) it.release() }
        if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        internal const val OPEN_DOWNLOADS = "com.nanahoshi.audioplayer.OPEN_AUDIO_DOWNLOADS"
        private const val EXTRA_REQUEST = "request"
        private const val CHANNEL_ID = "audio_downloads"
        private const val NOTIFICATION_ID = 0x4155

        internal fun updateJaVerificationNotification(context: Context, count: Int) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val notificationId = NOTIFICATION_ID + 1
            if (count == 0) {
                manager.cancel(notificationId)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_audio_downloads), NotificationManager.IMPORTANCE_LOW))
            }
            val open = PendingIntent.getActivity(context, notificationId,
                Intent(context, MainActivity::class.java).setAction(OPEN_DOWNLOADS)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE)
            val notice = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(context.getString(R.string.notification_ja_work_verification_title))
                .setContentText(context.getString(R.string.notification_ja_work_verification_message, count))
                .setContentIntent(open)
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

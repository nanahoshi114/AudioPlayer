package com.nanahoshi.audioplayer.data

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

internal const val AUDIO_DOWNLOAD_START_FAILED = "无法启动后台下载，请回到 App 重试"
internal const val AUDIO_DOWNLOAD_INTERRUPTED = "下载中断，请重试"
internal const val AUDIO_DOWNLOAD_TIMEOUT = "后台下载超时，请重试"

internal class AudioDownloadStopped(message: String) : CancellationException(message)

internal class AudioDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val downloads get() = application.graph().downloads
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private var worker: Job? = null
    private val preparing = mutableMapOf<Int, Job>()
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
                    downloads.drainQueue { activeIds = it }
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
                NotificationChannel(CHANNEL_ID, "音频下载", NotificationManager.IMPORTANCE_LOW),
            )
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(emptyList()).build(),
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
            downloads.observeActive().conflate().collect { jobs ->
                notifications.notify(NOTIFICATION_ID, notification(jobs).build())
            }
        }
        return true
    }

    private fun notification(jobs: List<DownloadEntity>): NotificationCompat.Builder {
        val openDownloads = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java)
                .setAction(OPEN_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val active = jobs.firstOrNull { it.status == DownloadStatus.RUNNING }
        val queued = jobs.count { it.status == DownloadStatus.QUEUED }
        val subtitle = jobs.firstOrNull { it.status == DownloadStatus.DONE && it.pendingBiliSubtitle == true }
        val phase = when (active?.phase) {
            "RESOLVING" -> "解析下载地址"
            "EXTRACTING" -> "解压中"
            "IMPORTING" -> "整理入库"
            else -> "获取 B 站字幕".takeIf { active == null && subtitle != null }
        }
        val total = active?.bytesTotal ?: 0
        val percent = if (total > 0L) {
            ((active?.bytesDone ?: 0L).coerceIn(0L, total) * 100.0 / total).toInt()
        } else 0
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载音频")
            .setContentText(active?.title ?: subtitle?.title ?: "准备下载")
            .setContentIntent(openDownloads)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent, total == 0L || phase != null)
        val detail = listOfNotNull(phase, "排队 $queued 项".takeIf { queued > 0 }).joinToString(" · ")
        if (detail.isNotEmpty()) builder.setSubText(detail)
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

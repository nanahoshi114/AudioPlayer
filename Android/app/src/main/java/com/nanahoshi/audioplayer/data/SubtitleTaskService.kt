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
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal const val SUBTITLE_START_FAILED = "无法启动后台字幕任务，请回到应用后重试"
internal const val SUBTITLE_INTERRUPTED = "后台字幕任务已中断，请重试"
private const val SUBTITLE_TIMEOUT = "后台字幕任务超时，请重试"

internal class SubtitleTaskStopped(message: String) : CancellationException(message)

internal enum class SubtitleTask(val label: String) {
    WHISPER("生成字幕"),
    TRANSLATE("翻译字幕"),
    POLISH("润色字幕"),
}

internal fun SubtitleTaskRequests(startService: (Long) -> Boolean) =
    ForegroundTaskRequests<SubtitleTask>(startService) { SubtitleTaskStopped(SUBTITLE_START_FAILED) }

/** 将本进程的任务交给服务。收到 Intent 并成功进入前台后才开始执行。 */
internal class ForegroundTaskRequests<K>(
    private val startService: (Long) -> Boolean,
    private val startFailure: () -> CancellationException,
) {
    private val pending = mutableMapOf<Long, Request<K, *>>()
    private var nextId = 0L

    fun <T> submit(task: K, block: suspend () -> T): Request<K, T> {
        val request = Request(task, block)
        synchronized(pending) {
            val id = ++nextId
            pending[id] = request
            request.result.invokeOnCompletion { synchronized(pending) { pending.remove(id) } }
            if (!startService(id)) {
                pending.remove(id)
                request.cancel(startFailure())
            }
        }
        return request
    }

    fun take(id: Long): Request<K, *>? = synchronized(pending) { pending.remove(id) }

    fun stopPending(reason: CancellationException, stopService: () -> Unit) {
        val stopped = synchronized(pending) {
            stopService()
            pending.values.toList().also { pending.clear() }
        }
        stopped.forEach { it.cancel(reason) }
    }

    class Request<K, T>(val task: K, private val block: suspend () -> T) {
        val result = CompletableDeferred<T>()
        private var worker: Job? = null
        private var cancellation: CancellationException? = null

        fun cancel(reason: CancellationException = CancellationException("已中止")) {
            val current = synchronized(this) {
                if (cancellation != null) return
                cancellation = reason
                worker
            }
            if (current == null) result.cancel(reason) else current.cancel(reason)
        }

        fun launchIn(scope: CoroutineScope): Job {
            val worker = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    result.complete(withContext(Dispatchers.IO) { block() })
                } catch (error: Exception) {
                    result.completeExceptionally(error)
                }
            }
            val stopped = synchronized(this) {
                this.worker = worker
                cancellation
            }
            worker.invokeOnCompletion { cause ->
                if (cause != null) result.completeExceptionally(cause)
            }
            if (stopped != null) worker.cancel(stopped)
            return worker
        }
    }
}

internal class SubtitleTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests get() = application.graph().subtitleTaskRequests
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val running = mutableMapOf<Int, SubtitleTask>()
    private var whisperJobs = emptyList<WhisperJobEntity>()
    private var notificationState: Triple<String, String?, Int>? = null
    private var latestStartId = 0
    private var foreground = false
    private var stopping = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val request = requests.take(intent?.getLongExtra(EXTRA_REQUEST, 0) ?: 0)
        if (stopping || (!foreground && !promote())) {
            request?.cancel(SubtitleTaskStopped(SUBTITLE_START_FAILED))
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (request == null) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        val worker = request.launchIn(scope)
        running[startId] = request.task
        updateNotification()
        worker.invokeOnCompletion {
            scope.launch {
                running.remove(startId)
                if (running.isEmpty()) stopIfIdle() else updateNotification()
            }
        }
        worker.start()
        return START_NOT_STICKY
    }

    private fun stopIfIdle() {
        // Android 会保留服务以接收已提交但尚未投递的更新请求。
        if (running.isEmpty()) stopSelfResult(latestStartId)
    }

    private fun promote(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "字幕任务", NotificationManager.IMPORTANCE_LOW),
            )
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification().build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (_: IllegalStateException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
        foreground = true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:SubtitleTasks")
            .apply {
                setReferenceCounted(false)
                acquire(TimeUnit.HOURS.toMillis(6))
            }
        scope.launch {
            application.graph().whisper.observe().conflate().collect { jobs ->
                whisperJobs = jobs
                updateNotification()
            }
        }
        return true
    }

    private fun updateNotification() {
        if (running.isEmpty() || stopping) return
        val whisper = if (SubtitleTask.WHISPER in running.values) {
            whisperJobs.firstOrNull { it.status == DownloadStatus.RUNNING || it.translateStatus == TranslateMark.RUNNING }
                ?: whisperJobs.lastOrNull { it.status == DownloadStatus.QUEUED }
        } else null
        val phases = running.values.toSet().map { task ->
            when {
                task != SubtitleTask.WHISPER -> task.label
                whisper?.translateStatus == TranslateMark.RUNNING -> SubtitleTask.TRANSLATE.label
                whisper?.status == DownloadStatus.QUEUED && whisper.fingerprint.isEmpty() -> "等待音频下载"
                else -> task.label
            }
        }.distinct()
        val progress = whisper?.takeIf { it.status == DownloadStatus.RUNNING }?.progress ?: 0f
        val percent = (progress.coerceIn(0f, 1f) * 100).toInt()
        val text = phases.joinToString(" · ")
        val state = Triple(text, whisper?.title, percent)
        if (notificationState == state) return
        notificationState = state
        notifications.notify(NOTIFICATION_ID, notification(text, whisper?.title, percent).build())
    }

    private fun notification(
        text: String = "准备字幕任务",
        title: String? = null,
        percent: Int = 0,
    ): NotificationCompat.Builder {
        val openTasks = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java)
                .setAction(OPEN_TASKS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("正在处理字幕")
            .setContentText(text)
            .setSubText(title)
            .setContentIntent(openTasks)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent, percent == 0)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopping = true
        val reason = SubtitleTaskStopped(SUBTITLE_TIMEOUT)
        scope.cancel(reason)
        requests.stopPending(reason) { stopSelf() }
    }

    override fun onDestroy() {
        stopping = true
        scope.cancel(SubtitleTaskStopped(SUBTITLE_INTERRUPTED))
        wakeLock?.let { if (it.isHeld) it.release() }
        if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val OPEN_TASKS = "com.nanahoshi.audioplayer.OPEN_SUBTITLE_TASKS"
        private const val EXTRA_REQUEST = "request"
        private const val CHANNEL_ID = "subtitle_tasks"
        private const val NOTIFICATION_ID = 0x4156

        fun start(context: Context, id: Long): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, SubtitleTaskService::class.java).putExtra(EXTRA_REQUEST, id))
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

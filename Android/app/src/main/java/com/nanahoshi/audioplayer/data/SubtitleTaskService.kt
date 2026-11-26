package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.text.persistedErrorText
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.R
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.data.db.TranslationJobEntity
import com.nanahoshi.audioplayer.graph
import androidx.annotation.StringRes
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal const val SUBTITLE_START_FAILED = PersistedErrors.SUBTITLE_START_FAILED
internal const val SUBTITLE_INTERRUPTED = PersistedErrors.SUBTITLE_INTERRUPTED
private const val SUBTITLE_TIMEOUT = PersistedErrors.SUBTITLE_TIMEOUT

internal class SubtitleTaskStopped(
    val persistedError: String,
) : CancellationException(persistedError), UserFacingError {
    override val userText = persistedErrorText(persistedError) ?: TextRef.Raw(persistedError)
}

internal enum class SubtitleTask(@param:StringRes val labelRes: Int) {
    WHISPER(R.string.subtitle_task_whisper),
    TRANSLATE(R.string.subtitle_task_translate),
    POLISH(R.string.subtitle_task_polish),
}

internal fun SubtitleTaskRequests(startService: (Long) -> Boolean) =
    ForegroundTaskRequests<SubtitleTask>(startService) { SubtitleTaskStopped(SUBTITLE_START_FAILED) }

internal class SubtitleTaskService : DataSyncForegroundService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests get() = application.graph().subtitleTaskRequests
    private val notifications by lazy { getSystemService(android.app.NotificationManager::class.java) }
    private val running = mutableMapOf<Int, SubtitleTask>()
    private var whisperJobs = emptyList<WhisperJobEntity>()
    private var translationJobs = emptyList<TranslationJobEntity>()
    private var notificationState: Triple<String, String?, Int?>? = null
    private var latestStartId = 0

    override val channelId get() = CHANNEL_ID
    override val channelNameRes get() = R.string.channel_subtitle_tasks
    override val notificationId get() = NOTIFICATION_ID

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val request = requests.take(intent?.getLongExtra(EXTRA_REQUEST, 0) ?: 0)
        if (stopping || !promote()) {
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
        if (isForeground) return true
        if (!enterForeground(notification().build())) return false
        scope.launch {
            application.graph().whisper.observe().map { rows -> rows.map { it.copy(progress = 0f, progressSummary = null) } }.distinctUntilChanged().conflate().collect { jobs ->
                whisperJobs = jobs
                updateNotification()
            }
        }
        scope.launch {
            application.graph().translator.jobs.map { rows -> rows.map { it.copy(progressSummary = null) } }.distinctUntilChanged().conflate().collect { jobs ->
                translationJobs = jobs
                updateNotification()
            }
        }
        scope.launch { application.graph().taskProgress.snapshots.collect { updateNotification() } }
        return true
    }

    private fun updateNotification() {
        if (running.isEmpty() || stopping) return
        val whisper = whisperJobs.firstOrNull { it.status == DownloadStatus.RUNNING }
        val waiting = whisperJobs.filter { it.status == DownloadStatus.QUEUED }
        val translating = whisperJobs.firstOrNull { it.translateStatus == TranslateMark.RUNNING }
        val independent = translationJobs.filter { it.status == DownloadStatus.RUNNING }
        val queuedTranslations = whisperJobs.count { it.translateStatus == TranslateMark.QUEUED } +
            translationJobs.count { it.status == DownloadStatus.QUEUED }
        val snapshots = application.graph().taskProgress.snapshots.value
        fun stage(kind: TaskKind, id: Long, fallback: String): String {
            val live = snapshots[TaskKey(kind, id)]?.takeIf { it.active } ?: return fallback
            val stats = live.commonText(this@SubtitleTaskService)
            val phase = live.summary.phase?.let { getString(it.labelRes) } ?: fallback
            return "$phase · $stats"
        }
        val phases = buildList {
            if (whisper != null) add(stage(TaskKind.WHISPER, whisper.id, getString(SubtitleTask.WHISPER.labelRes)))
            else if (waiting.any { it.fingerprint.isNotBlank() }) add(getString(R.string.subtitle_queued))
            else if (waiting.isNotEmpty()) add(getString(R.string.subtitle_waiting_audio))
            if (translating != null) add(stage(TaskKind.WHISPER, translating.id, getString(SubtitleTask.TRANSLATE.labelRes)))
            independent.forEach { add(stage(TaskKind.TRANSLATION, it.id, getString(it.operation.labelRes))) }
            if (queuedTranslations > 0) add(getString(R.string.subtitle_translation_queue, queuedTranslations))
            if (isEmpty()) add(getString(R.string.subtitle_preparing))
        }
        val first = whisper?.let { snapshots[TaskKey(TaskKind.WHISPER, it.id)] }
            ?: translating?.let { snapshots[TaskKey(TaskKind.WHISPER, it.id)] }
            ?: independent.firstOrNull()?.let { snapshots[TaskKey(TaskKind.TRANSLATION, it.id)] }
        val percent = first?.fraction()?.let { (it * 100).toInt() }
        val text = phases.joinToString(" · ")
        val title = whisper?.title ?: translating?.title ?: independent.firstOrNull()?.title ?: waiting.firstOrNull()?.title
        val state = Triple(text, title, percent)
        if (notificationState == state) return
        notificationState = state
        notifications.notify(NOTIFICATION_ID, notification(text, title, percent).build())
    }

    private fun notification(
        text: String = getString(R.string.notification_subtitle_preparing),
        title: String? = null,
        percent: Int? = null,
    ): NotificationCompat.Builder = DataSyncNotifications.progressBuilder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(getString(R.string.notification_subtitle_title))
        .setContentText(text)
        .setSubText(title)
        .setContentIntent(DataSyncNotifications.openApp(this, NOTIFICATION_ID, OPEN_TASKS))
        .setProgress(100, percent ?: 0, percent == null)

    override fun onTimeout(startId: Int, fgsType: Int) {
        markStopping()
        val reason = SubtitleTaskStopped(SUBTITLE_TIMEOUT)
        scope.cancel(reason)
        requests.stopPending(reason) { stopSelf() }
    }

    override fun onDestroy() {
        markStopping()
        scope.cancel(SubtitleTaskStopped(SUBTITLE_INTERRUPTED))
        exitForeground()
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
package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.R
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nanahoshi.audioplayer.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

internal class OfflineModelDownloadService : DataSyncForegroundService() {
    private class Transfer(val job: Job, val token: String, val cancel: PendingIntent)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transfers = mutableMapOf<OfflineWhisperModel, Transfer>()
    private val managers get() = application.graph().offlineWhisperModels
    private val notifications by lazy { getSystemService(android.app.NotificationManager::class.java) }
    private var latestStartId = 0
    private var progressUpdates: Job? = null

    override val channelId get() = CHANNEL_ID
    override val channelNameRes get() = R.string.channel_model_downloads
    override val notificationId get() = NOTIFICATION_ID

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val model = OfflineWhisperModel.fromId(intent?.getStringExtra(EXTRA_MODEL))
        if (model != null && intent != null) {
            when (intent.action) {
                ACTION_DOWNLOAD -> {
                    val source = ModelDownloadSource.entries.firstOrNull {
                        it.name == intent.getStringExtra(EXTRA_SOURCE)
                    }
                    if (source != null && promote()) download(model, source)
                }
                ACTION_CANCEL -> {
                    val transfer = transfers[model]
                    if (transfer != null && transfer.token == intent.data?.schemeSpecificPart) {
                        managers.getValue(model).cancelDownload(transfer.job)
                    }
                }
            }
        }
        stopWhenIdle()
        return START_NOT_STICKY
    }

    private fun promote(): Boolean {
        if (!enterForeground(progressNotification())) {
            reportStartFailure()
            return false
        }
        return true
    }

    private fun reportStartFailure() {
        application.graph().show(R.string.notification_model_start_failed)
    }

    private fun download(model: OfflineWhisperModel, source: ModelDownloadSource) {
        val job = managers.getValue(model).download(source) ?: return
        transfers.remove(model)?.cancel?.cancel()
        val token = UUID.randomUUID().toString()
        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, OfflineModelDownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .setData(Uri.fromParts("model-download", token, null))
                .putExtra(EXTRA_MODEL, model.id),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val transfer = Transfer(job, token, cancel)
        transfers[model] = transfer
        notifications.notify(NOTIFICATION_ID, progressNotification())
        if (progressUpdates == null) {
            progressUpdates = scope.launch {
                while (true) {
                    delay(1_000L)
                    notifications.notify(NOTIFICATION_ID, progressNotification())
                }
            }
        }
        scope.launch {
            job.join()
            cancel.cancel()
            if (transfers[model] === transfer) transfers.remove(model)
            if (transfers.isNotEmpty()) notifications.notify(NOTIFICATION_ID, progressNotification())
            stopWhenIdle()
        }
    }

    private fun stopWhenIdle() {
        if (transfers.isNotEmpty()) return
        progressUpdates?.cancel()
        progressUpdates = null
        releaseWakeLock()
        stopSelfResult(latestStartId)
    }

    private fun progressNotification(): Notification {
        val builder = DataSyncNotifications.progressBuilder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notification_model_title))
            .setContentIntent(DataSyncNotifications.openApp(this, NOTIFICATION_ID))
        val lines = mutableListOf<String>()
        var done = 0L
        var total = 0L
        for ((model, transfer) in transfers) {
            val state = managers.getValue(model).state.value as? OfflineWhisperModelState.Downloading ?: continue
            val label = getString(if (model == OfflineWhisperModel.SenseVoice) R.string.settings_model_sensevoice else R.string.settings_model_parakeet_short)
            val percent = state.bytesDone * 100 / state.bytesTotal
            val status = if (state.bytesDone >= state.bytesTotal) getString(R.string.notification_model_installing)
            else getString(R.string.notification_model_progress, state.source.label, percent)
            lines += "$label · $status"
            done += state.bytesDone.coerceAtMost(state.bytesTotal)
            total += state.bytesTotal
            builder.addAction(0, getString(R.string.notification_model_cancel, label), transfer.cancel)
        }
        if (lines.isNotEmpty()) {
            builder.setContentText(lines.joinToString(" / "))
            builder.setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach(style::addLine) })
        }
        val percent = if (total > 0L) (done * 100 / total).toInt() else 0
        return builder.setProgress(100, percent, total == 0L || done >= total).build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        markStopping()
        cancelTransfers(TextRef.Res(R.string.download_error_timeout))
        stopSelf()
    }

    override fun onDestroy() {
        markStopping()
        cancelTransfers(TextRef.Res(R.string.notification_model_interrupted))
        scope.cancel()
        exitForeground()
        super.onDestroy()
    }

    private fun cancelTransfers(message: TextRef) {
        for ((model, transfer) in transfers) {
            managers.getValue(model).cancelDownload(transfer.job, message)
            transfer.cancel.cancel()
        }
        transfers.clear()
    }

    companion object {
        private const val ACTION_DOWNLOAD = "com.nanahoshi.audioplayer.MODEL_DOWNLOAD"
        private const val ACTION_CANCEL = "com.nanahoshi.audioplayer.CANCEL_MODEL_DOWNLOAD"
        private const val EXTRA_MODEL = "model"
        private const val EXTRA_SOURCE = "source"
        private const val CHANNEL_ID = "offline_model_download"
        private const val NOTIFICATION_ID = 0x4D44

        fun start(context: Context, model: OfflineWhisperModel, source: ModelDownloadSource): Boolean {
            val intent = Intent(context, OfflineModelDownloadService::class.java)
                .setAction(ACTION_DOWNLOAD)
                .putExtra(EXTRA_MODEL, model.id)
                .putExtra(EXTRA_SOURCE, source.name)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (_: IllegalStateException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }
}
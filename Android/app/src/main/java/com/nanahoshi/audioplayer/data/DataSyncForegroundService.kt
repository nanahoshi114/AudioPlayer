package com.nanahoshi.audioplayer.data

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import java.util.concurrent.TimeUnit

/**
 * 三个 `dataSync` 前台服务的公共生命周期：进入前台、申请 6 小时部分唤醒锁、
 * 释放并在销毁时移除通知。子类只保留自己的任务泵和通知内容。
 */
internal abstract class DataSyncForegroundService : Service() {
    protected abstract val channelId: String
    protected abstract val channelNameRes: Int
    protected abstract val notificationId: Int

    /** 超时或销毁后置位，子类据此停止接受新任务。 */
    protected var stopping: Boolean = false
        private set

    private var foreground = false
    private var wakeLock: PowerManager.WakeLock? = null

    protected val isForeground: Boolean get() = foreground

    protected fun markStopping() {
        stopping = true
    }

    /** 返回 false 表示系统拒绝进入前台，调用方应停止服务。 */
    protected fun enterForeground(notification: Notification): Boolean {
        if (foreground) return true
        DataSyncNotifications.ensureChannel(this, channelId, channelNameRes)
        return try {
            ServiceCompat.startForeground(
                this,
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            foreground = true
            retainWakeLock()
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    protected fun retainWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(PowerManager::class.java) ?: return)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:${javaClass.simpleName}")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    protected fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    protected fun exitForeground() {
        releaseWakeLock()
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        val WAKE_LOCK_TIMEOUT_MS = TimeUnit.HOURS.toMillis(6)
    }
}
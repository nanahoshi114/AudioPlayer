package com.nanahoshi.audioplayer.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.nanahoshi.audioplayer.MainActivity

/**
 * dataSync 前台服务共用的通知约束：
 * 一律使用 `VISIBILITY_PRIVATE`，锁屏不展示正在处理的作品或曲目标题。
 */
internal object DataSyncNotifications {
    fun ensureChannel(context: Context, channelId: String, nameRes: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(channelId, context.getString(nameRes), NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun progressBuilder(context: Context, channelId: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

    fun openApp(context: Context, requestCode: Int, action: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (action != null) intent.setAction(action)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE)
    }
}
package com.alliot.osmo.demo.app.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.alliot.osmo.demo.app.OsmoDemoApplication
import com.alliot.osmo.demo.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * Foreground service that keeps the process (and the bound camera Wi-Fi network) alive while
 * [DownloadCoordinator] drains its queue, and mirrors the queue into an ongoing progress notification
 * with a Cancel action. It owns no download logic — it observes [DownloadCoordinator.state] and stops
 * itself when the queue is idle. Started/stopped by the coordinator.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null

    private val coordinator get() = (application as OsmoDemoApplication).downloadCoordinator

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            coordinator.cancelAll()
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startInForeground(coordinator.state.value)
        if (observer == null) {
            observer = scope.launch {
                coordinator.state.collect { st ->
                    if (!st.running) {
                        stopForegroundCompat()
                        stopSelf()
                    } else {
                        notificationManager().notify(NOTIF_ID, buildNotification(st))
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(st: DownloadQueueState) {
        val notification = buildNotification(st)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(st: DownloadQueueState): Notification {
        val total = st.total.coerceAtLeast(1)
        val index = (st.doneCount + 1).coerceAtMost(total)
        val cancelIntent = PendingIntent.getService(
            this, 0,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载媒体 $index/$total")
            .setContentText(st.currentName ?: "准备中 …")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, (st.currentProgress * 100).toInt(), st.currentName == null)
            .addAction(0, "取消", cancelIntent)
            .build()
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "媒体下载", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "相机媒体后台下载进度" }
            notificationManager().createNotificationChannel(channel)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val CHANNEL_ID = "media_downloads"
        private const val NOTIF_ID = 0x0D10
        const val ACTION_CANCEL = "com.alliot.osmo.demo.action.CANCEL_DOWNLOADS"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }
    }
}

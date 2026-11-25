package com.app.videoeditor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app process alive (and shows progress) while a video is exporting,
 * so the export survives when the user switches apps or the screen turns off.
 * The actual export runs in EditorViewModel; this service only hosts the notification.
 */
class ExportService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15+ can time-limit mediaProcessing foreground services.
     * Notify the editor so it cancels the in-process Transformer, then stop promptly.
     */
    override fun onTimeout(startId: Int, foregroundServiceType: Int) {
        runCatching {
            sendBroadcast(Intent(ACTION_TIMEOUT).setPackage(packageName))
        }
        stopSelf(startId)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val n = build(this, 0, "Exporting video…")
        try {
            if (Build.VERSION.SDK_INT >= 35) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            } else {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 4711
        private const val DONE_ID = 4712
        private const val CHANNEL = "export"
        const val ACTION_TIMEOUT = "com.app.videoeditor.action.EXPORT_SERVICE_TIMEOUT"

        private fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Video export", NotificationManager.IMPORTANCE_LOW))
            }
        }

        private fun build(ctx: Context, percent: Int, text: String): Notification =
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Video Editor")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent, false)
                .build()

        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, ExportService::class.java)) } catch (e: Exception) { }
        }

        fun progress(ctx: Context, percent: Int) {
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(ID, build(ctx, percent, "Exporting… $percent%"))
            } catch (e: Exception) { }
        }

        fun stop(ctx: Context, finishedText: String? = null, isError: Boolean = false) {
            try { ctx.stopService(Intent(ctx, ExportService::class.java)) } catch (e: Exception) { }
            if (finishedText != null) {
                try {
                    ensureChannel(ctx)
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(
                        DONE_ID,
                        NotificationCompat.Builder(ctx, CHANNEL)
                            .setSmallIcon(if (isError) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_upload_done)
                            .setContentTitle("Video Editor")
                            .setContentText(finishedText)
                            .setAutoCancel(true)
                            .build()
                    )
                } catch (e: Exception) { }
            }
        }
    }
}

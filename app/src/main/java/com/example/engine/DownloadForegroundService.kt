package com.example.engine

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

/**
 * Foreground service (`dataSync`) that keeps active multi-chunk Turbo downloads alive
 * when the user switches to other apps, locks the screen, or switches browser tabs.
 */
class DownloadForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP_FOREGROUND) {
            releaseWakeLock()
            runCatching {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val activeCount = intent?.getIntExtra(EXTRA_ACTIVE_COUNT, 1)?.coerceAtLeast(1) ?: 1
        val title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: if (activeCount == 1) "Downloading file…" else "Downloading $activeCount files…"
        val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE)?.takeIf { it.isNotBlank() }
            ?: "8× Parallel Turbo Engine active"
        val progressPercent = intent?.getIntExtra(EXTRA_PROGRESS_PERCENT, -1) ?: -1

        val notification = buildForegroundNotification(
            title = title,
            subtitle = subtitle,
            progressPercent = progressPercent
        )

        runCatching {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                FOREGROUND_NOTIFICATION_ID,
                notification,
                fgsType
            )
        }
        acquireWakeLock()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep downloads running even if user swipes the recents card while a download is active
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ApexBrowser:TurboDownloadWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L) // Up to 30 minutes per active download session
            }
            wakeLock = lock
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            val lock = wakeLock
            wakeLock = null
            if (lock != null && lock.isHeld) {
                lock.release()
            }
        }
    }

    private fun buildForegroundNotification(
        title: String,
        subtitle: String,
        progressPercent: Int
    ): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val channel = NotificationChannel(
                    DownloadNotificationHelper.CHANNEL_ID_DOWNLOADS,
                    getString(R.string.notification_channel_downloads_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notification_channel_downloads_desc)
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                manager.createNotificationChannel(channel)
            }
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = DownloadNotificationHelper.ACTION_OPEN_DOWNLOADS
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            FOREGROUND_NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hasDeterminateProgress = progressPercent in 0..100
        return NotificationCompat.Builder(this, DownloadNotificationHelper.CHANNEL_ID_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSubText(if (hasDeterminateProgress) "$progressPercent%" else "Turbo")
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setProgress(
                if (hasDeterminateProgress) 100 else 0,
                if (hasDeterminateProgress) progressPercent else 0,
                !hasDeterminateProgress
            )
            .build()
    }

    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 39_999
        const val ACTION_START_OR_UPDATE = "com.example.action.DOWNLOAD_FGS_START_OR_UPDATE"
        const val ACTION_STOP_FOREGROUND = "com.example.action.DOWNLOAD_FGS_STOP"
        const val EXTRA_ACTIVE_COUNT = "extra_active_count"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SUBTITLE = "extra_subtitle"
        const val EXTRA_PROGRESS_PERCENT = "extra_progress_percent"

        fun startOrUpdate(
            context: Context,
            activeCount: Int,
            title: String,
            subtitle: String,
            progressPercent: Int
        ) {
            runCatching {
                val appContext = context.applicationContext
                val intent = Intent(appContext, DownloadForegroundService::class.java).apply {
                    action = ACTION_START_OR_UPDATE
                    putExtra(EXTRA_ACTIVE_COUNT, activeCount)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_SUBTITLE, subtitle)
                    putExtra(EXTRA_PROGRESS_PERCENT, progressPercent)
                }
                ContextCompat.startForegroundService(appContext, intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                val stopIntent = Intent(appContext, DownloadForegroundService::class.java)
                appContext.stopService(stopIntent)
            }
        }
    }
}

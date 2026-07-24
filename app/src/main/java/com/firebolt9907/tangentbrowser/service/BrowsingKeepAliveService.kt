package com.firebolt9907.tangentbrowser.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.firebolt9907.tangentbrowser.MainActivity
import com.firebolt9907.tangentbrowser.R

/**
 * A minimal foreground service whose only job is to raise the process's importance while the app is
 * in the background, so Android's low-memory killer is less likely to reap the browser (and discard
 * its open tabs) under memory pressure.
 *
 * This is strictly opt-in ([com.firebolt9907.tangentbrowser.state.GlobalPreferencesStore.keepAliveInBackground],
 * default off) because it requires an ongoing notification. It is started/stopped from
 * [com.firebolt9907.tangentbrowser.TangentApplication] on app background/foreground transitions.
 */
class BrowsingKeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Don't recreate if the system kills us; the app re-starts the service on next background.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        ensureChannel()
        val launch = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.keep_alive_notification_title))
            .setContentText(getString(R.string.keep_alive_notification_text))
            .setSmallIcon(R.drawable.ic_keep_alive)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(tapIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.keep_alive_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.keep_alive_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "browsing_keep_alive"
        private const val NOTIFICATION_ID = 0x10C0

        fun start(context: Context) {
            // Started on the ON_STOP transition, while the process is still within the
            // foreground-importance grace window, so a foreground-service start is permitted.
            ContextCompat.startForegroundService(
                context,
                Intent(context, BrowsingKeepAliveService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BrowsingKeepAliveService::class.java))
        }
    }
}

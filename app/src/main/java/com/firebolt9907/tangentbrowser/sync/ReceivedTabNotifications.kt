package com.firebolt9907.tangentbrowser.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.firebolt9907.tangentbrowser.MainActivity
import com.firebolt9907.tangentbrowser.R

/**
 * Posts a system notification for a tab another device pushed here via Firefox "Send tab". Tapping
 * it launches [MainActivity] with [MainActivity.EXTRA_OPEN_URL] so the URL opens as a normal tab
 * (through the single `normalizeUrl` entry point — AGENTS.md invariant #9). This mirrors vanilla
 * Firefox: a received tab surfaces as a notification the user chooses to open, rather than silently
 * stealing the foreground with a new tab.
 */
object ReceivedTabNotifications {

    private const val CHANNEL_ID = "received_tabs"

    fun notify(context: Context, url: String, title: String, deviceName: String?) {
        if (url.isBlank()) return
        ensureChannel(context)

        val launch = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_OPEN_URL, url)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        // A per-URL request code keeps each pending tab its own distinct PendingIntent, so tapping
        // one notification doesn't reuse an earlier URL's extras (FLAG_IMMUTABLE alone would).
        val requestCode = url.hashCode()
        val tapIntent = PendingIntent.getActivity(
            context,
            requestCode,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val heading = title.ifBlank { url }
        val subtext = deviceName?.takeIf { it.isNotBlank() }
            ?.let { context.getString(R.string.received_tab_from, it) }
            ?: context.getString(R.string.received_tab_generic)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(heading)
            .setContentText(subtext)
            .setSmallIcon(R.drawable.ic_keep_alive)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .build()

        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) {
            // Distinct id per URL so multiple received tabs stack instead of replacing each other.
            runCatching { manager.notify(requestCode, notification) }
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.received_tab_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.received_tab_channel_description)
        }
        manager.createNotificationChannel(channel)
    }
}

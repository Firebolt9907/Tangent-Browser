package com.firebolt9907.tangentbrowser.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.firebolt9907.tangentbrowser.MainActivity
import com.firebolt9907.tangentbrowser.R

/**
 * Pins a launcher shortcut that opens [url] in Tangent (Safari's "Add to Home Screen").
 * Uses the page [icon] favicon when available, else the app icon. Reachable from the tab overview
 * overflow menu; was formerly a tile in the aA sheet. No-ops with a toast on launchers that don't
 * support pinned shortcuts.
 */
fun pinPageToHomeScreen(
    context: Context,
    url: String,
    title: String,
    icon: android.graphics.Bitmap?,
) {
    if (url.isBlank() || url == "about:blank") return
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        Toast.makeText(context, "Not supported on this launcher", Toast.LENGTH_SHORT).show()
        return
    }
    val label = title.ifBlank { url }
    val shortcutIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = url.toUri()
        putExtra(MainActivity.EXTRA_HOME_SHORTCUT, true)
    }
    val shortcutIcon = icon?.let { IconCompat.createWithBitmap(it) }
        ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher)
    val info = ShortcutInfoCompat.Builder(context, "tangent-${url.hashCode()}")
        .setShortLabel(label.take(24))
        .setLongLabel(label.take(48))
        .setIcon(shortcutIcon)
        .setIntent(shortcutIntent)
        .build()
    ShortcutManagerCompat.requestPinShortcut(context, info, null)
}

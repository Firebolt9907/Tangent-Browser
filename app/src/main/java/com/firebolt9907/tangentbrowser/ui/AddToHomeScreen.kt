package com.firebolt9907.tangentbrowser.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.firebolt9907.tangentbrowser.MainActivity
import com.firebolt9907.tangentbrowser.R

/**
 * Pins a launcher shortcut that opens [url] in Tangent (Safari's "Add to Home Screen").
 * Uses the page [icon] favicon when available, else the app icon. Reachable from the tab overview
 * overflow menu; was formerly a tile in the aA sheet. On launchers that don't support pinned
 * shortcuts it no-ops and says so through [onMessage] — a top-level helper reports, the caller shows
 * the themed snackbar (see [shareUrl]).
 */
fun pinPageToHomeScreen(
    context: Context,
    url: String,
    title: String,
    icon: android.graphics.Bitmap?,
    onMessage: (String) -> Unit,
) {
    if (url.isBlank() || url == "about:blank") return
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        onMessage("Not supported on this launcher")
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

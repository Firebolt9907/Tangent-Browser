package com.firebolt9907.tangentbrowser.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.firebolt9907.tangentbrowser.MainActivity
import com.firebolt9907.tangentbrowser.state.AppShortcutSignals

/**
 * A Quick Settings tile (notification-shade) that opens a new tab — the same action as the "New Tab"
 * launcher app-shortcut. The user adds it once from the shade's "edit tiles" tray; tapping it brings
 * the browser forward on a fresh tab.
 *
 * It carries no data of its own: it just launches [MainActivity] with [AppShortcutSignals.ACTION_NEW_TAB],
 * reusing the exact cold/warm-start path that `MainActivity.handleIncomingIntent` already handles for
 * the home-screen shortcut (so there's no second "open a new tab" code path to keep in sync).
 */
class NewTabTileService : TileService() {

    // Keep the tile in the neutral "inactive" state — it's a launch action, not a stateful toggle.
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = AppShortcutSignals.ACTION_NEW_TAB
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        // Android 14 (API 34) requires the PendingIntent overload; the Intent overload throws on 34+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}

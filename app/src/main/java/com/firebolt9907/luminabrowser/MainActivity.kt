package com.firebolt9907.luminabrowser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.firebolt9907.luminabrowser.ui.LuminaRoot

class MainActivity : LuminaBrowserActivity() {

    // One-shot POST_NOTIFICATIONS prompt (Android 13+). Needed so received-tab notifications
    // (Firefox "Send tab" → ReceivedTabNotifications) can actually appear.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result ignored: if denied, notifications simply won't show until enabled in settings */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            LuminaRoot(
                isInPipMode = isInPipMode,
                onFullScreenChanged = { isVideoFullscreen = it },
                onRequestPipMode = ::requestPipMode
            )
        }
        // Only handle the launch intent on a fresh start. On recreation (process-death restore or a
        // config change) onCreate re-runs with the original launch intent still set as getIntent(),
        // which would re-open a duplicate tab from the same ACTION_VIEW/SEND. Live intents that
        // arrive while the activity exists are delivered through onNewIntent instead.
        if (savedInstanceState == null) handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    // MainActivity only receives its own explicit intents now: the LAUNCHER entry (no URL) and the
    // "Add to Home Screen" shortcut (an explicit ACTION_VIEW that bypasses intent-filters). External
    // links from other apps go to CustomTabActivity instead. A home-shortcut opens as a normal,
    // persisted tab (no reader auto-attempt / Custom-Tab surface).
    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        // A Firefox "Send tab" notification tap (see ReceivedTabNotifications) carries the URL to
        // open as a normal, persisted tab — same single normalizeUrl path as a home-shortcut open.
        // MainActivity is exported (LAUNCHER), so any app can craft an explicit intent with this
        // extra; sanitizeExternalUrl blocks privileged schemes (file:/content:/javascript:/…) so a
        // hostile caller can't make the browser open a local/script URL.
        IntentUrls.sanitizeExternalUrl(intent.getStringExtra(EXTRA_OPEN_URL))?.let { url ->
            app.components.tabRepository.addTab(url = url, title = "")
            return
        }
        if (!intent.getBooleanExtra(EXTRA_HOME_SHORTCUT, false)) return
        val url = IntentUrls.extractUrl(intent) ?: return
        app.components.tabRepository.addTab(url = url, title = "")
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        runCatching { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    companion object {
        /** Set true on an "Add to Home Screen" shortcut intent so it opens as a normal saved tab. */
        const val EXTRA_HOME_SHORTCUT = "com.firebolt9907.luminabrowser.HOME_SHORTCUT"

        /** URL string carried by a received-tab notification tap; opens as a normal saved tab. */
        const val EXTRA_OPEN_URL = "com.firebolt9907.luminabrowser.OPEN_URL"
    }
}

package com.firebolt9907.luminabrowser

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import com.firebolt9907.luminabrowser.ui.LuminaRoot

class MainActivity : LuminaBrowserActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        if (intent?.getBooleanExtra(EXTRA_HOME_SHORTCUT, false) != true) return
        val url = IntentUrls.extractUrl(intent) ?: return
        app.components.tabRepository.addTab(url = url, title = "")
    }

    companion object {
        /** Set true on an "Add to Home Screen" shortcut intent so it opens as a normal saved tab. */
        const val EXTRA_HOME_SHORTCUT = "com.firebolt9907.luminabrowser.HOME_SHORTCUT"
    }
}

package com.firebolt9907.luminabrowser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.firebolt9907.luminabrowser.ui.LuminaRoot
import kotlinx.coroutines.launch

class MainActivity : LuminaBrowserActivity() {

    // One-shot POST_NOTIFICATIONS prompt (Android 13+). Needed so received-tab notifications
    // (Firefox "Send tab" → ReceivedTabNotifications) can actually appear.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result ignored: if denied, notifications simply won't show until enabled in settings */ }

    // SMS one-time-code (2FA) autofill via Play Services SMS User Consent. Registered as a field so
    // its consent ActivityResultLauncher is set up before the activity starts. Armed only while
    // foreground (onResume/onPause) so a backgrounded browser never starts watching for SMS.
    private val otpSmsController = OtpSmsController(this) { code ->
        com.firebolt9907.luminabrowser.ui.content.OtpAutofill.deliverCode(code)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            var showOnboarding by remember {
                mutableStateOf(!app.components.globalPreferencesStore.onboardingComplete)
            }
            if (showOnboarding) {
                com.firebolt9907.luminabrowser.ui.onboarding.LuminaOnboarding(
                    onFinish = {
                        app.components.globalPreferencesStore.onboardingComplete = true
                        showOnboarding = false
                    },
                )
            } else {
                LuminaRoot(
                    isInPipMode = isInPipMode,
                    onFullScreenChanged = { isVideoFullscreen = it },
                    onRequestPipMode = ::requestPipMode
                )
            }
        }
        // Only handle the launch intent on a fresh start. On recreation (process-death restore or a
        // config change) onCreate re-runs with the original launch intent still set as getIntent(),
        // which would re-open a duplicate tab from the same ACTION_VIEW/SEND. Live intents that
        // arrive while the activity exists are delivered through onNewIntent instead.
        if (savedInstanceState == null) handleIncomingIntent(intent)

        refreshSearchIndex()
    }

    // Rebuild the AppSearch index (history + bookmarks → system/launcher search) shortly after launch,
    // off the critical startup path. Best-effort and fully self-guarded (see BrowserSearchIndex).
    private fun refreshSearchIndex() {
        lifecycleScope.launch {
            kotlinx.coroutines.delay(4000)
            com.firebolt9907.luminabrowser.state.BrowserSearchIndex.refresh(
                context = applicationContext,
                historyStorage = app.components.historyStorage,
                bookmarksStorage = app.components.bookmarksStorage,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // The page (OtpAutofill, via the JS detector + prompt bridge) calls this when it sees an OTP
        // field; arm SMS User Consent then.
        com.firebolt9907.luminabrowser.ui.content.OtpAutofill.smsListenStarter = {
            otpSmsController.startListening()
        }
    }

    override fun onPause() {
        com.firebolt9907.luminabrowser.ui.content.OtpAutofill.smsListenStarter = null
        super.onPause()
    }

    override fun onDestroy() {
        otpSmsController.dispose()
        super.onDestroy()
    }

    // MainActivity only receives its own explicit intents now: the LAUNCHER entry (no URL) and the
    // "Add to Home Screen" shortcut (an explicit ACTION_VIEW that bypasses intent-filters). External
    // links from other apps go to CustomTabActivity instead. A home-shortcut opens as a normal,
    // persisted tab (no reader auto-attempt / Custom-Tab surface).
    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        // Launcher app-shortcut (long-press the home icon → New Tab / Bookmarks / History). Recorded
        // for LuminaRoot to act on once composed; works on cold start (set before the UI composes).
        com.firebolt9907.luminabrowser.state.AppShortcutSignals.fromAction(intent.action)?.let {
            com.firebolt9907.luminabrowser.state.AppShortcutSignals.request(it)
            return
        }
        // Web-search (assistant / "search the web for…") and process-text (a text selection in any app
        // → "Search in Lumina") intents carry a query or selected text, not a URL. Treat them as a
        // search: the engine's searchUrl URL-encodes the text into an https search URL, so external
        // text can never be interpreted as a privileged scheme (file:/javascript:/…).
        extractSearchQuery(intent)?.let { query ->
            val searchUrl = app.components.globalPreferencesStore.searchEngine.searchUrl(query)
            app.components.tabRepository.addTab(url = searchUrl, title = "")
            return
        }
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

    /** The search query carried by an ACTION_WEB_SEARCH or ACTION_PROCESS_TEXT intent, if any. */
    private fun extractSearchQuery(intent: Intent): String? = when (intent.action) {
        Intent.ACTION_WEB_SEARCH -> intent.getStringExtra(android.app.SearchManager.QUERY)
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }?.trim()?.takeIf { it.isNotBlank() }

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

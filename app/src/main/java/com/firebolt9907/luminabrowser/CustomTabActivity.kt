package com.firebolt9907.luminabrowser

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.firebolt9907.luminabrowser.ui.customtab.CustomTabRoot
import com.firebolt9907.luminabrowser.ui.reader.ReaderManager
import mozilla.components.browser.state.state.CustomTabConfig
import mozilla.components.feature.customtabs.createCustomTabConfigFromIntent
import mozilla.components.feature.customtabs.isCustomTabIntent
import mozilla.components.browser.state.state.SessionState

/**
 * Hosts an external link (from another app, e.g. a Google Discover article) as a **Custom Tab** —
 * a mozilla-components `CustomTabSessionState` that lives outside the normal tab list, grid,
 * persistence and Firefox Sync.
 *
 * Launched as its own task layered on the calling app (see the manifest: dedicated `taskAffinity` +
 * `singleTask` + `autoRemoveFromRecents`). Exit animation: when [resolveCloseAnimation] picks one,
 * `CustomTabRoot` intercepts Back with a predictive drag (page tracks the finger) and routes the
 * commit through [finish], which plays the animation via `overridePendingTransition`. Do NOT swap
 * this for `overrideActivityTransition` + no callback: tried, and on this cross-task path the
 * system plays nothing at all (see AGENTS.md). For plain ACTION_VIEW launches with no page history
 * we register **no** back callback, so Back finishes this activity and the OS plays its native
 * predictive-back animation straight back to the caller. "Open in Lumina" migrates the page into
 * the real browser ([MainActivity]).
 */
class CustomTabActivity : LuminaBrowserActivity() {

    /** The custom-tab session id currently shown, or null before an intent has been handled. */
    private var customTabId by mutableStateOf<String?>(null)

    /** Set when the tab is migrated into the main browser so [cleanUp] won't delete the migrated tab. */
    private var migratedAway = false

    /**
     * The close animation [finish] plays, resolved per launch by [resolveCloseAnimation], or null
     * to leave the close transition entirely to the system. [resPackage] is non-null when the anim
     * resource ids live in the CCT caller's APK (`CustomTabsIntent.setExitAnimations`).
     */
    private data class CloseAnimation(val resPackage: String?, val enterRes: Int, val exitRes: Int)

    private var closeAnimation by mutableStateOf<CloseAnimation?>(null)

    /**
     * While true, [getPackageName] reports the CCT client's package so the animation resource ids
     * in [closeAnimation] resolve against the *client's* APK during `overridePendingTransition`
     * (the same technique Chrome uses — the framework loads the anim XML from whatever package
     * this activity claims to be).
     */
    private var overridePackageNameForAnimations = false

    override fun getPackageName(): String {
        if (overridePackageNameForAnimations) {
            closeAnimation?.resPackage?.let { return it }
        }
        return super.getPackageName()
    }

    override fun finish() {
        super.finish()
        val animation = closeAnimation
        // Skip on migrate: "Open in Lumina" hands off to MainActivity, whose own task-open
        // transition should win, not a close animation.
        if (animation != null && !migratedAway) {
            overridePackageNameForAnimations = true
            try {
                @Suppress("DEPRECATION") // Chrome-parity path; still honored on 34+ for finish().
                overridePendingTransition(animation.enterRes, animation.exitRes)
            } catch (e: Exception) {
                android.util.Log.e("LuminaCustomTab", "Failed to override pending transition", e)
            } finally {
                overridePackageNameForAnimations = false
            }
        }
    }

    /**
     * Decides how the eventual close should animate:
     * 1. The caller attached a CCT exit-animation bundle → play the caller's animations (the
     *    browser's side of the Custom Tabs contract; requires the [getPackageName] trick).
     * 2. No bundle, but launched by the Google app → play [R.anim.custom_tab_exit]. The home-screen
     *    Discover panel launches plain VIEW intents (no CCT handshake), and the system animates
     *    *nothing* for the close: the return target is a Google-app activity embedded in the
     *    launcher's home task (the "-1 screen"), which the launcher's return-to-home transition
     *    can't animate — verified on device as a hard cut even on the TYPE_RETURN_TO_HOME path.
     * 3. Anything else → null. No back callback gets registered and the OS plays its native
     *    predictive-back animation to the calling app, which works and should not be overridden.
     */
    private fun resolveCloseAnimation(config: CustomTabConfig): CloseAnimation? {
        val bundle = config.exitAnimations
        val clientPackage = bundle?.getString(ANIM_BUNDLE_PACKAGE)
        if (bundle != null && !clientPackage.isNullOrEmpty()) {
            return CloseAnimation(
                resPackage = clientPackage,
                enterRes = bundle.getInt(ANIM_BUNDLE_ENTER_RES),
                exitRes = bundle.getInt(ANIM_BUNDLE_EXIT_RES),
            )
        }
        if (referrer?.host == GOOGLE_APP_PACKAGE) {
            return CloseAnimation(resPackage = null, enterRes = 0, exitRes = R.anim.custom_tab_exit)
        }
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Restore an in-flight custom tab across a process-recreate if the store still has it;
        // otherwise (re)create one from the launch intent.
        val restored = savedInstanceState?.getString(STATE_CUSTOM_TAB_ID)
            ?.takeIf { id -> app.components.store.state.customTabs.any { it.id == id } }
        if (restored != null) {
            customTabId = restored
        } else {
            openCustomTabFor(intent)
        }

        setContent {
            CustomTabRoot(
                customTabId = customTabId,
                hasCloseAnimation = closeAnimation != null,
                onOpenInLumina = ::openInLumina,
                onClose = { finish() },
                isInPipMode = isInPipMode,
                onFullScreenChanged = { isVideoFullscreen = it },
                onRequestPipMode = ::requestPipMode,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A second external link replaces the current preview (single custom-tab policy).
        removeCurrentCustomTab()
        openCustomTabFor(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        customTabId?.let { outState.putString(STATE_CUSTOM_TAB_ID, it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        // isFinishing distinguishes a real close (Back / Close / migrate) from a config-change or
        // process-recreate, where the custom tab must survive.
        if (isFinishing) cleanUp()
    }

    private fun openCustomTabFor(intent: Intent?) {
        val url = IntentUrls.extractUrl(intent) ?: run { finish(); return }
        // A Firefox-Sync OAuth redirect can arrive here as an https VIEW intent; consume it and exit
        // rather than opening it as a page.
        if (app.components.firefoxSyncController.handleOAuthRedirect(url)) {
            finish()
            return
        }
        migratedAway = false
        // When the caller used the Custom Tabs protocol (via LuminaCustomTabsService), parse its
        // real config — session token (links this tab to the caller's CCT session), toolbar color,
        // and the enter/exit-animation options bundle — instead of a bare default. A plain
        // ACTION_VIEW (no CCT session) falls back to CustomTabConfig().
        val isCct = intent != null && runCatching { isCustomTabIntent(intent) }.getOrDefault(false)
        val config = if (isCct) {
            runCatching { createCustomTabConfigFromIntent(intent, resources) }
                .getOrDefault(CustomTabConfig())
        } else {
            CustomTabConfig()
        }
        closeAnimation = resolveCloseAnimation(config)
        android.util.Log.i(
            LOG_TAG,
            "open: isCCT=${intent != null && isCustomTabIntent(intent)} " +
                "extras=${intent?.extras?.keySet()?.joinToString()} " +
                "exitBundle=${config.exitAnimations != null} referrer=$referrer " +
                "closeAnimation=$closeAnimation",
        )
        val useCache = ReaderManager.instance.useGoogleCache.value &&
            com.firebolt9907.luminabrowser.ui.reader.shouldRedirectToGoogleCache(url)
        val targetUrl = if (useCache) {
            com.firebolt9907.luminabrowser.ui.reader.getGoogleCacheUrl(url)
        } else {
            url
        }
        val id = app.components.customTabsUseCases.add.invoke(
            targetUrl,
            config,
            false,
            null,
            SessionState.Source.External.CustomTab(null),
        )
        customTabId = id
        if (useCache) {
            ReaderManager.instance.setOriginalUrl(id, url)
        }
        // Open straight into reader when the page turns out to be a readable article.
        ReaderManager.instance.requestReaderActivation(id)
    }

    private fun openInLumina(id: String) {
        // Turn the custom tab into a normal, persisted tab and bring the real browser forward.
        app.components.customTabsUseCases.migrate.invoke(id, true)
        migratedAway = true
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    private fun removeCurrentCustomTab() {
        val id = customTabId ?: return
        app.components.customTabsUseCases.remove.invoke(id)
        ReaderManager.instance.clearReaderState(id)
        customTabId = null
    }

    private fun cleanUp() {
        val id = customTabId ?: return
        if (!migratedAway) {
            app.components.customTabsUseCases.remove.invoke(id)
            ReaderManager.instance.clearReaderState(id)
        }
        customTabId = null
    }

    companion object {
        private const val STATE_CUSTOM_TAB_ID = "customTabId"
        private const val LOG_TAG = "LuminaCustomTab"

        /** The Google app — hosts both the in-app feed and the home-screen Discover panel. */
        private const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"

        // Keys inside the ActivityOptions bundle built by ActivityOptions.makeCustomAnimation
        // (what CustomTabsIntent.setExitAnimations stores). Hidden framework constants, hardcoded
        // by every CCT browser implementation.
        private const val ANIM_BUNDLE_PACKAGE = "android:activity.packageName"
        private const val ANIM_BUNDLE_ENTER_RES = "android:activity.animEnterRes"
        private const val ANIM_BUNDLE_EXIT_RES = "android:activity.animExitRes"
    }
}

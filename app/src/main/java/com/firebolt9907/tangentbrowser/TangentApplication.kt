package com.firebolt9907.tangentbrowser

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.firebolt9907.tangentbrowser.engine.TangentBrowserComponents
import com.firebolt9907.tangentbrowser.service.BrowsingKeepAliveService
import mozilla.components.browser.state.action.SystemAction
import mozilla.components.lib.fetch.httpurlconnection.HttpURLConnectionClient
import mozilla.components.support.AppServicesInitializer
import mozilla.components.support.rusthttp.RustHttpConfig

class TangentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppServicesInitializer.init(AppServicesInitializer.Config(null))
        RustHttpConfig.setClient(lazy { HttpURLConnectionClient() })
        // Load per-site reader overrides (assets/reader_rules.json + cached remote file) once, off the
        // components graph so reader extraction has them before the first page loads. Reading the
        // toggle here uses a lightweight SharedPreferences wrapper (process-singleton store) and does
        // NOT force the heavy components graph to init. refreshIfDue then pulls newer rules from GitHub
        // on a background thread (throttled, and only when the toggle is on).
        val readerPrefs = com.firebolt9907.tangentbrowser.state.GlobalPreferencesStore(this)
        com.firebolt9907.tangentbrowser.ui.reader.ReaderRules.setEnabled(readerPrefs.readerSiteRules)
        com.firebolt9907.tangentbrowser.ui.reader.ReaderRules.init(this)
        com.firebolt9907.tangentbrowser.ui.reader.ReaderRules.refreshIfDue(this, readerPrefs)
        ProcessLifecycleOwner.get().lifecycle.addObserver(backgroundKeepAliveObserver)
    }

    private val componentsLazy = lazy { TangentBrowserComponents(this) }
    val components: TangentBrowserComponents by componentsLazy

    /**
     * Forward system memory pressure into the engine and snapshot cache. [SystemAction.LowMemoryAction]
     * is handled by mozilla-components' `TrimMemoryMiddleware`, which suspends the engine sessions of
     * non-selected tabs — the main lever that keeps RAM (and the low-memory killer's appetite for this
     * process) down as the tab count grows. We only act if the engine graph is already built, so a
     * trim callback never forces the heavy [TangentBrowserComponents] init.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Free the TextToSpeech engine (and its bound service) when backgrounded and idle. Cheap to
        // recreate; independent of the components graph, so release it even before that is built.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            com.firebolt9907.tangentbrowser.ui.reader.PageSpeaker.releaseIfIdle()
        }
        if (!componentsLazy.isInitialized()) return
        components.store.dispatch(SystemAction.LowMemoryAction(level))
        components.tabSnapshotCache.trimMemory(level)
        // Shrink the live-session LRU budget so the pool doesn't refill to its normal device-class
        // size while the system is still under pressure. Restored on the next foreground (onStart).
        components.backgroundTabSuspender.onTrimMemory(level)
    }

    /**
     * While the app is backgrounded, optionally run a foreground service to raise process importance
     * so the system is less likely to kill the browser (and discard open tabs). Opt-in via
     * [com.firebolt9907.tangentbrowser.state.GlobalPreferencesStore.keepAliveInBackground]; the pref is
     * re-read on every transition so toggling it takes effect on the next background.
     */
    private val backgroundKeepAliveObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            // Free the TextToSpeech engine (and its bound system service) on the deterministic
            // background transition. onTrimMemory's TRIM_MEMORY_* levels are unreliable — and
            // deprecated on Android 14+ — so relying on them alone can leave the engine bound for the
            // whole app lifetime after a single "Listen to Page". releaseIfIdle() no-ops while
            // actively reading, so background playback is unaffected.
            com.firebolt9907.tangentbrowser.ui.reader.PageSpeaker.releaseIfIdle()
            if (componentsLazy.isInitialized()) {
                // Capture the foreground tab's current history/scroll into the store now, while its
                // GeckoSession is still alive, so the debounced persist writes fresh session state
                // before the OS can kill this backgrounded process. Background tabs were already
                // flushed as they left the foreground.
                components.tabRepository.flushActiveTabState()
                if (components.globalPreferencesStore.keepAliveInBackground) {
                    BrowsingKeepAliveService.start(this@TangentApplication)
                }
            }
        }

        override fun onStart(owner: LifecycleOwner) {
            BrowsingKeepAliveService.stop(this@TangentApplication)
            // Foreground = the memory-pressure episode is over; let the live-session LRU pool refill
            // to the device-class base as the user resumes browsing.
            if (componentsLazy.isInitialized()) {
                components.backgroundTabSuspender.restoreBudget()
            }
        }
    }
}

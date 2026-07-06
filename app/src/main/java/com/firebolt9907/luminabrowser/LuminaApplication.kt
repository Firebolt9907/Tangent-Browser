package com.firebolt9907.luminabrowser

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.firebolt9907.luminabrowser.engine.LuminaBrowserComponents
import com.firebolt9907.luminabrowser.service.BrowsingKeepAliveService
import mozilla.components.browser.state.action.SystemAction
import mozilla.components.lib.fetch.httpurlconnection.HttpURLConnectionClient
import mozilla.components.support.AppServicesInitializer
import mozilla.components.support.rusthttp.RustHttpConfig

class LuminaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppServicesInitializer.init(AppServicesInitializer.Config(null))
        RustHttpConfig.setClient(lazy { HttpURLConnectionClient() })
        ProcessLifecycleOwner.get().lifecycle.addObserver(backgroundKeepAliveObserver)
    }

    private val componentsLazy = lazy { LuminaBrowserComponents(this) }
    val components: LuminaBrowserComponents by componentsLazy

    /**
     * Forward system memory pressure into the engine and snapshot cache. [SystemAction.LowMemoryAction]
     * is handled by mozilla-components' `TrimMemoryMiddleware`, which suspends the engine sessions of
     * non-selected tabs — the main lever that keeps RAM (and the low-memory killer's appetite for this
     * process) down as the tab count grows. We only act if the engine graph is already built, so a
     * trim callback never forces the heavy [LuminaBrowserComponents] init.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Free the TextToSpeech engine (and its bound service) when backgrounded and idle. Cheap to
        // recreate; independent of the components graph, so release it even before that is built.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            com.firebolt9907.luminabrowser.ui.reader.PageSpeaker.releaseIfIdle()
        }
        if (!componentsLazy.isInitialized()) return
        components.store.dispatch(SystemAction.LowMemoryAction(level))
        components.tabSnapshotCache.trimMemory(level)
    }

    /**
     * While the app is backgrounded, optionally run a foreground service to raise process importance
     * so the system is less likely to kill the browser (and discard open tabs). Opt-in via
     * [com.firebolt9907.luminabrowser.state.GlobalPreferencesStore.keepAliveInBackground]; the pref is
     * re-read on every transition so toggling it takes effect on the next background.
     */
    private val backgroundKeepAliveObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            if (componentsLazy.isInitialized() &&
                components.globalPreferencesStore.keepAliveInBackground
            ) {
                BrowsingKeepAliveService.start(this@LuminaApplication)
            }
        }

        override fun onStart(owner: LifecycleOwner) {
            BrowsingKeepAliveService.stop(this@LuminaApplication)
        }
    }
}

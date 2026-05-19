package com.firebolt9907.luminabrowser.engine

import android.app.Application
import com.firebolt9907.luminabrowser.addons.AddonController
import com.firebolt9907.luminabrowser.state.GeckoTabRepository
import com.firebolt9907.luminabrowser.state.GlobalPreferencesStore
import com.firebolt9907.luminabrowser.state.SitePreferencesStore
import com.firebolt9907.luminabrowser.state.TabSnapshotCache
import com.firebolt9907.luminabrowser.sync.FirefoxSyncController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases

/**
 * Application-scoped DI graph. Owns the [GeckoEngine], the [BrowserStore], the tab
 * repository, snapshot cache, site preferences, and the add-on controller. Constructed
 * lazily by [com.firebolt9907.luminabrowser.LuminaApplication]; see `ARCHITECTURE.md`.
 */
class LuminaBrowserComponents(application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val engine = GeckoEngine(application)
    val store = BrowserStore(
        initialState = BrowserState(),
        middleware = EngineMiddleware.create(engine, scope),
    )
    val sessionUseCases = SessionUseCases(store)
    val tabsUseCases = TabsUseCases(store)
    val tabRepository = GeckoTabRepository(store, sessionUseCases, tabsUseCases)
    val tabSnapshotCache = TabSnapshotCache(application.cacheDir)
    val sitePreferencesStore = SitePreferencesStore(application)
    val globalPreferencesStore = GlobalPreferencesStore(application)
    val addonController = AddonController(engine, scope)
    val firefoxSyncController = FirefoxSyncController(application, store, scope)

    private val _browserState = MutableStateFlow(store.state)
    val browserState: StateFlow<BrowserState> = _browserState.asStateFlow()

    private val storeSubscription = store.observeManually { state ->
        _browserState.value = state
    }

    init {
        storeSubscription.resume()
        tabRepository.seedIfEmpty(
            listOf(
                "https://www.apple.com/" to "Apple",
                "https://www.nytimes.com/" to "The New York Times",
                "https://www.wikipedia.org/" to "Wikipedia",
            )
        )
    }
}

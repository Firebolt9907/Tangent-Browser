package com.firebolt9907.luminabrowser.engine

import android.app.Application
import com.firebolt9907.luminabrowser.addons.AddonController
import com.firebolt9907.luminabrowser.state.GeckoTabRepository
import com.firebolt9907.luminabrowser.state.GlobalPreferencesStore
import com.firebolt9907.luminabrowser.state.SitePreferencesStore
import com.firebolt9907.luminabrowser.state.TabSnapshotCache
import com.firebolt9907.luminabrowser.sync.FirefoxSyncController
import com.firebolt9907.luminabrowser.sync.PlacesLoginsStorage
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
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy
import mozilla.components.browser.storage.sync.PlacesBookmarksStorage
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.feature.session.HistoryDelegate
import mozilla.components.feature.downloads.DownloadsUseCases
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases

/**
 * Application-scoped DI graph. Owns the [GeckoEngine], the [BrowserStore], the tab
 * repository, snapshot cache, site preferences, and the add-on controller. Constructed
 * lazily by [com.firebolt9907.luminabrowser.LuminaApplication]; see `ARCHITECTURE.md`.
 */
class LuminaBrowserComponents(application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val globalPreferencesStore = GlobalPreferencesStore(application)
    val historyStorage = PlacesHistoryStorage(application)
    val bookmarksStorage = PlacesBookmarksStorage(application)
    val engine = GeckoEngine(application, buildDefaultSettings(globalPreferencesStore, historyStorage))
    val store = BrowserStore(
        initialState = BrowserState(),
        middleware = EngineMiddleware.create(engine, scope),
    )
    val sessionUseCases = SessionUseCases(store)
    val tabsUseCases = TabsUseCases(store)
    val downloadsUseCases = DownloadsUseCases(store, application)
    val tabPersistence = com.firebolt9907.luminabrowser.state.TabPersistence(application)
    val tabRepository =
        GeckoTabRepository(store, sessionUseCases, tabsUseCases, tabPersistence, scope)
    val tabSnapshotCache = TabSnapshotCache(application.cacheDir)
    val sitePreferencesStore = SitePreferencesStore(application)
    val addonController = AddonController(engine, scope)
    val loginsStorage = PlacesLoginsStorage(application)
    val firefoxSyncController = FirefoxSyncController(application, store, scope, loginsStorage)
    val sitePermissionController =
        com.firebolt9907.luminabrowser.permissions.SitePermissionController(application)

    fun applyEngineSettings() {
        val prefs = globalPreferencesStore
        engine.settings.trackingProtectionPolicy = if (prefs.trackingProtection) {
            TrackingProtectionPolicy.recommended()
        } else {
            TrackingProtectionPolicy.none()
        }
        engine.settings.javaScriptCanOpenWindowsAutomatically = !prefs.blockPopUps
        engine.settings.javascriptEnabled = prefs.javaScriptEnabled
        engine.settings.httpsOnlyMode = if (prefs.httpsOnly) {
            Engine.HttpsOnlyMode.ENABLED
        } else {
            Engine.HttpsOnlyMode.DISABLED
        }
        engine.settings.globalPrivacyControlEnabled = prefs.globalPrivacyControl
    }

    private val _browserState = MutableStateFlow(store.state)
    val browserState: StateFlow<BrowserState> = _browserState.asStateFlow()

    private val storeSubscription = store.observeManually { state ->
        _browserState.value = state
    }

    init {
        storeSubscription.resume()
        tabRepository.restoreOrSeedBlank(TAB_MAX_AGE_MILLIS)
    }

    companion object {
        /** Tabs not viewed within this window are auto-discarded on the next launch. */
        const val TAB_MAX_AGE_MILLIS = 72L * 60 * 60 * 1000
    }
}

private fun buildDefaultSettings(
    prefs: GlobalPreferencesStore,
    historyStorage: PlacesHistoryStorage,
) = DefaultSettings(
    historyTrackingDelegate = HistoryDelegate(lazy { historyStorage }),
    trackingProtectionPolicy = if (prefs.trackingProtection) {
        TrackingProtectionPolicy.recommended()
    } else {
        TrackingProtectionPolicy.none()
    },
    javaScriptCanOpenWindowsAutomatically = !prefs.blockPopUps,
    javascriptEnabled = prefs.javaScriptEnabled,
    httpsOnlyMode = if (prefs.httpsOnly) Engine.HttpsOnlyMode.ENABLED else Engine.HttpsOnlyMode.DISABLED,
    globalPrivacyControlEnabled = prefs.globalPrivacyControl,
    loginAutofillEnabled = true,
)

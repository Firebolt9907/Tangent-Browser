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
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

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
    val engine = GeckoEngine(
        application,
        buildDefaultSettings(globalPreferencesStore, historyStorage),
        buildRuntime(application),
    )
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
    // Bounds live GeckoSessions to the foreground tab + a few recent ones so many open tabs stay
    // responsive. See BackgroundTabSuspender.
    val backgroundTabSuspender =
        com.firebolt9907.luminabrowser.state.BackgroundTabSuspender(store)

    fun applyEngineSettings() {
        val prefs = globalPreferencesStore
        engine.settings.trackingProtectionPolicy = trackingPolicyOf(prefs)
        engine.settings.javaScriptCanOpenWindowsAutomatically = !prefs.blockPopUps
        engine.settings.javascriptEnabled = prefs.javaScriptEnabled
        engine.settings.httpsOnlyMode = httpsModeOf(prefs)
        engine.settings.globalPrivacyControlEnabled = prefs.globalPrivacyControl
    }

    private val _browserState = MutableStateFlow(store.state)
    val browserState: StateFlow<BrowserState> = _browserState.asStateFlow()

    private val storeSubscription = store.observeManually { state ->
        _browserState.value = state
    }

    init {
        storeSubscription.resume()
        backgroundTabSuspender.start()
        tabRepository.restoreOrSeedBlank(TAB_MAX_AGE_MILLIS)
    }

    companion object {
        /** Tabs not viewed within this window are auto-discarded on the next launch. */
        const val TAB_MAX_AGE_MILLIS = 72L * 60 * 60 * 1000
    }
}

// Shared so the initial DefaultSettings and the runtime applyEngineSettings re-apply can't drift.
private fun trackingPolicyOf(prefs: GlobalPreferencesStore) =
    if (prefs.trackingProtection) TrackingProtectionPolicy.recommended() else TrackingProtectionPolicy.none()

private fun httpsModeOf(prefs: GlobalPreferencesStore) =
    if (prefs.httpsOnly) Engine.HttpsOnlyMode.ENABLED else Engine.HttpsOnlyMode.DISABLED

/**
 * The single per-process [GeckoRuntime]. There must be exactly one runtime per process; it is
 * owned by the app-scoped [LuminaBrowserComponents]. Tuned for memory/responsiveness rather than
 * left fully default:
 * - `lowMemoryDetection` lets Gecko shed internal caches under pressure (pairs with the app-level
 *   [mozilla.components.browser.state.action.SystemAction.LowMemoryAction] forwarding that suspends
 *   background tabs).
 * - `consoleOutput` / `debugLogging` / `remoteDebugging` off removes per-frame logging cost in
 *   non-debug use; remote debugging is an attack surface we don't ship enabled.
 * - `aboutConfig` off keeps the runtime from exposing the raw pref editor.
 */
private fun buildRuntime(application: Application): GeckoRuntime {
    val settings = GeckoRuntimeSettings.Builder()
        .lowMemoryDetection(true)
        .consoleOutput(false)
        .debugLogging(false)
        .remoteDebuggingEnabled(false)
        .aboutConfigEnabled(false)
        .build()
    return GeckoRuntime.create(application, settings)
}

private fun buildDefaultSettings(
    prefs: GlobalPreferencesStore,
    historyStorage: PlacesHistoryStorage,
) = DefaultSettings(
    historyTrackingDelegate = HistoryDelegate(lazy { historyStorage }),
    trackingProtectionPolicy = trackingPolicyOf(prefs),
    javaScriptCanOpenWindowsAutomatically = !prefs.blockPopUps,
    javascriptEnabled = prefs.javaScriptEnabled,
    httpsOnlyMode = httpsModeOf(prefs),
    globalPrivacyControlEnabled = prefs.globalPrivacyControl,
    loginAutofillEnabled = true,
)

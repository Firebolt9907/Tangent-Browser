package com.firebolt9907.tangentbrowser.engine

import android.app.Application
import com.firebolt9907.tangentbrowser.addons.AddonController
import com.firebolt9907.tangentbrowser.state.GeckoTabRepository
import com.firebolt9907.tangentbrowser.state.GlobalPreferencesStore
import com.firebolt9907.tangentbrowser.state.SitePreferencesStore
import com.firebolt9907.tangentbrowser.state.TabSnapshotCache
import com.firebolt9907.tangentbrowser.sync.FirefoxSyncController
import com.firebolt9907.tangentbrowser.sync.PlacesLoginsStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy
import mozilla.components.browser.storage.sync.PlacesBookmarksStorage
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.feature.session.HistoryDelegate
import mozilla.components.feature.downloads.DownloadsUseCases
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.customtabs.store.CustomTabsServiceStore
import mozilla.components.feature.tabs.CustomTabsUseCases
import mozilla.components.feature.tabs.TabsUseCases
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * Application-scoped DI graph. Owns the [GeckoEngine], the [BrowserStore], the tab
 * repository, snapshot cache, site preferences, and the add-on controller. Constructed
 * lazily by [com.firebolt9907.tangentbrowser.TangentApplication]; see `ARCHITECTURE.md`.
 */
class TangentBrowserComponents(application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val globalPreferencesStore = GlobalPreferencesStore(application)

    // Emits the blocked URI whenever a navigation fails under HTTPS-Only mode. Collected by TangentRoot
    // to raise the in-app "load over HTTP" prompt. Declared before `engine` because buildDefaultSettings
    // (called during `engine` init) wires the interceptor's failure callback to it.
    private val _httpsOnlyFailures = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val httpsOnlyFailures: SharedFlow<String> = _httpsOnlyFailures.asSharedFlow()
    val historyStorage = PlacesHistoryStorage(application)
    val bookmarksStorage = PlacesBookmarksStorage(application)
    // Held as a field (not inlined into GeckoEngine) so we can attach the autocomplete storage
    // delegate to it below, once the logins / credit-card stores exist.
    private val runtime = buildRuntime(application, globalPreferencesStore)
    val engine = GeckoEngine(
        application,
        buildDefaultSettings(
            application,
            globalPreferencesStore,
            historyStorage,
            onHttpsOnlyError = { _httpsOnlyFailures.tryEmit(it) },
        ),
        runtime,
    )
    val store = BrowserStore(
        initialState = BrowserState(),
        middleware = EngineMiddleware.create(engine, scope),
    )
    val sessionUseCases = SessionUseCases(store)
    val tabsUseCases = TabsUseCases(store)
    // Per-site tracking-protection exceptions (the "shield" toggle): lets the user disable ETP for a
    // single site when it breaks the page, without turning protection off globally.
    val trackingProtectionUseCases =
        mozilla.components.feature.session.TrackingProtectionUseCases(store, engine)
    // Loads real site favicons (memory + disk cached) for the tab overview captions, via the
    // HttpURLConnection fetch client. Falls back to a letter tile in the UI when a site has none.
    val icons = BrowserIcons(application, mozilla.components.lib.fetch.httpurlconnection.HttpURLConnectionClient())
    // Owns add/remove/migrate for custom-tab sessions (external-link "Custom Tab" surface hosted by
    // CustomTabActivity). Custom tabs live in store.state.customTabs, separate from the normal tab
    // list, so they never touch the grid, persistence, or Firefox Sync.
    val customTabsUseCases = CustomTabsUseCases(store, sessionUseCases.loadUrl)
    // Backs TangentCustomTabsService — the CustomTabsService we advertise so callers using the
    // Custom Tabs protocol (e.g. the Google app / Discover) recognize Tangent as a CCT provider and
    // launch us through CustomTabsIntent (session token + enter-animation options bundle + first-
    // class custom-tab treatment) instead of a plain ACTION_VIEW that carries no animation.
    val customTabsServiceStore = CustomTabsServiceStore()
    val downloadsUseCases = DownloadsUseCases(store, application)
    val tabPersistence = com.firebolt9907.tangentbrowser.state.TabPersistence(application)
    // Organizational tab groups (Chrome/Arc-style labels over open tabs). Constructed before the tab
    // repository so membership can be re-linked as tabs are restored. Not cookie isolation — that is
    // containers (below).
    val tabGroupsRepository =
        com.firebolt9907.tangentbrowser.state.TabGroupsRepository(store, application)
    // Contextual identities / containers: real per-container cookie-store isolation via GeckoView
    // context ids. Registry (name/colour/icon) persisted + mirrored into store.state.containers.
    val containerRepository =
        com.firebolt9907.tangentbrowser.state.ContainerRepository(store, application)
    val tabRepository =
        GeckoTabRepository(
            store,
            sessionUseCases,
            tabsUseCases,
            engine,
            tabPersistence,
            scope,
            searchEngine = { globalPreferencesStore.searchEngine },
            tabGroups = tabGroupsRepository,
        )
    // Owns the runtime HTTPS-Only setting: keeps it on globally but drops it while the selected tab is
    // on a user-approved local host (GeckoView has no per-site HTTPS-Only API). See the class doc.
    val httpsOnlyExceptionController =
        HttpsOnlyExceptionController(store, engine, globalPreferencesStore, scope).also { it.start() }
    // Persist tab thumbnails in filesDir, not cacheDir: cacheDir is disposable storage that Android
    // evicts under storage pressure (common on space-constrained devices), which wiped restored
    // tabs' thumbnails between sessions. filesDir survives until we prune it ourselves (the cache
    // still self-bounds via maxEntries), so thumbnails persist across app restarts / force-stops.
    val tabSnapshotCache = TabSnapshotCache(application.filesDir)
    val sitePreferencesStore = SitePreferencesStore(application)
    val addonController = AddonController(engine, scope)
    val loginsStorage = PlacesLoginsStorage(application)
    // Credit-card (and address) autofill storage. Card numbers are encrypted at rest with a key
    // AutofillCrypto keeps in the hardware-backed SecureAbove22Preferences (the store refuses to
    // persist a plaintext card number), and sync to the account rides the same sync15 E2E path as
    // logins. Backs both the credit-card and address autofill features (each gated at runtime by its
    // own pref via the storage delegate's enabled lambdas).
    val creditCardsStorage = mozilla.components.service.sync.autofill.AutofillCreditCardsAddressesStorage(
        application,
        lazy {
            mozilla.components.lib.dataprotect.SecureAbove22Preferences(
                application,
                "tangent_autofill_secure",
                false,
            )
        },
    )
    // A tab pushed here via Firefox "Send tab" raises a notification (ReceivedTabNotifications);
    // tapping it opens the URL as a normal tab in MainActivity (single normalizeUrl entry point).
    val firefoxSyncController = FirefoxSyncController(
        application,
        store,
        scope,
        loginsStorage,
        creditCardsStorage,
        historyStorage,
        bookmarksStorage,
    )
    val sitePermissionController =
        com.firebolt9907.tangentbrowser.permissions.SitePermissionController(application)
    // Bounds live GeckoSessions to the foreground tab + a few recent ones so many open tabs stay
    // responsive. The keep-alive budget is scaled to the device's RAM class (a 3 GB budget phone
    // stutters at 2 live web sessions where an 8 GB flagship handles 5), and shrinks further under
    // system memory pressure via onTrimMemory. See BackgroundTabSuspender.
    val backgroundTabSuspender =
        com.firebolt9907.tangentbrowser.state.BackgroundTabSuspender(
            store,
            baseKeepAliveBackground = keepAliveBudgetForDevice(application),
        )
    // Warms connections to frecent origins so revisiting a common site (google.com etc.) lands on an
    // already-open TLS connection with its assets already in Gecko's enlarged HTTP disk cache.
    val speculativePreconnector =
        com.firebolt9907.tangentbrowser.state.SpeculativePreconnector(engine, scope)
    // Preloads Custom Tabs `mayLaunchUrl` targets (e.g. Google Discover article links) into the
    // shared HTTP cache so the tap-through open is served from cache. See TangentCustomTabsService.
    val externalLinkPreloader =
        com.firebolt9907.tangentbrowser.state.ExternalLinkPreloader(engine, scope)

    fun applyEngineSettings() {
        val prefs = globalPreferencesStore
        runCatching { engine.settings.trackingProtectionPolicy = trackingPolicyOf(prefs) }
        runCatching { engine.settings.javaScriptCanOpenWindowsAutomatically = !prefs.blockPopUps }
        runCatching { engine.settings.javascriptEnabled = prefs.javaScriptEnabled }
        // HttpsOnlyExceptionController owns the runtime httpsOnlyMode (base setting + per-host
        // exceptions), so re-derive through it rather than writing the base mode directly here.
        httpsOnlyExceptionController.recompute()
        runCatching { engine.settings.globalPrivacyControlEnabled = prefs.globalPrivacyControl }

        // Cookie banner auto-dismissal
        val cookieMode = when (prefs.cookieBannerHandlingMode) {
            1 -> mozilla.components.concept.engine.EngineSession.CookieBannerHandlingMode.REJECT_ALL
            2 -> mozilla.components.concept.engine.EngineSession.CookieBannerHandlingMode.REJECT_OR_ACCEPT_ALL
            else -> mozilla.components.concept.engine.EngineSession.CookieBannerHandlingMode.DISABLED
        }
        runCatching { engine.settings.cookieBannerHandlingMode = cookieMode }
        runCatching { engine.settings.cookieBannerHandlingModePrivateBrowsing = cookieMode }

        // Query parameter stripping
        runCatching { engine.settings.queryParameterStripping = prefs.queryParameterStripping }
        runCatching { engine.settings.queryParameterStrippingPrivateBrowsing = prefs.queryParameterStripping }

        // Media autoplay requiring user gesture
        runCatching { engine.settings.mediaPlaybackRequiresUserGesture = prefs.mediaPlaybackRequiresUserGesture }

        // Cookie partitioning (dFPI / TCP)
        runCatching { engine.settings.cookieBehaviorOptInPartitioning = prefs.cookieBehaviorOptInPartitioning }
        runCatching { engine.settings.cookieBehaviorOptInPartitioningPBM = prefs.cookieBehaviorOptInPartitioning }

        // Fingerprinting protection
        runCatching { engine.settings.fingerprintingProtection = prefs.fingerprintingProtection }
        runCatching { engine.settings.fingerprintingProtectionPrivateBrowsing = prefs.fingerprintingProtection }

        // DNS over HTTPS (DoH)
        val dohMode = when (prefs.dohSettingsMode) {
            1 -> mozilla.components.concept.engine.Engine.DohSettingsMode.DEFAULT
            2 -> mozilla.components.concept.engine.Engine.DohSettingsMode.INCREASED
            3 -> mozilla.components.concept.engine.Engine.DohSettingsMode.MAX
            else -> mozilla.components.concept.engine.Engine.DohSettingsMode.OFF
        }
        runCatching { engine.settings.dohSettingsMode = dohMode }

        // Enterprise Roots
        runCatching { engine.settings.enterpriseRootsEnabled = prefs.enterpriseRootsEnabled }

        // Force User Scalable Content (Force Zoom)
        runCatching { engine.settings.forceUserScalableContent = prefs.forceUserScalableContent }

        // Font size factor
        runCatching { engine.settings.fontSizeFactor = prefs.fontSizeFactor }

        // Web dark mode — Tier 1 (native). Flip the `prefers-color-scheme: dark` media query for all
        // content so any site with a real dark theme renders it for free (best quality, zero cost).
        // Tier 2 (the filter-invert fallback for sites that stay light) is injected per-load by
        // ContentHost via WebDarkMode. Only the explicit "On" trigger forces Dark; "Auto" and "Off"
        // stay on System — which for Auto means Gecko already follows the OS dark setting natively, so
        // dark-theme sites render dark whenever the device is dark with no extra work. See
        // ui/content/WebDarkMode.kt.
        runCatching {
            engine.settings.preferredColorScheme =
                if (prefs.webDarkModeTrigger == com.firebolt9907.tangentbrowser.state.WebDarkModeTrigger.On) {
                    mozilla.components.concept.engine.mediaquery.PreferredColorScheme.Dark
                } else {
                    mozilla.components.concept.engine.mediaquery.PreferredColorScheme.System
                }
        }

        // Login Autofill — the engine flag maps to Gecko's `signon.autofillForms`, which does
        // *silent* page-load autofill: when a form has exactly one saved match Gecko fills it on
        // load with NO prompt and NO biometric. That is the path that made autofill sometimes fill
        // without asking for biometrics. We keep it OFF unconditionally so a saved login is NEVER
        // filled silently. The focus-triggered autocomplete popup still fetches logins (it passes
        // `autofillForm:true, userTriggered:true` to Gecko's _fillForm regardless of this pref) and
        // surfaces as a PromptRequest.SelectLoginPrompt, which PromptHandler gates behind
        // BiometricGate. The user's `prefs.loginAutofillEnabled` toggle now gates whether that
        // biometric picker is offered at all (see PromptHandler), not silent fill.
        runCatching { engine.settings.loginAutofillEnabled = false }

        // Generic form-field memory (the "Remember form fields" pref). Gated host-side so the injected
        // capture/suggest script self-disarms when the user turns it off.
        com.firebolt9907.tangentbrowser.ui.content.FormFieldMemory.enabled = prefs.formFieldMemoryEnabled

        // Web Fonts
        runCatching { engine.settings.webFontsEnabled = prefs.webFontsEnabled }

        // DOM Storage
        runCatching { engine.settings.domStorageEnabled = prefs.domStorageEnabled }

        // Suspend Media When Inactive
        runCatching { engine.settings.suspendMediaWhenInactive = prefs.suspendMediaWhenInactive }

        // Scrollbars
        runCatching {
            engine.settings.verticalScrollBarEnabled = prefs.scrollBarsEnabled
            engine.settings.horizontalScrollBarEnabled = prefs.scrollBarsEnabled
        }

        // Allow File Access
        runCatching { engine.settings.allowFileAccess = prefs.allowFileAccess }

        // Post-Quantum Key Exchange
        runCatching { engine.settings.postQuantumKeyExchangeEnabled = prefs.postQuantumKeyExchangeEnabled }

        // Safe Browsing V5
        runCatching { engine.settings.safeBrowsingV5Enabled = prefs.safeBrowsingV5Enabled }

        // Automatic Font Size Adjustment
        runCatching { engine.settings.automaticFontSizeAdjustment = prefs.automaticFontSizeAdjustment }

        // Automatic Language Adjustment
        runCatching { engine.settings.automaticLanguageAdjustment = prefs.automaticLanguageAdjustment }

        // Cookie Banner Subframes Dismissal
        runCatching { engine.settings.cookieBannerHandlingGlobalRulesSubFrames = prefs.cookieBannerHandlingGlobalRulesSubFrames }

        // Email Tracker Blocking
        runCatching { engine.settings.emailTrackerBlockingPrivateBrowsing = prefs.emailTrackerBlockingPrivateBrowsing }

        // Custom DNS over HTTPS (DoH) Provider URL
        runCatching {
            engine.settings.dohProviderUrl = prefs.dohProviderUrl
        }

        // Local Network Access (LNA) Blocking
        runCatching {
            engine.settings.lnaBlockingEnabled = prefs.lnaBlockingEnabled
            engine.settings.lnaFeatureEnabled = prefs.lnaBlockingEnabled
        }

        // Allow File Access from File URLs (File-to-File cross-access only). The "Allow file URL
        // cross-access" toggle grants a local file:// page access to OTHER LOCAL FILES — nothing
        // more. Universal access (allowUniversalAccessFromFileURLs) is deliberately kept OFF: it
        // would let a local file:// page read ANY remote origin cross-origin and exfiltrate other
        // local files to a server — far beyond the toggle's stated scope and a serious risk if the
        // user ever opens a hostile downloaded HTML file. Do NOT couple it back to the pref.
        runCatching {
            engine.settings.allowFileAccessFromFileURLs = prefs.allowFileAccessFromFileURLs
            engine.settings.allowUniversalAccessFromFileURLs = false
        }

        // Custom Blocked Ports Override (Banned Ports)
        runCatching {
            engine.settings.bannedPorts = prefs.bannedPorts
        }

        // Platform-Independent Math (Fdlibm)
        runCatching {
            engine.settings.fdlibmMathEnabled = prefs.fdlibmMathEnabled
        }

        // Query Parameter Stripping custom lists
        runCatching {
            engine.settings.queryParameterStrippingStripList = prefs.queryParameterStrippingStripList
            engine.settings.queryParameterStrippingAllowList = prefs.queryParameterStrippingAllowList
        }

        // CRLite Channel
        runCatching {
            engine.settings.crliteChannel = prefs.crliteChannel
        }
    }

    private val _browserState = MutableStateFlow(store.state)
    val browserState: StateFlow<BrowserState> = _browserState.asStateFlow()

    // Derived flow that only emits when the selected tab ID changes, not on every BrowserState
    // update (tab content changes, loading state, etc.). Collecting this instead of the full
    // browserState prevents TangentRoot from recomposing on every tab content update.
    private val _selectedTabId = MutableStateFlow(store.state.selectedTabId)
    val selectedTabId: StateFlow<String?> = _selectedTabId.asStateFlow()

    // Derived flow for the tabs list — only emits when tabs are added/removed/reordered, not when
    // a tab's content (URL, title, loading state) changes. This prevents recomposition of the
    // tab-overview and grid composables when unrelated tabs update.
    private val _tabsList = MutableStateFlow(store.state.tabs)
    val tabsList: StateFlow<List<mozilla.components.browser.state.state.TabSessionState>> = _tabsList.asStateFlow()

    private val storeSubscription = store.observeManually { state ->
        _browserState.value = state
        // Only emit derived flows when their specific content changed, avoiding downstream
        // recompositions on the hot path (scroll, content updates).
        if (_selectedTabId.value != state.selectedTabId) {
            _selectedTabId.value = state.selectedTabId
        }
        if (_tabsList.value != state.tabs) {
            _tabsList.value = state.tabs
        }
    }

    // Bridges GeckoView's autofill to our stores: when Gecko detects a login or credit-card field it
    // asks this delegate for matching records and reports saves back. Attaching it to the runtime is
    // what actually makes autofill work — `loginAutofillEnabled`/the CC prefs alone only tell Gecko
    // to try; without a storage delegate it has nothing to fill from and nowhere to save to.
    private val autocompleteStorageDelegate =
        mozilla.components.browser.engine.gecko.autofill.GeckoAutocompleteStorageDelegate(
            mozilla.components.service.sync.autofill.GeckoCreditCardsAddressesStorageDelegate(
                lazy { creditCardsStorage },
                scope,
                mozilla.components.service.sync.autofill.DefaultCreditCardValidationDelegate(
                    lazy { creditCardsStorage },
                ),
                { globalPreferencesStore.creditCardAutofillEnabled },
                { globalPreferencesStore.addressAutofillEnabled },
            ),
            com.firebolt9907.tangentbrowser.sync.TangentLoginStorageDelegate(
                loginsStorage,
                scope,
            ),
        )

    init {
        // Backing store for generic (non-login/card/address) form-field memory. The bridge handlers
        // in ReaderExtractor are static, so the store is attached once here.
        com.firebolt9907.tangentbrowser.ui.content.FormFieldMemory.attach(application)
        applyEngineSettings()
        runtime.autocompleteStorageDelegate = autocompleteStorageDelegate
        storeSubscription.resume()
        backgroundTabSuspender.start()
        tabRepository.restoreOrSeedBlank(tabMaxAgeMillis(globalPreferencesStore))
        // Get a head start on the first navigation of the session: spin up a content process now so
        // the first page load doesn't pay process-spawn latency, and warm the user's top sites while
        // they're still looking at the restored tab / new-tab bar.
        engine.speculativeCreateSession()
        speculativePreconnector.warmTopSites(historyStorage)
        // Install the built-in document_start dark-mode pre-paint extension (removes the white flash
        // on light pages). Enabled only while web dark mode is globally on — start enabled iff the
        // trigger is explicitly On; TangentRoot corrects for the Auto/system case once it composes.
        com.firebolt9907.tangentbrowser.ui.content.WebDarkModeExtension.install(
            engine,
            initialEnabled = globalPreferencesStore.webDarkModeTrigger ==
                com.firebolt9907.tangentbrowser.state.WebDarkModeTrigger.On,
        )
    }

    companion object {
        /** Default window: tabs not viewed within it are auto-discarded on the next launch. */
        const val TAB_MAX_AGE_MILLIS = 72L * 60 * 60 * 1000

        /**
         * Resolve the auto-archive window from the user's setting
         * ([GlobalPreferencesStore.tabAutoArchiveHours]). `0` hours disables archiving, which we
         * express as [Long.MAX_VALUE] so [TabPersistence.load]'s age check never drops a tab.
         */
        fun tabMaxAgeMillis(prefs: GlobalPreferencesStore): Long {
            val hours = prefs.tabAutoArchiveHours
            return if (hours <= 0) Long.MAX_VALUE else hours * 60L * 60 * 1000
        }
    }
}

// Shared so the initial DefaultSettings and the runtime applyEngineSettings re-apply can't drift.
private fun trackingPolicyOf(prefs: GlobalPreferencesStore) =
    if (prefs.trackingProtection) TrackingProtectionPolicy.recommended() else TrackingProtectionPolicy.none()

private fun httpsModeOf(prefs: GlobalPreferencesStore) =
    if (prefs.httpsOnly) Engine.HttpsOnlyMode.ENABLED else Engine.HttpsOnlyMode.DISABLED

/**
 * The single per-process [GeckoRuntime]. There must be exactly one runtime per process; it is
 * owned by the app-scoped [TangentBrowserComponents]. Tuned for memory/responsiveness rather than
 * left fully default:
 * - `lowMemoryDetection` lets Gecko shed internal caches under pressure (pairs with the app-level
 *   [mozilla.components.browser.state.action.SystemAction.LowMemoryAction] forwarding that suspends
 *   background tabs).
 * - `consoleOutput` / `debugLogging` / `remoteDebugging` off removes per-frame logging cost in
 *   non-debug use; remote debugging is an attack surface we don't ship enabled.
 * - `aboutConfig` off keeps the runtime from exposing the raw pref editor.
 * - `configFilePath` points at a generated YAML that enlarges the necko HTTP disk cache (see
 *   [writeGeckoConfig]) so frequently-visited sites' assets survive in cache far longer. Setting the
 *   path explicitly is what lets a non-debuggable (release) build read the config at all — otherwise
 *   Gecko only reads it for debuggable apps.
 */
private fun buildRuntime(application: Application, prefs: GlobalPreferencesStore): GeckoRuntime {
    val settings = GeckoRuntimeSettings.Builder()
        .lowMemoryDetection(true)
        .consoleOutput(true) // TEMP: archive-reflow diagnostics (revert to false)
        .debugLogging(false)
        .remoteDebuggingEnabled(false)
        .aboutConfigEnabled(false)
        .apply { writeGeckoConfig(application, prefs)?.let { configFilePath(it) } }
        .build()
    // WebAuthn/passkeys normally need no pref overrides: Gecko's Android defaults already route
    // requests to the platform FIDO2/Credential Manager path (Google Password Manager) with
    // resident-key support enabled. Forcing `webauthn_enable_softtoken` on would silently service
    // requests with Gecko's virtual test authenticator instead of showing the system passkey UI.
    // NOTE: WebAuthn is currently disabled outright via `security.webauth.webauthn: false` in
    // writeGeckoConfig() — a TEMPORARY measure until Tangent is on the GPM trusted-browser allowlist.
    return GeckoRuntime.create(application, settings)
}

/**
 * Writes the GeckoView config YAML that tunes necko for aggressive asset caching, returning its
 * absolute path (or null if it couldn't be written — in which case we simply fall back to Gecko's
 * defaults). Rewritten every launch so the [GlobalPreferencesStore.httpCacheSizeMb] setting always
 * takes effect on the next launch.
 *
 * - `browser.cache.disk.parent_directory` relocates the necko disk cache (`cache2/`) into the app's
 *   Android **cache** directory. That's what keeps it out of the "app data" bucket in Android
 *   Settings → Storage: files under `getCacheDir()` count as Cache (and Android may evict them under
 *   storage pressure) rather than as durable user data.
 * - `browser.cache.disk.smart_size.enabled = false` stops Gecko from auto-shrinking the cache to a
 *   fraction of free space; without this, a large `capacity` is ignored.
 * - `browser.cache.disk.capacity` (KB) is the on-disk HTTP cache cap — user-configurable, default
 *   512 MB vs the ~tens of MB a small device would otherwise get, so a heavy site's assets aren't
 *   evicted between visits.
 * - `network.http.speculative-parallel-limit` widens how many origins we can pre-warm at once (see
 *   [com.firebolt9907.tangentbrowser.state.SpeculativePreconnector]).
 * - `network.predictor.*` turns on necko's built-in predictor: it records which subresources each
 *   origin pulls and, the moment a navigation to that origin starts, preconnects/prefetches them
 *   from local history. This is what makes a *revisited* site load noticeably faster (the browser
 *   is already fetching the CSS/JS/images before the HTML finishes parsing).
 * - The remaining `content.*`/`gfx.*`/`media.*`/`image.*`/`network.*` block ports Betterfox's
 *   "Fastfox" performance tuning (yokoffing/Betterfox), filtered to prefs that make sense on mobile
 *   GeckoView: reflow batching, larger Skia font / media / image caches, higher connection ceilings
 *   with request pacing off, and fatter DNS + TLS-token caches so revisits skip DNS/handshake cost.
 *   Desktop-only mousewheel/scroll-physics ("Smoothfox") prefs are intentionally excluded — touch
 *   scrolling is driven by APZ and those prefs would fight it.
 */
private fun writeGeckoConfig(application: Application, prefs: GlobalPreferencesStore): String? = try {
    val capacityKb = prefs.httpCacheSizeMb.toLong() * 1024L
    val cacheParent = application.cacheDir.absolutePath
    // User-facing about:config toggles with no typed GeckoEngine setting. Written into the YAML below
    // so they apply on the next launch (Gecko reads this file once at GeckoRuntime.create()); their
    // settings toggles note "Restart to apply".
    val autoplayDefault = if (prefs.blockAllAutoplay) 5 else 1   // 5 = block all, 1 = block audible
    val blockAutoplayBg = prefs.blockAllAutoplay                 // block until foregrounded too
    val webNotifications = prefs.webNotificationsEnabled
    val serviceWorkers = prefs.serviceWorkersEnabled
    val webRtcHideIp = prefs.webRtcIpsHidden
    val sendBeacon = prefs.sendBeaconEnabled
    val file = java.io.File(application.filesDir, "geckoview-config.yaml")
    file.writeText(
        """
        prefs:
          browser.cache.disk.parent_directory: "$cacheParent"
          browser.cache.disk.smart_size.enabled: false
          browser.cache.disk.capacity: $capacityKb
          # In-memory HTTP cache: Gecko auto-sizes this from physical RAM but caps it near 32 MB
          # even on high-RAM phones. Pinning 50 MB keeps more recently-used images/CSS resident in
          # RAM, so back/forward and revisits are served without re-reading and re-decoding from
          # disk — smoother navigation and marginally fewer disk reads. lowMemoryDetection() still
          # lets Gecko shed it under pressure, and 50 MB is trivial on this device's RAM budget.
          browser.cache.memory.capacity: 51200
          # Per-entry cap for the in-memory cache. Gecko's default (~5 MB) means any single
          # response larger than that is never kept in RAM — it only lives on disk, so a big
          # framework bundle, web font, or hero image gets re-read (and re-decoded) from disk on
          # every back/forward or same-session revisit even though we just pinned a 50 MB RAM
          # cache above. Raise the cap to 20 MB so those larger assets actually stay resident,
          # matching Chrome's behavior of serving recent large resources straight from memory.
          # Still bounded by the 50 MB capacity above and shed under memory pressure.
          browser.cache.memory.max_entry_size: 20480
          network.http.speculative-parallel-limit: 12
          network.predictor.enabled: true
          network.predictor.enable-prefetch: true
          network.http.http3.enable: true
          # --- Chrome-parity: HTTP/3 discovery via HTTPS DNS records (SVCB/HTTPS RR) ---
          # HTTP/3 is enabled above, but without HTTPS RR a browser only *learns* an origin
          # speaks HTTP/3 from the `Alt-Svc` header on a prior HTTP/1.1-or-H2 response — so the
          # very first visit to a site always pays a slower TCP+H2 connection, and only later
          # visits get QUIC. Chrome instead reads the DNS `HTTPS`/`SVCB` record (which advertises
          # `h3` in its ALPN) during name resolution and connects straight over QUIC on the first
          # hit. Turn on Gecko's equivalent: consult the HTTPS RR and treat its ALPN as an
          # Alt-Svc source so the first navigation goes directly to HTTP/3 when the origin's DNS
          # advertises it. Faster first-visit connects to the many H3 origins behind Cloudflare/
          # Google/Fastly; inert for origins without an HTTPS RR. Safe fallback: if the QUIC race
          # fails Gecko still falls back to TCP.
          network.dns.upgrade_with_https_rr: true
          network.dns.use_https_rr_as_altsvc: true
          network.http.max-persistent-connections-per-server: 10
          network.dns.disablePrefetch: false
          # --- Smoothness: paint the first frame immediately (Betterfox "Fastfox") ---
          # Gecko delays the initial paint by 5ms (250ms for out-of-process iframes) to
          # batch a page's earliest reflows into the first paint. On a phone that delay just
          # reads as a beat of blank before content appears; zeroing both makes every
          # navigation feel snappier without changing what eventually gets painted.
          nglayout.initialpaint.delay: 0
          nglayout.initialpaint.delay_in_oopif: 0
          # --- Chrome-parity: back/forward cache (instant Back/Forward navigation) ---
          # Chrome's BFCache keeps a few recently-visited pages fully alive (DOM + JS heap +
          # scroll position) so Back/Forward restores them instantly instead of reloading and
          # re-running the page. Firefox *has* BFCache too, but Firefox-for-Android historically
          # pins the viewer budget very low (often 0) to save RAM — so on GeckoView Back typically
          # triggers a full reload, the single biggest navigation-feel gap vs Chrome. Pin a small
          # positive budget: 4 in-memory page viewers is enough to make the common Back/Forward
          # (and swipe-back) restore instant while bounding worst-case memory to a handful of
          # pages; lowMemoryDetection() still lets Gecko evict them under pressure. Pages that opt
          # out (unload handlers, Cache-Control: no-store) are never cached, matching Chrome.
          browser.sessionhistory.max_total_viewers: 4
          # BFCache must also be allowed to operate when a page has out-of-process (Fission)
          # subframes — otherwise any page with a cross-origin iframe (ads, embeds, social
          # widgets) silently falls out of BFCache and reloads on Back. Default varies by channel;
          # set explicitly so the budget above actually applies to real-world pages. No-op if
          # already on.
          fission.bfcacheInParent: true
          # Long-press: shorten the hold before Gecko fires the long-tap (→ HitResult → the link
          # "glance" preview). Gecko's default is 500ms, which feels sluggish for a peek gesture;
          # 280ms triggers noticeably sooner while staying above the ~150ms tap/scroll threshold so
          # normal taps and scrolls aren't misread as long-presses.
          ui.click_hold_context_menus.delay: 280
          # --- Battery: drop Gecko's redundant captive-portal probing ---
          # GeckoView's captive-portal service periodically fetches a canonical URL to detect
          # Wi-Fi sign-in portals. Android already owns captive-portal detection at the OS
          # level (it shows its own sign-in flow before the browser is even involved), so
          # Gecko's copy is a duplicate periodic wakeup + radio poke we don't need.
          network.captive-portal-service.enabled: false
          captivedetect.canonicalURL: ""
          # Also stop the connectivity service, which probes that same canonical URL on a timer
          # to re-derive network capabilities. On Android the authoritative online/offline signal
          # comes from the OS ConnectivityManager, and navigator.onLine stays true regardless of
          # this probe, so its only effect here is extra periodic radio wakeups. Failure mode is
          # benign (assume-online), which is what the page sees anyway.
          network.connectivity-service.enabled: false
          # Battery: throttle JS timers in backgrounded tabs harder. BackgroundTabSuspender
          # (state/) suspends background GeckoSessions beyond a small keep-alive budget, but the
          # 1-3 tabs it keeps live still fire their setTimeout/setInterval work while off-screen.
          # Raising the background-timer floor from Gecko's 1s default to 5s cuts that idle CPU
          # burn; foregrounding a tab un-throttles it immediately, sub-5s pollers are exactly what
          # we want slowed while hidden, and audible/media tabs are exempt from this clamp.
          dom.min_background_timeout_value: 5000
          # --- Betterfox "Fastfox" (mobile-appropriate subset) ---
          # General: batch layout notifications during load into ~100ms slices so a busy
          # page reflows fewer times while parsing (fewer, larger paints = faster to done).
          content.notify.interval: 100000
          # GFX: give Skia a larger in-process font cache so repeated glyph rasterization
          # (common on text-heavy pages / infinite scroll) hits the cache instead of redrawing.
          gfx.content.skia-font-cache-size: 20
          # GFX: enlarge the accelerated-canvas cache so canvas-heavy pages (charts, games,
          # map tiles) reuse GPU-cached draw items across frames instead of re-uploading them,
          # for steadier frame pacing. Only bites when accelerated canvas is active; otherwise
          # inert. (Betterfox "Fastfox".)
          gfx.canvas.accelerated.cache-items: 4096
          gfx.canvas.accelerated.cache-size: 512
          # Autoplay activation policy: block audible autoplay even after the page is interacted
          # with. Policy 2 ("user gesture activation") means only a gesture *directly on the media
          # element* lifts the autoplay block — the default policy 0 ("sticky activation") lets any
          # tap/scroll to read an article count as activation, which then unmutes a lurking article/
          # ad video. This only takes effect while autoplay is blocked (the "Block autoplaying media"
          # setting / mediaPlaybackRequiresUserGesture=true); muted autoplay and tap-to-play are
          # unaffected. Pairs with SitePermissionController rejecting ContentAutoPlayAudible.
          media.autoplay.blocking_policy: 2
          # Battery: also gate the Web Audio API behind the autoplay block. The policy above
          # covers <audio>/<video>, but by default a page can create and start an AudioContext
          # (Web Audio) with no user gesture at all — ad frames and idle game canvases routinely
          # do this, running an audio DSP graph (and sometimes emitting sound) the moment the page
          # loads. Setting this true makes a fresh AudioContext start "suspended" until a real user
          # gesture, exactly like Chrome's autoplay policy, so background audio processing doesn't
          # burn CPU/battery until the user actually interacts. Legitimate audio (a gesture, or the
          # site calling resume() after one) is unaffected.
          media.autoplay.block-webaudio: true
          # Media cache: larger in-memory media buffer + more aggressive readahead so audio/
          # video streams buffer further ahead and re-seek without re-fetching.
          media.memory_cache_max_size: 65536
          media.cache_readahead_limit: 7200
          media.cache_resume_threshold: 3600
          # Battery: suspend the *video* decoder for a tab whose video keeps playing in the
          # background. BackgroundTabSuspender (state/) suspends idle background sessions, but
          # audible/media tabs are intentionally exempt so audio keeps playing — which leaves a
          # background video (a muted autoplay loop, or a music video kept on for its audio) still
          # running its GPU/CPU video decode off-screen for no visible benefit. Chrome throttles
          # background video the same way. Turn Gecko's suspender on and give it a 10s grace period
          # (so a quick tab-flip doesn't stutter playback); audio is untouched and the frames
          # resume the instant the tab is foregrounded again.
          media.suspend-background-video.enabled: true
          media.suspend-background-video.delay-ms: 10000
          # Image cache: decode images in larger chunks so large images appear sooner.
          image.mem.decode_bytes_at_a_time: 32768
          # Keep decoded image surfaces around longer. Gecko's surface cache can evict a decoded
          # image as soon as it's been off-screen for min_expiration_ms (default 60s), so scrolling
          # back up a long article — or returning to a tab after a minute — re-decodes those images
          # from their compressed source (CPU + battery + a brief blank-then-pop). Raise the floor
          # to 2 minutes so revisited/scrolled-back images are still decoded and paint instantly,
          # matching Chrome's more generous decoded-image retention. Still bounded by the surface
          # cache's overall size cap and discarded first under memory pressure, so worst case is
          # just a re-decode — never an OOM.
          image.mem.surfacecache.min_expiration_ms: 120000
          # Network: raise connection ceilings and stop artificially pacing request bursts so
          # asset-heavy pages open all their sockets up front instead of trickling requests.
          network.http.max-connections: 1800
          network.http.max-urgent-start-excessive-connections-per-host: 5
          network.http.pacing.requests.enabled: false
          # When a host is already at its per-server connection ceiling, Gecko holds a further
          # request in the queue for up to this long (default 10s) hoping an in-flight connection
          # frees up before it opens a brand-new socket. On a busy page that briefly saturates a
          # host, that wait can stall a needed sub-resource for several seconds. Halving it to 5s
          # opens the extra connection sooner so the asset starts downloading rather than waiting
          # — closer to Chrome's more eager socket scheduling. (Betterfox "Fastfox".)
          network.http.request.max-start-delay: 5
          # Network buffers: larger socket read buffers move each transfer in fewer, bigger
          # chunks — fewer read syscalls per response (a touch less CPU per byte on big pages).
          # (Betterfox "Fastfox".)
          network.buffer.cache.count: 24
          network.buffer.cache.size: 262144
          # Network caching: keep far more resolved DNS entries and TLS session tokens around so
          # revisits skip the DNS round-trip and resume TLS (0-RTT) instead of a full handshake.
          network.dnsCacheEntries: 10000
          network.dnsCacheExpiration: 3600
          network.ssl_tokens_cache_capacity: 10240
          # Make the payoff of that TLS-token cache explicit: enable TLS 1.3 0-RTT ("early data").
          # With a cached session token, 0-RTT lets a reconnect send its first request *in the
          # opening handshake flight* instead of waiting a full round-trip for the handshake to
          # finish — the fast reconnect Chrome does to sites you revisit. It only applies to TLS
          # 1.3 sessions we've already spoken to, and browsers restrict early data to replay-safe
          # (idempotent) requests, so it's not the security downgrade that e.g. disabling OCSP
          # would be. The token cache above only pays off if this is on, and GeckoView's release
          # channel doesn't necessarily default it on — set it explicitly (same pattern as the
          # Early Hints block). Worst case (already on) = no-op.
          security.tls.enable_0rtt_data: true
          # First-visit DNS parallelism: Gecko resolves at most 5 high-priority hostnames at once
          # (default). A modern page pulls sub-resources from many distinct hosts (the origin plus
          # a fan of CDN/ad/analytics/font domains); on the first visit, before the enlarged DNS
          # cache above is warm, those lookups queue behind that limit of 5 and serialize, delaying
          # the connects that depend on them. Raise the ceiling to 8 so more of a page's hosts
          # resolve concurrently — closer to Chrome's larger resolver pool. Race-to-idle keeps it
          # battery-neutral (the lookups finish sooner) and the cache means repeat visits rarely
          # hit the resolver at all.
          network.dns.max_high_priority_threads: 8
          # Battery/data: turn off "Race Cache With Network". When RCWN is on, Gecko may fire a
          # network request in parallel with a disk-cache read and take whichever wins — a hedge
          # that pays off on slow spinning disks but rarely on phone flash, where the cache read
          # almost always wins anyway. With the enlarged disk cache above, that parallel fetch is
          # mostly wasted radio + data, so serve cache hits straight from cache instead.
          network.http.rcwn.enabled: false
          # Speculative loading: allow DNS prefetch to fire from HTTPS pages and honor
          # <link rel=prefetch> hints so likely next-hops are warmed before the tap.
          network.dns.disablePrefetchFromHTTPS: false
          network.prefetch-next: true
          # --- Chrome-parity load prioritization: Early Hints (HTTP 103) + Priority Hints ---
          # Two web-platform features Chrome ships that let a page start warming its critical
          # resources before the main document response is even complete:
          #  * Early Hints: when a server sends a `103 Early Hints` interim response, honor the
          #    `Link: <…>; rel=preload/preconnect` headers in it and begin preloading/preconnecting
          #    those subresources immediately — so the CSS/JS/hero image is already in flight while
          #    the origin is still generating the real 200. Materially faster first paint on sites
          #    that emit 103 (Cloudflare, Shopify, many CDNs), no effect on sites that don't.
          #  * Priority Hints (`fetchpriority` attribute): honor `fetchpriority="high"` / `"low"`
          #    on <img>/<link>/<script>/fetch() so the LCP image loads ahead of below-the-fold
          #    assets, matching Chrome's request scheduling. Inert on pages that don't use it.
          # Both are default-on in recent desktop Firefox; set explicitly so this GeckoView build
          # picks them up regardless of its channel defaults. Worst case (already on) = no-op.
          network.early-hints.enabled: true
          network.early-hints.preconnect.enabled: true
          network.fetchpriority.enabled: true
          # Chrome-parity companion: disable HTTP/2 Server Push. Push was the old way for a server
          # to send sub-resources before the client asked, but it routinely re-sends assets the
          # client already has cached (wasted radio + data + battery on mobile) and is hard for
          # servers to get right — so Chrome removed support for it entirely in 2022. Early Hints
          # above is its modern, opt-in replacement (the client decides what to fetch). Firefox
          # still *accepts* pushed streams by default; turn that off so we match Chrome's posture
          # (push off, Early Hints on) and never spend battery receiving unsolicited pushed bytes.
          network.http.http2.allow-push: false
          # --- Scroll smoothness: keep the content thread off the scroll critical path (APZ) ---
          # Chrome's 2023-2026 "halved scroll jank" work (Input Vizard / Input Framer / Input
          # Prediction / Direct2Thread) all reduce to one idea: never let a busy main/content
          # thread block a scroll from being produced on the compositor. GeckoView's equivalent is
          # APZ (Async Pan/Zoom), which already scrolls on the compositor thread — but two of its
          # knobs decide how much it still defers to the content thread, and Gecko's defaults are
          # tuned conservatively. Tighten them:
          #
          #  * apz.content_response_timeout — when a touch lands on an element whose page registered
          #    a *non-passive* touch listener, APZ must wait to hear whether the page will
          #    preventDefault() (i.e. handle the gesture itself) before it dares async-scroll, or it
          #    would scroll a carousel/canvas/custom-scroller that meant to consume the touch. It
          #    waits up to this timeout for the (possibly janky) content thread to answer; until then
          #    the finger drags but the page doesn't move. Gecko's default is 400ms — long enough
          #    that a momentarily-busy page shows a visible hitch at scroll *start*. Passive
          #    listeners (the modern default, and all of scroll-anchored content) don't wait at all,
          #    so this only bites the non-passive case; lowering it to 250ms means a wedged content
          #    thread cedes to the compositor ~1.5x sooner. Cost: if a page's non-passive handler is
          #    legitimately >250ms late to preventDefault, APZ may start scrolling under it — rare on
          #    real sites and the same tradeoff Chrome makes with its aggressive async scrolling.
          apz.content_response_timeout: 250
          #  * apz.paint_skipping.enabled — lets APZ keep advancing the scroll offset purely on the
          #    compositor across frames where the content thread hasn't produced a fresh paint yet,
          #    instead of stalling scroll to stay in lockstep with main-thread painting. This is the
          #    direct analog of the article's "the GPU process applies each frame's scroll offset"
          #    without waiting on the slower thread. It's default-on, but (like the 0-RTT / Early
          #    Hints prefs above) set explicitly so this GeckoView build keeps it regardless of
          #    channel defaults; worst case (already on) = no-op.
          apz.paint_skipping.enabled: true
          # --- Credit-card autofill ---
          # Turn on Gecko's form-autofill detection for credit-card fields so it invokes our
          # autocomplete storage delegate (GeckoCreditCardsAddressesStorageDelegate) to offer/save
          # cards. Logins are gated by the loginAutofillEnabled engine setting; credit cards have no
          # such setting, so the pref lives here. `.supported` lifts the region gate GeckoView
          # otherwise applies. (No CVC: GeckoView's credit-card model has no CVC field, by design —
          # PCI-DSS forbids persisting it — so autofill covers number/name/expiry only.)
          extensions.formautofill.creditCards.enabled: true
          extensions.formautofill.creditCards.supported: "on"
          # --- Address autofill ---
          # Same story for addresses: enable Gecko's address form detection + lift the region gate so
          # our storage delegate is invoked to offer saved addresses. Gated at runtime by the user's
          # addressAutofillEnabled pref (via the delegate's isAddressAutofillEnabled lambda).
          extensions.formautofill.addresses.enabled: true
          extensions.formautofill.addresses.supported: "on"
          dom.forms.autocomplete.formautofill: true
          # --- TEMPORARY: passkeys/WebAuthn disabled ---
          # Tangent isn't on Google Password Manager's trusted-browser allowlist yet, so the platform
          # FIDO2 path rejects our cert and passkey flows fail. Disable the WebAuthn API entirely so
          # sites fall back to passwords instead of offering a broken passkey prompt. This turns OFF
          # the whole API (isUserVerifyingPlatformAuthenticatorAvailable() → false) — it is NOT the
          # `webauthn_enable_softtoken` override that AGENTS.md invariant #12 forbids (that would
          # silently service requests with Gecko's virtual test authenticator). Re-enable by removing
          # this pref once we're on the GPM allowlist.
          security.webauth.webauthn: false
          # --- Security: enforce TLS 1.2 minimum ---
          # Block TLS 1.0/1.1 (known-vulnerable); all modern sites support TLS 1.2+. (3 = TLS 1.2.)
          security.tls.version.min: 3
          # --- Performance: ensure hardware video decoding ---
          # Usually auto-detected on Android but explicit is safer — helps 1080p/4K playback battery.
          media.hardware-video-decoding.enabled: true
          # --- Performance: force GPU WebRender compositing ---
          # Already the Android default; pin it so a driver-quirk fallback can't drop us to software.
          gfx.webrender.all: true
          # --- Privacy: block cryptominers ---
          # Explicit cryptomining-script blocking (complements Enhanced Tracking Protection).
          privacy.trackingprotection.cryptomining.enabled: true
          # --- Privacy: block fingerprinting scripts ---
          # Explicit fingerprinting-script blocking (complements the fingerprintingProtection setting).
          privacy.trackingprotection.fingerprinting.enabled: true
          # --- Privacy: block social-media trackers ---
          # The third sibling to cryptomining/fingerprinting: FB/LinkedIn/X widget trackers.
          privacy.trackingprotection.socialtracking.enabled: true
          # --- Privacy: partition network state by top origin ---
          # Partitions the HTTP cache + connection pool per top-level site, defeating cache-based
          # supercookies and cross-site connection tracking. Default-on in modern Firefox; pin it.
          privacy.partition.network_state: true
          # --- Privacy: stricter referrer policy ---
          # 2 = strict-origin-when-cross-origin style: send only the origin (no path/query) to
          # cross-origin resources, full referer only same-origin. Stops leaking full URLs.
          network.http.referer.defaultPolicy: 2
          # Complement: trim any cross-origin referer that IS sent down to just the origin.
          network.http.referer.XOriginTrimmingPolicy: 2
          # --- Anti-phishing: show IDN hostnames as punycode ---
          # Renders homograph domains (e.g. Cyrillic "а" in "аpple.com") as punycode so they're visible.
          network.IDN_show_punycode: true
          # --- Security: defeat back-button hijacking (history-manipulation intervention) ---
          # A hostile page can trap the user by stuffing the session history with entries it inserts
          # itself — history.pushState()/replaceState() in a loop, or a popstate handler that re-pushes
          # on every Back — so Back walks a wall of same-page fake entries and never escapes to the
          # real previous site. This is Gecko's built-in fix (the analog of Chrome's "history
          # manipulation intervention"): a session-history entry created *without* user interaction is
          # marked skippable, and Back/Forward jumps over the whole run of skippable entries in one
          # press to land on the last entry the user actually reached. Entries created by a genuine
          # click/tap keep a normal Back stop, so legitimate SPA in-app navigation is unaffected —
          # only gestureless history spam is skipped. Default is off in this channel; pin it on. The
          # skip happens inside Gecko's own goBack traversal, so our sessionUseCases.goBack /
          # swipe-back commit both land on the real target regardless of what the preview thumbnail
          # (which reads HistoryState[currentIndex-1]) shows mid-gesture.
          browser.navigation.requireUserInteraction: true
          # --- Security: no JavaScript inside PDFs (pdf.js) ---
          # Closes a real malware vector; virtually no legitimate PDF needs scripting.
          pdfjs.enableScripting: false
          # --- Privacy: don't send downloaded-binary hashes to Google's remote reputation service ---
          # Local Safe Browsing (safeBrowsingV5Enabled) still protects downloads.
          browser.safebrowsing.downloads.remote.enabled: false
          # --- Data/battery: advertise AVIF support ---
          # Sites then serve smaller AVIF images = less data + less decode work on mobile.
          image.avif.enabled: true
          # --- Security: auto-upgrade passive mixed content to HTTPS ---
          # http images/media on an https page get upgraded instead of left insecure. Pairs with
          # HTTPS-Only mode; mostly default-on now, pinned for consistency.
          security.mixed_content.upgrade_display_content: true
          # === User-facing toggles (Advanced settings) — apply on next launch ===
          # Media autoplay policy ("Block all autoplay"): 5 = block ALL incl. muted video, 1 = block
          # only audible; also block background-tab autoplay until foregrounded when strict. Battery.
          media.autoplay.default: $autoplayDefault
          media.block-autoplay-until-in-foreground: $blockAutoplayBg
          # Web notifications + Push API ("Web notifications"): off by default for battery.
          dom.webnotifications.enabled: $webNotifications
          dom.push.enabled: $webNotifications
          dom.push.connection.enabled: $webNotifications
          # Service Workers ("Service workers"): on by default (PWAs / modern web apps).
          dom.serviceWorkers.enabled: $serviceWorkers
          # WebRTC local-IP hiding ("Hide local IP in WebRTC"): on by default for privacy; off if a
          # call needs local-network access.
          media.peerconnection.ice.no_host: $webRtcHideIp
          media.peerconnection.ice.default_address_only: $webRtcHideIp
          # navigator.sendBeacon ("Allow analytics beacons"): off blocks fire-on-unload analytics.
          beacon.enabled: $sendBeacon
        """.trimIndent() + "\n",
    )
    file.absolutePath
} catch (_: Exception) {
    null
}

/**
 * Choose the base live-background-session budget for [BackgroundTabSuspender] from the device's RAM
 * class. Each extra live GeckoSession is a full content process; the number a device sustains before
 * tab switching stutters scales with physical RAM. A low-RAM device (per [ActivityManager.isLowRamDevice],
 * which also implies Android's low-memory profile) is held to a single background session; otherwise
 * we step the budget by total RAM. Foreground tab is always kept on top of this, so total live
 * sessions are budget + 1.
 */
private fun keepAliveBudgetForDevice(application: Application): Int {
    val am = application.getSystemService(android.content.Context.ACTIVITY_SERVICE)
        as android.app.ActivityManager
    if (am.isLowRamDevice) return 1
    val totalMb = try {
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem / (1024L * 1024L)
    } catch (_: Exception) {
        return com.firebolt9907.tangentbrowser.state.BackgroundTabSuspender.DEFAULT_KEEP_ALIVE_BACKGROUND
    }
    return when {
        totalMb >= 7_000 -> 5   // ~8 GB+ flagship
        totalMb >= 5_000 -> 4   // ~6 GB
        totalMb >= 3_500 -> 3   // ~4 GB
        totalMb >= 2_500 -> 2   // ~3 GB
        else -> 1               // ~2 GB budget device
    }
}

private fun buildDefaultSettings(
    application: Application,
    prefs: GlobalPreferencesStore,
    historyStorage: PlacesHistoryStorage,
    onHttpsOnlyError: (String) -> Unit,
) = DefaultSettings(
    // Record archived (web.archive.org) visits under the original page URL, so history stays clean
    // and consistent with the de-archived URL shown everywhere else. No-op for non-snapshot URLs.
    historyTrackingDelegate = com.firebolt9907.tangentbrowser.ui.archive.DeArchivingHistoryDelegate(
        HistoryDelegate(lazy { historyStorage }),
    ),
    trackingProtectionPolicy = trackingPolicyOf(prefs),
    javaScriptCanOpenWindowsAutomatically = !prefs.blockPopUps,
    javascriptEnabled = prefs.javaScriptEnabled,
    httpsOnlyMode = httpsModeOf(prefs),
    globalPrivacyControlEnabled = prefs.globalPrivacyControl,
    // Silent page-load autofill stays off — see the runtime apply-prefs block above. Fills always
    // go through the biometric-gated SelectLoginPrompt instead.
    loginAutofillEnabled = false,
    // Branded, localized error pages (assets/error_page_js.html) for failed navigations, replacing
    // GeckoView's raw default page. See TangentRequestInterceptor.
    requestInterceptor = com.firebolt9907.tangentbrowser.engine.TangentRequestInterceptor(
        application,
        onHttpsOnlyError = onHttpsOnlyError,
    ),
)

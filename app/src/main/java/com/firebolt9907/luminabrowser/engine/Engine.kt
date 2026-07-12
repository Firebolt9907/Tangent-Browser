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
import mozilla.components.feature.customtabs.store.CustomTabsServiceStore
import mozilla.components.feature.tabs.CustomTabsUseCases
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
        buildRuntime(application, globalPreferencesStore),
    )
    val store = BrowserStore(
        initialState = BrowserState(),
        middleware = EngineMiddleware.create(engine, scope),
    )
    val sessionUseCases = SessionUseCases(store)
    val tabsUseCases = TabsUseCases(store)
    // Owns add/remove/migrate for custom-tab sessions (external-link "Custom Tab" surface hosted by
    // CustomTabActivity). Custom tabs live in store.state.customTabs, separate from the normal tab
    // list, so they never touch the grid, persistence, or Firefox Sync.
    val customTabsUseCases = CustomTabsUseCases(store, sessionUseCases.loadUrl)
    // Backs LuminaCustomTabsService — the CustomTabsService we advertise so callers using the
    // Custom Tabs protocol (e.g. the Google app / Discover) recognize Lumina as a CCT provider and
    // launch us through CustomTabsIntent (session token + enter-animation options bundle + first-
    // class custom-tab treatment) instead of a plain ACTION_VIEW that carries no animation.
    val customTabsServiceStore = CustomTabsServiceStore()
    val downloadsUseCases = DownloadsUseCases(store, application)
    val tabPersistence = com.firebolt9907.luminabrowser.state.TabPersistence(application)
    val tabRepository =
        GeckoTabRepository(
            store,
            sessionUseCases,
            tabsUseCases,
            tabPersistence,
            scope,
            searchEngine = { globalPreferencesStore.searchEngine },
        )
    // Persist tab thumbnails in filesDir, not cacheDir: cacheDir is disposable storage that Android
    // evicts under storage pressure (common on space-constrained devices), which wiped restored
    // tabs' thumbnails between sessions. filesDir survives until we prune it ourselves (the cache
    // still self-bounds via maxEntries), so thumbnails persist across app restarts / force-stops.
    val tabSnapshotCache = TabSnapshotCache(application.filesDir)
    val sitePreferencesStore = SitePreferencesStore(application)
    val addonController = AddonController(engine, scope)
    val loginsStorage = PlacesLoginsStorage(application)
    val firefoxSyncController = FirefoxSyncController(
        application,
        store,
        scope,
        loginsStorage,
        // A tab pushed here via Firefox "Send tab" opens as a new selected tab (goes through the
        // single normalizeUrl entry point in GeckoTabRepository).
        onSendTabReceived = { url, title -> tabRepository.addTab(url = url, title = title) },
    )
    val sitePermissionController =
        com.firebolt9907.luminabrowser.permissions.SitePermissionController(application)
    // Bounds live GeckoSessions to the foreground tab + a few recent ones so many open tabs stay
    // responsive. See BackgroundTabSuspender.
    val backgroundTabSuspender =
        com.firebolt9907.luminabrowser.state.BackgroundTabSuspender(store)
    // Warms connections to frecent origins so revisiting a common site (google.com etc.) lands on an
    // already-open TLS connection with its assets already in Gecko's enlarged HTTP disk cache.
    val speculativePreconnector =
        com.firebolt9907.luminabrowser.state.SpeculativePreconnector(engine, scope)
    // Preloads Custom Tabs `mayLaunchUrl` targets (e.g. Google Discover article links) into the
    // shared HTTP cache so the tap-through open is served from cache. See LuminaCustomTabsService.
    val externalLinkPreloader =
        com.firebolt9907.luminabrowser.state.ExternalLinkPreloader(engine, scope)

    fun applyEngineSettings() {
        val prefs = globalPreferencesStore
        runCatching { engine.settings.trackingProtectionPolicy = trackingPolicyOf(prefs) }
        runCatching { engine.settings.javaScriptCanOpenWindowsAutomatically = !prefs.blockPopUps }
        runCatching { engine.settings.javascriptEnabled = prefs.javaScriptEnabled }
        runCatching { engine.settings.httpsOnlyMode = httpsModeOf(prefs) }
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

        // Login Autofill
        runCatching { engine.settings.loginAutofillEnabled = prefs.loginAutofillEnabled }

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

        // Allow File Access from File URLs (File-to-File cross-access)
        runCatching {
            engine.settings.allowFileAccessFromFileURLs = prefs.allowFileAccessFromFileURLs
            engine.settings.allowUniversalAccessFromFileURLs = prefs.allowFileAccessFromFileURLs
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

    private val storeSubscription = store.observeManually { state ->
        _browserState.value = state
    }

    init {
        applyEngineSettings()
        storeSubscription.resume()
        backgroundTabSuspender.start()
        tabRepository.restoreOrSeedBlank(TAB_MAX_AGE_MILLIS)
        // Get a head start on the first navigation of the session: spin up a content process now so
        // the first page load doesn't pay process-spawn latency, and warm the user's top sites while
        // they're still looking at the restored tab / new-tab bar.
        engine.speculativeCreateSession()
        speculativePreconnector.warmTopSites(historyStorage)
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
 * - `configFilePath` points at a generated YAML that enlarges the necko HTTP disk cache (see
 *   [writeGeckoConfig]) so frequently-visited sites' assets survive in cache far longer. Setting the
 *   path explicitly is what lets a non-debuggable (release) build read the config at all — otherwise
 *   Gecko only reads it for debuggable apps.
 */
private fun buildRuntime(application: Application, prefs: GlobalPreferencesStore): GeckoRuntime {
    val settings = GeckoRuntimeSettings.Builder()
        .lowMemoryDetection(true)
        .consoleOutput(false)
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
    // writeGeckoConfig() — a TEMPORARY measure until Lumina is on the GPM trusted-browser allowlist.
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
 *   [com.firebolt9907.luminabrowser.state.SpeculativePreconnector]).
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
    val file = java.io.File(application.filesDir, "geckoview-config.yaml")
    file.writeText(
        """
        prefs:
          browser.cache.disk.parent_directory: "$cacheParent"
          browser.cache.disk.smart_size.enabled: false
          browser.cache.disk.capacity: $capacityKb
          network.http.speculative-parallel-limit: 12
          network.predictor.enabled: true
          network.predictor.enable-prefetch: true
          network.http.http3.enable: true
          network.http.max-persistent-connections-per-server: 10
          network.dns.disablePrefetch: false
          # --- Smoothness: paint the first frame immediately (Betterfox "Fastfox") ---
          # Gecko delays the initial paint by 5ms (250ms for out-of-process iframes) to
          # batch a page's earliest reflows into the first paint. On a phone that delay just
          # reads as a beat of blank before content appears; zeroing both makes every
          # navigation feel snappier without changing what eventually gets painted.
          nglayout.initialpaint.delay: 0
          nglayout.initialpaint.delay_in_oopif: 0
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
          # Media cache: larger in-memory media buffer + more aggressive readahead so audio/
          # video streams buffer further ahead and re-seek without re-fetching.
          media.memory_cache_max_size: 65536
          media.cache_readahead_limit: 7200
          media.cache_resume_threshold: 3600
          # Image cache: decode images in larger chunks so large images appear sooner.
          image.mem.decode_bytes_at_a_time: 32768
          # Network: raise connection ceilings and stop artificially pacing request bursts so
          # asset-heavy pages open all their sockets up front instead of trickling requests.
          network.http.max-connections: 1800
          network.http.max-urgent-start-excessive-connections-per-host: 5
          network.http.pacing.requests.enabled: false
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
          # --- TEMPORARY: passkeys/WebAuthn disabled ---
          # Lumina isn't on Google Password Manager's trusted-browser allowlist yet, so the platform
          # FIDO2 path rejects our cert and passkey flows fail. Disable the WebAuthn API entirely so
          # sites fall back to passwords instead of offering a broken passkey prompt. This turns OFF
          # the whole API (isUserVerifyingPlatformAuthenticatorAvailable() → false) — it is NOT the
          # `webauthn_enable_softtoken` override that AGENTS.md invariant #12 forbids (that would
          # silently service requests with Gecko's virtual test authenticator). Re-enable by removing
          # this pref once we're on the GPM allowlist.
          security.webauth.webauthn: false
        """.trimIndent() + "\n",
    )
    file.absolutePath
} catch (_: Exception) {
    null
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
    loginAutofillEnabled = prefs.loginAutofillEnabled,
)

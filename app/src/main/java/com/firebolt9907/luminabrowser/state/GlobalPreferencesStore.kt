package com.firebolt9907.luminabrowser.state

import android.content.Context
import androidx.core.content.edit

class GlobalPreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("lumina_global_prefs", Context.MODE_PRIVATE)

    // null = not set, "" = Default browser, "com.foo" = specific app
    var preferredBrowserPackage: String?
        get() = prefs.getString("preferredBrowserPackage", null)
        set(value) {
            prefs.edit {
                if (value == null) remove("preferredBrowserPackage")
                else putString("preferredBrowserPackage", value)
            }
        }

    // "" = Default browser, "com.foo" = specific app
    var recentBrowserPackages: List<String>
        get() {
            val raw = prefs.getString("recentBrowserPackages", null) ?: return emptyList()
            if (raw.isEmpty()) return emptyList()
            return raw.split("|")
        }
        private set(value) {
            prefs.edit { putString("recentBrowserPackages", value.joinToString("|")) }
        }

    fun recordRecentBrowser(pkg: String) {
        val current = recentBrowserPackages.toMutableList()
        current.remove(pkg)
        current.add(0, pkg)
        recentBrowserPackages = current.take(5)
    }

    var trackingProtection: Boolean
        get() = prefs.getBoolean("trackingProtection", true)
        set(value) { prefs.edit { putBoolean("trackingProtection", value) } }

    var blockPopUps: Boolean
        get() = prefs.getBoolean("blockPopUps", true)
        set(value) { prefs.edit { putBoolean("blockPopUps", value) } }

    var javaScriptEnabled: Boolean
        get() = prefs.getBoolean("javaScriptEnabled", true)
        set(value) { prefs.edit { putBoolean("javaScriptEnabled", value) } }

    var httpsOnly: Boolean
        get() = prefs.getBoolean("httpsOnly", false)
        set(value) { prefs.edit { putBoolean("httpsOnly", value) } }

    // Hosts the user has explicitly approved to load over plain HTTP despite HTTPS-Only mode being
    // on (e.g. a self-hosted `immich.lab` that has no TLS). GeckoView exposes only a *global*
    // HTTPS-Only switch, so HttpsOnlyExceptionController turns the mode off only while the selected
    // tab is on one of these hosts; everything else still gets upgraded. Stored lowercased.
    var httpsOnlyAllowedHosts: Set<String>
        // Defensive copy: SharedPreferences forbids mutating the returned Set instance.
        get() = prefs.getStringSet("httpsOnlyAllowedHosts", emptySet())?.toSet().orEmpty()
        set(value) { prefs.edit { putStringSet("httpsOnlyAllowedHosts", value) } }

    fun allowHttpForHost(host: String) {
        val h = host.trim().lowercase()
        if (h.isEmpty()) return
        httpsOnlyAllowedHosts = httpsOnlyAllowedHosts + h
    }

    fun isHttpAllowedForHost(host: String): Boolean =
        host.trim().lowercase() in httpsOnlyAllowedHosts

    var globalPrivacyControl: Boolean
        get() = prefs.getBoolean("globalPrivacyControl", false)
        set(value) { prefs.edit { putBoolean("globalPrivacyControl", value) } }

    // When on, the app runs a foreground service while backgrounded so Android's low-memory killer
    // is less likely to reap the browser process (and its open tabs). Off by default because it
    // shows a persistent notification and costs some battery — it's a deliberate opt-in.
    var keepAliveInBackground: Boolean
        get() = prefs.getBoolean("keepAliveInBackground", false)
        set(value) { prefs.edit { putBoolean("keepAliveInBackground", value) } }

    var showBottomToolbar: Boolean
        get() = prefs.getBoolean("showBottomToolbar", false)
        set(value) { prefs.edit { putBoolean("showBottomToolbar", value) } }

    // When on, the browser chrome recolors to match the current site's declared theme color
    // (`<meta name="theme-color">`, the same tag Safari samples): the bottom bar pill takes the
    // color (with auto-contrasted content) and the Material `primary` accent is derived from it.
    // Sampled by ThemeColorProbe. Off by default; toggled in Settings → Appearance.
    var matchSiteThemeColor: Boolean
        get() = prefs.getBoolean("matchSiteThemeColor", false)
        set(value) { prefs.edit { putBoolean("matchSiteThemeColor", value) } }

    // When on, web pages are tinted toward the device's system accent color (Material You), an
    // Arc-boost-style recolor applied via a mix-blend-mode overlay injected by SiteTint.kt. Off by
    // default; toggled in Settings → Appearance.
    var tintPagesToSystemColor: Boolean
        get() = prefs.getBoolean("tintPagesToSystemColor", false)
        set(value) { prefs.edit { putBoolean("tintPagesToSystemColor", value) } }

    // "Dark mode for every website" — a high-performance two-tier dark mode (see
    // ui/content/WebDarkMode.kt). Tier 1 flips the native `prefers-color-scheme: dark` media query
    // for all content (engine.settings.preferredColorScheme, applied in Engine.applyEngineSettings)
    // so sites with a real dark theme render it for free; Tier 2 injects a GPU-composited
    // filter-invert only on pages that stay light. Off by default; toggled in the aA sheet /
    // Settings → Appearance. Far cheaper than a DarkReader-style per-element dynamic recolor.
    // Trigger for the global "dark mode for every website" feature: Off, On (always), or Auto (follow
    // the device's system dark-mode setting). Migrates the legacy boolean pref on first read. Resolved
    // to a concrete on/off where the system theme is known (the UI layer) via
    // WebDarkModeTrigger.resolve(systemDark). See ui/content/WebDarkMode.kt.
    var webDarkModeTrigger: WebDarkModeTrigger
        get() {
            val raw = prefs.getString("webDarkModeTrigger", null)
            if (raw != null) return WebDarkModeTrigger.fromName(raw)
            // Legacy migration: the old boolean pref maps On/Off.
            return if (prefs.getBoolean("webDarkModeEnabled", false)) {
                WebDarkModeTrigger.On
            } else {
                WebDarkModeTrigger.Off
            }
        }
        set(value) { prefs.edit { putString("webDarkModeTrigger", value.name) } }

    // Opt-in "aggressive" sub-mode for web dark mode (only meaningful when webDarkModeEnabled). When
    // on, the Tier-2 fallback also detects class-based (non-inline) CSS background images at runtime
    // and re-inverts them, closing the one quality gap the cheap filter tier leaves (hero/banner
    // photos rendering as negatives). Costs a bounded getComputedStyle scan + incremental observer,
    // so it's OFF by default — the default path stays the pure high-performance filter. See
    // ui/content/WebDarkMode.kt.
    var webDarkModeAggressive: Boolean
        get() = prefs.getBoolean("webDarkModeAggressive", false)
        set(value) { prefs.edit { putBoolean("webDarkModeAggressive", value) } }

    // When off, the "Continue from <device>" handoff banner (a tab recently active on another
    // Firefox Sync device) is never surfaced. Read by FirefoxSyncController when picking a handoff
    // candidate. On by default. Does not affect received "Send tab" pushes or synced-tab lists.
    var handoffEnabled: Boolean
        get() = prefs.getBoolean("handoffEnabled", true)
        set(value) { prefs.edit { putBoolean("handoffEnabled", value) } }

    // When on, a website's request to open a native app (an `intent://` or custom-scheme deep link,
    // surfaced by LuminaRequestInterceptor.onLoadRequest → onLaunchIntentRequest) launches the app
    // immediately instead of surfacing the "Open in <app>" banner. Off by default so app hand-offs
    // stay user-initiated. Only affects site-*requested* intents — never the passive "this site has
    // an app" suggestion banner, which is always a tap, never automatic.
    var autoOpenAppLinks: Boolean
        get() = prefs.getBoolean("autoOpenAppLinks", false)
        set(value) { prefs.edit { putBoolean("autoOpenAppLinks", value) } }

    var onboardingComplete: Boolean
        get() = prefs.getBoolean("onboardingComplete", false)
        set(value) { prefs.edit { putBoolean("onboardingComplete", value) } }

    // How the bottom bar arranges itself on wide screens (tablets / landscape). Stored by name
    // (WideBarLayout enum) as a raw String so the state layer stays independent of the ui.chrome
    // package; the UI parses it via WideBarLayout.fromName. null/unknown falls back to SPLIT.
    var wideBarLayoutName: String?
        get() = prefs.getString("wideBarLayout", null)
        set(value) { prefs.edit { putString("wideBarLayout", value) } }

    // Which "all tabs" overview to render (TabViewMode enum, stored by name). Kept as a raw String
    // here so the state layer doesn't depend on the ui.grid package; the UI parses it via
    // TabViewMode.fromName. null/unknown falls back to the caller's default (DECK).
    var tabViewModeName: String?
        get() = prefs.getString("tabViewMode", null)
        set(value) { prefs.edit { putString("tabViewMode", value) } }

    // Last parsed Zen workspaces snapshot (spaces + their synced tabs), serialized by
    // ZenWorkspacesStorage. Cached so synced spaces render instantly on cold start, before any
    // Firefox Sync round-trip completes. null = nothing synced yet.
    var zenWorkspacesJson: String?
        get() = prefs.getString("zenWorkspacesJson", null)
        set(value) {
            prefs.edit {
                if (value == null) remove("zenWorkspacesJson") else putString("zenWorkspacesJson", value)
            }
        }

    // Reader-view appearance, persisted so the user's choices survive app restarts. Theme is stored
    // by enum name; an unknown/missing value falls back to the caller's default.
    var readerThemeName: String?
        get() = prefs.getString("readerThemeName", null)
        set(value) { prefs.edit { putString("readerThemeName", value) } }

    var readerFontSize: Int
        get() = prefs.getInt("readerFontSize", 100)
        set(value) { prefs.edit { putInt("readerFontSize", value) } }

    var readerFontName: String
        get() = prefs.getString("readerFontName", "System") ?: "System"
        set(value) { prefs.edit { putString("readerFontName", value) } }

    // Per-site reader overrides (assets/reader_rules.json): include/strip selectors that force the
    // article container / drop out-of-article regions on specific hosts. Purely local DOM shaping,
    // but exposed as a toggle so a privacy-conscious user can fall back to the pure heuristic — and
    // so a future remote rules channel (which would reveal visited hosts) can be opted out of.
    var readerSiteRules: Boolean
        get() = prefs.getBoolean("readerSiteRules", true)
        set(value) { prefs.edit { putBoolean("readerSiteRules", value) } }

    // Epoch-ms of the last time the remote reader-rules file was checked, used to throttle the
    // background refresh (see ReaderRules). 0 = never checked.
    var readerRulesLastCheckMs: Long
        get() = prefs.getLong("readerRulesLastCheckMs", 0L)
        set(value) { prefs.edit { putLong("readerRulesLastCheckMs", value) } }

    // Search engine used both for address-bar searches (via normalizeUrl) and for the live query
    // suggestions in the address sheet. Stored by enum name; unknown/missing falls back to default.
    var searchEngine: SearchEngine
        get() = SearchEngine.fromName(prefs.getString("searchEngine", null))
        set(value) { prefs.edit { putString("searchEngine", value.name) } }

    // When on, a search-results page for the current [searchEngine] shows the typed query in the
    // address bar instead of the engine's host (e.g. "cat videos" rather than "google.com").
    var showSearchTermsInBar: Boolean
        get() = prefs.getBoolean("showSearchTermsInBar", true)
        set(value) { prefs.edit { putBoolean("showSearchTermsInBar", value) } }

    // When off, the address sheet shows only local (history/bookmark) suggestions and never sends
    // keystrokes to the search engine's suggestion endpoint.
    var searchSuggestionsEnabled: Boolean
        get() = prefs.getBoolean("searchSuggestionsEnabled", true)
        set(value) { prefs.edit { putBoolean("searchSuggestionsEnabled", value) } }

    // How aggressively to preload pages the user is likely to open next (external Discover/Search
    // links and the address-bar top hit). Stored by enum name; defaults to unmetered-only.
    var preloadMode: PreloadMode
        get() = PreloadMode.fromName(prefs.getString("preloadMode", null))
        set(value) { prefs.edit { putString("preloadMode", value.name) } }

    // On-disk HTTP asset cache cap, in MB. Read once at startup when the Gecko runtime is built (see
    // engine/Engine.kt), so a change takes effect on the next launch. Coerced to a sane range.
    var httpCacheSizeMb: Int
        get() = prefs.getInt("httpCacheSizeMb", DEFAULT_HTTP_CACHE_MB).coerceIn(MIN_HTTP_CACHE_MB, MAX_HTTP_CACHE_MB)
        set(value) {
            prefs.edit { putInt("httpCacheSizeMb", value.coerceIn(MIN_HTTP_CACHE_MB, MAX_HTTP_CACHE_MB)) }
        }

    // 0 = Disabled, 1 = Reject all, 2 = Reject or accept all
    var cookieBannerHandlingMode: Int
        get() = prefs.getInt("cookieBannerHandlingMode", 0)
        set(value) { prefs.edit { putInt("cookieBannerHandlingMode", value) } }

    var queryParameterStripping: Boolean
        get() = prefs.getBoolean("queryParameterStripping", true)
        set(value) { prefs.edit { putBoolean("queryParameterStripping", value) } }

    var mediaPlaybackRequiresUserGesture: Boolean
        get() = prefs.getBoolean("mediaPlaybackRequiresUserGesture", true)
        set(value) { prefs.edit { putBoolean("mediaPlaybackRequiresUserGesture", value) } }

    var cookieBehaviorOptInPartitioning: Boolean
        get() = prefs.getBoolean("cookieBehaviorOptInPartitioning", true)
        set(value) { prefs.edit { putBoolean("cookieBehaviorOptInPartitioning", value) } }

    var fingerprintingProtection: Boolean
        get() = prefs.getBoolean("fingerprintingProtection", false)
        set(value) { prefs.edit { putBoolean("fingerprintingProtection", value) } }

    // 0 = Off, 1 = Default, 2 = Increased, 3 = Max
    var dohSettingsMode: Int
        get() = prefs.getInt("dohSettingsMode", 0)
        set(value) { prefs.edit { putInt("dohSettingsMode", value) } }

    var enterpriseRootsEnabled: Boolean
        get() = prefs.getBoolean("enterpriseRootsEnabled", false)
        set(value) { prefs.edit { putBoolean("enterpriseRootsEnabled", value) } }

    var forceUserScalableContent: Boolean
        get() = prefs.getBoolean("forceUserScalableContent", false)
        set(value) { prefs.edit { putBoolean("forceUserScalableContent", value) } }

    var fontSizeFactor: Float
        get() = prefs.getFloat("fontSizeFactor", 1.0f)
        set(value) { prefs.edit { putFloat("fontSizeFactor", value) } }

    var loginAutofillEnabled: Boolean
        get() = prefs.getBoolean("loginAutofillEnabled", true)
        set(value) { prefs.edit { putBoolean("loginAutofillEnabled", value) } }

    // When true, filling a saved login requires biometric/device-credential confirmation. Default
    // on (secure). Flipping this off in Settings is itself gated behind BiometricGate so an attacker
    // with an unlocked-but-glanced-at phone can't quietly disable the protection.
    var biometricAutofillEnabled: Boolean
        get() = prefs.getBoolean("biometricAutofillEnabled", true)
        set(value) { prefs.edit { putBoolean("biometricAutofillEnabled", value) } }

    var creditCardAutofillEnabled: Boolean
        get() = prefs.getBoolean("creditCardAutofillEnabled", true)
        set(value) { prefs.edit { putBoolean("creditCardAutofillEnabled", value) } }

    var addressAutofillEnabled: Boolean
        get() = prefs.getBoolean("addressAutofillEnabled", true)
        set(value) { prefs.edit { putBoolean("addressAutofillEnabled", value) } }

    // When true, arbitrary non-sensitive form fields (name, email, phone, and other freeform text
    // inputs outside logins/cards/addresses) are remembered on submit and offered back on focus.
    var formFieldMemoryEnabled: Boolean
        get() = prefs.getBoolean("formFieldMemoryEnabled", true)
        set(value) { prefs.edit { putBoolean("formFieldMemoryEnabled", value) } }

    // When true, "Listen to Page" (read-aloud TTS) is always offered in the aA sheet for any
    // readable page. When false (default) it only appears while Reader mode is active — so the
    // control isn't surfaced to users who never asked for read-aloud. See AaSheet.
    var listenToPageEnabled: Boolean
        get() = prefs.getBoolean("listenToPageEnabled", false)
        set(value) { prefs.edit { putBoolean("listenToPageEnabled", value) } }

    var webFontsEnabled: Boolean
        get() = prefs.getBoolean("webFontsEnabled", true)
        set(value) { prefs.edit { putBoolean("webFontsEnabled", value) } }

    var domStorageEnabled: Boolean
        get() = prefs.getBoolean("domStorageEnabled", true)
        set(value) { prefs.edit { putBoolean("domStorageEnabled", value) } }

    var suspendMediaWhenInactive: Boolean
        get() = prefs.getBoolean("suspendMediaWhenInactive", true)
        set(value) { prefs.edit { putBoolean("suspendMediaWhenInactive", value) } }

    /**
     * How long (in hours) a tab may go unviewed before it is auto-discarded on the next launch.
     * `0` disables auto-archiving entirely (tabs are kept forever). Default 72h — see
     * [com.firebolt9907.luminabrowser.engine.LuminaBrowserComponents.TAB_MAX_AGE_MILLIS].
     */
    var tabAutoArchiveHours: Int
        get() = prefs.getInt("tabAutoArchiveHours", 72)
        set(value) { prefs.edit { putInt("tabAutoArchiveHours", value.coerceAtLeast(0)) } }

    var scrollBarsEnabled: Boolean
        get() = prefs.getBoolean("scrollBarsEnabled", true)
        set(value) { prefs.edit { putBoolean("scrollBarsEnabled", value) } }

    var allowFileAccess: Boolean
        get() = prefs.getBoolean("allowFileAccess", false)
        set(value) { prefs.edit { putBoolean("allowFileAccess", value) } }

    var postQuantumKeyExchangeEnabled: Boolean
        get() = prefs.getBoolean("postQuantumKeyExchangeEnabled", true)
        set(value) { prefs.edit { putBoolean("postQuantumKeyExchangeEnabled", value) } }

    var safeBrowsingV5Enabled: Boolean
        get() = prefs.getBoolean("safeBrowsingV5Enabled", true)
        set(value) { prefs.edit { putBoolean("safeBrowsingV5Enabled", value) } }

    var automaticFontSizeAdjustment: Boolean
        get() = prefs.getBoolean("automaticFontSizeAdjustment", true)
        set(value) { prefs.edit { putBoolean("automaticFontSizeAdjustment", value) } }

    var automaticLanguageAdjustment: Boolean
        get() = prefs.getBoolean("automaticLanguageAdjustment", true)
        set(value) { prefs.edit { putBoolean("automaticLanguageAdjustment", value) } }

    var cookieBannerHandlingGlobalRulesSubFrames: Boolean
        get() = prefs.getBoolean("cookieBannerHandlingGlobalRulesSubFrames", true)
        set(value) { prefs.edit { putBoolean("cookieBannerHandlingGlobalRulesSubFrames", value) } }

    var emailTrackerBlockingPrivateBrowsing: Boolean
        get() = prefs.getBoolean("emailTrackerBlockingPrivateBrowsing", true)
        set(value) { prefs.edit { putBoolean("emailTrackerBlockingPrivateBrowsing", value) } }

    var dohProviderUrl: String
        get() = prefs.getString("dohProviderUrl", "") ?: ""
        set(value) { prefs.edit { putString("dohProviderUrl", value) } }

    var lnaBlockingEnabled: Boolean
        get() = prefs.getBoolean("lnaBlockingEnabled", true)
        set(value) { prefs.edit { putBoolean("lnaBlockingEnabled", value) } }

    var allowFileAccessFromFileURLs: Boolean
        get() = prefs.getBoolean("allowFileAccessFromFileURLs", false)
        set(value) { prefs.edit { putBoolean("allowFileAccessFromFileURLs", value) } }

    var bannedPorts: String
        get() = prefs.getString("bannedPorts", "") ?: ""
        set(value) { prefs.edit { putString("bannedPorts", value) } }

    var fdlibmMathEnabled: Boolean
        get() = prefs.getBoolean("fdlibmMathEnabled", false)
        set(value) { prefs.edit { putBoolean("fdlibmMathEnabled", value) } }

    var queryParameterStrippingStripList: String
        get() = prefs.getString("queryParameterStrippingStripList", "") ?: ""
        set(value) { prefs.edit { putString("queryParameterStrippingStripList", value) } }

    var queryParameterStrippingAllowList: String
        get() = prefs.getString("queryParameterStrippingAllowList", "") ?: ""
        set(value) { prefs.edit { putString("queryParameterStrippingAllowList", value) } }

    var crliteChannel: String
        get() = prefs.getString("crliteChannel", "") ?: ""
        set(value) { prefs.edit { putString("crliteChannel", value) } }

    companion object {
        const val DEFAULT_HTTP_CACHE_MB = 512
        const val MIN_HTTP_CACHE_MB = 64
        const val MAX_HTTP_CACHE_MB = 2048
    }
}

/**
 * How the global web dark mode is triggered. [Auto] follows the device's system dark-mode setting;
 * [On]/[Off] force it. Resolved to a concrete boolean by [resolve] where the current system theme is
 * known (the Compose UI layer). Note Tier 1 (Gecko `preferredColorScheme`) handles [Auto] for free by
 * staying on `System` — only Tier 2 (the injected filter) needs this resolved boolean.
 */
enum class WebDarkModeTrigger(val label: String) {
    Off("Off"),
    On("On"),
    Auto("Match system");

    fun resolve(systemDark: Boolean): Boolean = when (this) {
        Off -> false
        On -> true
        Auto -> systemDark
    }

    companion object {
        fun fromName(name: String?): WebDarkModeTrigger =
            entries.firstOrNull { it.name == name } ?: Off
    }
}

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

    var useGoogleCache: Boolean
        get() = prefs.getBoolean("useGoogleCache", false)
        set(value) { prefs.edit { putBoolean("useGoogleCache", value) } }

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

    // Hidden debug toggle: when on, typing "debug:cache" in the address bar opens the
    // Google Cache page's parsed HTML in a new tab so the user can inspect what GeckoView
    // actually received (useful for diagnosing paywall-bypass consistency issues).
    var debugCacheHtml: Boolean
        get() = prefs.getBoolean("debugCacheHtml", false)
        set(value) { prefs.edit { putBoolean("debugCacheHtml", value) } }

    // Search engine used both for address-bar searches (via normalizeUrl) and for the live query
    // suggestions in the address sheet. Stored by enum name; unknown/missing falls back to default.
    var searchEngine: SearchEngine
        get() = SearchEngine.fromName(prefs.getString("searchEngine", null))
        set(value) { prefs.edit { putString("searchEngine", value.name) } }

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

    var webFontsEnabled: Boolean
        get() = prefs.getBoolean("webFontsEnabled", true)
        set(value) { prefs.edit { putBoolean("webFontsEnabled", value) } }

    var domStorageEnabled: Boolean
        get() = prefs.getBoolean("domStorageEnabled", true)
        set(value) { prefs.edit { putBoolean("domStorageEnabled", value) } }

    var suspendMediaWhenInactive: Boolean
        get() = prefs.getBoolean("suspendMediaWhenInactive", true)
        set(value) { prefs.edit { putBoolean("suspendMediaWhenInactive", value) } }

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

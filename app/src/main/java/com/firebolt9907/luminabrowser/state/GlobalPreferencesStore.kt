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

    companion object {
        const val DEFAULT_HTTP_CACHE_MB = 512
        const val MIN_HTTP_CACHE_MB = 64
        const val MAX_HTTP_CACHE_MB = 2048
    }
}

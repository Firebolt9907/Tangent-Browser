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
}

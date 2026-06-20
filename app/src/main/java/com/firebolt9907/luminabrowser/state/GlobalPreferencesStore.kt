package com.firebolt9907.luminabrowser.state

import android.content.Context

class GlobalPreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("lumina_global_prefs", Context.MODE_PRIVATE)

    // null = not set, "" = Default browser, "com.foo" = specific app
    var preferredBrowserPackage: String?
        get() = prefs.getString("preferredBrowserPackage", null)
        set(value) {
            if (value == null) prefs.edit().remove("preferredBrowserPackage").apply()
            else prefs.edit().putString("preferredBrowserPackage", value).apply()
        }

    // "" = Default browser, "com.foo" = specific app
    var recentBrowserPackages: List<String>
        get() {
            val raw = prefs.getString("recentBrowserPackages", null) ?: return emptyList()
            if (raw.isEmpty()) return emptyList()
            return raw.split("|")
        }
        private set(value) {
            prefs.edit().putString("recentBrowserPackages", value.joinToString("|")).apply()
        }

    fun recordRecentBrowser(pkg: String) {
        val current = recentBrowserPackages.toMutableList()
        current.remove(pkg)
        current.add(0, pkg)
        recentBrowserPackages = current.take(5)
    }

    var trackingProtection: Boolean
        get() = prefs.getBoolean("trackingProtection", true)
        set(value) { prefs.edit().putBoolean("trackingProtection", value).apply() }

    var blockPopUps: Boolean
        get() = prefs.getBoolean("blockPopUps", true)
        set(value) { prefs.edit().putBoolean("blockPopUps", value).apply() }

    var javaScriptEnabled: Boolean
        get() = prefs.getBoolean("javaScriptEnabled", true)
        set(value) { prefs.edit().putBoolean("javaScriptEnabled", value).apply() }

    var httpsOnly: Boolean
        get() = prefs.getBoolean("httpsOnly", false)
        set(value) { prefs.edit().putBoolean("httpsOnly", value).apply() }

    var globalPrivacyControl: Boolean
        get() = prefs.getBoolean("globalPrivacyControl", false)
        set(value) { prefs.edit().putBoolean("globalPrivacyControl", value).apply() }
}

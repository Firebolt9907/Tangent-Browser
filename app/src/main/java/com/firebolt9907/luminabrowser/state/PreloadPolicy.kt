package com.firebolt9907.luminabrowser.state

import android.content.Context
import android.net.ConnectivityManager

/**
 * When Lumina may spend data *loading* a page the user hasn't opened yet — i.e. the external-link
 * [ExternalLinkPreloader] (Custom Tabs `mayLaunchUrl`) and the address-bar top-hit prefetch.
 *
 * This gates only the heavy full-page preloads. Lightweight `speculativeConnect` warmups are always
 * allowed — they cost a socket, not a download.
 */
enum class PreloadMode(val label: String) {
    Off("Off"),
    WifiOnly("On Wi-Fi only"),
    Always("Always");

    companion object {
        val DEFAULT = WifiOnly

        fun fromName(name: String?): PreloadMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * True if a full-page preload is permitted right now under [mode]. [PreloadMode.WifiOnly] keys off
 * the connection being *unmetered* (so a metered Wi-Fi hotspot is correctly excluded, and only free
 * connections prefetch). Fails closed (no preload) if connectivity can't be read.
 */
fun isPreloadAllowed(context: Context, mode: PreloadMode): Boolean = when (mode) {
    PreloadMode.Off -> false
    PreloadMode.Always -> true
    PreloadMode.WifiOnly -> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm != null && !cm.isActiveNetworkMetered
    }
}

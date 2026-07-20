package com.firebolt9907.luminabrowser.state

import android.content.Context
import android.net.ConnectivityManager
import android.os.PowerManager

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
 *
 * The OS **Data Saver** toggle is always honored: when the user has enabled Data Saver and hasn't
 * allowlisted this app, no heavy preload runs even in [PreloadMode.Always] — spending data on a page
 * they haven't opened is exactly what Data Saver asks us not to do. (Cheap `speculativeConnect`
 * warmups in [SpeculativePreconnector] are unaffected — they open a socket, not a download.)
 *
 * The OS **Battery Saver** mode is honored the same way: when power-save is active, speculative
 * full-page loads (CPU + radio + a wasted download for pages that may never open) are exactly the
 * background work Battery Saver asks apps to shed, so they're skipped regardless of [mode].
 */
fun isPreloadAllowed(context: Context, mode: PreloadMode): Boolean {
    if (mode == PreloadMode.Off) return false
    // Battery Saver on → skip speculative downloads regardless of mode/network.
    val pm = context.getSystemService(PowerManager::class.java)
    if (pm?.isPowerSaveMode == true) return false
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    // Data Saver on + app not allowlisted → skip speculative downloads regardless of mode.
    if (cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
        return false
    }
    return when (mode) {
        PreloadMode.Off -> false
        PreloadMode.Always -> true
        PreloadMode.WifiOnly -> !cm.isActiveNetworkMetered
    }
}

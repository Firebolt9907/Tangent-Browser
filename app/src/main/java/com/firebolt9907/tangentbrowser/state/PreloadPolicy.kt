package com.firebolt9907.tangentbrowser.state

import android.content.Context
import android.net.ConnectivityManager
import android.os.PowerManager
import java.net.URI

/**
 * When Tangent may spend data *loading* a page the user hasn't opened yet — i.e. the external-link
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

/**
 * True if [url] is safe to **auto-preload on behalf of an untrusted external caller** — i.e. the
 * Custom Tabs `mayLaunchUrl` path, which our *exported* `TangentCustomTabsService` exposes to **any**
 * app on the device. That path drives [ExternalLinkPreloader], which does a full *cookied* headless
 * page load; without this gate a background app could aim it at internal endpoints (a router admin
 * page, `169.254.169.254` cloud-metadata, a `localhost` debug server) and turn the browser into an
 * unattended SSRF oracle carrying the user's ambient network position.
 *
 * So this refuses anything that isn't a plausibly-**public** http(s) host: loopback / private /
 * link-local / unique-local / CGNAT IP literals, `localhost`, `*.local` mDNS, and bare single-label
 * intranet names. Public FQDNs (what Google Discover actually hands us) pass. Fails closed on
 * unparseable input. Does NOT gate the user-driven in-app link-peek preloader — a user browsing their
 * own intranet may legitimately warm a private page there; the risk is unique to the *external* path.
 *
 * Residual: a public hostname that DNS-rebinds to a private address still passes (only the literal
 * host is inspected, not the resolved IP) — a deeper attack than the trivial direct-IP/localhost abuse
 * this closes.
 */
fun isPublicPreloadHost(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val parsed = runCatching { URI(url) }.getOrNull() ?: return false
    val scheme = parsed.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return false
    val host = parsed.host?.lowercase()?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() }
        ?: return false

    // IPv6 literal (contains a colon): reject loopback/unspecified/link-local/unique-local.
    if (host.contains(':')) {
        if (host == "::1" || host == "::") return false
        // fe80::/10 link-local, fc00::/7 unique-local (fc/fd), fec0 deprecated site-local.
        if (host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") ||
            host.startsWith("feb") || host.startsWith("fc") || host.startsWith("fd") ||
            host.startsWith("fec") || host.startsWith("fed") || host.startsWith("fee") ||
            host.startsWith("fef")
        ) {
            return false
        }
        return true
    }

    // IPv4 literal (four numeric dotted octets): apply private/special-range checks.
    val octets = host.split('.')
    if (octets.size == 4 && octets.all { o -> o.toIntOrNull()?.let { it in 0..255 } == true }) {
        val a = octets[0].toInt(); val b = octets[1].toInt()
        val private = a == 0 || a == 10 || a == 127 ||
            (a == 169 && b == 254) ||               // link-local (incl. 169.254.169.254 metadata)
            (a == 172 && b in 16..31) ||            // 172.16.0.0/12
            (a == 192 && b == 168) ||               // 192.168.0.0/16
            (a == 100 && b in 64..127) ||           // 100.64.0.0/10 CGNAT
            a >= 224                                 // multicast / reserved / 255.255.255.255
        return !private
    }

    // Hostname (not an IP literal). Reject loopback/intranet-shaped names.
    if (host == "localhost" || host.endsWith(".localhost")) return false
    if (host.endsWith(".local")) return false        // mDNS / Bonjour
    if (!host.contains('.')) return false            // bare single-label intranet name
    return true
}

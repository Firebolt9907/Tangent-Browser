package com.firebolt9907.tangentbrowser.state

import android.content.Context
import android.net.ConnectivityManager
import android.os.PowerManager
import com.google.common.net.InetAddresses
import java.net.Inet4Address
import java.net.Inet6Address
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
    val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    if (parsed.rawUserInfo != null) return false
    val scheme = parsed.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return false
    val rawHost = parsed.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotBlank() }
        ?: return false
    val host = rawHost.removeSurrounding("[", "]")
    if (host.isEmpty() || host.contains('%')) return false

    // Parse canonical IPv4 and IPv6 literals WITHOUT performing DNS resolution.
    if (InetAddresses.isInetAddress(host)) {
        val addr = runCatching { InetAddresses.forString(host) }.getOrNull() ?: return false
        return isPublicInetAddress(addr)
    }
    // Any remaining colon means a malformed or scoped IPv6 literal — fail closed.
    if (host.contains(':')) return false

    // Reject non-canonical numeric/hex/octal IPv4 encodings (e.g. 127.1, 0x7f.0.0.1, 0177.0.0.1):
    // real ICANN TLDs are never all-digits or hex literals, so requiring a non-numeric final label
    // closes every shorthand IPv4 form URL/OS resolvers accept.
    val labels = host.split('.')
    if (labels.size < 2 || labels.any { it.isEmpty() }) return false
    val tld = labels.last()
    if (tld.all { it.isDigit() } || tld.startsWith("0x")) return false

    // Reject loopback, mDNS, and reserved local-use suffixes.
    if (host == "localhost" || host.endsWith(".localhost")) return false
    if (host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".home.arpa")) return false
    return true
}

private fun isPublicInetAddress(addr: java.net.InetAddress): Boolean {
    if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
        addr.isSiteLocalAddress || addr.isMulticastAddress
    ) {
        return false
    }
    val bytes = addr.address
    if (addr is Inet4Address && bytes.size == 4) {
        val a = bytes[0].toInt() and 0xFF
        val b = bytes[1].toInt() and 0xFF
        val c = bytes[2].toInt() and 0xFF
        if (a == 0 || a == 10 || a == 127) return false
        if (a == 169 && b == 254) return false           // 169.254.0.0/16 link-local / cloud metadata
        if (a == 172 && b in 16..31) return false        // 172.16.0.0/12
        if (a == 192 && b == 168) return false           // 192.168.0.0/16
        if (a == 100 && b in 64..127) return false       // 100.64.0.0/10 CGNAT
        if (a == 198 && (b == 18 || b == 19)) return false // 198.18.0.0/15 benchmarking
        if (a == 192 && b == 0 && (c == 0 || c == 2)) return false
        if (a == 198 && b == 51 && c == 100) return false
        if (a == 203 && b == 0 && c == 113) return false
        if (a >= 224) return false                       // multicast / reserved / broadcast
        return true
    }
    if (addr is Inet6Address && bytes.size == 16) {
        // IPv4-mapped (::ffff:x.x.x.x) or IPv4-compatible (::x.x.x.x) — validate embedded IPv4.
        val isV4Mapped = (0 until 10).all { bytes[it] == 0.toByte() } &&
            bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
        val isV4Compat = (0 until 12).all { bytes[it] == 0.toByte() }
        if (isV4Mapped || isV4Compat) {
            val v4 = runCatching {
                java.net.InetAddress.getByAddress(bytes.copyOfRange(12, 16))
            }.getOrNull() ?: return false
            return isPublicInetAddress(v4)
        }
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        // fc00::/7 Unique Local, fe80::/10 Link-Local, fec0::/10 Site-Local, ff00::/8 Multicast.
        if ((b0 and 0xFE) == 0xFC) return false
        if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return false
        if (b0 == 0xFE && (b1 and 0xC0) == 0xC0) return false
        if (b0 == 0xFF) return false
        // 2001:db8::/32 documentation, 64:ff9b::/96 NAT64 (embeds arbitrary IPv4!).
        if (b0 == 0x20 && b1 == 0x01 &&
            (bytes[2].toInt() and 0xFF) == 0x0D && (bytes[3].toInt() and 0xFF) == 0xB8
        ) return false
        if (b0 == 0x00 && b1 == 0x64 &&
            (bytes[2].toInt() and 0xFF) == 0xFF && (bytes[3].toInt() and 0xFF) == 0x9B
        ) {
            val v4 = runCatching {
                java.net.InetAddress.getByAddress(bytes.copyOfRange(12, 16))
            }.getOrNull() ?: return false
            return isPublicInetAddress(v4)
        }
        // Only global unicast (2000::/3) may be auto-preloaded.
        return (b0 and 0xE0) == 0x20
    }
    return false
}

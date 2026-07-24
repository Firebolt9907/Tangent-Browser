package com.firebolt9907.tangentbrowser.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.storage.FrecencyThresholdOption
import mozilla.components.concept.storage.HistoryStorage
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Warms DNS + TCP + TLS connections to origins the user is likely to navigate to next, so the first
 * request lands on an already-open connection.
 *
 * This is the trick that makes Chrome's google.com feel faster than any cold network round-trip: the
 * page's static assets are usually already in Gecko's HTTP disk cache (see the enlarged cache in
 * [com.firebolt9907.tangentbrowser.engine] `buildRuntime`), so the only remaining cost is the
 * connection handshake — which a speculative connect eliminates ahead of the navigation.
 *
 * All connects go through [Engine.speculativeConnect]. We only ever preconnect to origins the user
 * has already visited (their frecent history / their own suggestions), so this leaks nothing beyond
 * what history already records. Per-origin throttling stops rapid suggestion updates or repeated
 * bar-focus from spamming the same host.
 */
class SpeculativePreconnector(
    private val engine: Engine,
    private val scope: CoroutineScope,
) {
    // origin -> last preconnect uptime (ms). Bounded by [MAX_TRACKED]; a user touches only a handful
    // of hosts per editing session, so this stays tiny in practice.
    private val lastConnect = ConcurrentHashMap<String, Long>()

    /** Preconnect to [url]'s origin, at most once per [THROTTLE_MS]. Safe to call from any thread. */
    fun preconnect(url: String) {
        val origin = originOf(url) ?: return
        val now = System.currentTimeMillis()
        val prev = lastConnect[origin]
        if (prev != null && now - prev < THROTTLE_MS) return
        if (lastConnect.size > MAX_TRACKED) lastConnect.clear()
        lastConnect[origin] = now
        // Engine calls expect the main thread; scope is Main.immediate. speculativeConnect only kicks
        // off async native work, so this is cheap.
        scope.launch { runCatching { engine.speculativeConnect(origin) } }
    }

    /**
     * Warm the user's most frecent origins — call when the address bar gains focus and once on
     * launch, so the likely next destination is already connected by the time it's tapped.
     */
    fun warmTopSites(history: HistoryStorage, count: Int = TOP_SITE_COUNT) {
        scope.launch {
            val sites = withContext(Dispatchers.IO) {
                runCatching {
                    history.getTopFrecentSites(count, FrecencyThresholdOption.SKIP_ONE_TIME_PAGES)
                }.getOrDefault(emptyList())
            }
            sites.forEach { preconnect(it.url) }
        }
    }

    /** Reduce a full URL to its `scheme://host[:port]` origin, or null if not an http(s) URL. */
    private fun originOf(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return null
        return try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host ?: return null
            val port = if (uri.port != -1) ":${uri.port}" else ""
            "$scheme://$host$port"
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val THROTTLE_MS = 10_000L
        private const val MAX_TRACKED = 64
        private const val TOP_SITE_COUNT = 6
    }
}

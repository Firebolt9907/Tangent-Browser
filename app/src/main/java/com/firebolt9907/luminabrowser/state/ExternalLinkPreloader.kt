package com.firebolt9907.luminabrowser.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession

/**
 * Primes the shared HTTP cache for a URL an external caller (e.g. the Google app / Discover) has
 * signalled the user is likely to open, via the Custom Tabs `mayLaunchUrl` handshake.
 *
 * The Custom Tabs base service already `speculativeConnect`s the origin (DNS+TCP+TLS). This goes one
 * step further and actually *loads* the page in a short-lived headless [EngineSession] — no
 * [mozilla.components.concept.engine.EngineView] is attached, so nothing paints, but necko fetches
 * the HTML and its eager subresources into the same on-disk HTTP cache the real Custom Tab session
 * will read from. So when the user taps and [com.firebolt9907.luminabrowser.CustomTabActivity]
 * creates the actual tab, most of the load is served from cache instead of the network.
 *
 * Bounded and self-cleaning: at most [MAX_INFLIGHT] concurrent preloads, each closed after
 * [PRELOAD_TTL_MS] (or when evicted) so a preload the user never taps can't leak a session/process.
 * All engine access is marshalled onto [scope] (the app's main-immediate scope) since sessions are
 * main-thread objects and `mayLaunchUrl` arrives on a Binder thread.
 */
class ExternalLinkPreloader(
    private val engine: Engine,
    private val scope: CoroutineScope,
) {
    private val inFlight = LinkedHashMap<String, EngineSession>()

    /** Begin (or refresh) a headless preload of [url]. Safe to call from any thread. */
    fun preload(url: String) {
        val key = url.trim()
        if (key.isEmpty()) return
        scope.launch {
            if (inFlight.containsKey(key)) return@launch
            if (inFlight.size >= MAX_INFLIGHT) {
                val oldest = inFlight.keys.firstOrNull()
                if (oldest != null) inFlight.remove(oldest)?.close()
            }
            val session = runCatching { engine.createSession() }.getOrNull() ?: return@launch
            inFlight[key] = session
            runCatching { session.loadUrl(key) }
            // Give the fetch time to land in cache, then drop the session so it (and its content
            // process) doesn't linger. If the user tapped through in the meantime, their real Custom
            // Tab session is independent and reads the now-warm cache.
            delay(PRELOAD_TTL_MS)
            if (inFlight.remove(key) === session) session.close()
        }
    }

    /** Close everything — call if the app is tearing down. */
    fun clear() {
        scope.launch {
            inFlight.values.forEach { runCatching { it.close() } }
            inFlight.clear()
        }
    }

    companion object {
        private const val MAX_INFLIGHT = 2
        private const val PRELOAD_TTL_MS = 25_000L
    }
}

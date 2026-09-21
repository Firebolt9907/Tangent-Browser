package com.firebolt9907.tangentbrowser.sync.zen

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.icons.Icon
import mozilla.components.browser.icons.IconRequest
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** Portable icons for Zen's `data:`-only remote favicon path. No third-party favicon service. */
internal class ZenPhoneFavicons(private val icons: BrowserIcons?) {
    private data class Cached(val revision: Int?, val data: String)
    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun enrich(tabs: List<ZenPhoneTab>, bitmaps: Map<String, Bitmap?>): List<ZenPhoneTab> {
        val publicTabs = ZenPhoneSpace.visibleTabs(tabs)
        cache.keys.retainAll(publicTabs.map { it.url }.toSet())
        // Loaded Gecko icons take precedence. Encode off the main thread, only when pixels change.
        withContext(Dispatchers.Default) {
            for (tab in publicTabs) {
                val bitmap = bitmaps[tab.id] ?: continue
                if (cache[tab.url]?.revision == tab.iconRevision) continue
                encode(bitmap)?.let { cache[tab.url] = Cached(tab.iconRevision, it) }
            }
        }
        // Bound both fan-out and total latency so a missing icon never holds up tab publishing.
        val permits = Semaphore(4)
        withTimeoutOrNull(5_000L) {
            coroutineScope {
                publicTabs.distinctBy { it.url }.filter { cache[it.url] == null }.forEach { tab ->
                    launch {
                        permits.withPermit {
                            var request: kotlinx.coroutines.Deferred<Icon>? = null
                            try {
                                val activeRequest = icons?.loadIcon(IconRequest(tab.url)) ?: return@withPermit
                                request = activeRequest
                                val icon = withTimeoutOrNull(1_500L) { activeRequest.await() }
                                if (icon != null && icon.source != Icon.Source.GENERATOR) {
                                    withContext(Dispatchers.Default) { encode(icon.bitmap) }
                                        ?.let { cache[tab.url] = Cached(null, it) }
                                }
                            } catch (error: Exception) {
                                if (error is kotlinx.coroutines.CancellationException) throw error
                                // Favicon failures must not fail a tab upload.
                            } finally {
                                if (request?.isCompleted == false) request.cancel()
                            }
                        }
                    }
                }
            }
        }
        return publicTabs.map { it.copy(icon = cache[it.url]?.data) }
    }

    private fun encode(bitmap: Bitmap): String? = runCatching {
        if (bitmap.isRecycled) return null
        val scale = minOf(1.0, 64.0 / max(bitmap.width, bitmap.height))
        val small = if (scale < 1) Bitmap.createScaledBitmap(bitmap,
            max(1, (bitmap.width * scale).toInt()), max(1, (bitmap.height * scale).toInt()), true) else bitmap
        try {
            val output = ByteArrayOutputStream()
            if (!small.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
            val bytes = output.toByteArray()
            if (bytes.size > 24 * 1024) return null
            "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
        } finally {
            // Never recycle a bitmap owned by Gecko or BrowserIcons.
            if (small !== bitmap) small.recycle()
        }
    }.getOrNull()
}

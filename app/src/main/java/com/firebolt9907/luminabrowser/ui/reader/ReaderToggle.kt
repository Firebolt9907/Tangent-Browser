package com.firebolt9907.luminabrowser.ui.reader

import mozilla.components.concept.engine.EngineSession

/**
 * The shared reader on/off state machine behind every toggle surface — the main browser's pill
 * long-press ([com.firebolt9907.luminabrowser.ui.LuminaRoot]), the aA sheet's Show/Hide Reader
 * button, and the Custom Tab bottom bar. It was copy-pasted in all three before being extracted
 * here; keep them on this single path.
 *
 * Branches:
 *  - **Active** → restore the original URL if a Google-cache detour saved one (marking the tab as
 *    restoring so the load isn't mistaken for a navigation), then deactivate.
 *  - **Eligible** (article already extracted) → activate immediately — unless the Google-cache
 *    detour is enabled, in which case remember the real URL, load the cache copy, and let the
 *    pending activation fire when its extraction lands.
 *  - **Otherwise** → request activation and kick off extraction (or the Google-cache detour).
 *
 * Returns `true` when reader is being turned ON (immediately or pending), `false` when turned off,
 * so callers can layer their own bookkeeping (site reader preferences, auto-activation flags) on
 * top without duplicating the core.
 */
fun ReaderManager.toggleReader(
    tabId: String,
    pageUrl: String,
    engineSession: EngineSession?,
): Boolean {
    return if (isReaderActive(tabId)) {
        getOriginalUrl(tabId)?.let { originalUrl ->
            setRestoring(tabId, true)
            engineSession?.loadUrl(originalUrl)
            setOriginalUrl(tabId, null)
        }
        setReaderActive(tabId, false)
        false
    } else if (isReaderEligible(tabId)) {
        if (useGoogleCache.value && shouldRedirectToGoogleCache(pageUrl)) {
            requestReaderActivation(tabId)
            setOriginalUrl(tabId, pageUrl)
            engineSession?.loadUrl(getGoogleCacheUrl(pageUrl))
        } else {
            setReaderActive(tabId, true)
        }
        true
    } else {
        requestReaderActivation(tabId)
        engineSession?.let { session ->
            if (useGoogleCache.value && shouldRedirectToGoogleCache(pageUrl)) {
                setOriginalUrl(tabId, pageUrl)
                session.loadUrl(getGoogleCacheUrl(pageUrl))
            } else {
                ReaderExtractor.extractReaderContent(session, tabId)
            }
        }
        true
    }
}

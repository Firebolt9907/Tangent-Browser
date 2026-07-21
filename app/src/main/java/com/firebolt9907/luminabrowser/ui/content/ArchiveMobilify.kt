package com.firebolt9907.luminabrowser.ui.content

import com.firebolt9907.luminabrowser.ui.archive.WaybackManager
import mozilla.components.concept.engine.EngineSession

/**
 * Normalizes an archived (Wayback Machine) page to device width and hides any residual Wayback
 * chrome — see [WaybackManager.buildMobilifyScript]. Injected per-load by [ContentHost] only while
 * the tab is in archive mode and on a `web.archive.org` URL. Mirrors the other injector extensions
 * ([injectYouTubeCleanPlayer], [injectSiteTint]); the call site owns the invariant-#11 guard.
 */
internal fun EngineSession.injectArchiveMobilify() {
    loadJavascriptUrl(WaybackManager.buildMobilifyScript())
}

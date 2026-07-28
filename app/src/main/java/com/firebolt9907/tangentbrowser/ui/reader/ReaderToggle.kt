package com.firebolt9907.tangentbrowser.ui.reader

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import mozilla.components.concept.engine.EngineSession

/**
 * Privileged/local schemes we must never hand to `startActivity(ACTION_VIEW, …)` from reader
 * content. The reader link is parsed out of an **untrusted page**, and unlike a normal in-browser
 * navigation (which GeckoView routes through its own external-protocol prompt) a reader-overlay tap
 * launches an implicit intent directly. A hostile article could point a link at `file:`/`content:`
 * (local data access / URI leak), `intent:`/`android-app:` (Android intent-scheme redirection), or
 * `javascript:`/`data:`/`blob:` (script/inline payloads), so those are refused here. Ordinary
 * external-app schemes (mailto:, tel:, sms:, geo:, market:, and app deeplinks) still launch.
 */
private val BLOCKED_READER_LINK_SCHEMES =
    setOf("file", "content", "intent", "android-app", "javascript", "data", "blob")

// Scheme = leading token before the first ':' (RFC 3986 shape), or null when the href is
// schemeless/relative. Pure + JVM-testable (no android.net.Uri).
private val readerSchemeRegex = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

/**
 * Whether a non-http(s) reader link may be launched as an external `ACTION_VIEW` intent. False for
 * the privileged/local schemes in [BLOCKED_READER_LINK_SCHEMES]; a schemeless href has no external
 * handler to launch, so it is refused too. Visible for unit tests.
 */
internal fun isLaunchableReaderScheme(href: String): Boolean {
    val scheme = readerSchemeRegex.find(href.trim())?.groupValues?.get(1)?.lowercase() ?: return false
    return scheme !in BLOCKED_READER_LINK_SCHEMES
}

/**
 * Handles a link tapped inside the reader overlay — shared by both reader surfaces (main browser
 * and Custom Tab). Web links drop out of reader and load in the underlying page session; any other
 * scheme (mailto:, tel:, market:, …) is handed to the system — except privileged/local schemes
 * ([isLaunchableReaderScheme]), which are refused so untrusted reader content can't launch a
 * `file:`/`content:`/`intent:` intent on a single tap. Silently ignored when nothing handles it.
 */
fun ReaderManager.openReaderLink(
    context: Context,
    tabId: String?,
    engineSession: EngineSession?,
    href: String,
) {
    val lowHref = href.trim().lowercase()
    if (lowHref.startsWith("http://") || lowHref.startsWith("https://")) {
        tabId?.let { setReaderActive(it, false) }
        engineSession?.loadUrl(href)
    } else if (isLaunchableReaderScheme(href)) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, href.toUri()))
        } catch (_: Exception) {
            // No app handles the scheme — drop the tap.
        }
    }
}

/**
 * The shared reader on/off state machine behind every toggle surface — the main browser's pill
 * long-press ([com.firebolt9907.tangentbrowser.ui.TangentRoot]), the aA sheet's Show/Hide Reader
 * button, and the Custom Tab bottom bar. It was copy-pasted in all three before being extracted
 * here; keep them on this single path.
 *
 * Branches:
 *  - **Active** → deactivate.
 *  - **Eligible** (article already extracted) → activate immediately.
 *  - **Otherwise** → request activation and kick off extraction.
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
        setReaderActive(tabId, false)
        false
    } else if (isReaderEligible(tabId)) {
        setReaderActive(tabId, true)
        true
    } else {
        requestReaderActivation(tabId)
        engineSession?.let { session ->
            // Prefer the isolated-world Port extractor for BOTH articles and social threads: it returns
            // over a native Port, which (unlike the window.prompt bridge) survives the strict CSP that
            // X/Reddit serve. The content script picks article-vs-social extraction by host. Only when no
            // live Port is connected yet do we fall back to the matching javascript: prompt extractor.
            ReaderExtension.registerFor(session, tabId)
            if (!ReaderExtension.requestExtract(session)) {
                if (SocialExtractor.isSocialUrl(pageUrl)) {
                    SocialExtractor.extract(session, tabId)
                } else {
                    ReaderExtractor.extractReaderContent(session, tabId)
                }
            }
        }
        true
    }
}

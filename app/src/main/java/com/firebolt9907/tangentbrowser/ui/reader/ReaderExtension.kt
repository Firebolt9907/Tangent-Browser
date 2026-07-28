package com.firebolt9907.tangentbrowser.ui.reader

import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.WebExtension

/**
 * Owns the built-in **reader-extractor** content-script extension
 * (`assets/extensions/reader/`, content script `extractor.js`) and its native-messaging
 * [ReaderPortHandler].
 *
 * ## Why this exists (transport, not algorithm)
 *
 * The default reader path injects a `javascript:` URL and hands the extracted article back via
 * `window.prompt("tangent-reader", …)` ([ReaderExtractor.extractReaderContent]). That fails on pages
 * served with a CSP `sandbox` directive that omits `allow-modals` — `prompt()` is then a silent
 * no-op, so the article can never reach the app and reader mode looks dead (e.g. TechCrunch). This
 * content script runs in an **isolated world** and returns the article over a **Port**, which no CSP
 * sandbox nor hostile page JS can block. The extractor logic inside `extractor.js` is a straight
 * port of [ReaderExtractor.buildReaderExtractorScript] — same heuristic, same per-site rules, same
 * ReaderBlock output — so switching transport does not change extraction behaviour.
 *
 * ## Lifecycle
 *
 * Installed once at startup ([install], from `Engine.init`). `extractor.js` matches `<all_urls>` and
 * auto-extracts at `document_end` (plus a bounded re-extract as late content lands), posting
 * `{type:"article"|"none"}` over the port. [registerFor] wires the port to a session/tab so those
 * messages route to [ReaderManager]; [requestExtract] nudges a re-extraction for the manual "Show
 * Reader" button and per-load checks. Every operation is fail-safe: if the extension isn't installed
 * or a session has no live port yet, callers fall back to the prompt-bridge extractor.
 */
object ReaderExtension {
    const val ID = "reader@tangent.firebolt9907"
    private const val URL = "resource://android/assets/extensions/reader/"

    private var extension: WebExtension? = null
    private var installing = false

    /** Shared across sessions; registered per-session in [registerFor]. */
    val portHandler = ReaderPortHandler()

    /** True once the extension is installed and its port channel is usable. */
    fun isReady(): Boolean = extension != null

    /** Install the extension once (idempotent). Safe to call before/after the engine has sessions. */
    fun install(engine: Engine) {
        if (extension != null || installing) return
        installing = true
        engine.installBuiltInWebExtension(
            id = ID,
            url = URL,
            onSuccess = { ext ->
                extension = ext
                installing = false
            },
            onError = { installing = false },
        )
    }

    /**
     * Route this session's reader-extractor port messages to [tabId]. Idempotent per session (the
     * handler replaces any prior registration). No-op until [install] completes — the prompt-bridge
     * path covers reader in that brief window.
     */
    fun registerFor(session: EngineSession, tabId: String) {
        val ext = extension ?: return
        portHandler.registerHandler(session, tabId, ext)
    }

    /**
     * Ask the content script to re-extract now. Returns true iff a live port received the request;
     * false means the caller should fall back to [ReaderExtractor.extractReaderContent] (extension
     * not installed, or the content script hasn't connected its port for this load yet).
     */
    fun requestExtract(session: EngineSession): Boolean {
        if (extension == null) return false
        return portHandler.sendExtract(session)
    }
}

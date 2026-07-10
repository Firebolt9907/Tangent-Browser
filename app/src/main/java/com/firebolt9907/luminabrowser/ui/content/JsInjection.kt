package com.firebolt9907.luminabrowser.ui.content

import mozilla.components.concept.engine.EngineSession

/**
 * Runs an injection script in the page via a `javascript:` URL load — the single flag combination
 * every injector uses (viewport spacers, page zoom, reader extraction, video control, AMO installer,
 * YouTube clean player). `REPLACE_HISTORY` keeps the `javascript:` URL out of session history so
 * Back never lands on it.
 *
 * AGENTS.md invariant #11 still applies at the CALL SITE: injections triggered from load callbacks
 * must check/set `suppressJavascriptLoadCallback` before calling this — the helper only wraps the
 * load flags, not the re-entry guard.
 */
internal fun EngineSession.loadJavascriptUrl(script: String) {
    loadUrl(
        url = script,
        flags = EngineSession.LoadUrlFlags.select(
            EngineSession.LoadUrlFlags.ALLOW_JAVASCRIPT_URL,
            EngineSession.LoadUrlFlags.LOAD_FLAGS_REPLACE_HISTORY,
        ),
    )
}

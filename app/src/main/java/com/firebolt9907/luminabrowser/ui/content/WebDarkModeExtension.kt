package com.firebolt9907.luminabrowser.ui.content

import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.webextension.EnableSource
import mozilla.components.concept.engine.webextension.WebExtension

/**
 * Manages the built-in **document_start** dark-mode pre-paint extension
 * (`assets/extensions/darkmode/`, content script `darkmode.js`).
 *
 * The native dark-mode injection ([WebDarkMode.injectWebDarkMode]) can only run at
 * first-contentful-paint / load-complete — after a light page has already painted white — so it
 * can't stop the white flash on its own. This built-in content script runs at `document_start` and
 * inverts a light page *before* the first paint (removing itself before paint for pages that turn out
 * genuinely dark), then hands off to the native injection which reuses the same class/id. See
 * `darkmode.js` and `WebDarkMode.kt`.
 *
 * The content script matches `<all_urls>`, so it must run **only while web dark mode is globally on**.
 * It's installed once at startup and then enabled/disabled to track the global resolved state
 * (`WebDarkModeTrigger.resolve(...)`) — driven from `LuminaRoot`. Enable/disable is global (matches
 * the trigger's global scope); per-site overrides and the filter-vs-dynamic choice are reconciled by
 * the native FCP injection, not here. All operations are fail-safe: if install/enable fails the page
 * simply renders without the pre-paint layer (the native tier still applies at FCP).
 */
object WebDarkModeExtension {
    const val ID = "darkmode@lumina.firebolt9907"
    private const val URL = "resource://android/assets/extensions/darkmode/"

    private var extension: WebExtension? = null
    private var installing = false
    // Desired enabled state; applied once install completes (install is async) and on every change.
    private var desiredEnabled = false

    /**
     * Install the extension once (idempotent) and set its initial enabled state. [initialEnabled]
     * should be the resolved global dark-mode state as best known at startup (system theme isn't
     * available yet for the Auto trigger, so callers pass `trigger == On`; [setEnabled] corrects it
     * as soon as `LuminaRoot` composes with the real resolved value).
     */
    fun install(engine: Engine, initialEnabled: Boolean) {
        desiredEnabled = initialEnabled
        if (extension != null || installing) return
        installing = true
        engine.installBuiltInWebExtension(
            id = ID,
            url = URL,
            onSuccess = { ext ->
                extension = ext
                installing = false
                // A freshly installed built-in is enabled by default; force it to the desired state.
                applyEnabled(engine, desiredEnabled)
            },
            onError = { installing = false },
        )
    }

    /** Track the global resolved web-dark-mode state; enables/disables the pre-paint content script. */
    fun setEnabled(engine: Engine, enabled: Boolean) {
        desiredEnabled = enabled
        if (extension == null) {
            // Not installed yet (e.g. dark mode toggled on before install ran) — install now with
            // this as the initial state.
            install(engine, enabled)
            return
        }
        applyEnabled(engine, enabled)
    }

    private fun applyEnabled(engine: Engine, enabled: Boolean) {
        val ext = extension ?: return
        try {
            if (enabled) {
                engine.enableWebExtension(ext, source = EnableSource.APP_SUPPORT, onSuccess = { extension = it })
            } else {
                engine.disableWebExtension(ext, source = EnableSource.APP_SUPPORT, onSuccess = { extension = it })
            }
        } catch (_: Throwable) {
            // Engine without web-extension support (shouldn't happen for GeckoEngine) — ignore; the
            // native FCP tier still applies dark mode, just without the pre-paint flash fix.
        }
    }
}

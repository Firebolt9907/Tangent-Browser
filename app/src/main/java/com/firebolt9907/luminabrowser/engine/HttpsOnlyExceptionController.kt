package com.firebolt9907.luminabrowser.engine

import com.firebolt9907.luminabrowser.state.GlobalPreferencesStore
import com.firebolt9907.luminabrowser.state.hostOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.lib.state.ext.flow

/**
 * Reconciles GeckoView's single, *global* HTTPS-Only switch with a per-host allow-list.
 *
 * GeckoView exposes only `allowInsecureConnections` (all sites vs. none) — there is no per-site
 * HTTPS-Only exception API (Gecko's own exception is a chrome-internal `reloadWithHttpsOnlyException`
 * we cannot call from app code). So we approximate per-site exceptions by making the effective mode a
 * function of the **selected tab's host**: HTTPS-Only stays on globally, but drops to off while the
 * foreground tab is on a host the user approved for HTTP (see [GlobalPreferencesStore.httpsOnlyAllowedHosts]).
 *
 * Tradeoff (acceptable for a personal browser): the switch is global, so during the brief window a
 * background tab loads while an approved http host is foregrounded, that background load is not
 * upgraded. Scoping to the selected tab keeps the window to deliberate foreground navigation.
 *
 * First visit to an approved-but-not-yet-known host still fails once (the tab isn't selected on an
 * approved host yet); the failure flows through [LuminaRequestInterceptor] to the in-app "load over
 * HTTP" prompt, whose confirm calls [allow].
 */
class HttpsOnlyExceptionController(
    private val store: BrowserStore,
    private val engine: Engine,
    private val prefs: GlobalPreferencesStore,
    private val scope: CoroutineScope,
) {
    // Tracks what we last wrote so we don't hammer the runtime setting on every unrelated store emission.
    private var lastApplied: Engine.HttpsOnlyMode? = null

    fun start() {
        scope.launch {
            store.flow()
                .map { it.selectedTab?.content?.url }
                .distinctUntilChanged()
                .collect { recompute() }
        }
    }

    /**
     * Re-derive and apply the effective HTTPS-Only mode. Idempotent — safe to call from
     * `applyEngineSettings()` (the single owner of the runtime setting now) and after [allow].
     */
    fun recompute() {
        val base =
            if (prefs.httpsOnly) Engine.HttpsOnlyMode.ENABLED else Engine.HttpsOnlyMode.DISABLED
        val selectedHost = store.state.selectedTab?.content?.url?.let { hostOf(it) }
        val desired = when {
            base == Engine.HttpsOnlyMode.DISABLED -> base
            selectedHost != null && prefs.isHttpAllowedForHost(selectedHost) ->
                Engine.HttpsOnlyMode.DISABLED
            else -> base
        }
        if (desired != lastApplied) {
            lastApplied = desired
            runCatching { engine.settings.httpsOnlyMode = desired }
        }
    }

    /** Approve [host] for HTTP and immediately drop HTTPS-Only so a pending reload can succeed. */
    fun allow(host: String) {
        prefs.allowHttpForHost(host)
        // Force a re-apply even if the selected-tab url hasn't changed yet.
        lastApplied = null
        recompute()
    }
}

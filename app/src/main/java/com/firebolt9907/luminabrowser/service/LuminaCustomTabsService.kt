package com.firebolt9907.luminabrowser.service

import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsSessionToken
import com.firebolt9907.luminabrowser.LuminaApplication
import mozilla.components.concept.engine.Engine
import mozilla.components.feature.customtabs.AbstractCustomTabsService
import mozilla.components.feature.customtabs.store.CustomTabsServiceStore

/**
 * The [android.support.customtabs.action.CustomTabsService] Lumina advertises (see the manifest
 * `<service>` declaration). Registering it is what makes callers that speak the Custom Tabs
 * protocol — most notably the Google app / Discover — recognize Lumina as a Custom Tabs provider.
 *
 * Why this matters: when a caller can bind to a CCT provider it launches article links through a
 * `CustomTabsIntent`, which carries `EXTRA_SESSION` (so [isCustomTabIntent] is true) and an
 * `ActivityOptions` enter/exit-animation bundle. Without a registered service the Google app falls
 * back to a plain `ACTION_VIEW` intent that carries no session and no animation bundle, so the
 * open/close between apps cuts instead of animating. (The cross-task *close* animation itself is
 * owned by the OS via CustomTabActivity's document-task placement; this service supplies the
 * protocol handshake + enter-animation options bundle that make the caller route to us.)
 *
 * mozilla-components' [AbstractCustomTabsService] handles the whole `CustomTabsService` surface
 * (warmup, session registration, mayLaunchUrl prefetch, post-message); we only supply the two
 * app-scoped singletons it needs.
 */
class LuminaCustomTabsService : AbstractCustomTabsService() {

    private val app get() = application as LuminaApplication

    override val engine: Engine get() = app.components.engine

    override val customTabsServiceStore: CustomTabsServiceStore
        get() = app.components.customTabsServiceStore

    /**
     * A caller (Google Discover/Search) tells us the user is likely to open [url] soon. The base
     * implementation `speculativeConnect`s the origin; we additionally warm a content process so the
     * eventual tap-through doesn't pay process-spawn latency.
     */
    override fun warmup(flags: Long): Boolean {
        val handled = super.warmup(flags)
        runCatching { app.components.engine.speculativeCreateSession() }
        return handled
    }

    /**
     * A caller signals the likely-next URL(s). On top of the base's speculative connect, actually
     * preload the primary target into the shared HTTP cache (see [ExternalLinkPreloader]) so the
     * Custom Tab open is served from cache rather than the network. Only http(s) targets are
     * preloaded; app-scheme/intent URLs are left to the base handling.
     */
    override fun mayLaunchUrl(
        sessionToken: CustomTabsSessionToken,
        url: Uri?,
        extras: Bundle?,
        otherLikelyBundles: List<Bundle>?,
    ): Boolean {
        val handled = super.mayLaunchUrl(sessionToken, url, extras, otherLikelyBundles)
        val scheme = url?.scheme?.lowercase()
        val allowed = com.firebolt9907.luminabrowser.state.isPreloadAllowed(
            applicationContext,
            app.components.globalPreferencesStore.preloadMode,
        )
        if (allowed && (scheme == "http" || scheme == "https")) {
            app.components.externalLinkPreloader.preload(url.toString())
        }
        return handled
    }
}

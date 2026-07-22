package com.firebolt9907.luminabrowser.ui.content

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide relay for a site-requested native-app launch. When a page navigates to an app deep
 * link (an `intent://` or custom-scheme URL), `LuminaRequestInterceptor` returns an
 * `InterceptionResponse.AppIntent`; GeckoView then fires
 * `EngineSession.Observer.onLaunchIntentRequest`, which [ContentHost] forwards here. `LuminaRoot`
 * collects [pending] to either auto-open the app (when the `autoOpenAppLinks` pref is on) or surface
 * the "Open in <app>" banner.
 *
 * Foreground-scoped by construction: [ContentHost] only observes the *visible* tab's session, so an
 * offer always refers to the selected tab. A tiny singleton (rather than a callback threaded through
 * both host surfaces — LuminaRoot and CustomTabRoot) keeps the [ContentHost] signature untouched.
 */
object AppLaunchRequests {
    data class AppLaunch(
        val tabId: String?,
        val url: String,
        val intent: Intent,
        val appName: String?,
        val packageName: String?,
        val fallbackUrl: String?,
    )

    private val _pending = MutableStateFlow<AppLaunch?>(null)
    val pending: StateFlow<AppLaunch?> = _pending.asStateFlow()

    fun offer(
        tabId: String?,
        url: String,
        intent: Intent,
        appName: String?,
        packageName: String?,
        fallbackUrl: String?,
    ) {
        _pending.value = AppLaunch(tabId, url, intent, appName, packageName, fallbackUrl)
    }

    fun clear() {
        _pending.value = null
    }
}

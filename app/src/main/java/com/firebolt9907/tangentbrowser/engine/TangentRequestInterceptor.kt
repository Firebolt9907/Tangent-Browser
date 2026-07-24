package com.firebolt9907.tangentbrowser.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import java.net.URI
import java.net.URLEncoder
import mozilla.components.browser.errorpages.ErrorPages
import mozilla.components.browser.errorpages.ErrorType
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.request.RequestInterceptor

/**
 * Serves a branded, localized error page for failed navigations (no connection, DNS failure, timeout,
 * connection refused, bad cert, …) instead of GeckoView's raw built-in page — the same approach
 * Fenix uses. The page HTML lives in `assets/error_page_js.html`; [ErrorPages.createUrlEncodedErrorPage]
 * fills it with the localized strings the `browser-errorpages` component ships for each [ErrorType].
 *
 * [onErrorRequest] serves the branded error pages. [onLoadRequest] additionally intercepts a page's
 * attempt to open a native app — an `intent://` or custom-scheme deep link (e.g. a site redirecting
 * to `whatsapp://…` or `market://…`). It resolves the target app ([resolveAppLink]) and returns an
 * [RequestInterceptor.InterceptionResponse.AppIntent]; GeckoView then fires
 * `EngineSession.Observer.onLaunchIntentRequest`, which the app surfaces as the "Open in <app>"
 * banner (or auto-opens per the `autoOpenAppLinks` pref). This only fires for **web-content-initiated**
 * navigations — [interceptsAppInitiatedRequests] stays false, so our own `loadUrl` calls (which are
 * always http/https anyway) are never touched. Ordinary web navigations return null and pass through.
 *
 * @param onHttpsOnlyError invoked with the blocked URI when a navigation fails under HTTPS-Only mode.
 *   The app surfaces an in-app "load over HTTP" prompt (HttpsOnlyExceptionController + the dialog in
 *   TangentRoot). We *also* serve our branded page for this type now: Gecko's built-in HTTPS-Only page
 *   renders cut off behind our chrome insets and its "Continue to HTTP Site" control was unreachable,
 *   so the in-app prompt is the escape hatch instead.
 */
class TangentRequestInterceptor(
    private val context: Context,
    private val onHttpsOnlyError: (String) -> Unit = {},
) : RequestInterceptor {

    override fun onLoadRequest(
        engineSession: EngineSession,
        uri: String,
        lastUri: String?,
        hasUserGesture: Boolean,
        isSameDomain: Boolean,
        isRedirect: Boolean,
        isDirectNavigation: Boolean,
        isSubframeRequest: Boolean,
    ): RequestInterceptor.InterceptionResponse? {
        return when (val link = resolveAppLink(context, uri)) {
            is AppLinkResult.Launch -> RequestInterceptor.InterceptionResponse.AppIntent(
                appIntent = link.intent,
                url = uri,
                fallbackUrl = link.fallbackUrl,
                appName = link.appName,
            )
            // No app handles the deep link, but the site declared an http(s) fallback — follow it
            // in place, matching Chrome's `browser_fallback_url` behavior, instead of erroring.
            is AppLinkResult.Fallback -> RequestInterceptor.InterceptionResponse.Url(link.url)
            null -> null
        }
    }

    override fun onErrorRequest(
        session: EngineSession,
        errorType: ErrorType,
        uri: String?,
    ): RequestInterceptor.ErrorResponse? {
        // HTTPS-Only upgrade failure: signal the app so it can offer the per-host "load over HTTP"
        // exception. (Bad-cert errors intentionally do NOT get an escape hatch — we never offer a
        // silent cert bypass.)
        if (errorType == ErrorType.ERROR_HTTPS_ONLY && uri != null) {
            onHttpsOnlyError(uri)
        }

        val errorPage = ErrorPages.createUrlEncodedErrorPage(
            context = context,
            errorType = errorType,
            uri = uri,
        )
        return RequestInterceptor.ErrorResponse(
            withSystemAccent(withDetails(errorPage, errorType, uri)),
        )
    }

    /**
     * Append richer, page-specific detail the base component doesn't surface: the failing host (shown
     * prominently under the title) and the technical error-type name (folded into the page's
     * collapsible "Technical details" section alongside the component's error code). The page renders
     * both via `textContent`, so a hostile URL still can't inject markup. Ride along as extra query
     * params on the existing `resource://…error_page_js.html?…` URL.
     */
    private fun withDetails(url: String, errorType: ErrorType, uri: String?): String {
        fun q(name: String, value: String) = "&$name=" + URLEncoder.encode(value, "UTF-8")
        val host = uri
            ?.let { runCatching { URI(it).host }.getOrNull() }
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
        var out = url + q("errorName", errorType.name)
        if (host != null) out += q("host", host)
        if (!uri.isNullOrBlank()) out += q("failedUrl", uri)
        return out
    }

    /**
     * Append the device's Material You accent (Android 12+ dynamic-color palette) to the error-page
     * URL so its "Try again" button matches the rest of the app instead of the hard-coded Firefox
     * blue. Light and dark variants are both passed; the page's own `prefers-color-scheme` JS picks
     * the right one and falls back to its built-in blue when these params are absent (pre-Android-12
     * or on failure). Values ride along as extra query params on the existing
     * `resource://…error_page_js.html?…` URL.
     */
    private fun withSystemAccent(url: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return url
        return try {
            fun hex(colorRes: Int) = String.format("#%06X", 0xFFFFFF and context.getColor(colorRes))
            fun q(name: String, value: String) = "&$name=" + URLEncoder.encode(value, "UTF-8")
            url +
                q("accentLight", hex(android.R.color.system_accent1_600)) +
                q("accentOnLight", hex(android.R.color.system_accent1_0)) +
                q("accentDark", hex(android.R.color.system_accent1_200)) +
                q("accentOnDark", hex(android.R.color.system_accent1_800))
        } catch (e: Exception) {
            url
        }
    }
}

/** Our own package — never hand a page's deep link back to ourselves. */
private const val OWN_PKG = "com.firebolt9907.tangentbrowser"

/**
 * Schemes GeckoView renders itself. Anything NOT in this set and NOT a real navigation is treated as
 * a potential native-app deep link. (`ws`/`wss` are here defensively — they never reach onLoadRequest,
 * but must never be mistaken for an app link.)
 */
private val WEB_SCHEMES = setOf(
    "http", "https", "about", "blob", "data", "javascript", "file",
    "resource", "chrome", "moz-extension", "view-source", "ws", "wss",
)

/** Outcome of resolving a non-web URL to how it should be handled. */
internal sealed interface AppLinkResult {
    /** An installed app ([appName]) claims this deep link; launch [intent]. */
    data class Launch(val intent: Intent, val appName: String?, val fallbackUrl: String?) : AppLinkResult

    /** No app handles the link, but the site declared an http(s) fallback to load instead. */
    data class Fallback(val url: String) : AppLinkResult
}

/**
 * Resolve a page navigation to a native-app deep link, the way Chrome opens an `intent://` or a
 * custom app scheme (`whatsapp://`, `spotify:`, `market://`, …). Returns null for ordinary web URLs
 * (and for app links nothing on the device can handle and with no web fallback), so the caller lets
 * the navigation proceed normally.
 *
 * Security (a page can put an arbitrary string here): the parsed intent is stripped of any explicit
 * [Intent.component]/[Intent.setSelector] and of every URI-permission grant flag before it is
 * resolved, so a hostile page cannot target a non-exported/internal component or smuggle a permission
 * grant — only an app that *publicly* claims the URL via a BROWSABLE VIEW intent can be launched.
 */
internal fun resolveAppLink(context: Context, url: String): AppLinkResult? {
    val uri = runCatching { url.toUri() }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme in WEB_SCHEMES) return null

    val intent = runCatching {
        if (scheme == "intent") {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }
    }.getOrNull() ?: return null

    intent.apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        component = null
        selector = null
        flags = flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            ).inv()
    }

    // The site's declared web fallback (only http(s) is honored — a fallback could itself be hostile).
    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
        ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }

    val pm = context.packageManager
    val resolved = pm.resolveActivity(intent, 0)
    val pkg = resolved?.activityInfo?.packageName
    // pkg == "android" is the system disambiguation/resolver activity — treat as "no dedicated app".
    if (pkg == null || pkg == OWN_PKG || pkg == "android") {
        return fallbackUrl?.let { AppLinkResult.Fallback(it) }
    }
    val appName = runCatching { resolved.loadLabel(pm)?.toString() }.getOrNull()
    return AppLinkResult.Launch(intent, appName, fallbackUrl)
}

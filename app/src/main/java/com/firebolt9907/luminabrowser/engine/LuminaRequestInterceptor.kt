package com.firebolt9907.luminabrowser.engine

import android.content.Context
import android.os.Build
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
 * Only [onErrorRequest] is overridden: we don't intercept normal load requests (external-app / intent
 * handling stays at the activity boundary — see IntentUrls / the two activities), and app-initiated
 * `loadUrl` calls are left alone ([interceptsAppInitiatedRequests] stays false).
 *
 * @param onHttpsOnlyError invoked with the blocked URI when a navigation fails under HTTPS-Only mode.
 *   The app surfaces an in-app "load over HTTP" prompt (HttpsOnlyExceptionController + the dialog in
 *   LuminaRoot). We *also* serve our branded page for this type now: Gecko's built-in HTTPS-Only page
 *   renders cut off behind our chrome insets and its "Continue to HTTP Site" control was unreachable,
 *   so the in-app prompt is the escape hatch instead.
 */
class LuminaRequestInterceptor(
    private val context: Context,
    private val onHttpsOnlyError: (String) -> Unit = {},
) : RequestInterceptor {

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

package com.firebolt9907.luminabrowser.engine

import android.content.Context
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
 */
class LuminaRequestInterceptor(private val context: Context) : RequestInterceptor {

    override fun onErrorRequest(
        session: EngineSession,
        errorType: ErrorType,
        uri: String?,
    ): RequestInterceptor.ErrorResponse? {
        // HTTPS-Only failures need the per-site "Continue to HTTP site" escape hatch, which only
        // Gecko's built-in error page (via document.reloadWithHttpsOnlyException) provides. Returning
        // null defers to that native page so a user who opted into HTTPS-Only can still reach an
        // HTTP-only site. Our branded page has no such control, so serving it here would strand them.
        // (Bad-cert errors intentionally stay on our page — we do NOT offer a silent cert bypass.)
        if (errorType == ErrorType.ERROR_HTTPS_ONLY) return null

        val errorPage = ErrorPages.createUrlEncodedErrorPage(
            context = context,
            errorType = errorType,
            uri = uri,
        )
        return RequestInterceptor.ErrorResponse(errorPage)
    }
}

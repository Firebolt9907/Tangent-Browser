package com.firebolt9907.tangentbrowser.ui.content

import java.net.URI

/** Canonical web origin for credential matching, including scheme and non-default port. */
internal fun loginAutofillOrigin(url: String?): String? {
    val origin = bridgeTrustedOrigin(url) ?: return null
    val uri = URI(origin)
    val defaultPort = if (uri.scheme == "https") 443 else 80
    if (uri.port > 65535) return null
    return if (uri.port == defaultPort) "${uri.scheme}://${uri.host}" else origin
}

/** A probe cannot choose another tab or supply the origin used to fetch credentials. */
internal data class LoginAutofillTarget(val tabId: String, val origin: String) {
    fun isCurrent(selectedTabId: String?): Boolean =
        selectedTabId == tabId && loginAutofillOrigin(BridgeGuard.trustedOrigin(tabId)) == origin

    fun acceptsCredential(credentialOrigin: String): Boolean =
        loginAutofillOrigin(credentialOrigin) == origin
}

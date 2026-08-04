package com.firebolt9907.tangentbrowser.state

import java.net.URI

/**
 * Recognises the **transient hops of a federated sign-in chain** — the `login.microsoftonline.com`
 * → IdP → MFA → SAML-POST-back sequence an enterprise SSO login walks through before landing on the
 * page you actually asked for.
 *
 * Nothing here tries to *skip* a hop. Every hop of such a chain carries a server-minted, single-use
 * value (`SAMLRequest`/`SAMLResponse`, an OAuth `code`, the ESTS `ctx`) and usually a `Set-Cookie`
 * the next hop reads, so hop N+1 cannot be synthesised without executing hop N. What this enables is
 * treating those hops as **infrastructure rather than destinations**:
 *
 * - [collapsedBackIndex] — Back jumps over the whole contiguous run instead of stepping into it (a
 *   step into a spent IdP entry just gets redirected forward again: the classic "Back is broken
 *   after login").
 * - History recording skips them (`SsoFilteringHistoryDelegate`).
 * - `ContentHost` skips the per-load injectors that are meaningless on them (reader extraction, form
 *   -field memory, zaps, snapshot capture, …) — see [isAuthUrl]'s callers.
 *
 * ## Why the matching is deliberately narrow
 *
 * A false positive silently eats a history entry, so a bare `/login` path or a `login`-ish word in a
 * URL is **not** enough. A URL qualifies only if it is either:
 *
 *  1. on a host that exists to serve identity and nothing else ([AUTH_HOST_SUFFIXES], or a
 *     first-label match in [AUTH_HOST_LABELS] like `idp.purdue.edu`), or
 *  2. carrying an unambiguous federation-protocol marker in its path or query ([AUTH_PATHS],
 *     [AUTH_PARAMS]) — a `SAMLRequest`, an `/adfs/ls` endpoint, a `Shibboleth.sso` handler.
 *
 * The accepted residual false positive is an ordinary site's own `login.`/`auth.` subdomain: Back
 * then skips its sign-in form rather than the form plus the chain. Landing back on a spent login
 * form is not a destination a user wants either, so the failure mode is benign.
 */
object SsoFlow {

    /** Hosts (matched as exact or dot-suffix) whose entire purpose is identity. */
    private val AUTH_HOST_SUFFIXES = listOf(
        // Microsoft Entra ID / Microsoft 365
        "login.microsoftonline.com",
        "login.microsoftonline.us",
        "login.microsoft.com",
        "login.windows.net",
        "login.live.com",
        "device.login.microsoftonline.com",
        "autologon.microsoftazuread-sso.com",
        "account.activedirectory.windowsazure.com",
        "b2clogin.com",
        "ciamlogin.com",
        "msauth.net",
        "msftauth.net",
        // MFA providers
        "duosecurity.com",
        "microsoftonline-p.com",
        // Other common enterprise/edu IdPs
        "okta.com",
        "oktapreview.com",
        "onelogin.com",
        "pingone.com",
        "auth0.com",
        "accounts.google.com",
        "sso.rutgers.edu",
        "shibboleth.net",
    )

    /**
     * First DNS labels that mark a host as an identity provider (`idp.purdue.edu`, `adfs.corp.com`).
     * Matched only as the leading label, never as a substring.
     */
    private val AUTH_HOST_LABELS = listOf(
        "idp", "sso", "adfs", "shibboleth", "login", "auth", "signin", "openid",
    )

    /** Path fragments that only appear on federation endpoints. Matched case-insensitively. */
    private val AUTH_PATHS = listOf(
        "/oauth2/authorize",
        "/oauth2/v2.0/authorize",
        "/oauth2/v1/authorize",
        "/adfs/ls",
        "/adfs/oauth2",
        "/idp/profile/saml2",
        "/shibboleth.sso",
        "/simplesaml/",
        "/saml2/",
        "/sso/saml",
        "/cas/login",
        "/common/oauth2",
        "/common/login",
        "/kmsi",
    )

    /** Query parameter names that only appear on a federation redirect. */
    private val AUTH_PARAMS = listOf(
        "samlrequest", "samlresponse", "relaystate", "wa=wsignin", "wresult",
    )

    /**
     * True when [url] is a hop of a federated sign-in chain rather than a page the user navigated to
     * on purpose. Safe to call on any string; anything unparseable or non-http(s) is false.
     */
    fun isAuthUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false

        if (AUTH_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }) return true
        if (AUTH_HOST_LABELS.any { host == it || host.startsWith("$it.") }) return true

        val path = uri.path?.lowercase().orEmpty()
        if (AUTH_PATHS.any { path.contains(it) }) return true

        val query = uri.rawQuery?.lowercase().orEmpty()
        if (query.isNotEmpty() && AUTH_PARAMS.any { query.contains(it) }) return true

        return false
    }

    /**
     * A **strictly narrower** predicate than [isAuthUrl], for the one caller that acts on a page
     * without asking the user first: unattended login autofill.
     *
     * [isAuthUrl] is tuned for cosmetic decisions (skip an injector, drop a history entry) where a
     * false positive costs almost nothing, so it accepts an ordinary site's `login.`/`auth.`
     * subdomain and any host carrying a federation marker. Neither is acceptable when the outcome is
     * "type a saved password into this page unprompted":
     *
     *  - `login.<anything>` is an *individual website's* sign-in page. The user may well have several
     *    accounts there, and it is not an SSO endpoint.
     *  - A federation marker (`SAMLResponse=`, `/Shibboleth.sso/`) rides on the **service provider's**
     *    own host during a chain, so matching on it would arm autofill across the whole web.
     *
     * So this matches only the curated identity-only host list and the unambiguous IdP labels — hosts
     * that exist to authenticate and nothing else, and where an enterprise user has exactly one
     * identity by construction. Path and query are ignored entirely.
     */
    fun isEnterpriseIdp(url: String): Boolean {
        if (url.isBlank()) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() != "https") return false
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false

        if (AUTH_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }) return true
        return ENTERPRISE_IDP_LABELS.any { host == it || host.startsWith("$it.") }
    }

    /**
     * The subset of [AUTH_HOST_LABELS] that means "identity provider" rather than "this site's own
     * login page". `login`/`auth`/`signin` are deliberately absent — see [isEnterpriseIdp].
     */
    private val ENTERPRISE_IDP_LABELS = listOf("idp", "sso", "adfs", "shibboleth")

    /**
     * Given a tab's back/forward list ([urls], newest last) and its [currentIndex], returns the index
     * Back should jump to in order to clear a sign-in chain in one step — or null when there is no
     * chain immediately behind the current entry and an ordinary one-step Back is correct.
     *
     * Only a **contiguous** run of auth hops directly behind [currentIndex] is collapsed, and only
     * when there is a real (non-auth) entry beneath it to land on. If the run reaches the bottom of
     * the list, null is returned: there is nowhere better to go, so Back should behave normally
     * rather than refuse to move.
     */
    fun collapsedBackIndex(urls: List<String>, currentIndex: Int): Int? {
        if (currentIndex !in 1..urls.lastIndex) return null
        // Nothing to collapse unless the entry we would land on is itself part of a chain.
        if (!isAuthUrl(urls[currentIndex - 1])) return null

        var target = currentIndex - 1
        while (target >= 0 && isAuthUrl(urls[target])) target--

        // The whole history behind us is the chain — let the normal one-step Back handle it.
        return if (target < 0) null else target
    }
}

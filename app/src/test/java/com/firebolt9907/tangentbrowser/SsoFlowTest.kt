package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.state.SsoFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [SsoFlow]'s matching width. The risk here is asymmetric: a false NEGATIVE just means a
 * sign-in chain is handled the way it is today, while a false POSITIVE silently drops a history
 * entry and makes Back skip a page the user wanted. The `notAuth` cases below are therefore the
 * load-bearing half of this file.
 */
class SsoFlowTest {

    // ── isAuthUrl: identity hosts ─────────────────────────────────────────────

    @Test fun entraAuthorizeEndpoint_isAuth() = assertTrue(
        SsoFlow.isAuthUrl(
            "https://login.microsoftonline.com/6f3ce3f0-0000-0000-0000-000000000000/oauth2/v2.0/authorize?client_id=abc",
        ),
    )

    @Test fun entraKmsiInterstitial_isAuth() =
        assertTrue(SsoFlow.isAuthUrl("https://login.microsoftonline.com/kmsi"))

    @Test fun purdueShibbolethIdp_isAuth() =
        assertTrue(SsoFlow.isAuthUrl("https://idp.purdue.edu/idp/profile/SAML2/Redirect/SSO"))

    @Test fun bareIdpHost_isAuth() =
        assertTrue(SsoFlow.isAuthUrl("https://sso.purdue.edu/"))

    @Test fun adfsHost_isAuth() =
        assertTrue(SsoFlow.isAuthUrl("https://adfs.corp.example.com/adfs/ls/?wa=wsignin1.0"))

    @Test fun duoMfa_isAuth() =
        assertTrue(SsoFlow.isAuthUrl("https://api-1234abcd.duosecurity.com/frame/prompt"))

    @Test fun oktaSubdomain_isAuth() =
        assertTrue(SsoFlow.isAuthUrl("https://example.okta.com/app/office365/exk1/sso/saml"))

    @Test fun hostSuffixMatchIsDotAnchored_notAuth() =
        assertFalse(SsoFlow.isAuthUrl("https://notokta.com/page"))

    // ── isAuthUrl: protocol markers on an ordinary host ───────────────────────

    @Test fun shibbolethServicePointOnSpHost_isAuth() =
        assertTrue(SsoFlow.isAuthUrl("https://apps.example.edu/Shibboleth.sso/SAML2/POST"))

    @Test fun samlResponseParam_isAuth() =
        assertTrue(SsoFlow.isAuthUrl("https://apps.example.edu/acs?SAMLResponse=PHNhbWxw&RelayState=x"))

    @Test fun casLogin_isAuth() =
        assertTrue(SsoFlow.isAuthUrl("https://cas.example.edu/cas/login?service=https%3A%2F%2Fa.edu"))

    // ── isAuthUrl: the false-positive guard rail ──────────────────────────────

    @Test fun ordinaryUniversityPage_notAuth() =
        assertFalse(SsoFlow.isAuthUrl("https://www.purdue.edu/newsroom/"))

    @Test fun ordinaryLoginPath_notAuth() =
        assertFalse(SsoFlow.isAuthUrl("https://github.com/login"))

    @Test fun signInWordInPath_notAuth() =
        assertFalse(SsoFlow.isAuthUrl("https://en.wikipedia.org/wiki/Single_sign-on"))

    @Test fun brightspaceContentPage_notAuth() =
        assertFalse(SsoFlow.isAuthUrl("https://purdue.brightspace.com/d2l/le/content/12345/Home"))

    @Test fun nonHttpScheme_notAuth() =
        assertFalse(SsoFlow.isAuthUrl("msauth://com.example/callback"))

    @Test fun aboutBlank_notAuth() = assertFalse(SsoFlow.isAuthUrl("about:blank"))

    @Test fun blank_notAuth() = assertFalse(SsoFlow.isAuthUrl(""))

    @Test fun garbage_notAuth() = assertFalse(SsoFlow.isAuthUrl("not a url at all"))

    // ── isEnterpriseIdp: the unattended-autofill gate ─────────────────────────
    //
    // This one arms a password fill with no user present, so it must be strictly narrower than
    // isAuthUrl. The `notIdp` cases are the ones that matter.

    @Test fun entra_isEnterpriseIdp() =
        assertTrue(SsoFlow.isEnterpriseIdp("https://login.microsoftonline.com/tenant/oauth2/authorize"))

    @Test fun purdueIdp_isEnterpriseIdp() =
        assertTrue(SsoFlow.isEnterpriseIdp("https://idp.purdue.edu/idp/profile/SAML2/Redirect/SSO"))

    @Test fun okta_isEnterpriseIdp() =
        assertTrue(SsoFlow.isEnterpriseIdp("https://example.okta.com/app/x"))

    /** An ordinary site's own sign-in subdomain: the user may have several accounts there. */
    @Test fun siteOwnLoginSubdomain_notEnterpriseIdp() {
        assertFalse(SsoFlow.isEnterpriseIdp("https://login.yahoo.com/"))
        assertTrue(SsoFlow.isAuthUrl("https://login.yahoo.com/")) // still cosmetic-suppressed
    }

    /** A federation marker rides on the SERVICE PROVIDER's host — matching it would arm the web. */
    @Test fun federationMarkerOnOrdinaryHost_notEnterpriseIdp() {
        assertFalse(SsoFlow.isEnterpriseIdp("https://apps.example.edu/acs?SAMLResponse=PHNhbWxw"))
        assertTrue(SsoFlow.isAuthUrl("https://apps.example.edu/acs?SAMLResponse=PHNhbWxw"))
    }

    @Test fun plainHttp_notEnterpriseIdp() =
        assertFalse(SsoFlow.isEnterpriseIdp("http://idp.purdue.edu/"))

    @Test fun ordinarySite_notEnterpriseIdp() =
        assertFalse(SsoFlow.isEnterpriseIdp("https://github.com/login"))

    // ── collapsedBackIndex ────────────────────────────────────────────────────

    private val chain = listOf(
        "https://purdue.brightspace.com/d2l/home",
        "https://login.microsoftonline.com/tenant/oauth2/v2.0/authorize?client_id=a",
        "https://idp.purdue.edu/idp/profile/SAML2/Redirect/SSO",
        "https://login.microsoftonline.com/kmsi",
        "https://purdue.brightspace.com/d2l/le/content/999/Home",
    )

    @Test fun collapsesWholeChainToPreAuthPage() =
        assertEquals(0, SsoFlow.collapsedBackIndex(chain, currentIndex = 4))

    @Test fun noChainBehind_returnsNullSoPlainBackIsUsed() = assertNull(
        SsoFlow.collapsedBackIndex(
            listOf("https://a.example.com/", "https://a.example.com/two"),
            currentIndex = 1,
        ),
    )

    @Test fun atStartOfHistory_returnsNull() =
        assertNull(SsoFlow.collapsedBackIndex(chain, currentIndex = 0))

    @Test fun indexOutOfRange_returnsNull() =
        assertNull(SsoFlow.collapsedBackIndex(chain, currentIndex = 99))

    @Test fun emptyHistory_returnsNull() =
        assertNull(SsoFlow.collapsedBackIndex(emptyList(), currentIndex = 0))

    /** Chain runs to the bottom of the list: nowhere better to land, so let plain Back work. */
    @Test fun chainReachesStartOfHistory_returnsNull() = assertNull(
        SsoFlow.collapsedBackIndex(chain.drop(1), currentIndex = 3),
    )

    /** Only the run directly behind the current entry collapses, not an earlier one. */
    @Test fun collapsesOnlyContiguousRun() {
        val urls = listOf(
            "https://a.example.com/one",
            "https://idp.purdue.edu/idp/profile/SAML2/Redirect/SSO",
            "https://a.example.com/two",
            "https://login.microsoftonline.com/kmsi",
            "https://a.example.com/three",
        )
        assertEquals(2, SsoFlow.collapsedBackIndex(urls, currentIndex = 4))
    }

    /** Back pressed while still mid-chain also exits the whole chain. */
    @Test fun fromInsideChain_stillCollapsesOut() =
        assertEquals(0, SsoFlow.collapsedBackIndex(chain, currentIndex = 3))
}

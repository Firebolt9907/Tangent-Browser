package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.content.BridgeGuard
import com.firebolt9907.tangentbrowser.ui.content.LoginAutofillTarget
import com.firebolt9907.tangentbrowser.ui.content.loginAutofillOrigin
import org.junit.Assert.*
import org.junit.Test

class LoginAutofillTargetTest {
    @Test fun credentialsRequireExactOrigin() {
        val target = LoginAutofillTarget("a", "https://idp.example.com")
        assertTrue(target.acceptsCredential("https://idp.example.com/login"))
        assertTrue(target.acceptsCredential("https://IDP.example.com:443"))
        for (origin in listOf(
            "http://idp.example.com", "https://example.com", "https://other.example.com",
            "https://idp.example.com:8443", "https://idp.example.com.evil.test",
            "javascript:alert(1)", "file:///tmp/login.html", "not a URL",
        )) assertFalse(origin, target.acceptsCredential(origin))
    }

    @Test fun navigationAndTabSwitchInvalidatePendingFill() {
        val target = LoginAutofillTarget("a", "https://idp.example.com")
        try {
            BridgeGuard.setTrustedOrigin("a", "https://idp.example.com/login")
            assertTrue(target.isCurrent("a"))
            assertFalse(target.isCurrent("b"))
            assertFalse(target.isCurrent(null))
            BridgeGuard.setTrustedOrigin("a", "https://evil.example.com/login")
            assertFalse(target.isCurrent("a"))
            BridgeGuard.setTrustedOrigin("a", "about:blank")
            assertFalse(target.isCurrent("a"))
        } finally {
            BridgeGuard.forget("a")
        }
    }

    @Test fun canonicalOriginsKeepSecurityBoundaries() {
        assertEquals("https://example.com", loginAutofillOrigin("https://EXAMPLE.com:443/path"))
        assertEquals("http://example.com", loginAutofillOrigin("http://example.com:80"))
        assertEquals("https://example.com:8443", loginAutofillOrigin("https://example.com:8443"))
        assertNull(loginAutofillOrigin("https://example.com:65536"))
        assertNull(loginAutofillOrigin("data:text/html,hello"))
    }
}

package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.content.BridgeGuard
import com.firebolt9907.tangentbrowser.ui.content.OtpAutofill
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class OtpSecurityTest {
    @After fun cleanup() {
        BridgeGuard.forget("otp-a")
        BridgeGuard.forget("otp-b")
    }

    @Test fun backgroundAndOpaquePagesCannotCaptureDelivery() {
        BridgeGuard.setTrustedOrigin("otp-a", "https://bank.example")
        assertNull(OtpAutofill.createDelivery("otp-a", { "otp-b" }) { _, _ -> fail() })
        assertNull(OtpAutofill.createDelivery("otp-b", { "otp-b" }) { _, _ -> fail() })
    }

    @Test fun deliveryDoesNotFollowSelectionOrNavigation() {
        var selected = "otp-a"
        var filled = ""
        BridgeGuard.setTrustedOrigin("otp-a", "https://bank.example")
        val deliver = requireNotNull(OtpAutofill.createDelivery("otp-a", { selected }) { code, origin ->
            filled = "$origin|$code"
        })
        selected = "otp-b"
        deliver("123456")
        assertEquals("", filled)
        selected = "otp-a"
        for (url in listOf("https://evil.example", "http://bank.example", "https://bank.example:8443", "about:blank")) {
            BridgeGuard.setTrustedOrigin("otp-a", url)
            deliver("123456")
            assertEquals(url, "", filled)
        }
        BridgeGuard.setTrustedOrigin("otp-a", "https://bank.example:443/verify")
        deliver("123456")
        assertEquals("https://bank.example|123456", filled)
    }

    @Test fun consentKeepsOriginalCallbackAndConsumesItOnce() {
        var time = 0L
        val state = OtpConsentDelivery { time }
        val received = mutableListOf<String>()
        assertFalse(state.beginConsent())
        assertTrue(state.arm { received += "first:$it" })
        assertFalse(state.arm { fail("Replaced listening destination") })
        assertTrue(state.beginConsent())
        assertFalse(state.beginConsent())
        assertFalse(state.arm { fail("Replaced consent destination") })
        state.finish("123456")
        state.finish("654321")
        assertEquals(listOf("first:123456"), received)
        assertTrue(state.arm { received += "second:$it" })
        assertTrue(state.beginConsent())
        time = 300_001L
        assertFalse(state.arm { fail("An expired but open dialog can still return") })
        state.finish("000000")
        assertEquals(1, received.size)
        assertTrue(state.arm { received += it })
        assertTrue(state.beginConsent())
        state.finish(null)
        assertEquals(1, received.size)
    }

    @Test fun timeoutAndDisposalDropPendingCodes() {
        var time = 0L
        val state = OtpConsentDelivery { time }
        state.arm { fail("Expired delivery") }
        time = 300_001L
        assertFalse(state.beginConsent())
        state.finish("123456")
        state.arm { fail("Disposed delivery") }
        state.beginConsent()
        state.clear()
        state.finish("123456")
    }

    @Test fun fillScriptChecksCanonicalOriginBeforeReadingFields() {
        val script = OtpAutofill.buildFillScript("123456", "https://bank.example:443")
        assertTrue(script.indexOf("if (location.origin !== \"https://bank.example\") return;") in
            0 until script.indexOf("function tangentBridge"))
        assertEquals("javascript:void(0)", OtpAutofill.buildFillScript("123456", "file:///private"))
    }
}

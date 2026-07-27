package com.firebolt9907.tangentbrowser.sync.webauthn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the bridge's one security-critical decision — [PasskeyBridge.effectiveRpId], which stops a
 * page claiming a credential scoped to another site — and the wire serialization the content script
 * depends on.
 */
class PasskeyBridgeTest {

    @Test fun rpId_defaults_to_origin_host() {
        assertEquals("example.com", PasskeyBridge.effectiveRpId("https://example.com", null))
        assertEquals("example.com", PasskeyBridge.effectiveRpId("https://example.com:443", null))
    }

    @Test fun rpId_may_be_a_registrable_parent() {
        assertEquals("example.com", PasskeyBridge.effectiveRpId("https://accounts.example.com", "example.com"))
        assertEquals("example.com", PasskeyBridge.effectiveRpId("https://example.com", "example.com"))
    }

    @Test fun rpId_cannot_claim_another_domain() {
        assertNull(PasskeyBridge.effectiveRpId("https://example.com", "evil.com"))
        // A sibling suffix that is not a parent (host is example.com, not *.evil-example.com).
        assertNull(PasskeyBridge.effectiveRpId("https://example.com", "notexample.com"))
        // rpId must be a suffix boundary: "ample.com" is a string-suffix but not a domain parent.
        assertNull(PasskeyBridge.effectiveRpId("https://example.com", "ample.com"))
    }

    @Test fun insecure_origins_are_rejected_except_localhost() {
        assertNull(PasskeyBridge.effectiveRpId("http://example.com", null))
        assertEquals("localhost", PasskeyBridge.effectiveRpId("http://localhost:8080", null))
        assertNull(PasskeyBridge.effectiveRpId("not a url", null))
    }

    @Test fun reply_serializes_assertion() {
        val json = PasskeyBridge.reply(
            PasskeyOutcome.Asserted(
                credentialId = byteArrayOf(1, 2, 3),
                authenticatorData = byteArrayOf(4, 5),
                signature = byteArrayOf(6),
                userHandle = null,
            ),
        )
        assertTrue(json.getBoolean("ok"))
        assertEquals("AQID", json.getString("credentialId")) // base64url of {1,2,3}
        assertTrue(json.isNull("userHandle"))
    }

    @Test fun reply_marks_fallback_and_failure() {
        val fallback = PasskeyBridge.reply(PasskeyOutcome.Fallback)
        assertFalse(fallback.getBoolean("ok"))
        assertTrue(fallback.getBoolean("fallback"))

        val failed = PasskeyBridge.reply(PasskeyOutcome.Failed("NotAllowedError", "cancelled"))
        assertFalse(failed.getBoolean("ok"))
        assertEquals("NotAllowedError", failed.getString("error"))
    }
}

package com.firebolt9907.tangentbrowser.sync.webauthn

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

    @Test fun public_suffix_and_private_tenant_boundaries_are_rejected() {
        for ((origin, rp) in listOf(
            "https://example.com" to "com",
            "https://example.co.uk" to "co.uk",
            "https://attacker.github.io" to "github.io",
            "https://attacker.blogspot.com" to "blogspot.com",
            "https://foo.example.invalid" to "example.invalid",
        )) assertNull("$origin must not claim $rp", PasskeyBridge.effectiveRpId(origin, rp))
        assertEquals("example.co.uk", PasskeyBridge.effectiveRpId("https://login.example.co.uk", "example.co.uk"))
        assertEquals("tenant.github.io", PasskeyBridge.effectiveRpId("https://login.tenant.github.io", "tenant.github.io"))
    }

    @Test fun malformed_origins_and_localhost_scheme_bypasses_are_rejected() {
        for (origin in listOf(
            "file://localhost", "ftp://localhost", "javascript://127.0.0.1",
            "https://user@example.com", "https://example.com/path",
            "https://example.com?query", "https://example.com#fragment", "https://example.com:65536",
        )) assertNull(origin, PasskeyBridge.effectiveRpId(origin, null))
        assertNull(PasskeyBridge.effectiveRpId("https://example.com", " example.com "))
        assertNull(PasskeyBridge.effectiveRpId("http://127.0.0.1", "0.0.1"))
    }

    @Test fun providers_receive_only_the_validated_rp_in_both_representations() = runBlocking {
        var get: PasskeyGetRequest? = null
        var create: PasskeyCreateRequest? = null
        val bridge = PasskeyBridge(this, object : PasskeyProvider {
            override suspend fun getAssertion(request: PasskeyGetRequest): PasskeyOutcome {
                get = request
                return PasskeyOutcome.Fallback
            }
            override suspend fun makeCredential(request: PasskeyCreateRequest): PasskeyOutcome {
                create = request
                return PasskeyOutcome.Fallback
            }
        })
        val message = JSONObject().put("origin", "https://login.example.com")
            .put("rpId", "example.com").put("clientDataJSON", "synthetic-client-data")
        bridge.handle(message.put("op", "get").put("requestJson", """{"rpId":"victim.com","challenge":"test"}"""))
        val receivedGet = requireNotNull(get)
        assertEquals("example.com", receivedGet.rpId)
        assertEquals("example.com", JSONObject(receivedGet.credentialManagerRequestJson!!).getString("rpId"))
        bridge.handle(message.put("op", "create").put("requestJson", """{"rp":{"id":"victim.com","name":"Test"}}"""))
        val receivedCreate = requireNotNull(create)
        assertEquals("example.com", receivedCreate.rpId)
        val rp = JSONObject(receivedCreate.credentialManagerRequestJson!!).getJSONObject("rp")
        assertEquals("example.com", rp.getString("id"))
        assertEquals("Test", rp.getString("name"))

        get = null
        create = null
        val denied = bridge.handle(message.put("rpId", "com"))
        assertFalse(denied.getBoolean("ok"))
        assertEquals("SecurityError", denied.getString("error"))
        assertNull(create)
        bridge.handle(message.put("op", "get"))
        assertNull(get)
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

    @Test fun reply_preserves_credential_manager_response_json() {
        val response = """{"id":"credential-id","type":"public-key"}"""
        val created = PasskeyBridge.reply(PasskeyOutcome.CredentialManagerCreated(response))
        val asserted = PasskeyBridge.reply(PasskeyOutcome.CredentialManagerAsserted(response))

        assertEquals(response, created.getString("credentialManagerResponse"))
        assertEquals(response, asserted.getString("credentialManagerResponse"))
    }
}

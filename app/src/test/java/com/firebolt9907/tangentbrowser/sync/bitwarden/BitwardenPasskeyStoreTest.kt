package com.firebolt9907.tangentbrowser.sync.bitwarden

import com.firebolt9907.tangentbrowser.sync.webauthn.WebAuthnAuthenticator
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end (minus the page bridge): mint a passkey with [WebAuthnAuthenticator], persist it as a
 * Bitwarden cipher via [BitwardenPasskeyStore.encodeNewCipher], read it back with [parseCiphers], and
 * prove the recovered private key still produces an assertion that verifies. This is the internal
 * round-trip; cross-client interop with desktop-Bitwarden-created passkeys needs on-device checks.
 */
class BitwardenPasskeyStoreTest {

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private fun publicKeyFromCose(cose: ByteArray): java.security.PublicKey {
        val x = cose.copyOfRange(10, 42)
        val y = cose.copyOfRange(45, 77)
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
        val ecSpec = params.getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, x), BigInteger(1, y))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, ecSpec))
    }

    private fun randomUserKey(): BitwardenCrypto.SymmetricKey {
        val bytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
        return BitwardenCrypto.SymmetricKey.fromBytes(bytes)
    }

    @Test fun encode_then_parse_roundtrips_and_assertion_verifies() {
        val rpId = "example.com"
        val userKey = randomUserKey()
        val store = BitwardenPasskeyStore(accountStore = null, userKeyProvider = { userKey })

        // 1. Mint a passkey (module 1) using a GUID's .NET-endian bytes as the credential id — the
        //    same thing the production provider does via BitwardenPasskeyStore.guidToCredentialIdBytes.
        val guid = java.util.UUID.randomUUID()
        val credIdBytes = BitwardenPasskeyStore.guidToCredentialIdBytes(guid)
        val reg = WebAuthnAuthenticator.makeCredential(rpId, credentialId = credIdBytes)

        // 2. Persist as a Bitwarden cipher. encodeNewCipher now stores the GUID whose bytes ARE
        //    credIdBytes (no manual overwrite needed), so the parse recovers the same RP-facing bytes.
        val cipher = store.encodeNewCipher(
            BitwardenPasskeyStore.NewPasskey(
                rpId = rpId,
                rpName = "Example",
                credentialIdBytes = credIdBytes,
                privateKeyPkcs8 = reg.privateKeyPkcs8,
                userHandle = byteArrayOf(1, 2, 3, 4),
                userName = "alice",
                userDisplayName = "Alice",
            ),
            userKey,
        )
        cipher.put("id", "cipher-1") // a synced cipher carries a server id (the create body doesn't)

        // 3. Read it back through the pure parse path.
        val candidates = store.parseCiphers(JSONArray().put(cipher), userKey, rpId, allowCredentialIdsB64Url = null)
        assertEquals(1, candidates.size)
        val c = candidates[0]
        assertEquals(rpId, c.rpId)
        assertEquals("alice", c.userName)
        assertTrue(credIdBytes.contentEquals(c.credentialIdBytes))

        // 4. The recovered key must still produce a verifiable assertion.
        val clientDataHash = sha256("""{"type":"webauthn.get"}""".toByteArray())
        val assertion = WebAuthnAuthenticator.getAssertion(rpId, c.privateKeyPkcs8, clientDataHash)
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(publicKeyFromCose(reg.publicKeyCose))
            update(assertion.authenticatorData + clientDataHash)
        }
        assertTrue("recovered vault key must sign a valid assertion", verifier.verify(assertion.signature))
    }

    @Test fun allowCredentials_filter_excludes_nonmatching() {
        val rpId = "example.com"
        val userKey = randomUserKey()
        val store = BitwardenPasskeyStore(accountStore = null, userKeyProvider = { userKey })
        val reg = WebAuthnAuthenticator.makeCredential(rpId)
        val cipher = store.encodeNewCipher(
            BitwardenPasskeyStore.NewPasskey(rpId, "Example", reg.credentialId, reg.privateKeyPkcs8, null, null, null),
            userKey,
        )
        cipher.put("id", "cipher-1")
        // A random allow-list id that won't match the cipher's minted credential id → filtered out.
        val bogus = BitwardenPasskeyStore.b64Url(ByteArray(16) { 9 })
        val filtered = store.parseCiphers(JSONArray().put(cipher), userKey, rpId, listOf(bogus))
        assertEquals(0, filtered.size)
    }
}

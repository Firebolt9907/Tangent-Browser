package com.firebolt9907.tangentbrowser.sync.bitwarden

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BitwardenCryptoTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** Anchor the KDF to a published PBKDF2-HMAC-SHA256 known-answer test (P="password", S="salt", c=1). */
    @Test fun pbkdf2_sha256_knownAnswer() {
        val out = BitwardenCrypto.pbkdf2Sha256(
            "password".toByteArray(Charsets.UTF_8),
            "salt".toByteArray(Charsets.UTF_8),
            iterations = 1,
            lengthBytes = 32,
        )
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b", hex(out))
    }

    @Test fun masterKey_isDeterministic_andEmailNormalized() {
        val kdf = BitwardenCrypto.KdfParams(BitwardenCrypto.KdfType.PBKDF2_SHA256, iterations = 5_000)
        val a = BitwardenCrypto.deriveMasterKey("hunter2", "User@Example.com ", kdf)
        val b = BitwardenCrypto.deriveMasterKey("hunter2", "user@example.com", kdf)
        assertEquals(32, a.size)
        assertEquals(hex(a), hex(b)) // email is trimmed + lowercased for the salt
    }

    @Test fun masterPasswordHash_is32ByteBase64() {
        val masterKey = ByteArray(32) { it.toByte() }
        val hash = BitwardenCrypto.masterPasswordHash(masterKey, "hunter2")
        // 32 bytes base64-encode to 44 chars ending in "=".
        assertEquals(44, hash.length)
        assertEquals(hash, BitwardenCrypto.masterPasswordHash(masterKey, "hunter2"))
    }

    @Test fun stretchMasterKey_splitsIntoDistinctEncAndMac() {
        val masterKey = ByteArray(32) { (it * 7).toByte() }
        val k = BitwardenCrypto.stretchMasterKey(masterKey)
        assertEquals(32, k.encKey.size)
        assertEquals(32, k.macKey.size)
        assertNotEquals(hex(k.encKey), hex(k.macKey))
        // Deterministic.
        assertEquals(hex(k.encKey), hex(BitwardenCrypto.stretchMasterKey(masterKey).encKey))
    }

    @Test fun encString_roundTrips() {
        val key = randomKey()
        val plaintext = "correct horse battery staple 🐴"
        val enc = BitwardenCrypto.encryptToString(plaintext, key)
        assertTrue("type-2 EncString", enc.startsWith("2."))
        assertEquals(plaintext, BitwardenCrypto.decryptToString(enc, key))
    }

    @Test fun decryptUserKey_recoversProtectedKey() {
        val stretched = randomKey()
        val userKey = randomKey()
        // Simulate the server's protected key: the 64-byte user key encrypted under the stretched key.
        val protectedKey = BitwardenCrypto.encryptBytes(userKey.toBytes(), stretched)
        val recovered = BitwardenCrypto.decryptUserKey(protectedKey, stretched)
        assertEquals(hex(userKey.toBytes()), hex(recovered.toBytes()))
    }

    @Test fun tamperedMac_isRejected() {
        val key = randomKey()
        val enc = BitwardenCrypto.encryptToString("secret", key)
        // Flip the last base64 char of the MAC segment.
        val tampered = enc.dropLast(1) + if (enc.last() == 'A') 'B' else 'A'
        assertThrows(SecurityException::class.java) {
            BitwardenCrypto.decryptToString(tampered, key)
        }
    }

    @Test fun decrypt_wrongKey_failsClosed() {
        val enc = BitwardenCrypto.encryptToString("secret", randomKey())
        // A different key should fail the MAC check (never silently return garbage).
        var threw = false
        try {
            BitwardenCrypto.decryptToString(enc, randomKey())
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test fun argon2id_derivesDeterministic32Bytes() {
        // Small params keep the test fast while exercising the Argon2id path.
        val kdf = BitwardenCrypto.KdfParams(
            BitwardenCrypto.KdfType.ARGON2ID,
            iterations = 1,
            memoryMib = 16,
            parallelism = 1,
        )
        val a = BitwardenCrypto.deriveMasterKey("hunter2", "user@example.com", kdf)
        val b = BitwardenCrypto.deriveMasterKey("hunter2", "user@example.com", kdf)
        assertEquals(32, a.size)
        assertEquals(hex(a), hex(b))
        // A different password yields a different key.
        assertFalse(hex(a) == hex(BitwardenCrypto.deriveMasterKey("hunter3", "user@example.com", kdf)))
    }

    private fun randomKey(): BitwardenCrypto.SymmetricKey {
        val rnd = SecureRandom()
        return BitwardenCrypto.SymmetricKey(
            ByteArray(32).also(rnd::nextBytes),
            ByteArray(32).also(rnd::nextBytes),
        )
    }
}

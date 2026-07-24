package com.firebolt9907.tangentbrowser.sync.zen

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal Firefox Sync (sync15) record crypto: AES-256-CBC + HMAC-SHA256 key bundles and the
 * EncryptedPayload envelope. Used to read custom Sync collections that app-services does not
 * expose (currently Zen's `workspaces` collection, see ZenWorkspacesSyncClient).
 */

/** AES-256-CBC encryption key + HMAC-SHA256 key pair for one Sync collection (or the root). */
class KeyBundle(val encryptionKey: ByteArray, val hmacKey: ByteArray) {
    init {
        require(encryptionKey.size == 32) { "encryption key must be 32 bytes" }
        require(hmacKey.size == 32) { "hmac key must be 32 bytes" }
    }

    companion object {
        /**
         * Builds the root key bundle from the FxA `oldsync` scoped key's `k` value. That key is
         * already the final 64-byte sync key (the kB→kSync HKDF happened server-side during the
         * scoped-key OAuth flow), so it is split in half — first 32 bytes AES, last 32 HMAC.
         * Do NOT re-derive with HKDF here; that would produce keys nothing else on the account
         * uses and every crypto/keys HMAC check would fail.
         */
        fun fromKSync(kBase64Url: String): KeyBundle {
            val bytes = Base64.getUrlDecoder().decode(kBase64Url)
            require(bytes.size == 64) { "kSync must be 64 bytes, got ${bytes.size}" }
            return KeyBundle(bytes.copyOfRange(0, 32), bytes.copyOfRange(32, 64))
        }

        fun fromBase64(encryptionKeyB64: String, hmacKeyB64: String): KeyBundle = KeyBundle(
            Base64.getDecoder().decode(encryptionKeyB64),
            Base64.getDecoder().decode(hmacKeyB64),
        )
    }
}

/**
 * The account's collection keys from the `crypto/keys` record: a default bundle plus optional
 * per-collection overrides.
 */
class CollectionKeys(
    val defaultBundle: KeyBundle,
    private val collections: Map<String, KeyBundle>,
) {
    fun forCollection(name: String): KeyBundle = collections[name] ?: defaultBundle

    companion object {
        /** Parses the decrypted cleartext of the `crypto/keys` record. */
        fun fromCryptoKeysCleartext(cleartext: String): CollectionKeys {
            val json = JSONObject(cleartext)
            val defaultPair = json.getJSONArray("default")
            val collections = mutableMapOf<String, KeyBundle>()
            json.optJSONObject("collections")?.let { obj ->
                for (name in obj.keys()) {
                    val pair = obj.getJSONArray(name)
                    collections[name] = KeyBundle.fromBase64(pair.getString(0), pair.getString(1))
                }
            }
            return CollectionKeys(
                defaultBundle = KeyBundle.fromBase64(defaultPair.getString(0), defaultPair.getString(1)),
                collections = collections,
            )
        }
    }
}

object Sync15Crypto {
    private val random = SecureRandom()

    /**
     * Verifies and decrypts a sync15 EncryptedPayload `{ciphertext, IV, hmac}`.
     *
     * Wire quirk that must not be "fixed": the HMAC is computed over the ASCII bytes of the
     * base64 ciphertext *string* (not the raw ciphertext bytes) and transmitted hex-encoded.
     * Every Sync client (desktop, app-services) does it this way.
     */
    fun decryptPayload(payload: JSONObject, keys: KeyBundle): String {
        val ciphertextB64 = payload.getString("ciphertext")
        val expected = hexToBytes(payload.getString("hmac"))
        val actual = hmacSha256(keys.hmacKey, ciphertextB64.toByteArray(Charsets.US_ASCII))
        if (!MessageDigest.isEqual(actual, expected)) {
            throw SecurityException("Sync record HMAC mismatch")
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keys.encryptionKey, "AES"),
            IvParameterSpec(Base64.getDecoder().decode(payload.getString("IV"))),
        )
        return String(cipher.doFinal(Base64.getDecoder().decode(ciphertextB64)), Charsets.UTF_8)
    }

    /** Inverse of [decryptPayload]; needed for round-trip tests and future write support. */
    fun encryptPayload(cleartext: String, keys: KeyBundle, iv: ByteArray = randomIv()): JSONObject {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keys.encryptionKey, "AES"),
            IvParameterSpec(iv),
        )
        val ciphertextB64 = Base64.getEncoder()
            .encodeToString(cipher.doFinal(cleartext.toByteArray(Charsets.UTF_8)))
        val hmac = hmacSha256(keys.hmacKey, ciphertextB64.toByteArray(Charsets.US_ASCII))
        return JSONObject()
            .put("ciphertext", ciphertextB64)
            .put("IV", Base64.getEncoder().encodeToString(iv))
            .put("hmac", bytesToHex(hmac))
    }

    internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun randomIv(): ByteArray = ByteArray(16).also(random::nextBytes)

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd-length hex string" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}

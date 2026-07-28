package com.firebolt9907.tangentbrowser.sync.bitwarden

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * Bitwarden client-side crypto — everything needed to authenticate and decrypt/encrypt a vault
 * locally, so the master password and derived keys never leave the device. Pure JVM (BouncyCastle
 * primitives + javax.crypto AES/HMAC), so it is unit-testable off-device like the zen sync crypto.
 *
 * Flow (Bitwarden security white paper):
 *  1. master key (32 B) = KDF(master password, email as salt)         [[BitwardenCrypto.deriveMasterKey]]
 *  2. master-password-hash (auth) = PBKDF2(masterKey, password, 1)    [[BitwardenCrypto.masterPasswordHash]]
 *  3. stretched master key (64 B) = HKDF-Expand(masterKey, "enc"/"mac")   → decrypts the protected key
 *  4. user symmetric key (64 B) = decrypt(profile.Key EncString, stretched key)
 *  5. every vault field is an EncString type 2 (AES-256-CBC + HMAC-SHA256) under the user key.
 */
object BitwardenCrypto {

    private val random = SecureRandom()

    /** KDF types as reported by the prelogin endpoint. */
    enum class KdfType(val id: Int) { PBKDF2_SHA256(0), ARGON2ID(1) }

    /** KDF parameters from `/accounts/prelogin`. Argon2 fields are null for PBKDF2. */
    data class KdfParams(
        val type: KdfType,
        val iterations: Int,
        val memoryMib: Int? = null,
        val parallelism: Int? = null,
    )

    /**
     * A symmetric key pair: 32-byte AES-256 encryption key + 32-byte HMAC-SHA256 authentication
     * key. Both the stretched master key and the user's vault key take this shape.
     */
    class SymmetricKey(val encKey: ByteArray, val macKey: ByteArray) {
        init {
            require(encKey.size == 32) { "enc key must be 32 bytes, got ${encKey.size}" }
            require(macKey.size == 32) { "mac key must be 32 bytes, got ${macKey.size}" }
        }

        /** enc(32) ‖ mac(64) as one 64-byte blob (how the user key is stored/sealed). */
        fun toBytes(): ByteArray = encKey + macKey

        companion object {
            /** Split a 64-byte blob into (enc, mac). */
            fun fromBytes(bytes: ByteArray): SymmetricKey {
                require(bytes.size == 64) { "symmetric key must be 64 bytes, got ${bytes.size}" }
                return SymmetricKey(bytes.copyOfRange(0, 32), bytes.copyOfRange(32, 64))
            }
        }
    }

    /** Step 1: derive the 32-byte master key from the master password + email salt. */
    fun deriveMasterKey(password: String, email: String, kdf: KdfParams): ByteArray {
        val normalizedEmail = email.trim().lowercase()
        val pwd = password.toByteArray(Charsets.UTF_8)
        return when (kdf.type) {
            KdfType.PBKDF2_SHA256 ->
                pbkdf2Sha256(pwd, normalizedEmail.toByteArray(Charsets.UTF_8), kdf.iterations, 32)

            KdfType.ARGON2ID -> {
                // Bitwarden salts Argon2 with SHA-256 of the email (PBKDF2 uses the raw email).
                val salt = sha256(normalizedEmail.toByteArray(Charsets.UTF_8))
                val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withIterations(kdf.iterations)
                    .withMemoryAsKB((kdf.memoryMib ?: 64) * 1024)
                    .withParallelism(kdf.parallelism ?: 4)
                    .withSalt(salt)
                    .build()
                val gen = Argon2BytesGenerator().apply { init(params) }
                ByteArray(32).also { gen.generateBytes(pwd, it) }
            }
        }
    }

    /**
     * Step 2: the base64 master-password-hash sent to the identity server for the password grant.
     * PBKDF2-SHA256 of the master key (as the "password" input) salted by the master password, 1
     * iteration.
     */
    fun masterPasswordHash(masterKey: ByteArray, password: String): String {
        val hash = pbkdf2Sha256(masterKey, password.toByteArray(Charsets.UTF_8), 1, 32)
        return Base64.getEncoder().encodeToString(hash)
    }

    /** Step 3: HKDF-Expand the master key into a 64-byte stretched key (enc "enc", mac "mac"). */
    fun stretchMasterKey(masterKey: ByteArray): SymmetricKey =
        SymmetricKey(
            encKey = hkdfExpand(masterKey, "enc", 32),
            macKey = hkdfExpand(masterKey, "mac", 32),
        )

    /**
     * Step 4: decrypt the account's protected symmetric key (profile `Key`) with the stretched
     * master key, yielding the 64-byte user vault key used for every cipher field.
     */
    fun decryptUserKey(protectedKey: String, stretched: SymmetricKey): SymmetricKey =
        SymmetricKey.fromBytes(decryptToBytes(protectedKey, stretched))

    /** Decrypt an EncString (type 2) to UTF-8 text. */
    fun decryptToString(encString: String, key: SymmetricKey): String =
        String(decryptToBytes(encString, key), Charsets.UTF_8)

    /**
     * Decrypt an EncString of the form `2.<iv_b64>|<ct_b64>|<mac_b64>` (AES-256-CBC + HMAC-SHA256).
     * Verifies the MAC over `iv ‖ ct` in constant time before decrypting; throws on tamper.
     */
    fun decryptToBytes(encString: String, key: SymmetricKey): ByteArray {
        val dot = encString.indexOf('.')
        require(dot == 1 && encString[0] == '2') { "unsupported EncString type: $encString" }
        val parts = encString.substring(dot + 1).split('|')
        require(parts.size == 3) { "malformed type-2 EncString" }
        val iv = Base64.getDecoder().decode(parts[0])
        val ct = Base64.getDecoder().decode(parts[1])
        val mac = Base64.getDecoder().decode(parts[2])

        val expectedMac = hmacSha256(key.macKey, iv + ct)
        if (!MessageDigest.isEqual(expectedMac, mac)) {
            throw SecurityException("Bitwarden EncString HMAC mismatch")
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.encKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ct)
    }

    /** Encrypt UTF-8 text into a type-2 EncString under [key] (for write-back). */
    fun encryptToString(plaintext: String, key: SymmetricKey): String =
        encryptBytes(plaintext.toByteArray(Charsets.UTF_8), key)

    /** Encrypt bytes into a `2.<iv>|<ct>|<mac>` EncString. */
    fun encryptBytes(plaintext: ByteArray, key: SymmetricKey, iv: ByteArray = randomIv()): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.encKey, "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(plaintext)
        val mac = hmacSha256(key.macKey, iv + ct)
        val enc = Base64.getEncoder()
        return "2.${enc.encodeToString(iv)}|${enc.encodeToString(ct)}|${enc.encodeToString(mac)}"
    }

    // --- primitives ---------------------------------------------------------

    /** PBKDF2-SHA256 via BouncyCastle so we can pass raw-byte passwords (masterKey isn't a char[]). */
    internal fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, lengthBytes: Int): ByteArray {
        val gen = PKCS5S2ParametersGenerator(SHA256Digest())
        gen.init(password, salt, iterations)
        return (gen.generateDerivedParameters(lengthBytes * 8) as KeyParameter).key
    }

    /** HKDF-Expand only (the master key is already a high-entropy PRK), RFC 5869 with SHA-256. */
    internal fun hkdfExpand(prk: ByteArray, info: String, lengthBytes: Int): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters.skipExtractParameters(prk, info.toByteArray(Charsets.UTF_8)))
        return ByteArray(lengthBytes).also { hkdf.generateBytes(it, 0, lengthBytes) }
    }

    internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun randomIv(): ByteArray = ByteArray(16).also(random::nextBytes)
}

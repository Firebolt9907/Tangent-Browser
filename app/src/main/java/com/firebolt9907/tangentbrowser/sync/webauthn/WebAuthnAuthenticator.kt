package com.firebolt9907.tangentbrowser.sync.webauthn

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/**
 * A software FIDO2/WebAuthn authenticator — the ceremony logic a platform authenticator normally
 * performs, done in-process so it works WITHOUT Tangent being on the platform trusted-browser
 * allowlist (see [[passkeys-blocked-on-gpm-allowlist]]). Vault-agnostic: it only turns key material
 * + ceremony inputs into the exact byte structures WebAuthn requires (authenticatorData, a "none"
 * attestationObject, and an ES256 DER assertion signature). The private keys themselves are supplied
 * and returned to the caller for persistence.
 *
 * Correctness notes (the two things a prior JS shim got wrong — AGENTS.md invariant #12):
 *  - Signatures are ES256 (ECDSA-P256-SHA256) in **ASN.1 DER**, which is what RPs verify. We sign via
 *    `java.security.Signature("SHA256withECDSA")`, whose output is already DER — no raw r‖s.
 *  - Real, persisted key material (from the vault), never faked/localStorage values.
 *
 * Only ES256 (COSE alg -7, the near-universal passkey algorithm) is implemented; callers should
 * advertise only -7 in `pubKeyCredParams` so an RP never expects an algorithm we can't service.
 */
object WebAuthnAuthenticator {

    /** COSE algorithm identifier for ECDSA w/ SHA-256 over P-256. */
    const val COSE_ALG_ES256 = -7

    private val random = SecureRandom()

    /** Authenticator flag bits (WebAuthn §6.1). */
    private const val FLAG_UP = 0x01 // user present
    private const val FLAG_UV = 0x04 // user verified
    private const val FLAG_AT = 0x40 // attested credential data included

    /**
     * Result of a registration (`navigator.credentials.create`). [privateKeyPkcs8] is the PKCS#8
     * private key the caller must persist (encrypted) against [credentialId]; everything else is
     * returned to the page.
     */
    data class MakeCredentialResult(
        val credentialId: ByteArray,
        val attestationObject: ByteArray,
        val privateKeyPkcs8: ByteArray,
        val publicKeyCose: ByteArray,
        /** The raw authenticatorData embedded in [attestationObject], exposed for `getAuthenticatorData()`. */
        val authenticatorData: ByteArray,
    )

    /** Result of an authentication (`navigator.credentials.get`). */
    data class GetAssertionResult(
        val authenticatorData: ByteArray,
        val signature: ByteArray,
    )

    /**
     * Create a new passkey: generate a P-256 key pair and build the `none`-attestation
     * attestationObject. [credentialId] may be supplied (e.g. to reuse a vault GUID's bytes) or left
     * null to mint 32 random bytes.
     */
    fun makeCredential(
        rpId: String,
        credentialId: ByteArray? = null,
        userVerified: Boolean = true,
        signCount: Int = 0,
    ): MakeCredentialResult {
        val credId = credentialId ?: ByteArray(32).also(random::nextBytes)
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pair = gen.generateKeyPair()
        val cose = coseKeyEs256(pair.public as ECPublicKey)

        val authData = buildAuthenticatorData(
            rpId = rpId,
            userVerified = userVerified,
            signCount = signCount,
            attestedCredential = AttestedCredential(credId, cose),
        )
        val attestationObject = cbor {
            beginMap(3)
            textKey("fmt"); text("none")
            textKey("attStmt"); beginMap(0)
            textKey("authData"); bytes(authData)
        }
        return MakeCredentialResult(credId, attestationObject, pair.private.encoded, cose, authData)
    }

    /**
     * Produce an assertion for an existing passkey. [clientDataHash] is SHA-256 of the page's
     * clientDataJSON (the caller builds clientDataJSON; the signature covers `authData ‖ hash`).
     */
    fun getAssertion(
        rpId: String,
        privateKeyPkcs8: ByteArray,
        clientDataHash: ByteArray,
        userVerified: Boolean = true,
        signCount: Int = 0,
    ): GetAssertionResult {
        val authData = buildAuthenticatorData(rpId, userVerified, signCount, attestedCredential = null)
        val signature = signEs256(privateKeyPkcs8, authData + clientDataHash)
        return GetAssertionResult(authData, signature)
    }

    // --- structure builders -------------------------------------------------

    private class AttestedCredential(val credentialId: ByteArray, val coseKey: ByteArray)

    private fun buildAuthenticatorData(
        rpId: String,
        userVerified: Boolean,
        signCount: Int,
        attestedCredential: AttestedCredential?,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(sha256(rpId.toByteArray(Charsets.UTF_8))) // rpIdHash (32)
        var flags = FLAG_UP
        if (userVerified) flags = flags or FLAG_UV
        if (attestedCredential != null) flags = flags or FLAG_AT
        out.write(flags)
        // signCount, 32-bit big-endian.
        out.write((signCount ushr 24) and 0xFF)
        out.write((signCount ushr 16) and 0xFF)
        out.write((signCount ushr 8) and 0xFF)
        out.write(signCount and 0xFF)
        if (attestedCredential != null) {
            out.write(ByteArray(16)) // AAGUID: all-zero (self/none attestation)
            val credId = attestedCredential.credentialId
            out.write((credId.size ushr 8) and 0xFF)
            out.write(credId.size and 0xFF)
            out.write(credId)
            out.write(attestedCredential.coseKey)
        }
        return out.toByteArray()
    }

    /** Encode an EC public key as a COSE_Key (EC2/P-256/ES256) map. */
    private fun coseKeyEs256(pub: ECPublicKey): ByteArray {
        val x = fixedLength(pub.w.affineX.toByteArray(), 32)
        val y = fixedLength(pub.w.affineY.toByteArray(), 32)
        return cbor {
            beginMap(5)
            intKey(1); int(2)              // kty: EC2
            intKey(3); int(COSE_ALG_ES256) // alg: ES256
            intKey(-1); int(1)             // crv: P-256
            intKey(-2); bytes(x)           // x
            intKey(-3); bytes(y)           // y
        }
    }

    private fun signEs256(privateKeyPkcs8: ByteArray, message: ByteArray): ByteArray {
        val key: PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8))
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(message)
            sign() // DER-encoded
        }
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /**
     * Left-pad/trim a big-endian integer's two's-complement bytes to exactly [length]. `BigInteger`
     * may emit a leading 0x00 sign byte (drop it) or fewer bytes than the field width (left-pad).
     */
    private fun fixedLength(bytes: ByteArray, length: Int): ByteArray = when {
        bytes.size == length -> bytes
        bytes.size == length + 1 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, bytes.size)
        bytes.size < length -> ByteArray(length).also { bytes.copyInto(it, length - bytes.size) }
        else -> bytes.copyOfRange(bytes.size - length, bytes.size)
    }

    // --- minimal deterministic CBOR encoder (RFC 8949 core types we need) ---

    private fun cbor(block: CborWriter.() -> Unit): ByteArray = CborWriter().apply(block).toByteArray()

    /**
     * Just enough CBOR to emit COSE keys and the attestationObject: unsigned/negative ints, byte
     * strings, text strings, and definite-length maps. Canonical enough for WebAuthn (RPs decode,
     * they don't require canonical ordering here; we still emit map entries in insertion order).
     */
    private class CborWriter {
        private val out = ByteArrayOutputStream()

        fun toByteArray(): ByteArray = out.toByteArray()

        fun beginMap(size: Int) = writeTypeAndLength(5, size.toLong())
        fun textKey(s: String) = text(s)
        fun intKey(v: Int) = int(v)

        fun int(v: Int) {
            if (v >= 0) writeTypeAndLength(0, v.toLong())
            else writeTypeAndLength(1, (-1 - v).toLong()) // major 1: -1 - n
        }

        fun bytes(b: ByteArray) {
            writeTypeAndLength(2, b.size.toLong())
            out.write(b)
        }

        fun text(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            writeTypeAndLength(3, b.size.toLong())
            out.write(b)
        }

        private fun writeTypeAndLength(major: Int, length: Long) {
            val mt = major shl 5
            when {
                length < 24 -> out.write(mt or length.toInt())
                length < 0x100 -> { out.write(mt or 24); out.write(length.toInt()) }
                length < 0x10000 -> {
                    out.write(mt or 25)
                    out.write((length ushr 8).toInt() and 0xFF)
                    out.write(length.toInt() and 0xFF)
                }
                else -> {
                    out.write(mt or 26)
                    out.write((length ushr 24).toInt() and 0xFF)
                    out.write((length ushr 16).toInt() and 0xFF)
                    out.write((length ushr 8).toInt() and 0xFF)
                    out.write(length.toInt() and 0xFF)
                }
            }
        }
    }
}

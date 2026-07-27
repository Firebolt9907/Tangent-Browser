package com.firebolt9907.tangentbrowser.sync.bitwarden

import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * The FIDO2 passkey half of the Bitwarden vault: reads `login.fido2Credentials` off cached ciphers
 * (for `navigator.credentials.get`) and encodes new ones (for `create`). Split out from
 * [BitwardenLoginsStore] because passkeys are a different cipher sub-structure with their own
 * encrypted fields, and only this path needs the raw private-key material.
 *
 * All fields are Bitwarden EncStrings; each cipher may carry its own `Key` (a 64-byte cipher key
 * wrapped under the user key) — when present every field of that cipher is encrypted under it, not the
 * user key directly ([cipherKey]). The actual crypto (sign/keygen) is [com.firebolt9907.tangentbrowser.sync.webauthn.WebAuthnAuthenticator];
 * this class only moves key material in and out of the vault.
 *
 * Schema/interop notes (Bitwarden's fields have drifted across versions; parse leniently):
 *  - `credentialId` decrypts to either a GUID string (older/.NET clients) or base64url raw bytes. The
 *    16 raw bytes are what the RP stored, so [credentialIdBytes] reproduces .NET's mixed-endian
 *    `Guid.ToByteArray()` for the GUID form (else base64url-decodes).
 *  - `keyValue` is the PKCS#8 private key, base64 (url or standard) — [decodeB64] tries both.
 */
class BitwardenPasskeyStore(
    // Nullable only so the pure-parse core ([parseCiphers]) is unit-testable without AndroidKeyStore;
    // production always supplies it (via BitwardenController).
    private val accountStore: BitwardenAccountStore?,
    private val userKeyProvider: () -> BitwardenCrypto.SymmetricKey?,
) {

    /** A decrypted passkey ready to sign with, plus the cipher it lives on (for counter write-back). */
    data class Candidate(
        val cipherId: String,
        val credentialIdBytes: ByteArray,
        val privateKeyPkcs8: ByteArray,
        val rpId: String,
        val userHandle: ByteArray?,
        val userName: String?,
        val userDisplayName: String?,
        val counter: Int,
    ) {
        val credentialIdB64Url: String get() = b64Url(credentialIdBytes)
    }

    /** Everything needed to persist a freshly-created passkey as a Bitwarden login cipher. */
    data class NewPasskey(
        val rpId: String,
        val rpName: String?,
        val credentialIdBytes: ByteArray,
        val privateKeyPkcs8: ByteArray,
        val userHandle: ByteArray?,
        val userName: String?,
        val userDisplayName: String?,
    )

    /**
     * All vault passkeys for [rpId], optionally filtered to a set of base64url credential ids
     * (the RP's `allowCredentials`). Empty when locked or none match.
     */
    fun findForRp(rpId: String, allowCredentialIdsB64Url: List<String>? = null): List<Candidate> {
        val userKey = userKeyProvider() ?: return emptyList()
        val ciphers = accountStore?.loadVaultCache()?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: return emptyList()
        return parseCiphers(ciphers, userKey, rpId, allowCredentialIdsB64Url)
    }

    /** Pure parse over a cipher array (no Android/keystore) — the unit-testable core of [findForRp]. */
    internal fun parseCiphers(
        ciphers: JSONArray,
        userKey: BitwardenCrypto.SymmetricKey,
        rpId: String,
        allowCredentialIdsB64Url: List<String>?,
    ): List<Candidate> {
        val allow = allowCredentialIdsB64Url?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val out = ArrayList<Candidate>()
        for (i in 0 until ciphers.length()) {
            val cipher = ciphers.optJSONObject(i) ?: continue
            runCatching { parseCipher(cipher, userKey, rpId, allow, out) }
        }
        return out
    }

    private fun parseCipher(
        cipher: JSONObject,
        userKey: BitwardenCrypto.SymmetricKey,
        rpId: String,
        allow: Set<String>?,
        out: MutableList<Candidate>,
    ) {
        val id = cipher.optStringCi("Id") ?: return
        val login = cipher.optObjectCi("Login") ?: return
        val creds = (login.getCi("Fido2Credentials", "fido2Credentials") as? JSONArray) ?: return
        val key = cipherKey(cipher, userKey)
        for (j in 0 until creds.length()) {
            val cred = creds.optJSONObject(j) ?: continue
            runCatching {
                val credRpId = dec(cred, key, "RpId") ?: return@runCatching
                if (!credRpId.equals(rpId, ignoreCase = true)) return@runCatching
                val credIdStr = dec(cred, key, "CredentialId") ?: return@runCatching
                val credIdBytes = credentialIdBytes(credIdStr)
                if (allow != null && b64Url(credIdBytes) !in allow) return@runCatching
                val pkcs8 = dec(cred, key, "KeyValue")?.let { decodeB64(it) } ?: return@runCatching
                out.add(
                    Candidate(
                        cipherId = id,
                        credentialIdBytes = credIdBytes,
                        privateKeyPkcs8 = pkcs8,
                        rpId = credRpId,
                        userHandle = dec(cred, key, "UserHandle")?.let { decodeB64(it) },
                        userName = dec(cred, key, "UserName"),
                        userDisplayName = dec(cred, key, "UserDisplayName", "UserName"),
                        counter = dec(cred, key, "Counter")?.trim()?.toIntOrNull() ?: 0,
                    ),
                )
            }
        }
    }

    /**
     * Build the request body for a new login cipher carrying one passkey, encrypting every field
     * under a fresh per-cipher key (Bitwarden's modern layout). Returns the JSON to POST to
     * `/ciphers` — [BitwardenApi.createCipher].
     */
    fun encodeNewCipher(passkey: NewPasskey, userKey: BitwardenCrypto.SymmetricKey): JSONObject {
        // Fresh 64-byte cipher key, wrapped under the user key.
        val cipherKeyBytes = ByteArray(64).also { java.security.SecureRandom().nextBytes(it) }
        val cipherKey = BitwardenCrypto.SymmetricKey.fromBytes(cipherKeyBytes)
        val wrappedKey = BitwardenCrypto.encryptBytes(cipherKeyBytes, userKey)
        fun e(v: String) = BitwardenCrypto.encryptToString(v, cipherKey)

        val fido2 = JSONObject()
            .put("credentialId", e(credentialIdToStored(passkey.credentialIdBytes)))
            .put("keyType", e("public-key"))
            .put("keyAlgorithm", e("ECDSA"))
            .put("keyCurve", e("P-256"))
            .put("keyValue", e(b64Url(passkey.privateKeyPkcs8)))
            .put("rpId", e(passkey.rpId))
            .put("rpName", e(passkey.rpName ?: passkey.rpId))
            .put("counter", e("0"))
            .put("discoverable", e("true"))
            .put("userHandle", passkey.userHandle?.let { e(b64Url(it)) } ?: JSONObject.NULL)
            .put("userName", passkey.userName?.let { e(it) } ?: JSONObject.NULL)
            .put("userDisplayName", passkey.userDisplayName?.let { e(it) } ?: JSONObject.NULL)
            .put("creationDate", java.time.Instant.now().toString())
        // credentialId is stored as the GUID string whose .NET-endian bytes ARE passkey.credentialIdBytes
        // (credentialIdToStored is the exact inverse of credentialIdBytes), so a later get() — from us or
        // any Bitwarden client — recovers the same RP-facing 16 bytes. Non-GUID ids fall back to base64url.
        val loginObj = JSONObject()
            .put("username", passkey.userName?.let { e(it) } ?: JSONObject.NULL)
            .put("password", JSONObject.NULL)
            .put("uris", JSONArray().put(JSONObject().put("uri", e("https://${passkey.rpId}")).put("match", JSONObject.NULL)))
            .put("fido2Credentials", JSONArray().put(fido2))
        return JSONObject()
            .put("type", 1)
            .put("key", wrappedKey)
            .put("name", e(passkey.rpName ?: passkey.rpId))
            .put("notes", JSONObject.NULL)
            .put("favorite", false)
            .put("login", loginObj)
    }

    // --- helpers ------------------------------------------------------------

    private fun cipherKey(cipher: JSONObject, userKey: BitwardenCrypto.SymmetricKey): BitwardenCrypto.SymmetricKey {
        val wrapped = cipher.optStringCi("Key") ?: return userKey
        return runCatching {
            BitwardenCrypto.SymmetricKey.fromBytes(BitwardenCrypto.decryptToBytes(wrapped, userKey))
        }.getOrDefault(userKey)
    }

    private fun dec(obj: JSONObject, key: BitwardenCrypto.SymmetricKey, vararg names: String): String? {
        val enc = obj.optStringCi(*names) ?: return null
        return runCatching { BitwardenCrypto.decryptToString(enc, key) }.getOrNull()
    }

    /**
     * The RP-facing 16-byte credential id. Bitwarden stores it as a GUID string; the on-the-wire
     * bytes are .NET's `Guid.ToByteArray()` (first three groups little-endian). Non-GUID strings are
     * treated as base64url of the raw bytes.
     */
    private fun credentialIdBytes(stored: String): ByteArray {
        val uuid = runCatching { UUID.fromString(stored.trim()) }.getOrNull()
            ?: return decodeB64(stored)
        return guidToCredentialIdBytes(uuid)
    }

    /**
     * The GUID string to persist for [credIdBytes] — the exact inverse of [credentialIdBytes]. 16-byte
     * ids are re-assembled into their .NET GUID string (reversing the mixed-endian byte order); any
     * other length is stored as base64url (the parse path decodes it back).
     */
    private fun credentialIdToStored(credIdBytes: ByteArray): String {
        if (credIdBytes.size != 16) return b64Url(credIdBytes)
        val n = credIdBytes
        val big = byteArrayOf(
            n[3], n[2], n[1], n[0],
            n[5], n[4],
            n[7], n[6],
            n[8], n[9], n[10], n[11], n[12], n[13], n[14], n[15],
        )
        val bb = ByteBuffer.wrap(big)
        return UUID(bb.long, bb.long).toString()
    }

    private fun decodeB64(s: String): ByteArray {
        val t = s.trim()
        return runCatching { Base64.getUrlDecoder().decode(t.replace('+', '-').replace('/', '_').trimEnd('=')) }
            .recoverCatching { Base64.getDecoder().decode(t) }
            .getOrThrow()
    }

    companion object {
        fun b64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        /**
         * The RP-facing 16 credential-id bytes for a GUID: .NET's `Guid.ToByteArray()` (first three
         * groups little-endian). The provider mints a fresh GUID and uses this for both the page's
         * credential id and (via [credentialIdToStored]) the vault's stored string.
         */
        fun guidToCredentialIdBytes(uuid: UUID): ByteArray {
            val big = ByteBuffer.allocate(16)
                .putLong(uuid.mostSignificantBits)
                .putLong(uuid.leastSignificantBits)
                .array()
            return byteArrayOf(
                big[3], big[2], big[1], big[0],
                big[5], big[4],
                big[7], big[6],
                big[8], big[9], big[10], big[11], big[12], big[13], big[14], big[15],
            )
        }
    }
}

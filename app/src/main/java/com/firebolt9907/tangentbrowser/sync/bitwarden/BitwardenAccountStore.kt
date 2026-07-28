package com.firebolt9907.tangentbrowser.sync.bitwarden

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Persists the Bitwarden account: non-secret metadata (region, email, KDF params, device id) in
 * plain SharedPreferences, and the sensitive material (access/refresh tokens + the decrypted user
 * vault key) sealed under an AndroidKeyStore AES-GCM wrapping key that never leaves the keystore —
 * the exact at-rest pattern used for the logins DB key in [com.firebolt9907.tangentbrowser.sync.PlacesLoginsStorage].
 *
 * The master password is never stored. On a cold start the account is "locked": the sealed secrets
 * exist on disk but the caller re-loads the user key only after a biometric unlock.
 */
class BitwardenAccountStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val isConnected: Boolean get() = prefs.getString(KEY_EMAIL, null) != null && prefs.contains(KEY_SEALED_CT)

    /** A stable per-install device id the identity server ties the session to. */
    val deviceIdentifier: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_DEVICE_ID, it) }
        }

    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)
        private set(value) { prefs.edit { putString(KEY_EMAIL, value) } }

    val region: BitwardenRegion
        get() = runCatching { BitwardenRegion.valueOf(prefs.getString(KEY_REGION, null) ?: "") }
            .getOrDefault(BitwardenRegion.US)

    val selfHostedBaseUrl: String? get() = prefs.getString(KEY_SELF_HOST, null)

    val authMethod: BitwardenAuthMethod
        get() = runCatching { BitwardenAuthMethod.valueOf(prefs.getString(KEY_AUTH_METHOD, null) ?: "") }
            .getOrDefault(BitwardenAuthMethod.PASSWORD)

    val endpoints: BitwardenEndpoints get() = BitwardenEndpoints.forRegion(region, selfHostedBaseUrl)

    val kdfParams: BitwardenCrypto.KdfParams
        get() = BitwardenCrypto.KdfParams(
            type = if (prefs.getInt(KEY_KDF_TYPE, 0) == 1) BitwardenCrypto.KdfType.ARGON2ID
            else BitwardenCrypto.KdfType.PBKDF2_SHA256,
            iterations = prefs.getInt(KEY_KDF_ITERS, 600_000),
            memoryMib = prefs.getInt(KEY_KDF_MEM, -1).takeIf { it > 0 },
            parallelism = prefs.getInt(KEY_KDF_PARALLEL, -1).takeIf { it > 0 },
        )

    /** Persist the non-secret account metadata (called on a successful connect). */
    fun saveMetadata(
        email: String,
        region: BitwardenRegion,
        selfHostedBaseUrl: String?,
        authMethod: BitwardenAuthMethod,
        kdf: BitwardenCrypto.KdfParams,
    ) {
        prefs.edit {
            putString(KEY_EMAIL, email.trim().lowercase())
            putString(KEY_REGION, region.name)
            putString(KEY_SELF_HOST, selfHostedBaseUrl)
            putString(KEY_AUTH_METHOD, authMethod.name)
            putInt(KEY_KDF_TYPE, kdf.type.id)
            putInt(KEY_KDF_ITERS, kdf.iterations)
            putInt(KEY_KDF_MEM, kdf.memoryMib ?: -1)
            putInt(KEY_KDF_PARALLEL, kdf.parallelism ?: -1)
        }
    }

    /**
     * The decrypted, in-use secrets. [userKey] is null while locked. For the API-key method the
     * long-lived [clientId]/[clientSecret] are sealed too so the session can be silently renewed
     * (client_credentials issues no refresh token).
     */
    data class Secrets(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtMs: Long,
        val userKey: BitwardenCrypto.SymmetricKey?,
        val clientId: String? = null,
        val clientSecret: String? = null,
    )

    /** Seal tokens + (optionally) the user key and API-key credentials. */
    fun saveSecrets(secrets: Secrets) {
        val json = JSONObject()
            .put("access", secrets.accessToken)
            .put("refresh", secrets.refreshToken ?: JSONObject.NULL)
            .put("expires", secrets.expiresAtMs)
            .put("userKey", secrets.userKey?.let { Base64.getEncoder().encodeToString(it.toBytes()) } ?: JSONObject.NULL)
            .put("clientId", secrets.clientId ?: JSONObject.NULL)
            .put("clientSecret", secrets.clientSecret ?: JSONObject.NULL)
        val (iv, ct) = seal(json.toString().toByteArray(Charsets.UTF_8))
        prefs.edit {
            putString(KEY_SEALED_IV, Base64.getEncoder().encodeToString(iv))
            putString(KEY_SEALED_CT, Base64.getEncoder().encodeToString(ct))
        }
    }

    /** Load and unseal the stored secrets, or null if none / unsealable. */
    fun loadSecrets(): Secrets? {
        val iv = prefs.getString(KEY_SEALED_IV, null) ?: return null
        val ct = prefs.getString(KEY_SEALED_CT, null) ?: return null
        val plain = runCatching {
            unseal(Base64.getDecoder().decode(iv), Base64.getDecoder().decode(ct))
        }.getOrNull() ?: return null
        val json = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return null
        val userKeyB64 = json.optStringCi("userKey")
        return Secrets(
            accessToken = json.getString("access"),
            refreshToken = json.optStringCi("refresh"),
            expiresAtMs = json.optLong("expires", 0L),
            userKey = userKeyB64?.let { BitwardenCrypto.SymmetricKey.fromBytes(Base64.getDecoder().decode(it)) },
            clientId = json.optStringCi("clientId"),
            clientSecret = json.optStringCi("clientSecret"),
        )
    }

    /** Seal the raw (already Bitwarden-encrypted) login-cipher array for offline unlock. */
    fun saveVaultCache(loginCiphersJson: String) {
        val (iv, ct) = seal(loginCiphersJson.toByteArray(Charsets.UTF_8))
        prefs.edit {
            putString(KEY_CACHE_IV, Base64.getEncoder().encodeToString(iv))
            putString(KEY_CACHE_CT, Base64.getEncoder().encodeToString(ct))
        }
    }

    fun loadVaultCache(): String? {
        val iv = prefs.getString(KEY_CACHE_IV, null) ?: return null
        val ct = prefs.getString(KEY_CACHE_CT, null) ?: return null
        return runCatching {
            String(unseal(Base64.getDecoder().decode(iv), Base64.getDecoder().decode(ct)), Charsets.UTF_8)
        }.getOrNull()
    }

    /** Forget everything (disconnect). */
    fun clear() {
        prefs.edit { clear() }
    }

    // --- AndroidKeyStore sealing (mirrors PlacesLoginsStorage.wrap/unwrapDbKey) ------------------

    private fun seal(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        return cipher.iv to cipher.doFinal(plain)
    }

    private fun unseal(iv: ByteArray, ct: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    private fun wrapKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(WRAP_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAP_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "tangent_bitwarden"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val WRAP_KEY_ALIAS = "tangent_bitwarden_key_wrap"
        const val GCM_TAG_BITS = 128

        const val KEY_EMAIL = "email"
        const val KEY_REGION = "region"
        const val KEY_SELF_HOST = "self_host"
        const val KEY_AUTH_METHOD = "auth_method"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_KDF_TYPE = "kdf_type"
        const val KEY_KDF_ITERS = "kdf_iters"
        const val KEY_KDF_MEM = "kdf_mem"
        const val KEY_KDF_PARALLEL = "kdf_parallel"
        const val KEY_SEALED_IV = "sealed_iv"
        const val KEY_SEALED_CT = "sealed_ct"
        const val KEY_CACHE_IV = "cache_iv"
        const val KEY_CACHE_CT = "cache_ct"
    }
}

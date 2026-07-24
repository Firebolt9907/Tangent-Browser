package com.firebolt9907.tangentbrowser.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.appservices.logins.DatabaseLoginsStorage
import mozilla.appservices.logins.KeyManager
import mozilla.components.concept.storage.Login
import mozilla.components.concept.storage.LoginEntry
import mozilla.components.concept.storage.LoginsStorage
import mozilla.components.concept.sync.SyncableStore

// AndroidKeyStore AES-GCM key that *wraps* (encrypts) the appservices JWK DB key; the wrapping key
// itself never leaves the keystore.
private const val WRAP_KEY_ALIAS = "tangent_logins_key_wrap"
private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

private const val KEY_PREFS = "tangent_logins_key"
private const val PREF_DB_KEY_IV = "db_key_iv"
private const val PREF_DB_KEY_CT = "db_key_ct"
private const val GCM_TAG_BITS = 128

class PlacesLoginsStorage(
    context: Context,
) : LoginsStorage, SyncableStore {

    private val appContext = context.applicationContext

    private val dbPath = context.getDatabasePath("tangent_logins.db").apply {
        parentFile?.mkdirs()
    }.absolutePath

    // The encryption key appservices uses for the logins DB. Resolved EAGERLY here — and, crucially,
    // BEFORE [nativeStorage] opens the DB file — so that when a fresh key has to be minted we can
    // delete any stale, now-undecryptable DB before the store gets a handle on it. getKey() then
    // just hands back these cached bytes (the appservices encryptor may call it on a sync thread).
    private val encryptionKey: ByteArray = getOrCreateEncryptionKey()

    private val keyManager = object : KeyManager {
        override fun getKey(): ByteArray = encryptionKey
    }

    private val nativeStorage = DatabaseLoginsStorage(dbPath, keyManager)

    // Provides the key appservices uses to encrypt/decrypt the logins SQLite DB.
    //
    // IMPORTANT — key FORMAT: appservices' `ManagedEncryptorDecryptor` does
    // `std::str::from_utf8(get_key())` and parses the result as a **JWK** via
    // `jwcrypto::EncryptorDecryptor::new(key)`. So `getKey()` must return the UTF-8 bytes of a key
    // string produced by appservices' own `createKey()` (a `{"kty":"oct",...}` JWK). It must NOT be
    // 32 raw random bytes — those fail the UTF-8/JWK parse with `InvalidKey`, which made every
    // encrypt/decrypt (local add AND every incoming synced record) throw, so the store stayed
    // permanently empty. That was the bug behind "no passwords ever show up".
    //
    // The JWK is kept at rest sealed under an AndroidKeyStore AES-GCM wrapping key (the wrapping key
    // itself never leaves the keystore); only the encrypted JWK lands in SharedPreferences.
    //
    // Synchronized so a concurrent first-run can't race two threads into minting / persisting two
    // different keys, which would make data encrypted with the first unreadable.
    @Synchronized
    private fun getOrCreateEncryptionKey(): ByteArray {
        val prefs = appContext.getSharedPreferences(KEY_PREFS, Context.MODE_PRIVATE)
        val storedIv = prefs.getString(PREF_DB_KEY_IV, null)
        val storedCt = prefs.getString(PREF_DB_KEY_CT, null)
        if (storedIv != null && storedCt != null) {
            val unwrapped = runCatching {
                unwrapDbKey(Base64.getDecoder().decode(storedIv), Base64.getDecoder().decode(storedCt))
            }.getOrNull()
            // Accept the stored key only if it's a well-formed appservices JWK. Earlier builds
            // persisted 32 raw random bytes here (never a valid key); treat those — and any blob the
            // keystore can no longer unwrap — as absent and regenerate below.
            if (unwrapped != null && looksLikeJwkKey(unwrapped)) return unwrapped
        }

        // First run, a legacy raw-byte key, or a blob the keystore wrapping key can no longer
        // unwrap (e.g. it was invalidated). Any logins DB on disk was written under a key we can no
        // longer produce — or under the old broken key that never actually encrypted anything — so
        // it can't be read with the new key. Drop it so the store starts clean and re-syncs from the
        // account, then mint and persist a fresh appservices JWK.
        wipeDatabaseFiles()
        val keyBytes = mozilla.appservices.logins.createKey().toByteArray(Charsets.UTF_8)
        val (iv, ct) = wrapDbKey(keyBytes)
        prefs.edit()
            .putString(PREF_DB_KEY_IV, Base64.getEncoder().encodeToString(iv))
            .putString(PREF_DB_KEY_CT, Base64.getEncoder().encodeToString(ct))
            .apply()
        return keyBytes
    }

    /** A createKey() JWK is JSON — a quick shape check to reject legacy raw-byte keys. */
    private fun looksLikeJwkKey(bytes: ByteArray): Boolean =
        runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            ?.trimStart()?.startsWith("{") == true

    /** Delete the logins SQLite DB (and its WAL/SHM sidecars) — used when the key is regenerated. */
    private fun wipeDatabaseFiles() {
        runCatching {
            listOf(dbPath, "$dbPath-wal", "$dbPath-shm").forEach { java.io.File(it).delete() }
        }
    }

    /** The AndroidKeyStore AES-GCM key that seals the DB key; created on first use. */
    private fun getOrCreateWrapKey(): SecretKey {
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

    /** Seal [dbKey] under the keystore wrapping key; returns (keystore-generated IV, ciphertext+tag). */
    private fun wrapDbKey(dbKey: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey())
        val ct = cipher.doFinal(dbKey)
        return cipher.iv to ct
    }

    private fun unwrapDbKey(iv: ByteArray, ct: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrapKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    // The appservices DatabaseLoginsStorage calls are blocking (synchronous SQLite + per-row
    // decryption), so every suspend method dispatches to IO to honor the LoginsStorage contract
    // that callers (e.g. PasswordsSheet's LaunchedEffect on the main thread) can rely on being
    // main-safe.
    override suspend fun list(): List<Login> = withContext(Dispatchers.IO) {
        nativeStorage.list().map { it.toConceptLogin() }
    }

    override suspend fun get(id: String): Login? = withContext(Dispatchers.IO) {
        nativeStorage.get(id)?.toConceptLogin()
    }

    override suspend fun getByBaseDomain(domain: String): List<Login> = withContext(Dispatchers.IO) {
        nativeStorage.getByBaseDomain(domain).map { it.toConceptLogin() }
    }

    override suspend fun add(entry: LoginEntry): Login = withContext(Dispatchers.IO) {
        nativeStorage.add(entry.toAppservicesEntry()).toConceptLogin()
    }

    override suspend fun update(id: String, entry: LoginEntry): Login = withContext(Dispatchers.IO) {
        nativeStorage.update(id, entry.toAppservicesEntry()).toConceptLogin()
    }

    override suspend fun addOrUpdate(entry: LoginEntry): Login = withContext(Dispatchers.IO) {
        nativeStorage.addOrUpdate(entry.toAppservicesEntry()).toConceptLogin()
    }

    override suspend fun findLoginToUpdate(entry: LoginEntry): Login? = withContext(Dispatchers.IO) {
        nativeStorage.findLoginToUpdate(entry.toAppservicesEntry())?.toConceptLogin()
    }

    override suspend fun touch(id: String) = withContext(Dispatchers.IO) {
        nativeStorage.touch(id)
    }

    override suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        nativeStorage.delete(id)
    }

    override suspend fun wipeLocal() = withContext(Dispatchers.IO) {
        nativeStorage.wipeLocal()
    }

    override fun close() = nativeStorage.close()

    override fun registerWithSyncManager() =
        nativeStorage.registerWithSyncManager()

    private fun mozilla.appservices.logins.Login.toConceptLogin(): Login =
        Login(
            guid = id,
            username = username,
            password = password,
            origin = origin,
            formActionOrigin = formActionOrigin,
            httpRealm = httpRealm,
            usernameField = usernameField,
            passwordField = passwordField,
            timesUsed = timesUsed,
            timeCreated = timeCreated,
            timeLastUsed = timeLastUsed,
            timePasswordChanged = timePasswordChanged,
        )

    private fun LoginEntry.toAppservicesEntry(): mozilla.appservices.logins.LoginEntry =
        mozilla.appservices.logins.LoginEntry(
            origin = origin,
            httpRealm = httpRealm,
            formActionOrigin = formActionOrigin,
            usernameField = usernameField,
            passwordField = passwordField,
            password = password,
            username = username,
        )
}

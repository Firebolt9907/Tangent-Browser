package com.firebolt9907.luminabrowser.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
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

// Legacy alias: earlier builds generated an AndroidKeyStore AES key here and used its raw `.encoded`
// bytes as the DB key. Kept only so the migration path can reuse those bytes on any device where
// they were somehow non-null (see getOrCreateEncryptionKey).
private const val KEYSTORE_ALIAS = "lumina_logins_encryption_key"
// AndroidKeyStore AES-GCM key that *wraps* (encrypts) the DB key; the wrapping key itself never
// leaves the keystore.
private const val WRAP_KEY_ALIAS = "lumina_logins_key_wrap"
private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

private const val KEY_PREFS = "lumina_logins_key"
private const val PREF_DB_KEY_IV = "db_key_iv"
private const val PREF_DB_KEY_CT = "db_key_ct"
private const val DB_KEY_SIZE_BYTES = 32
private const val GCM_TAG_BITS = 128

class PlacesLoginsStorage(
    context: Context,
) : LoginsStorage, SyncableStore {

    private val appContext = context.applicationContext

    private val dbPath = context.getDatabasePath("lumina_logins.db").apply {
        parentFile?.mkdirs()
    }.absolutePath

    private val keyManager = object : KeyManager {
        override fun getKey(): ByteArray = getOrCreateEncryptionKey()
    }

    private val nativeStorage = DatabaseLoginsStorage(dbPath, keyManager)

    // Provides the 256-bit key that appservices uses to encrypt the logins SQLite DB.
    //
    // The previous implementation generated an AES key *inside* AndroidKeyStore and returned its
    // raw `.encoded` bytes. AndroidKeyStore secret keys are non-exportable, so `.encoded` is `null`
    // on essentially every device — the DB key was effectively null and the store never actually
    // protected anything. AndroidKeyStore is meant to *use* keys via a Cipher, not hand out their
    // bytes, so instead we keep a random DB key of our own and seal it with a keystore AES-GCM key:
    //   1. Migration: if the old alias ever yielded usable raw bytes on this device, keep using them
    //      so that device's existing encrypted DB stays readable (no data loss).
    //   2. Otherwise load — or first-run create + persist — a random 256-bit DB key, stored as
    //      GCM(IV‖ciphertext) sealed under the keystore wrapping key. Only the encrypted DB key
    //      lands in SharedPreferences; the wrapping key never leaves the keystore.
    //
    // Synchronized so a concurrent first-run getKey() can't race two threads into generating /
    // persisting two different DB keys, which would make data encrypted with the first unreadable.
    @Synchronized
    private fun getOrCreateEncryptionKey(): ByteArray {
        migrateLegacyRawKey()?.let { return it }

        val prefs = appContext.getSharedPreferences(KEY_PREFS, Context.MODE_PRIVATE)
        val storedIv = prefs.getString(PREF_DB_KEY_IV, null)
        val storedCt = prefs.getString(PREF_DB_KEY_CT, null)
        if (storedIv != null && storedCt != null) {
            runCatching {
                unwrapDbKey(Base64.getDecoder().decode(storedIv), Base64.getDecoder().decode(storedCt))
            }.getOrNull()?.let { return it }
            // Stored blob won't unwrap (e.g. the keystore wrapping key was invalidated). Fall through
            // and regenerate: a store we can no longer unlock is already unusable.
        }

        val dbKey = ByteArray(DB_KEY_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        val (iv, ct) = wrapDbKey(dbKey)
        prefs.edit()
            .putString(PREF_DB_KEY_IV, Base64.getEncoder().encodeToString(iv))
            .putString(PREF_DB_KEY_CT, Base64.getEncoder().encodeToString(ct))
            .apply()
        return dbKey
    }

    /** Legacy raw-key bytes from [KEYSTORE_ALIAS], or null if absent/non-exportable (the usual case). */
    private fun migrateLegacyRawKey(): ByteArray? = runCatching {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.encoded
            ?.takeIf { it.size == DB_KEY_SIZE_BYTES }
    }.getOrNull()

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

package com.firebolt9907.luminabrowser.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.appservices.logins.DatabaseLoginsStorage
import mozilla.appservices.logins.KeyManager
import mozilla.components.concept.storage.Login
import mozilla.components.concept.storage.LoginEntry
import mozilla.components.concept.storage.LoginsStorage
import mozilla.components.concept.sync.SyncableStore

private const val KEYSTORE_ALIAS = "lumina_logins_encryption_key"
private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

class PlacesLoginsStorage(
    context: Context,
) : LoginsStorage, SyncableStore {

    private val dbPath = context.getDatabasePath("lumina_logins.db").apply {
        parentFile?.mkdirs()
    }.absolutePath

    private val keyManager = object : KeyManager {
        override fun getKey(): ByteArray = getOrCreateEncryptionKey()
    }

    private val nativeStorage = DatabaseLoginsStorage(dbPath, keyManager)

    // Synchronized so a concurrent first-run getKey() can't race two threads past the "key absent"
    // check and generate the AES key twice under the same alias — the second generateKey() would
    // overwrite the first, making any data already encrypted with it permanently unreadable.
    @Synchronized
    private fun getOrCreateEncryptionKey(): ByteArray {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)
        keyStore.getKey(KEYSTORE_ALIAS, null)?.let { return it.encoded }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey().encoded
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

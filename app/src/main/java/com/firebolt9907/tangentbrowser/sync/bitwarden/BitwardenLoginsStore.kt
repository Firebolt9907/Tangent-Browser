package com.firebolt9907.tangentbrowser.sync.bitwarden

import mozilla.components.concept.storage.Login
import mozilla.components.concept.storage.LoginEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Bitwarden vault as a read+write source of [Login]s. Not a [mozilla.components.concept.storage.LoginsStorage]
 * itself — it is one half of [com.firebolt9907.tangentbrowser.sync.MergedLoginsStorage], which unifies it with the
 * Firefox/local store. Login GUIDs here are the raw Bitwarden cipher ids (the merged store adds the
 * `bw:` prefix for routing).
 *
 * Holds decrypted logins in memory only while [unlock]ed; the raw (still Bitwarden-encrypted)
 * ciphers are cached sealed on disk by [BitwardenAccountStore] so the vault can be re-opened offline
 * after a restart. All crypto is local — the user key never leaves the device.
 */
class BitwardenLoginsStore(
    private val api: BitwardenApi,
    private val accountStore: BitwardenAccountStore,
) {
    @Volatile private var userKey: BitwardenCrypto.SymmetricKey? = null
    @Volatile private var logins: List<Login> = emptyList()

    val isUnlocked: Boolean get() = userKey != null

    /** The in-memory user key while unlocked (null when locked) — the passkey path needs it to
     *  decrypt `fido2Credentials` and wrap a new cipher key. */
    val currentUserKey: BitwardenCrypto.SymmetricKey? get() = userKey

    /** Load the user key and decrypt any cached ciphers into memory. */
    @Synchronized
    fun unlock(key: BitwardenCrypto.SymmetricKey) {
        userKey = key
        accountStore.loadVaultCache()?.let { cached ->
            logins = decryptCiphers(runCatching { JSONArray(cached) }.getOrNull(), key)
        }
    }

    /** Drop the key + decrypted logins from memory (the sealed cache stays on disk). */
    @Synchronized
    fun lock() {
        userKey = null
        logins = emptyList()
    }

    fun list(): List<Login> = logins

    fun get(id: String): Login? = logins.firstOrNull { it.guid == id }

    fun getByBaseDomain(domain: String): List<Login> {
        val target = domain.lowercase().removePrefix("www.")
        return logins.filter { hostMatches(hostOf(it.origin), target) }
    }

    /** Pull `/sync`, refresh the sealed cache, and re-decrypt into memory. Requires [isUnlocked]. */
    suspend fun sync(accessToken: String) {
        val key = userKey ?: return
        val data = api.sync(accountStore.endpoints, accessToken)
        val array = JSONArray().apply { data.loginCiphers.forEach { put(it) } }
        accountStore.saveVaultCache(array.toString())
        logins = decryptCiphers(array, key)
    }

    /** Encrypt and POST a new login cipher; returns the resulting [Login] (with server id). */
    suspend fun add(accessToken: String, entry: LoginEntry): Login {
        val key = userKey ?: error("vault locked")
        val id = api.createCipher(accountStore.endpoints, accessToken, encodeCipher(entry, key))
        val login = entry.toLogin(id)
        logins = logins + login
        return login
    }

    /** Encrypt and PUT an existing cipher in place. */
    suspend fun update(accessToken: String, id: String, entry: LoginEntry): Login {
        val key = userKey ?: error("vault locked")
        api.updateCipher(accountStore.endpoints, accessToken, id, encodeCipher(entry, key))
        val login = entry.toLogin(id)
        logins = logins.map { if (it.guid == id) login else it }
        return login
    }

    suspend fun delete(accessToken: String, id: String): Boolean {
        if (logins.none { it.guid == id }) return false
        api.deleteCipher(accountStore.endpoints, accessToken, id)
        logins = logins.filterNot { it.guid == id }
        return true
    }

    // --- decode / encode ----------------------------------------------------

    private fun decryptCiphers(array: JSONArray?, key: BitwardenCrypto.SymmetricKey): List<Login> {
        if (array == null) return emptyList()
        val out = ArrayList<Login>(array.length())
        for (i in 0 until array.length()) {
            val cipher = array.optJSONObject(i) ?: continue
            runCatching { decodeCipher(cipher, key) }.getOrNull()?.let(out::add)
        }
        return out
    }

    private fun decodeCipher(cipher: JSONObject, key: BitwardenCrypto.SymmetricKey): Login? {
        val id = cipher.optStringCi("Id") ?: return null
        val login = cipher.optObjectCi("Login") ?: return null
        val username = login.optStringCi("Username")?.let { BitwardenCrypto.decryptToString(it, key) }.orEmpty()
        val password = login.optStringCi("Password")?.let { BitwardenCrypto.decryptToString(it, key) }.orEmpty()
        val firstUri = (login.getCi("Uris") as? JSONArray)?.optJSONObject(0)?.optStringCi("Uri")
            ?.let { BitwardenCrypto.decryptToString(it, key) }
        val origin = normalizeOrigin(firstUri)
        // A vault item with neither a URL nor a username can't autofill anything meaningful; skip it.
        if (origin == null && username.isEmpty()) return null
        return Login(
            guid = id,
            username = username,
            password = password,
            origin = origin ?: "https://unknown.invalid",
            formActionOrigin = origin,
            httpRealm = null,
        )
    }

    private fun encodeCipher(entry: LoginEntry, key: BitwardenCrypto.SymmetricKey): JSONObject {
        fun enc(v: String) = BitwardenCrypto.encryptToString(v, key)
        val host = hostOf(entry.origin).ifBlank { entry.origin }
        val loginObj = JSONObject()
            .put("username", enc(entry.username))
            .put("password", enc(entry.password))
            .put("uris", JSONArray().put(JSONObject().put("uri", enc(entry.origin)).put("match", JSONObject.NULL)))
        return JSONObject()
            .put("type", 1)
            .put("name", enc(host))
            .put("notes", JSONObject.NULL)
            .put("favorite", false)
            .put("login", loginObj)
    }

    private fun LoginEntry.toLogin(id: String) = Login(
        guid = id,
        username = username,
        password = password,
        origin = origin,
        formActionOrigin = formActionOrigin ?: origin,
        httpRealm = httpRealm,
    )

    private fun normalizeOrigin(uri: String?): String? {
        val raw = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        val host = runCatching { java.net.URI(withScheme).host }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return null
        val scheme = runCatching { java.net.URI(withScheme).scheme }.getOrNull() ?: "https"
        return "$scheme://$host"
    }

    private fun hostOf(origin: String): String =
        runCatching { java.net.URI(origin).host }.getOrNull()?.lowercase()?.removePrefix("www.")
            ?: origin.lowercase()

    private fun hostMatches(host: String, target: String): Boolean =
        host == target || host.endsWith(".$target") || target.endsWith(".$host")
}

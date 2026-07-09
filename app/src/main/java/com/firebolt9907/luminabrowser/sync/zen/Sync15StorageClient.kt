package com.firebolt9907.luminabrowser.sync.zen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Request
import mozilla.components.concept.sync.OAuthAccount
import mozilla.components.service.fxa.manager.SCOPE_SYNC
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit

/** One decrypted BSO from a Sync collection. `modified` is server time in epoch seconds. */
data class Sync15Record(
    val id: String,
    val modified: Double,
    val cleartext: JSONObject,
)

private data class StorageCredentials(
    val hawkId: String,
    val hawkKey: String,
    val apiEndpoint: String,
    val rootKeys: KeyBundle,
    val expiresAtMs: Long,
)

private class SyncHttpException(val status: Int, message: String) : Exception(message)

/**
 * Read-only Firefox Sync storage client for collections app-services does not expose.
 *
 * Handshake: FxA access token + scoped key for the `oldsync` scope → tokenserver (Bearer +
 * X-KeyID) → Hawk credentials for the storage node → fetch and decrypt `crypto/keys` with the
 * root bundle → fetch and decrypt collection records with the collection's key bundle.
 *
 * Credentials and collection keys are cached until the tokenserver-provided duration lapses;
 * a 401 from the storage node invalidates the cache and the request is retried once (node
 * reassignment / token expiry are routine for Sync storage).
 */
class Sync15StorageClient(private val httpClient: Client) {
    @Volatile private var credentials: StorageCredentials? = null
    @Volatile private var collectionKeys: CollectionKeys? = null

    /**
     * Fetches every non-tombstone record in [collection], decrypted. Returns an empty list when
     * the collection does not exist on the server yet (404) — normal for a collection no client
     * on the account has written.
     */
    suspend fun fetchCollection(account: OAuthAccount, collection: String): List<Sync15Record> =
        withContext(Dispatchers.IO) {
            try {
                fetchCollectionOnce(account, collection)
            } catch (e: SyncHttpException) {
                if (e.status != 401) throw e
                credentials = null
                collectionKeys = null
                fetchCollectionOnce(account, collection)
            }
        }

    private suspend fun fetchCollectionOnce(
        account: OAuthAccount,
        collection: String,
    ): List<Sync15Record> {
        val creds = validCredentials() ?: handshake(account).also {
            credentials = it
            collectionKeys = null
        }
        val keys = collectionKeys ?: fetchCryptoKeys(creds).also { collectionKeys = it }
        val body = storageGet(creds, "/storage/$collection?full=1")
            ?: return emptyList() // 404: collection never written
        val bundle = keys.forCollection(collection)
        val bsos = JSONArray(body)
        val records = mutableListOf<Sync15Record>()
        for (i in 0 until bsos.length()) {
            val bso = bsos.getJSONObject(i)
            val payload = JSONObject(bso.getString("payload"))
            val cleartext = JSONObject(Sync15Crypto.decryptPayload(payload, bundle))
            if (cleartext.optBoolean("deleted")) continue
            records += Sync15Record(
                id = bso.getString("id"),
                modified = bso.optDouble("modified", 0.0),
                cleartext = cleartext,
            )
        }
        return records
    }

    private fun validCredentials(): StorageCredentials? =
        credentials?.takeIf { it.expiresAtMs > System.currentTimeMillis() }

    private suspend fun handshake(account: OAuthAccount): StorageCredentials {
        val tokenInfo = account.getAccessToken(SCOPE_SYNC)
            ?: throw IllegalStateException("No sync access token available")
        val scopedKey = tokenInfo.key
            ?: throw IllegalStateException("Sync access token carries no scoped key")
        val tokenServerUrl = account.getTokenServerEndpointURL()
            ?: throw IllegalStateException("No tokenserver endpoint URL")

        val request = Request(
            url = tokenServerUrl,
            headers = MutableHeaders(
                "Authorization" to "Bearer ${tokenInfo.token}",
                "X-KeyID" to scopedKey.kid,
            ),
            connectTimeout = 10L to TimeUnit.SECONDS,
            readTimeout = 30L to TimeUnit.SECONDS,
            cookiePolicy = Request.CookiePolicy.OMIT,
        )
        val json = httpClient.fetch(request).use { response ->
            if (response.status !in 200..299) {
                throw SyncHttpException(response.status, "tokenserver returned ${response.status}")
            }
            JSONObject(response.body.string(Charsets.UTF_8))
        }
        val durationMs = TimeUnit.SECONDS.toMillis(json.optLong("duration", 300L))
        return StorageCredentials(
            hawkId = json.getString("id"),
            hawkKey = json.getString("key"),
            apiEndpoint = json.getString("api_endpoint").trimEnd('/'),
            rootKeys = KeyBundle.fromKSync(scopedKey.k),
            // 60s slack so we never sign a request with a token mid-expiry.
            expiresAtMs = System.currentTimeMillis() + durationMs - 60_000L,
        )
    }

    private fun fetchCryptoKeys(creds: StorageCredentials): CollectionKeys {
        val body = storageGet(creds, "/storage/crypto/keys")
            ?: throw IllegalStateException("Account has no crypto/keys record")
        val bso = JSONObject(body)
        val payload = JSONObject(bso.getString("payload"))
        val cleartext = Sync15Crypto.decryptPayload(payload, creds.rootKeys)
        return CollectionKeys.fromCryptoKeysCleartext(cleartext)
    }

    /** Hawk-authenticated GET against the storage node. Returns null on 404, body otherwise. */
    private fun storageGet(creds: StorageCredentials, path: String): String? {
        val url = URL(creds.apiEndpoint + path)
        val request = Request(
            url = url.toString(),
            headers = MutableHeaders(
                "Authorization" to HawkAuth.authorizationHeader(
                    id = creds.hawkId,
                    key = creds.hawkKey,
                    method = "GET",
                    url = url,
                ),
                "Accept" to "application/json",
            ),
            connectTimeout = 10L to TimeUnit.SECONDS,
            readTimeout = 30L to TimeUnit.SECONDS,
            cookiePolicy = Request.CookiePolicy.OMIT,
        )
        return httpClient.fetch(request).use { response ->
            when {
                response.status == 404 -> null
                response.status !in 200..299 ->
                    throw SyncHttpException(response.status, "storage GET $path returned ${response.status}")
                else -> response.body.string(Charsets.UTF_8)
            }
        }
    }
}

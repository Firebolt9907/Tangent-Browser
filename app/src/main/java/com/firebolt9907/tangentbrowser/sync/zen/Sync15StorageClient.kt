package com.firebolt9907.tangentbrowser.sync.zen

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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** One decrypted BSO from a Sync collection. `modified` is server time in epoch seconds. */
data class Sync15Record(
    val id: String,
    val modified: Double,
    val cleartext: JSONObject,
)

/** Sync 1.5 path appended to the FxA token server base to request storage-node Hawk credentials. */
private const val SYNC_TOKEN_PATH = "/1.0/sync/1.5"

private data class StorageCredentials(
    val hawkId: String,
    val hawkKey: String,
    val apiEndpoint: String,
    val rootKeys: KeyBundle,
    val expiresAtMs: Long,
)

private class SyncHttpException(val status: Int, message: String) : Exception(message)

/**
 * Firefox Sync storage client for collections app-services does not expose.
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
    @Volatile private var backoffUntilMs = 0L
    @Volatile private var credentials: StorageCredentials? = null
    @Volatile private var collectionKeys: CollectionKeys? = null

    /**
     * Fetches every record (including tombstones for conditional writes) in [collection], decrypted. Returns an empty list when
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
        val bundle = keys.forCollection(collection)
        val records = mutableListOf<Sync15Record>()
        var offset: String? = null
        val seenOffsets = mutableSetOf<String>()
        do {
            val query = "/storage/$collection?full=1" +
                (offset?.let { "&offset=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val page = storageGetPage(creds, query) ?: if (offset == null) {
                return emptyList()
            } else {
                throw IllegalStateException("Sync collection disappeared during pagination")
            }
            val bsos = JSONArray(page.body)
            for (i in 0 until bsos.length()) {
                val bso = bsos.getJSONObject(i)
                val payload = JSONObject(bso.getString("payload"))
                val cleartext = JSONObject(Sync15Crypto.decryptPayload(payload, bundle))
                records += Sync15Record(
                    id = bso.getString("id"),
                    modified = bso.optDouble("modified", 0.0),
                    cleartext = cleartext,
                )
            }
            offset = page.nextOffset
            check(offset == null || seenOffsets.add(offset)) { "Repeated Sync pagination offset" }
        } while (offset != null)
        return records
    }

    /** Require Zen's existing engine registration; never bootstrap or overwrite meta/global. */
    suspend fun requireZenSpacesV3(account: OAuthAccount) = withContext(Dispatchers.IO) {
        val creds = validCredentials() ?: handshake(account).also {
            credentials = it
            collectionKeys = null
        }
        val raw = storageGet(creds, "/storage/meta/global")
            ?: error("Enable Spaces sync in Zen 1.22.1b and sync it once first.")
        val metadata = JSONObject(JSONObject(raw).getString("payload"))
        check(metadata.optJSONObject("engines")?.optJSONObject("spaces")?.optInt("version") == 3) {
            "Enable Spaces sync in Zen 1.22.1b and sync it once first."
        }
    }

    /** Encrypted, conditional single-record PUT; a conflict leaves the remote record untouched. */
    internal suspend fun putSpaceRecord(account: OAuthAccount, write: ZenPhoneWrite) = withContext(Dispatchers.IO) {
        suspend fun putOnce() {
            val creds = validCredentials() ?: handshake(account).also {
                credentials = it
                collectionKeys = null
            }
            val keys = collectionKeys ?: fetchCryptoKeys(creds).also { collectionKeys = it }
            require(write.id.matches(Regex("[A-Za-z0-9-]{1,64}")))
            val body = JSONObject().put("id", write.id).put("payload",
                Sync15Crypto.encryptPayload(write.cleartext.toString(), keys.forCollection("spaces")).toString(),
            ).toString()
            val url = URL(creds.apiEndpoint + "/storage/spaces/" + write.id)
            val request = Request(
                url = url.toString(), method = Request.Method.PUT,
                headers = MutableHeaders(
                    "Authorization" to HawkAuth.authorizationHeader(creds.hawkId, creds.hawkKey, "PUT", url),
                    "Content-Type" to "application/json",
                    "X-If-Unmodified-Since" to write.expectedModified.toString(),
                ),
                body = Request.Body.fromString(body),
                connectTimeout = 10L to TimeUnit.SECONDS, readTimeout = 30L to TimeUnit.SECONDS,
                cookiePolicy = Request.CookiePolicy.OMIT,
            )
            fetch(request).use { response ->
                if (response.status !in 200..299) {
                    throw SyncHttpException(response.status, "Zen phone space upload returned ${response.status}")
                }
            }
        }
        try {
            putOnce()
        } catch (error: SyncHttpException) {
            if (error.status != 401) throw error
            credentials = null
            collectionKeys = null
            putOnce()
        }
    }

    private fun fetch(request: Request): mozilla.components.concept.fetch.Response {
        check(System.currentTimeMillis() >= backoffUntilMs) { "Mozilla Sync asked us to wait before retrying." }
        val response = httpClient.fetch(request)
        val seconds = listOfNotNull(
            response.headers["X-Weave-Backoff"]?.toDoubleOrNull(),
            response.headers["Retry-After"]?.toDoubleOrNull(),
            if (response.status == 429 || response.status == 503) 60.0 else null,
        ).filter { it.isFinite() && it > 0 }.maxOrNull()
        if (seconds != null) backoffUntilMs = System.currentTimeMillis() + (seconds * 1000).toLong()
        return response
    }

    private fun validCredentials(): StorageCredentials? =
        credentials?.takeIf { it.expiresAtMs > System.currentTimeMillis() }

    private suspend fun handshake(account: OAuthAccount): StorageCredentials {
        val tokenInfo = account.getAccessToken(SCOPE_SYNC)
            ?: throw IllegalStateException("No sync access token available")
        val scopedKey = tokenInfo.key
            ?: throw IllegalStateException("Sync access token carries no scoped key")
        // getTokenServerEndpointURL() returns the token server *base* (e.g.
        // "https://token.services.mozilla.com/"); GETting that only serves the API docs HTML page.
        // Hawk credentials for a storage node come from the versioned Sync endpoint underneath it.
        val tokenServerUrl = account.getTokenServerEndpointURL()
            ?.let { base -> if (SYNC_TOKEN_PATH in base) base else base.trimEnd('/') + SYNC_TOKEN_PATH }
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
        val json = fetch(request).use { response ->
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
    private fun storageGet(creds: StorageCredentials, path: String): String? =
        storageGetPage(creds, path)?.body

    private data class StoragePage(val body: String, val nextOffset: String?)

    private fun storageGetPage(creds: StorageCredentials, path: String): StoragePage? {
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
        return fetch(request).use { response ->
            when {
                response.status == 404 -> null
                response.status !in 200..299 ->
                    throw SyncHttpException(response.status, "storage GET $path returned ${response.status}")
                else -> StoragePage(
                    response.body.string(Charsets.UTF_8),
                    response.headers["X-Weave-Next-Offset"]?.takeIf { it.isNotBlank() },
                )
            }
        }
    }
}

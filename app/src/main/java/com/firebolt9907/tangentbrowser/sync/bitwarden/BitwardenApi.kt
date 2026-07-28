package com.firebolt9907.tangentbrowser.sync.bitwarden

import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.Headers.Values.CONTENT_TYPE_APPLICATION_JSON
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin REST client for the Bitwarden identity + api servers. Talks the same `concept-fetch`
 * [Client] the zen sync client uses, and parses with `org.json` (responses vary between
 * Pascal/camel case across server versions — see [getCi]). Stateless: callers pass the resolved
 * [BitwardenEndpoints] and a valid access token.
 */
class BitwardenApi(private val httpClient: Client) {

    private companion object {
        const val DEVICE_TYPE_ANDROID = "0" // Bitwarden DeviceType.Android
        const val DEVICE_NAME = "android"
        const val CLIENT_ID_PASSWORD = "browser"
        val CONNECT = 10L to TimeUnit.SECONDS
        val READ = 30L to TimeUnit.SECONDS
    }

    /** `/accounts/prelogin` → the account's KDF configuration. */
    suspend fun prelogin(endpoints: BitwardenEndpoints, email: String): BitwardenCrypto.KdfParams =
        withContext(Dispatchers.IO) {
            val json = postJson(
                "${endpoints.identityUrl}/accounts/prelogin",
                JSONObject().put("email", email).toString(),
                headers = MutableHeaders(),
            )
            val kdfId = (json.getCi("kdf") as? Number)?.toInt() ?: 0
            BitwardenCrypto.KdfParams(
                type = if (kdfId == 1) BitwardenCrypto.KdfType.ARGON2ID else BitwardenCrypto.KdfType.PBKDF2_SHA256,
                iterations = (json.getCi("kdfIterations") as? Number)?.toInt() ?: 600_000,
                memoryMib = (json.getCi("kdfMemory") as? Number)?.toInt(),
                parallelism = (json.getCi("kdfParallelism") as? Number)?.toInt(),
            )
        }

    /**
     * Exchange credentials for an access token. Handles both grants; for the password grant it
     * classifies the two documented soft failures (2FA required, hCaptcha required) so the UI can
     * respond. [masterPasswordHash] is required for the password grant (never the raw password).
     */
    suspend fun token(
        endpoints: BitwardenEndpoints,
        credentials: BitwardenCredentials,
        masterPasswordHash: String?,
        deviceIdentifier: String,
    ): BitwardenTokenResult = withContext(Dispatchers.IO) {
        val params = buildList {
            add("scope" to "api offline_access")
            add("deviceType" to DEVICE_TYPE_ANDROID)
            add("deviceIdentifier" to deviceIdentifier)
            add("deviceName" to DEVICE_NAME)
            when (credentials.method) {
                BitwardenAuthMethod.PASSWORD -> {
                    add("grant_type" to "password")
                    add("client_id" to CLIENT_ID_PASSWORD)
                    add("username" to credentials.email.trim().lowercase())
                    add("password" to (masterPasswordHash ?: error("missing master password hash")))
                    credentials.twoFactorToken?.let { add("twoFactorToken" to it) }
                    credentials.twoFactorProvider?.let { add("twoFactorProvider" to it.toString()) }
                    if (credentials.twoFactorToken != null) add("twoFactorRemember" to "0")
                    credentials.captchaResponse?.let { add("captchaResponse" to it) }
                }
                BitwardenAuthMethod.API_KEY -> {
                    add("grant_type" to "client_credentials")
                    add("client_id" to (credentials.clientId ?: error("missing client_id")))
                    add("client_secret" to (credentials.clientSecret ?: error("missing client_secret")))
                }
            }
        }
        val headers = MutableHeaders(
            "Content-Type" to "application/x-www-form-urlencoded",
            "Device-Type" to DEVICE_TYPE_ANDROID,
        )
        if (credentials.method == BitwardenAuthMethod.PASSWORD) {
            headers.append("Auth-Email", base64Url(credentials.email.trim().lowercase()))
        }
        connectToken(endpoints, params, headers)
    }

    /** Silent re-auth with a stored refresh token (password grant only). */
    suspend fun refresh(endpoints: BitwardenEndpoints, refreshToken: String): BitwardenTokenResult =
        withContext(Dispatchers.IO) {
            connectToken(
                endpoints,
                listOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id" to CLIENT_ID_PASSWORD,
                ),
                MutableHeaders("Content-Type" to "application/x-www-form-urlencoded"),
            )
        }

    private fun connectToken(
        endpoints: BitwardenEndpoints,
        params: List<Pair<String, String>>,
        headers: MutableHeaders,
    ): BitwardenTokenResult {
        val request = Request(
            url = "${endpoints.identityUrl}/connect/token",
            method = Request.Method.POST,
            headers = headers,
            body = Request.Body.fromParamsForFormUrlEncoded(*params.toTypedArray()),
            connectTimeout = CONNECT,
            readTimeout = READ,
            cookiePolicy = Request.CookiePolicy.OMIT,
        )
        return httpClient.fetch(request).use { response ->
            val bodyText = response.body.string(Charsets.UTF_8)
            val json = runCatching { JSONObject(bodyText) }.getOrNull()
            if (response.status in 200..299 && json != null) {
                val expiresIn = (json.getCi("expires_in") as? Number)?.toLong() ?: 3600L
                BitwardenTokenResult.Success(
                    BitwardenToken(
                        accessToken = json.getString("access_token"),
                        refreshToken = json.optStringCi("refresh_token"),
                        expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L - 60_000L,
                        protectedKey = json.optStringCi("Key"),
                    ),
                )
            } else {
                classifyTokenError(response.status, json, bodyText)
            }
        }
    }

    private fun classifyTokenError(status: Int, json: JSONObject?, bodyText: String): BitwardenTokenResult {
        if (json != null) {
            // 2FA challenge.
            val providers = json.getCi("TwoFactorProviders") as? JSONArray
            val providers2 = json.optObjectCi("TwoFactorProviders2")
            if (providers != null || providers2 != null) {
                val ids = mutableSetOf<Int>()
                providers?.let { for (i in 0 until it.length()) it.optInt(i, -1).takeIf { n -> n >= 0 }?.let(ids::add) }
                providers2?.keys()?.forEach { k -> k.toIntOrNull()?.let(ids::add) }
                return BitwardenTokenResult.TwoFactorRequired(ids.sorted())
            }
            // hCaptcha challenge.
            val siteKey = json.optStringCi("HCaptcha_SiteKey")
            val desc = json.optStringCi("error_description").orEmpty()
            if (siteKey != null || desc.contains("captcha", ignoreCase = true)) {
                return BitwardenTokenResult.CaptchaRequired(siteKey)
            }
            val message = json.optStringCi("error_description")
                ?: json.optObjectCi("ErrorModel")?.optStringCi("Message")
                ?: json.optStringCi("error")
                ?: "Sign-in failed (HTTP $status)"
            return BitwardenTokenResult.Error(message)
        }
        return BitwardenTokenResult.Error("Sign-in failed (HTTP $status): ${bodyText.take(180)}")
    }

    /** `GET /sync` → the protected key plus every login-type cipher, still encrypted. */
    suspend fun sync(endpoints: BitwardenEndpoints, accessToken: String): BitwardenSyncData =
        withContext(Dispatchers.IO) {
            val request = Request(
                url = "${endpoints.apiUrl}/sync?excludeDomains=true",
                headers = MutableHeaders(
                    "Authorization" to "Bearer $accessToken",
                    "Accept" to CONTENT_TYPE_APPLICATION_JSON,
                ),
                connectTimeout = CONNECT,
                readTimeout = READ,
                cookiePolicy = Request.CookiePolicy.OMIT,
            )
            val json = httpClient.fetch(request).use { response ->
                if (response.status !in 200..299) {
                    throw BitwardenApiException(response.status, "sync returned ${response.status}")
                }
                JSONObject(response.body.string(Charsets.UTF_8))
            }
            val protectedKey = json.optObjectCi("Profile")?.optStringCi("Key")
            val ciphersJson = json.getCi("Ciphers") as? JSONArray ?: JSONArray()
            val logins = buildList {
                for (i in 0 until ciphersJson.length()) {
                    val cipher = ciphersJson.optJSONObject(i) ?: continue
                    // Type 1 == Login. Skip deleted (trashed) ciphers.
                    val type = (cipher.getCi("Type") as? Number)?.toInt()
                    val deleted = cipher.optStringCi("DeletedDate")
                    if (type == 1 && deleted == null) add(cipher)
                }
            }
            BitwardenSyncData(protectedKey, logins)
        }

    /** Create a new login cipher from an already-encrypted body; returns the new cipher id. */
    suspend fun createCipher(endpoints: BitwardenEndpoints, accessToken: String, body: JSONObject): String =
        withContext(Dispatchers.IO) {
            val json = postJson(
                "${endpoints.apiUrl}/ciphers",
                body.toString(),
                headers = MutableHeaders("Authorization" to "Bearer $accessToken"),
            )
            json.optStringCi("Id") ?: error("cipher create returned no id")
        }

    /** Update an existing login cipher in place. */
    suspend fun updateCipher(endpoints: BitwardenEndpoints, accessToken: String, id: String, body: JSONObject) =
        withContext(Dispatchers.IO) {
            sendJson(
                "${endpoints.apiUrl}/ciphers/$id",
                Request.Method.PUT,
                body.toString(),
                MutableHeaders("Authorization" to "Bearer $accessToken"),
            )
            Unit
        }

    /** Permanently delete a cipher. */
    suspend fun deleteCipher(endpoints: BitwardenEndpoints, accessToken: String, id: String) =
        withContext(Dispatchers.IO) {
            val request = Request(
                url = "${endpoints.apiUrl}/ciphers/$id",
                method = Request.Method.DELETE,
                headers = MutableHeaders("Authorization" to "Bearer $accessToken"),
                connectTimeout = CONNECT,
                readTimeout = READ,
                cookiePolicy = Request.CookiePolicy.OMIT,
            )
            httpClient.fetch(request).use { response ->
                if (response.status !in 200..299 && response.status != 404) {
                    throw BitwardenApiException(response.status, "delete returned ${response.status}")
                }
            }
            Unit
        }

    private fun postJson(url: String, body: String, headers: MutableHeaders): JSONObject =
        sendJson(url, Request.Method.POST, body, headers)

    private fun sendJson(url: String, method: Request.Method, body: String, headers: MutableHeaders): JSONObject {
        headers.append("Content-Type", CONTENT_TYPE_APPLICATION_JSON)
        headers.append("Accept", CONTENT_TYPE_APPLICATION_JSON)
        val request = Request(
            url = url,
            method = method,
            headers = headers,
            body = Request.Body.fromString(body),
            connectTimeout = CONNECT,
            readTimeout = READ,
            cookiePolicy = Request.CookiePolicy.OMIT,
        )
        return httpClient.fetch(request).use { response ->
            val text = response.body.string(Charsets.UTF_8)
            if (response.status !in 200..299) {
                throw BitwardenApiException(response.status, "$url returned ${response.status}: ${text.take(180)}")
            }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun base64Url(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
}

/** Non-auth HTTP failure from a Bitwarden endpoint. */
class BitwardenApiException(val status: Int, message: String) : Exception(message)

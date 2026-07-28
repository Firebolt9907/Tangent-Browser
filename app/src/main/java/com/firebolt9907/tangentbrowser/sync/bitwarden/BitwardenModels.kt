package com.firebolt9907.tangentbrowser.sync.bitwarden

import org.json.JSONObject

/**
 * Value types for the Bitwarden integration: server region/endpoints, credentials, token results,
 * and the small slice of the vault we care about (login ciphers). Kept free of Android/engine
 * dependencies so the API + store logic stays testable.
 */

/** Which Bitwarden server to talk to. [SELF_HOSTED] pairs with a user-supplied base URL. */
enum class BitwardenRegion { US, EU, SELF_HOSTED }

/** Resolved identity + api base URLs (no trailing slash). */
data class BitwardenEndpoints(val identityUrl: String, val apiUrl: String) {
    companion object {
        fun forRegion(region: BitwardenRegion, selfHostedBaseUrl: String? = null): BitwardenEndpoints =
            when (region) {
                BitwardenRegion.US -> BitwardenEndpoints(
                    "https://identity.bitwarden.com",
                    "https://api.bitwarden.com",
                )
                BitwardenRegion.EU -> BitwardenEndpoints(
                    "https://identity.bitwarden.eu",
                    "https://api.bitwarden.eu",
                )
                BitwardenRegion.SELF_HOSTED -> {
                    val base = (selfHostedBaseUrl ?: "").trim().trimEnd('/')
                    require(base.startsWith("http")) { "self-hosted base URL required" }
                    // Self-hosted stacks route identity + api under one origin.
                    BitwardenEndpoints("$base/identity", "$base/api")
                }
            }
    }
}

/** How the user authenticates. */
enum class BitwardenAuthMethod { PASSWORD, API_KEY }

/**
 * Everything needed to (re)connect an account. The master password is never persisted; it is held
 * only long enough to derive keys, then dropped.
 */
data class BitwardenCredentials(
    val method: BitwardenAuthMethod,
    val region: BitwardenRegion,
    val selfHostedBaseUrl: String? = null,
    val email: String,
    val masterPassword: String,
    // API-key method only:
    val clientId: String? = null,
    val clientSecret: String? = null,
    // 2FA (password method, second attempt):
    val twoFactorToken: String? = null,
    val twoFactorProvider: Int? = null,
    val captchaResponse: String? = null,
)

/** Successful token exchange. [protectedKey] is the account's encrypted symmetric key (profile Key). */
data class BitwardenToken(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
    val protectedKey: String?,
)

/** Outcome of a `connect/token` call. */
sealed class BitwardenTokenResult {
    data class Success(val token: BitwardenToken) : BitwardenTokenResult()

    /** Password grant needs a second factor; [providers] are the numeric provider ids offered. */
    data class TwoFactorRequired(val providers: List<Int>) : BitwardenTokenResult()

    /** Password grant is gated behind hCaptcha (the reason the API-key method exists). */
    data class CaptchaRequired(val siteKey: String?) : BitwardenTokenResult()

    data class Error(val message: String) : BitwardenTokenResult()
}

/** A decrypted login cipher — the subset of a Bitwarden vault item we expose as a [Login]. */
data class BitwardenLoginCipher(
    val id: String,
    val name: String,
    val username: String,
    val password: String,
    val uri: String?,
    val organizationId: String?,
    val revisionDate: String?,
)

/** Result of a `/sync`: the still-encrypted protected key plus raw login-cipher JSON to decrypt. */
data class BitwardenSyncData(
    val protectedKey: String?,
    val loginCiphers: List<JSONObject>,
)

/**
 * Case-insensitive object lookup. The Bitwarden sync/token responses have shifted between
 * PascalCase (legacy) and camelCase (newer) across server versions; read both.
 */
internal fun JSONObject.getCi(vararg keys: String): Any? {
    for (k in keys) {
        if (has(k)) return get(k)
    }
    // Fall back to a case-insensitive scan for the first requested key.
    val target = keys.firstOrNull()?.lowercase() ?: return null
    val it = keys()
    while (it.hasNext()) {
        val name = it.next()
        if (name.lowercase() == target) return get(name)
    }
    return null
}

internal fun JSONObject.optStringCi(vararg keys: String): String? =
    when (val v = getCi(*keys)) {
        null, JSONObject.NULL -> null
        else -> v.toString()
    }

internal fun JSONObject.optObjectCi(vararg keys: String): JSONObject? = getCi(*keys) as? JSONObject

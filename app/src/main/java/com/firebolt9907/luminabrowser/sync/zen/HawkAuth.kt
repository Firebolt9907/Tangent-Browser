package com.firebolt9907.luminabrowser.sync.zen

import java.net.URL
import java.security.SecureRandom
import java.util.Base64

/**
 * Hawk HTTP authentication (v1) header computation, as required by the Sync storage servers.
 * Only the request-signing subset the storage client needs: no payload hash (GET-only for now),
 * optional `ext`. Credentials come from the tokenserver handshake (Sync15StorageClient).
 */
object HawkAuth {
    private val random = SecureRandom()

    fun authorizationHeader(
        id: String,
        key: String,
        method: String,
        url: URL,
        tsSeconds: Long = System.currentTimeMillis() / 1000L,
        nonce: String = randomNonce(),
        ext: String = "",
    ): String {
        val mac = mac(key, method, url, tsSeconds, nonce, ext)
        val extPart = if (ext.isEmpty()) "" else ", ext=\"$ext\""
        return "Hawk id=\"$id\", ts=\"$tsSeconds\", nonce=\"$nonce\"$extPart, mac=\"$mac\""
    }

    /**
     * Base64 HMAC-SHA256 over the Hawk 1.0 normalized request string. The empty line before
     * `ext` is the (absent) payload hash; the trailing newline after `ext` is required.
     */
    internal fun mac(
        key: String,
        method: String,
        url: URL,
        tsSeconds: Long,
        nonce: String,
        ext: String = "",
    ): String {
        val port = when {
            url.port != -1 -> url.port
            url.protocol.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
        val resource = url.path + (url.query?.let { "?$it" } ?: "")
        val normalized = buildString {
            append("hawk.1.header\n")
            append(tsSeconds).append('\n')
            append(nonce).append('\n')
            append(method.uppercase()).append('\n')
            append(resource).append('\n')
            append(url.host.lowercase()).append('\n')
            append(port).append('\n')
            append('\n') // payload hash (none)
            append(ext).append('\n')
        }
        return Base64.getEncoder().encodeToString(
            Sync15Crypto.hmacSha256(
                key.toByteArray(Charsets.US_ASCII),
                normalized.toByteArray(Charsets.UTF_8),
            ),
        )
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(6).also(random::nextBytes)
        return Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }
}

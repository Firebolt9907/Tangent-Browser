package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.sync.zen.*
import kotlinx.coroutines.runBlocking
import mozilla.components.concept.fetch.*
import mozilla.components.concept.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Base64

/** Fake HTTP only: verifies the real token/key/encrypted-write path without account access. */
class ZenSpaceTransportTest {
    private val root = KeyBundle(ByteArray(32) { 1 }, ByteArray(32) { 1 })
    private val account = Proxy.newProxyInstance(OAuthAccount::class.java.classLoader,
        arrayOf(OAuthAccount::class.java)) { _, method, _ ->
        when (method.name) {
            "getAccessToken" -> AccessTokenInfo("oldsync", "fake-token",
                OAuthScopedKey("oct", "oldsync", "fake-kid", Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64) { 1 })),
                Long.MAX_VALUE)
            "getTokenServerEndpointURL" -> "https://token.example/"
            else -> error("Unexpected account method ${method.name}")
        }
    } as OAuthAccount

    private inner class FakeClient(var putStatus: Int = 200, var version: Int = 3) : Client() {
        val requests = mutableListOf<Request>()
        var uploaded: String? = null
        override fun fetch(request: Request): Response {
            requests += request
            var status = 200
            val body = when {
                request.method == Request.Method.PUT -> {
                    uploaded = request.body!!.useStream { it.bufferedReader().readText() }
                    status = putStatus
                    "42.0"
                }
                request.url.contains("token.example") -> """{"id":"hawk-id","key":"hawk-key","api_endpoint":"https://storage.example/1.5/123","duration":3600}"""
                request.url.endsWith("crypto/keys") -> {
                    val key = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
                    JSONObject().put("payload", Sync15Crypto.encryptPayload("""{"default":["$key","$key"]}""", root).toString()).toString()
                }
                request.url.endsWith("meta/global") -> JSONObject().put("payload", """{"engines":{"spaces":{"version":$version,"syncID":"test"}}}""").toString()
                else -> error("Unexpected URL ${request.url}")
            }
            return Response(request.url, status, MutableHeaders(), Response.Body(body.byteInputStream(), "application/json"))
        }
    }
    private val write = ZenPhoneWrite("phone-tab", JSONObject("""{"id":"phone-tab","kind":"tab","data":{"url":"https://private-payload.example/"}}"""), 12.5)

    @Test fun `uploads authenticated encrypted record with conflict precondition`() = runBlocking {
        val http = FakeClient()
        val client = Sync15StorageClient(http)
        client.requireZenSpacesV3(account)
        client.putSpaceRecord(account, write)
        val request = http.requests.last()
        assertEquals("https://storage.example/1.5/123/storage/spaces/phone-tab", request.url)
        assertEquals("12.5", request.headers!!["X-If-Unmodified-Since"])
        assertTrue(request.headers!!["Authorization"]!!.startsWith("Hawk "))
        assertEquals(Request.CookiePolicy.OMIT, request.cookiePolicy)
        assertFalse(http.uploaded!!.contains("private-payload"))
        val payload = JSONObject(JSONObject(http.uploaded!!).getString("payload"))
        assertEquals(write.cleartext.toString(), Sync15Crypto.decryptPayload(payload, root))
    }

    @Test fun `conflict is not retried as an unconditional overwrite`() = runBlocking {
        val http = FakeClient(putStatus = 412)
        val client = Sync15StorageClient(http)
        val result = runCatching { client.putSpaceRecord(account, write) }
        assertTrue(result.isFailure)
        assertEquals(1, http.requests.count { it.method == Request.Method.PUT })
    }

    @Test fun `unsupported engine fails before any upload`() = runBlocking {
        val http = FakeClient(version = 4)
        val client = Sync15StorageClient(http)
        assertTrue(runCatching { client.requireZenSpacesV3(account) }.isFailure)
        assertTrue(http.requests.none { it.method == Request.Method.PUT })
    }

    @Test fun `server backoff prevents immediate further requests`() = runBlocking {
        val http = FakeClient(putStatus = 503)
        val client = Sync15StorageClient(http)
        assertTrue(runCatching { client.putSpaceRecord(account, write) }.isFailure)
        val count = http.requests.size
        assertTrue(runCatching { client.putSpaceRecord(account, write) }.isFailure)
        assertEquals(count, http.requests.size)
    }
}

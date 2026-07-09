package com.firebolt9907.luminabrowser

import com.firebolt9907.luminabrowser.sync.zen.CollectionKeys
import com.firebolt9907.luminabrowser.sync.zen.HawkAuth
import com.firebolt9907.luminabrowser.sync.zen.KeyBundle
import com.firebolt9907.luminabrowser.sync.zen.Sync15Crypto
import com.firebolt9907.luminabrowser.sync.zen.Sync15Record
import com.firebolt9907.luminabrowser.sync.zen.ZenWorkspaceRecords
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL
import java.util.Base64

/**
 * Locks in the sync15/Zen-workspaces wire protocol. Zen's workspace engine is not in their
 * stable channel yet, so there is no live client to test against — these tests pin the
 * protocol-critical behaviors (Hawk MAC, the HMAC-over-base64-string quirk, kSync splitting,
 * Zen's exact record skip/ordering rules) against known vectors and the upstream source
 * (zen-browser/desktop src/zen/sync/ZenWorkspacesSync.sys.mjs).
 */
class ZenWorkspacesSyncTest {

    private fun testBundle(): KeyBundle = KeyBundle(ByteArray(32) { 1 }, ByteArray(32) { 2 })

    // --- Hawk ---

    @Test
    fun `hawk mac matches the published hawk 1_0 example vector`() {
        // From the Hawk README's "protocol example": GET example.com:8000/resource/1?b=1&a=2.
        val mac = HawkAuth.mac(
            key = "werxhqb98rpaxn39848xrunpaw3489ruxnpa98w4rxn",
            method = "GET",
            url = URL("http://example.com:8000/resource/1?b=1&a=2"),
            tsSeconds = 1353832234L,
            nonce = "j4h3g2",
            ext = "some-app-ext-data",
        )
        assertEquals("6R4rV5iE+NPoym+WwjeHzjAGXUtLNIxmo1vpMofpLAE=", mac)
    }

    @Test
    fun `hawk header carries id ts nonce and mac, omits empty ext`() {
        val header = HawkAuth.authorizationHeader(
            id = "someid",
            key = "somekey",
            method = "GET",
            url = URL("https://sync.example.com/1.5/12345/storage/workspaces?full=1"),
            tsSeconds = 1700000000L,
            nonce = "abcdef",
        )
        assertTrue(header.startsWith("Hawk id=\"someid\", ts=\"1700000000\", nonce=\"abcdef\", mac=\""))
        assertTrue(!header.contains("ext="))
    }

    @Test
    fun `hawk default port is 443 for https`() {
        // Same request with and without an explicit :443 must sign identically.
        val implicit = HawkAuth.mac("k", "GET", URL("https://host.example/1.5/1/storage/x"), 1L, "n")
        val explicit = HawkAuth.mac("k", "GET", URL("https://host.example:443/1.5/1/storage/x"), 1L, "n")
        assertEquals(explicit, implicit)
    }

    // --- Key bundles ---

    @Test
    fun `kSync scoped key is split in half, not re-derived`() {
        // 64 bytes: 0..31 must become the AES key, 32..63 the HMAC key, verbatim.
        val kSync = ByteArray(64) { it.toByte() }
        val bundle = KeyBundle.fromKSync(Base64.getUrlEncoder().withoutPadding().encodeToString(kSync))
        assertTrue(bundle.encryptionKey.contentEquals(kSync.copyOfRange(0, 32)))
        assertTrue(bundle.hmacKey.contentEquals(kSync.copyOfRange(32, 64)))
    }

    @Test
    fun `kSync of wrong length is rejected`() {
        val short = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
        assertThrows(IllegalArgumentException::class.java) { KeyBundle.fromKSync(short) }
    }

    // --- EncryptedPayload ---

    @Test
    fun `encrypt decrypt round trip preserves cleartext`() {
        val keys = testBundle()
        val cleartext = """{"id":"s~abc","type":"space","uuid":"abc","name":"Työ 🚀"}"""
        val payload = Sync15Crypto.encryptPayload(cleartext, keys)
        assertEquals(cleartext, Sync15Crypto.decryptPayload(payload, keys))
    }

    @Test
    fun `hmac is computed over the base64 ciphertext string not the raw bytes`() {
        // The classic sync15 implementation bug is HMACing raw ciphertext. Recompute both ways
        // and assert the payload carries the base64-string variant.
        val keys = testBundle()
        val payload = Sync15Crypto.encryptPayload("hello", keys)
        val ciphertextB64 = payload.getString("ciphertext")
        val overString = Sync15Crypto.hmacSha256(keys.hmacKey, ciphertextB64.toByteArray(Charsets.US_ASCII))
        val overBytes = Sync15Crypto.hmacSha256(keys.hmacKey, Base64.getDecoder().decode(ciphertextB64))
        val hex = overString.joinToString("") { "%02x".format(it) }
        val wrongHex = overBytes.joinToString("") { "%02x".format(it) }
        assertEquals(hex, payload.getString("hmac"))
        assertTrue(hex != wrongHex)
    }

    @Test
    fun `tampered ciphertext is rejected before decryption`() {
        val keys = testBundle()
        val payload = Sync15Crypto.encryptPayload("hello", keys)
        val tampered = Base64.getEncoder().encodeToString(
            Base64.getDecoder().decode(payload.getString("ciphertext")).also { it[0] = it[0].inc() },
        )
        payload.put("ciphertext", tampered)
        assertThrows(SecurityException::class.java) { Sync15Crypto.decryptPayload(payload, keys) }
    }

    @Test
    fun `wrong hmac key is rejected`() {
        val payload = Sync15Crypto.encryptPayload("hello", testBundle())
        val otherKeys = KeyBundle(ByteArray(32) { 1 }, ByteArray(32) { 9 })
        assertThrows(SecurityException::class.java) { Sync15Crypto.decryptPayload(payload, otherKeys) }
    }

    // --- crypto/keys ---

    @Test
    fun `collection keys prefer the per-collection bundle and fall back to default`() {
        val defaultEnc = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
        val defaultMac = Base64.getEncoder().encodeToString(ByteArray(32) { 4 })
        val wsEnc = Base64.getEncoder().encodeToString(ByteArray(32) { 5 })
        val wsMac = Base64.getEncoder().encodeToString(ByteArray(32) { 6 })
        val cleartext = JSONObject()
            .put("id", "keys")
            .put("collection", "crypto")
            .put("default", JSONArray(listOf(defaultEnc, defaultMac)))
            .put("collections", JSONObject().put("workspaces", JSONArray(listOf(wsEnc, wsMac))))
            .toString()
        val keys = CollectionKeys.fromCryptoKeysCleartext(cleartext)
        assertEquals(5.toByte(), keys.forCollection("workspaces").encryptionKey[0])
        assertEquals(3.toByte(), keys.forCollection("tabs").encryptionKey[0])
    }

    @Test
    fun `crypto keys without collections object still parses`() {
        val enc = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val mac = Base64.getEncoder().encodeToString(ByteArray(32) { 8 })
        val keys = CollectionKeys.fromCryptoKeysCleartext(
            JSONObject().put("default", JSONArray(listOf(enc, mac))).toString(),
        )
        assertEquals(7.toByte(), keys.forCollection("workspaces").encryptionKey[0])
    }

    @Test
    fun `crypto keys record decrypts with a kSync-derived root bundle end to end`() {
        // Simulates the real chain: root bundle from kSync → decrypt crypto/keys → use the
        // contained workspaces bundle to decrypt a record.
        val kSync = ByteArray(64) { (it * 7).toByte() }
        val root = KeyBundle.fromKSync(Base64.getUrlEncoder().withoutPadding().encodeToString(kSync))
        val wsBundle = testBundle()
        val keysCleartext = JSONObject()
            .put("default", JSONArray(listOf(
                Base64.getEncoder().encodeToString(wsBundle.encryptionKey),
                Base64.getEncoder().encodeToString(wsBundle.hmacKey),
            )))
            .toString()
        val keysPayload = Sync15Crypto.encryptPayload(keysCleartext, root)
        val parsed = CollectionKeys.fromCryptoKeysCleartext(Sync15Crypto.decryptPayload(keysPayload, root))

        val record = """{"id":"s~u1","type":"space","uuid":"u1","name":"Home"}"""
        val recordPayload = Sync15Crypto.encryptPayload(record, parsed.forCollection("workspaces"))
        assertEquals(record, Sync15Crypto.decryptPayload(recordPayload, parsed.forCollection("workspaces")))
    }

    // --- Zen record parsing ---

    private fun record(id: String, cleartext: String) =
        Sync15Record(id = id, modified = 0.0, cleartext = JSONObject(cleartext))

    @Test
    fun `parses a space record with zen's exact field set`() {
        val snapshot = ZenWorkspaceRecords.parse(
            listOf(
                record(
                    "s~u1",
                    """{"id":"s~u1","type":"space","uuid":"u1","name":"Work","icon":"💼",
                       "theme":{"type":"gradient","gradientColors":[]},"containerTabId":3,"position":0}""",
                ),
            ),
        )
        val space = snapshot.spaces.single()
        assertEquals("u1", space.uuid)
        assertEquals("Work", space.name)
        assertEquals("💼", space.icon)
        assertEquals(3, space.containerTabId)
        assertEquals(0, space.position)
        // Envelope stripped, everything else (incl. unknown fields like theme) preserved.
        assertTrue(space.raw.has("theme"))
        assertTrue(!space.raw.has("id"))
        assertTrue(!space.raw.has("type"))
    }

    @Test
    fun `space without uuid is skipped like zen does`() {
        val snapshot = ZenWorkspaceRecords.parse(
            listOf(record("s~x", """{"id":"s~x","type":"space","name":"NoUuid"}""")),
        )
        assertTrue(snapshot.spaces.isEmpty())
    }

    @Test
    fun `unknown record types are ignored`() {
        val snapshot = ZenWorkspaceRecords.parse(
            listOf(record("q~1", """{"id":"q~1","type":"pinboard","uuid":"z"}""")),
        )
        assertTrue(snapshot.spaces.isEmpty() && snapshot.containers.isEmpty())
    }

    @Test
    fun `spaces order by position with index fallback and stable ties`() {
        // b has no position → its sort key is its incoming index (1), matching Zen's
        // #getOrderedWorkspacesByPosition; c(0) then b(1) then a(2).
        val snapshot = ZenWorkspaceRecords.parse(
            listOf(
                record("s~a", """{"type":"space","uuid":"a","name":"A","position":2}"""),
                record("s~b", """{"type":"space","uuid":"b","name":"B"}"""),
                record("s~c", """{"type":"space","uuid":"c","name":"C","position":0}"""),
            ),
        )
        assertEquals(listOf("c", "b", "a"), snapshot.spaces.map { it.uuid })
    }

    @Test
    fun `container record parses name icon color and userContextId`() {
        val snapshot = ZenWorkspaceRecords.parse(
            listOf(
                record(
                    "c~4",
                    """{"id":"c~4","type":"container","userContextId":4,"name":"Shopping",
                       "icon":"cart","color":"pink"}""",
                ),
            ),
        )
        val container = snapshot.containers.single()
        assertEquals(4, container.userContextId)
        assertEquals("Shopping", container.name)
        assertEquals("cart", container.icon)
        assertEquals("pink", container.color)
    }

    @Test
    fun `container with string userContextId is normalized like zen does`() {
        val snapshot = ZenWorkspaceRecords.parse(
            listOf(record("c~5", """{"type":"container","userContextId":"5","name":"N"}""")),
        )
        assertEquals(5, snapshot.containers.single().userContextId)
    }

    @Test
    fun `containers with missing name or non-positive userContextId are skipped`() {
        val snapshot = ZenWorkspaceRecords.parse(
            listOf(
                record("c~1", """{"type":"container","userContextId":1}"""),
                record("c~0", """{"type":"container","userContextId":0,"name":"Zero"}"""),
                record("c~x", """{"type":"container","userContextId":"junk","name":"Junk"}"""),
                record("c~-2", """{"type":"container","userContextId":-2,"name":"Neg"}"""),
            ),
        )
        assertTrue(snapshot.containers.isEmpty())
    }

    @Test
    fun `record id helpers use zen's prefix scheme`() {
        assertEquals("s~abc-def", ZenWorkspaceRecords.spaceRecordId("abc-def"))
        assertEquals("c~7", ZenWorkspaceRecords.containerRecordId(7))
    }

    @Test
    fun `space icon defaults to null when absent`() {
        val snapshot = ZenWorkspaceRecords.parse(
            listOf(record("s~u", """{"type":"space","uuid":"u","name":"Plain"}""")),
        )
        assertNull(snapshot.spaces.single().icon)
    }
}

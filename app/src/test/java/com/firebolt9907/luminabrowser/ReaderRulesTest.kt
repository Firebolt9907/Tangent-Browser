package com.firebolt9907.luminabrowser

import com.firebolt9907.luminabrowser.ui.reader.ReaderRules
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/**
 * Covers [ReaderRules.parse] — the sanitizer that turns the bundled reader_rules.json into the
 * compact array inlined into the extractor. Host matching itself runs in the injected script (no
 * JVM coverage), but the load-time guarantees the script relies on are pinned here.
 */
class ReaderRulesTest {

    @Test
    fun `keeps match include and strip and normalizes a scalar selector to an array`() {
        val out = JSONArray(
            ReaderRules.parse(
                """{ "rules": [ { "match": "example.com", "include": ".article", "strip": [".ad", "aside"] } ] }"""
            )
        )
        assertEquals(1, out.length())
        val rule = out.getJSONObject(0)
        assertEquals("example.com", rule.getJSONArray("match").getString(0))
        assertEquals(".article", rule.getJSONArray("include").getString(0))
        assertEquals(2, rule.getJSONArray("strip").length())
    }

    @Test
    fun `drops a rule with no usable match`() {
        val out = JSONArray(ReaderRules.parse("""{ "rules": [ { "include": ".article" } ] }"""))
        assertEquals(0, out.length())
    }

    @Test
    fun `drops selectors containing a double-slash (the flatten footgun)`() {
        // A "//" surviving into the flattened script would comment out the rest of the extractor.
        val json = ReaderRules.parse(
            """{ "rules": [ { "match": "example.com", "strip": ["a[href*='//']", ".ad"] } ] }"""
        )
        assertFalse("no // may reach the inlined rules", json.contains("//"))
        val strip = JSONArray(json).getJSONObject(0).getJSONArray("strip")
        assertEquals(1, strip.length())
        assertEquals(".ad", strip.getString(0))
    }

    @Test
    fun `omits include and strip keys when empty`() {
        val rule = JSONArray(ReaderRules.parse("""{ "rules": [ { "match": "example.com" } ] }"""))
            .getJSONObject(0)
        assertFalse(rule.has("include"))
        assertFalse(rule.has("strip"))
    }

    @Test
    fun `missing rules array yields an empty array`() {
        assertEquals("[]", ReaderRules.parse("""{ "version": 1 }"""))
    }

    @Test
    fun `parseRuleset reads the version and defaults it to zero`() {
        assertEquals(3, ReaderRules.parseRuleset("""{ "version": 3, "rules": [] }""").version)
        assertEquals(0, ReaderRules.parseRuleset("""{ "rules": [] }""").version)
    }

    @Test
    fun `parseRuleset carries the sanitized compact array alongside the version`() {
        val ruleset = ReaderRules.parseRuleset(
            """{ "version": 5, "rules": [ { "match": "example.com", "include": ".article" } ] }"""
        )
        assertEquals(5, ruleset.version)
        assertEquals(".article", JSONArray(ruleset.compactJson).getJSONObject(0).getJSONArray("include").getString(0))
    }

    private class Signer {
        val priv: Ed25519PrivateKeyParameters
        val pubB64: String
        init {
            val gen = Ed25519KeyPairGenerator().apply {
                init(Ed25519KeyGenerationParameters(SecureRandom()))
            }
            val kp = gen.generateKeyPair()
            priv = kp.private as Ed25519PrivateKeyParameters
            pubB64 = Base64.getEncoder().encodeToString((kp.public as Ed25519PublicKeyParameters).encoded)
        }
        fun sign(payload: ByteArray): String {
            val s = Ed25519Signer().apply { init(true, priv) }
            s.update(payload, 0, payload.size)
            return Base64.getEncoder().encodeToString(s.generateSignature())
        }
    }

    @Test
    fun `verifyEd25519 accepts a genuine signature and rejects tampering`() {
        val signer = Signer()
        val payload = """{ "version": 2, "rules": [] }""".toByteArray(Charsets.UTF_8)
        val sig = signer.sign(payload)

        assertTrue("genuine signature verifies", ReaderRules.verifyEd25519(payload, sig, signer.pubB64))
        assertFalse(
            "tampered payload is rejected",
            ReaderRules.verifyEd25519("""{ "version": 3, "rules": [] }""".toByteArray(Charsets.UTF_8), sig, signer.pubB64),
        )
        assertFalse(
            "a different key is rejected",
            ReaderRules.verifyEd25519(payload, sig, Signer().pubB64),
        )
    }

    @Test
    fun `verifyEd25519 treats a blank key or signature (the default) as untrusted`() {
        val payload = "anything".toByteArray(Charsets.UTF_8)
        assertFalse("no embedded key means no trust", ReaderRules.verifyEd25519(payload, "c2ln", ""))
        assertFalse("no signature means no trust", ReaderRules.verifyEd25519(payload, "", Signer().pubB64))
        assertFalse("garbage base64 never throws", ReaderRules.verifyEd25519(payload, "!!!!", "!!!!"))
    }

    @Test
    fun `bundled default is loaded lazily and starts empty without init`() {
        // Without a Context-backed init (as in this JVM test), the cache is the safe no-override value.
        assertEquals("[]", ReaderRules.cached())
        assertNull(runCatching { JSONArray(ReaderRules.cached()) }.exceptionOrNull())
        assertTrue(JSONArray(ReaderRules.cached()).length() == 0)
    }
}

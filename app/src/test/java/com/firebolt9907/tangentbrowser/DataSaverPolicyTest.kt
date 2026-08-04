package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.state.DataSaverPolicy
import com.firebolt9907.tangentbrowser.state.siteKeyOf
import com.firebolt9907.tangentbrowser.state.normalizeSiteKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure half of the data saver: what [DataSaverPolicy.compute] hands the blocking
 * extension, and the host normalization both the policy and the evidence store key off.
 *
 * The behavior worth pinning here is the *shape* of the policy — that first-party script survives
 * the ordinary tier, that stylesheets are only ever reachable through the reader-proven tier, and
 * that an exemption beats every tier including that one.
 */
class DataSaverPolicyTest {

    // ── compute ───────────────────────────────────────────────────────────────

    @Test fun inactive_producesInertPolicy() {
        val policy = DataSaverPolicy.compute(
            active = false,
            readerProvenHosts = setOf("nytimes.com"),
            exemptHosts = emptySet(),
        )
        assertFalse(policy.enabled)
        assertTrue(policy.blockAll.isEmpty())
        assertTrue(policy.blockThirdParty.isEmpty())
        assertTrue(policy.aggressiveHosts.isEmpty())
    }

    @Test fun active_blocksImagesMediaAndFontsOutright() {
        val policy = DataSaverPolicy.compute(true, emptySet(), emptySet())
        assertTrue(policy.enabled)
        assertEquals(setOf("image", "media", "font"), policy.blockAll)
    }

    /** The core safety property: a site's own script keeps working, only cross-site script is cut. */
    @Test fun active_leavesFirstPartyScriptAlone() {
        val policy = DataSaverPolicy.compute(true, emptySet(), emptySet())
        assertTrue("script" in policy.blockThirdParty)
        assertFalse("script" in policy.blockAll)
    }

    /** Stylesheets are never in a tier that applies to an ordinary host — only to reader-proven ones. */
    @Test fun active_neverBlocksStylesheetsOutsideAggressiveTier() {
        val policy = DataSaverPolicy.compute(true, emptySet(), emptySet())
        assertFalse("stylesheet" in policy.blockAll)
        assertFalse("stylesheet" in policy.blockThirdParty)
        assertTrue("stylesheet" in policy.aggressiveTypes)
        assertTrue(policy.aggressiveHosts.isEmpty())
    }

    @Test fun readerProvenHosts_enterAggressiveTier() {
        val policy = DataSaverPolicy.compute(true, setOf("nytimes.com", "bbc.co.uk"), emptySet())
        assertEquals(setOf("nytimes.com", "bbc.co.uk"), policy.aggressiveHosts)
        assertEquals(setOf("script", "stylesheet"), policy.aggressiveTypes)
    }

    /** "Load full page" has to win over the aggressive tier, not just the ordinary one. */
    @Test fun exemptHost_isRemovedFromAggressiveTier() {
        val policy = DataSaverPolicy.compute(
            active = true,
            readerProvenHosts = setOf("nytimes.com", "bbc.co.uk"),
            exemptHosts = setOf("nytimes.com"),
        )
        assertEquals(setOf("bbc.co.uk"), policy.aggressiveHosts)
        assertEquals(setOf("nytimes.com"), policy.exemptHosts)
    }

    // ── serialization ─────────────────────────────────────────────────────────

    @Test fun toJson_carriesEveryTierTheExtensionReads() {
        val json = DataSaverPolicy.compute(true, setOf("nytimes.com"), setOf("example.com")).toJson()
        assertEquals("policy", json.getString("type"))
        assertTrue(json.getBoolean("enabled"))
        assertEquals(3, json.getJSONArray("blockAll").length())
        assertEquals("nytimes.com", json.getJSONArray("aggressiveHosts").getString(0))
        assertEquals("example.com", json.getJSONArray("exemptHosts").getString(0))
    }

    // ── host normalization ────────────────────────────────────────────────────

    @Test fun siteKeyOf_stripsSchemeWwwAndPath() =
        assertEquals("nytimes.com", siteKeyOf("https://www.nytimes.com/2026/01/01/story.html"))

    @Test fun siteKeyOf_lowercases() =
        assertEquals("nytimes.com", siteKeyOf("https://WWW.NyTimes.COM/"))

    @Test fun siteKeyOf_keepsSubdomains() =
        assertEquals("cooking.nytimes.com", siteKeyOf("https://cooking.nytimes.com/recipes"))

    @Test fun siteKeyOf_returnsNullForHostlessUrls() {
        assertNull(siteKeyOf("about:blank"))
        assertNull(siteKeyOf(""))
        assertNull(siteKeyOf(null))
    }

    /** Rules `match` entries may be written with a leading wildcard; both forms must key the same. */
    @Test fun normalizeSiteKey_stripsRulesWildcardPrefix() =
        assertEquals("nytimes.com", normalizeSiteKey("*.nytimes.com"))

    @Test fun normalizeSiteKey_stripsTrailingDot() =
        assertEquals("nytimes.com", normalizeSiteKey("nytimes.com."))

    /** A single-label name is never a host we can reason about as a site. */
    @Test fun normalizeSiteKey_rejectsSingleLabel() {
        assertNull(normalizeSiteKey("localhost"))
        assertNull(normalizeSiteKey(""))
    }
}

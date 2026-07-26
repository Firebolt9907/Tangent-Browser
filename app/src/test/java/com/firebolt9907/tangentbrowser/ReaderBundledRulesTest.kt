package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.reader.ReaderRules
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for the SHIPPED bundled ruleset (`assets/reader_rules.json`), as opposed to
 * [ReaderRulesTest] which exercises the sanitizer on inline strings. These assertions pin the
 * invariants the extractor and the anti-rollback logic depend on, so a bad hand-edit to the asset
 * fails the build instead of silently shipping broken reader mode.
 *
 * The reader-mode agent loop adds per-site rules to this file; this test is what stops an edit from
 * (a) breaking the JSON, (b) sneaking a `//` into a selector (which would comment out the entire
 * flattened extractor script), (c) shipping a rule with no `match` (silently dropped at load), or
 * (d) forgetting to bump `version` (which disables the signed remote upgrade via anti-rollback).
 */
class ReaderBundledRulesTest {

    private fun bundledRulesFile(): File {
        // Gradle runs JVM unit tests with the module dir (app/) as the working directory; fall back
        // to the repo-root-relative path so the test also passes if invoked from elsewhere.
        for (p in listOf("src/main/assets/reader_rules.json", "app/src/main/assets/reader_rules.json")) {
            val f = File(p)
            if (f.exists()) return f
        }
        throw AssertionError("bundled reader_rules.json not found from working dir ${File(".").absolutePath}")
    }

    private fun readBundled(): String = bundledRulesFile().readText()

    @Test
    fun `bundled asset is valid JSON with a versioned rules array`() {
        val root = JSONObject(readBundled())
        assertTrue("version must be a positive int", root.optInt("version", 0) >= 1)
        assertTrue("must have a rules array", root.optJSONArray("rules") != null)
    }

    @Test
    fun `version is at least the current floor (guards anti-rollback bumps)`() {
        // Every content change must bump `version`; this floor rises as the loop ships rules, so a
        // future edit that forgets the bump (or lowers it) trips here. Update the floor when you
        // intentionally raise the shipped version.
        val version = JSONObject(readBundled()).optInt("version", 0)
        assertTrue("version regressed below the known floor (72): $version", version >= 72)
    }

    @Test
    fun `no selector contains the double-slash flatten footgun or a newline`() {
        // A '//' surviving into the inlined rules would comment out the rest of the one-line
        // extractor script once it is flattened; a newline would break the javascript: URL.
        val compact = ReaderRules.parse(readBundled())
        assertFalse("bundled rules must not contain '//'", compact.contains("//"))
        assertFalse("bundled rules must not contain a newline", compact.contains("\n"))
    }

    @Test
    fun `every shipped rule has a usable match (none silently dropped by the sanitizer)`() {
        val rawRuleCount = JSONObject(readBundled()).getJSONArray("rules").length()
        val parsedCount = JSONArray(ReaderRules.parse(readBundled())).length()
        // The sanitizer drops any rule lacking a usable `match`; equal counts prove none were.
        assertEquals("a shipped rule has no usable 'match'", rawRuleCount, parsedCount)
    }

    @Test
    fun `anchor news hosts remain present (guards accidental truncation)`() {
        val compact = ReaderRules.parse(readBundled())
        // A representative spread across the CMS families the loop validated; if the asset is ever
        // truncated or a section is dropped, at least one of these disappears.
        for (host in listOf("bbc.com", "apnews.com", "cnn.com", "nytimes.com", "washingtonpost.com", "theguardian.com")) {
            assertTrue("anchor host missing from bundled rules: $host", compact.contains(host))
        }
    }

    @Test
    fun `include and strip selectors are non-blank strings`() {
        val rules = JSONArray(ReaderRules.parse(readBundled()))
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            for (key in listOf("include", "strip")) {
                val arr = rule.optJSONArray(key) ?: continue
                for (j in 0 until arr.length()) {
                    assertTrue("blank $key selector in rule $i", arr.getString(j).isNotBlank())
                }
            }
        }
    }
}

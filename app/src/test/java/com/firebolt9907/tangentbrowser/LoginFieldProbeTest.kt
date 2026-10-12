package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.content.LoginFieldProbe
import com.firebolt9907.tangentbrowser.ui.content.BridgeGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the parts of the login probe that make an identifier-first (enterprise SSO) sign-in work, and
 * the one part that must never regress into a disclosure.
 *
 * The scripts are flattened to a single line before injection, so a stray `//` comment would comment
 * out the rest of the payload and the probe would silently do nothing — hence the shape checks.
 */
class LoginFieldProbeTest {

    private fun capturePayload(payload: String, trustedUrl: String? = "https://login.microsoftonline.com/common/login"): Pair<String, String>? {
        BridgeGuard.setTrustedOrigin("tab", trustedUrl)
        var seen: Pair<String, String>? = null
        LoginFieldProbe.onFieldDetected = { stage, target -> seen = stage to target.origin }
        try {
            LoginFieldProbe.onPayload("tab", payload)
        } finally {
            LoginFieldProbe.onFieldDetected = null
            BridgeGuard.forget("tab")
        }
        return seen
    }

    @Test
    fun `payload carries the stage the page is showing`() {
        assertEquals(
            LoginFieldProbe.Stage.Password to "https://login.microsoftonline.com",
            capturePayload("field|pw|https://login.microsoftonline.com/common/login"),
        )
        assertEquals(
            LoginFieldProbe.Stage.User to "https://login.microsoftonline.com",
            capturePayload("field|user|https://login.microsoftonline.com/common/login"),
        )
    }

    @Test
    fun `payload cannot select a victim origin`() {
        assertEquals(
            LoginFieldProbe.Stage.Password to "https://evil.example",
            capturePayload("field|pw|https://login.microsoftonline.com/login", "https://evil.example/"),
        )
        assertNull(capturePayload("field|pw|https://bank.example", null))
        assertNull(capturePayload("field|pw|https://bank.example", "file:///tmp/page.html"))
    }

    @Test
    fun `unknown and malformed payloads are dropped`() {
        assertNull(capturePayload("field|https://example.com"))
        assertNull(capturePayload("field|bogus|https://example.com"))
        assertNull(capturePayload(""))
    }

    @Test
    fun `the identifier step never carries the password into the page`() {
        val secret = "hunter2-do-not-leak"
        val userStage = LoginFieldProbe.buildFillScript("me@purdue.edu", secret, LoginFieldProbe.Stage.User, expectedOrigin = "https://idp.example.com")
        assertFalse(userStage.contains(secret))
        assertTrue(userStage.contains("me@purdue.edu"))

        val pwStage = LoginFieldProbe.buildFillScript("me@purdue.edu", secret, LoginFieldProbe.Stage.Password, expectedOrigin = "https://idp.example.com")
        assertTrue(pwStage.contains(secret))
    }

    @Test
    fun `fill and submit check origin inside their retry functions`() {
        val fill = LoginFieldProbe.buildFillScript("u", "p", expectedOrigin = "https://IDP.example.com:443")
        val submit = LoginFieldProbe.buildSubmitScript(expectedOrigin = "https://idp.example.com")
        val guard = "if (location.origin !== \"https://idp.example.com\") return false;"
        assertTrue(fill.substringAfter("function fill() {").trimStart().startsWith(guard))
        assertTrue(submit.substringAfter("function trySubmit() {").trimStart().startsWith(guard))
        val invalid = LoginFieldProbe.buildFillScript("u", "p", expectedOrigin = "file:///tmp/login")
        assertTrue(invalid.substringAfter("function fill() {").trimStart().startsWith("return false;"))
    }

    @Test
    fun `the detector watches for a password field that appears without a navigation`() {
        val js = LoginFieldProbe.buildDetectScript()
        // The whole point: an IdP swaps the password field in with no load, so a load-time-only scan
        // never sees it and the user is left tapping the field by hand.
        assertTrue(js.contains("MutationObserver"))
        assertTrue(js.contains("disconnect"))
        // Only armed on pages that look like a sign-in — ordinary browsing must not pay for it.
        assertTrue(js.contains("input[type=password]"))
        // Deduped by field signature so a churning page can't machine-gun the bridge.
        assertTrue(js.contains("lastSig"))
    }

    @Test
    fun `the submit script is visibility-aware`() {
        // A hidden decoy password input is present on step one of every identifier-first IdP; a
        // visibility-blind querySelector finds it, reads it as empty and stalls the submit.
        val js = LoginFieldProbe.buildSubmitScript(LoginFieldProbe.Stage.Password)
        assertTrue(js.contains("tangentVisible"))
        assertTrue(js.contains("tangentLoginFields"))
    }

    @Test
    fun `scripts survive being flattened to one line`() {
        val scripts = listOf(
            LoginFieldProbe.buildDetectScript(),
            LoginFieldProbe.buildFillScript("u", "p", LoginFieldProbe.Stage.Password, expectedOrigin = "https://idp.example.com"),
            LoginFieldProbe.buildFillScript("u", "p", LoginFieldProbe.Stage.User, expectedOrigin = "https://idp.example.com"),
            LoginFieldProbe.buildSubmitScript(LoginFieldProbe.Stage.Password),
            LoginFieldProbe.buildSubmitScript(LoginFieldProbe.Stage.User),
        )
        for (js in scripts) {
            assertTrue(js.startsWith("javascript:"))
            assertFalse("newline would truncate the payload", js.contains("\n"))
            // Distinguish line comments from the expected origin's https:// literal.
            assertFalse("line comment in a flattened script", Regex("(?:^|\\s)//").containsMatchIn(js))
            assertEquals("unbalanced braces", js.count { it == '{' }, js.count { it == '}' })
            assertEquals("unbalanced parens", js.count { it == '(' }, js.count { it == ')' })
        }
    }
}

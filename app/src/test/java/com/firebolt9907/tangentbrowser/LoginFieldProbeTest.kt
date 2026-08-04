package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.content.LoginFieldProbe
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

    private fun capturePayload(payload: String): Pair<String, String>? {
        var seen: Pair<String, String>? = null
        LoginFieldProbe.onFieldDetected = { stage, url -> seen = stage to url }
        try {
            LoginFieldProbe.onPayload("tab", payload)
        } finally {
            LoginFieldProbe.onFieldDetected = null
        }
        return seen
    }

    @Test
    fun `payload carries the stage the page is showing`() {
        assertEquals(
            LoginFieldProbe.Stage.Password to "https://login.microsoftonline.com/common/login",
            capturePayload("field|pw|https://login.microsoftonline.com/common/login"),
        )
        assertEquals(
            LoginFieldProbe.Stage.User to "https://login.microsoftonline.com/common/login",
            capturePayload("field|user|https://login.microsoftonline.com/common/login"),
        )
    }

    @Test
    fun `a URL containing a pipe survives parsing`() {
        // limit=3 keeps the tail intact; a naive split would truncate the URL and the host lookup
        // would then miss every saved credential.
        assertEquals(
            LoginFieldProbe.Stage.Password to "https://idp.purdue.edu/x?a=b|c",
            capturePayload("field|pw|https://idp.purdue.edu/x?a=b|c"),
        )
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
        val userStage = LoginFieldProbe.buildFillScript("me@purdue.edu", secret, LoginFieldProbe.Stage.User)
        assertFalse(userStage.contains(secret))
        assertTrue(userStage.contains("me@purdue.edu"))

        val pwStage = LoginFieldProbe.buildFillScript("me@purdue.edu", secret, LoginFieldProbe.Stage.Password)
        assertTrue(pwStage.contains(secret))
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
            LoginFieldProbe.buildFillScript("u", "p", LoginFieldProbe.Stage.Password),
            LoginFieldProbe.buildFillScript("u", "p", LoginFieldProbe.Stage.User),
            LoginFieldProbe.buildSubmitScript(LoginFieldProbe.Stage.Password),
            LoginFieldProbe.buildSubmitScript(LoginFieldProbe.Stage.User),
        )
        for (js in scripts) {
            assertTrue(js.startsWith("javascript:"))
            assertFalse("newline would truncate the payload", js.contains("\n"))
            // A `//` comment on a flattened script swallows everything after it. `//` also appears in
            // `https://` literals, which these scripts do not contain — so any hit is a real bug.
            assertFalse("line comment in a flattened script", js.contains("//"))
            assertEquals("unbalanced braces", js.count { it == '{' }, js.count { it == '}' })
            assertEquals("unbalanced parens", js.count { it == '(' }, js.count { it == ')' })
        }
    }
}

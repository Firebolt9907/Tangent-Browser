package com.firebolt9907.luminabrowser

import com.firebolt9907.luminabrowser.ui.reader.isLaunchableReaderScheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the reader-overlay external-link launcher: a link tapped inside reader content (parsed from
 * an untrusted page) is launched as an implicit ACTION_VIEW intent, so privileged/local schemes must
 * be refused while ordinary external-app schemes still launch.
 */
class ReaderLinkSchemeTest {

    @Test
    fun `ordinary external app schemes are launchable`() {
        for (href in listOf(
            "mailto:someone@example.com",
            "tel:+15551234567",
            "sms:+15551234567",
            "geo:37.7,-122.4",
            "market://details?id=com.example",
            "whatsapp://send?text=hi",
            "spotify:track:abc",
        )) {
            assertTrue(href, isLaunchableReaderScheme(href))
        }
    }

    @Test
    fun `privileged and local schemes are refused`() {
        for (href in listOf(
            "file:///sdcard/secret.html",
            "FILE:///sdcard/secret.html",
            "content://com.android.providers/secret",
            "intent://scan/#Intent;scheme=zxing;end",
            "android-app://com.example",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "blob:https://evil.example/uuid",
        )) {
            assertFalse(href, isLaunchableReaderScheme(href))
        }
    }

    @Test
    fun `schemeless or relative hrefs are refused`() {
        // No scheme means no external app to launch — must not fall through to ACTION_VIEW.
        for (href in listOf("", "   ", "/relative/path", "example.com/page", "#anchor")) {
            assertFalse(href, isLaunchableReaderScheme(href))
        }
    }

    @Test
    fun `leading whitespace does not bypass the block`() {
        assertFalse(isLaunchableReaderScheme("   file:///etc/passwd"))
        assertTrue(isLaunchableReaderScheme("   mailto:a@b.com"))
    }
}

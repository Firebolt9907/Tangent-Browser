package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.content.bridgeTrustedOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Security boundary for the `tangent-*` prompt bridge's trusted-origin registry: freeform form-field
 * memory ([com.firebolt9907.tangentbrowser.ui.content.FormFieldMemory]) is bucketed by this origin, so
 * a plaintext or different-host page must never read values captured on the real TLS origin.
 */
class BridgeTrustedOriginTest {

    @Test fun httpsOrigin() =
        assertEquals("https://bank.com", bridgeTrustedOrigin("https://bank.com/login?next=/x"))

    @Test fun httpAndHttpsAreDifferentBuckets() =
        assertNotEquals(
            bridgeTrustedOrigin("https://bank.com/"),
            bridgeTrustedOrigin("http://bank.com/"),
        )

    @Test fun explicitPortIsPartOfOrigin() =
        assertEquals("https://bank.com:8443", bridgeTrustedOrigin("https://bank.com:8443/x"))

    @Test fun differentHostsDiffer() =
        assertNotEquals(
            bridgeTrustedOrigin("https://bank.com/"),
            bridgeTrustedOrigin("https://evil.com/"),
        )

    @Test fun subdomainNotConfusedWithParent() =
        assertNotEquals(
            bridgeTrustedOrigin("https://bank.com/"),
            bridgeTrustedOrigin("https://bank.com.evil.com/"),
        )

    @Test fun caseNormalized() =
        assertEquals("https://bank.com", bridgeTrustedOrigin("HTTPS://Bank.COM/"))

    @Test fun nonWebSchemeIsNull() {
        assertNull(bridgeTrustedOrigin("file:///sdcard/x.html"))
        assertNull(bridgeTrustedOrigin("about:blank"))
        assertNull(bridgeTrustedOrigin("data:text/html,hi"))
    }

    @Test fun nullOrBlankIsNull() {
        assertNull(bridgeTrustedOrigin(null))
        assertNull(bridgeTrustedOrigin("   "))
    }

    @Test fun injectedProbesNeverFallBackToPageWindowPrompt() {
        val scripts = listOf(
            com.firebolt9907.tangentbrowser.ui.content.FormFieldMemory.buildScript(),
            com.firebolt9907.tangentbrowser.ui.content.LoginFieldProbe.buildDetectScript(),
            com.firebolt9907.tangentbrowser.ui.content.OtpAutofill.buildDetectScript(),
            com.firebolt9907.tangentbrowser.ui.content.BlobCapture.buildScript(),
            com.firebolt9907.tangentbrowser.ui.content.ElementZap.buildPickerScript(),
            com.firebolt9907.tangentbrowser.ui.content.ThemeColorProbe.buildScript(),
            com.firebolt9907.tangentbrowser.ui.content.LinkRectProbe.buildScript("https://example.com", 10f, 10f),
            com.firebolt9907.tangentbrowser.ui.reader.ReaderExtractor.buildReaderExtractorScript(),
            com.firebolt9907.tangentbrowser.ui.reader.SocialExtractor.buildSocialExtractorScript(),
        )
        for (js in scripts) {
            org.junit.Assert.assertFalse(
                "Calling window.prompt leaks BridgeGuard.nonce to a monkey-patched page: $js",
                js.contains("window.prompt("),
            )
        }
    }
}

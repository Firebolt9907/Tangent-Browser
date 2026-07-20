package com.firebolt9907.luminabrowser

import com.firebolt9907.luminabrowser.ui.content.bridgeTrustedOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Security boundary for the `lumina-*` prompt bridge's trusted-origin registry: freeform form-field
 * memory ([com.firebolt9907.luminabrowser.ui.content.FormFieldMemory]) is bucketed by this origin, so
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
}

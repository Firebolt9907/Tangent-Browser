package com.firebolt9907.luminabrowser

import com.firebolt9907.luminabrowser.permissions.sitePermissionOriginKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Security boundary: remembered camera/mic/geolocation grants are keyed by full origin, so a grant
 * to one origin must never leak to a different scheme, host, or port.
 */
class SitePermissionOriginKeyTest {

    @Test fun httpsOrigin() =
        assertEquals("https://example.com", sitePermissionOriginKey("https://example.com/path?q=1"))

    @Test fun httpAndHttpsDiffer() =
        assertNotEquals(
            sitePermissionOriginKey("https://example.com/"),
            sitePermissionOriginKey("http://example.com/"),
        )

    @Test fun explicitPortIsPartOfOrigin() =
        assertEquals("https://example.com:8443", sitePermissionOriginKey("https://example.com:8443/x"))

    @Test fun differentHostsDiffer() =
        assertNotEquals(
            sitePermissionOriginKey("https://example.com/"),
            sitePermissionOriginKey("https://evil.example.com/"),
        )

    @Test fun caseNormalized() =
        assertEquals("https://example.com", sitePermissionOriginKey("HTTPS://Example.COM/"))

    @Test fun subdomainNotConfusedWithParent() =
        assertNotEquals(
            sitePermissionOriginKey("https://example.com/"),
            sitePermissionOriginKey("https://example.com.evil.com/"),
        )

    @Test fun nullUri() = assertNull(sitePermissionOriginKey(null))

    @Test fun noHost() = assertNull(sitePermissionOriginKey("about:blank"))

    @Test fun blank() = assertNull(sitePermissionOriginKey("   "))
}

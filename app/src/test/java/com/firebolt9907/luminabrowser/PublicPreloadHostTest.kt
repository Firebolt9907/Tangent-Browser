package com.firebolt9907.luminabrowser

import com.firebolt9907.luminabrowser.state.isPublicPreloadHost
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security boundary: the exported Custom Tabs `mayLaunchUrl` path lets any app drive a full cookied
 * headless preload, so only plausibly-public http(s) hosts may be auto-preloaded — internal /
 * loopback / private / link-local targets must be refused so the browser can't be turned into an
 * unattended SSRF oracle.
 */
class PublicPreloadHostTest {

    @Test fun publicFqdnsPass() {
        assertTrue(isPublicPreloadHost("https://www.nytimes.com/2026/07/20/x.html"))
        assertTrue(isPublicPreloadHost("http://example.co.uk/"))
        assertTrue(isPublicPreloadHost("https://8.8.8.8/")) // public IP literal
        assertTrue(isPublicPreloadHost("https://a.b.example.com/path?q=1"))
    }

    @Test fun loopbackRejected() {
        assertFalse(isPublicPreloadHost("http://127.0.0.1:8080/admin"))
        assertFalse(isPublicPreloadHost("http://127.5.6.7/"))
        assertFalse(isPublicPreloadHost("http://localhost/"))
        assertFalse(isPublicPreloadHost("http://api.localhost/"))
        assertFalse(isPublicPreloadHost("http://[::1]/"))
    }

    @Test fun privateRangesRejected() {
        assertFalse(isPublicPreloadHost("http://10.0.0.5/"))
        assertFalse(isPublicPreloadHost("http://192.168.1.1/"))
        assertFalse(isPublicPreloadHost("http://172.16.0.1/"))
        assertFalse(isPublicPreloadHost("http://172.31.255.255/"))
        assertTrue(isPublicPreloadHost("http://172.15.0.1/"))  // just outside 172.16/12 → public
        assertTrue(isPublicPreloadHost("http://172.32.0.1/"))
    }

    @Test fun linkLocalAndMetadataRejected() {
        assertFalse(isPublicPreloadHost("http://169.254.169.254/latest/meta-data/")) // cloud metadata
        assertFalse(isPublicPreloadHost("http://169.254.1.1/"))
        assertFalse(isPublicPreloadHost("http://[fe80::1]/"))
    }

    @Test fun cgnatAndReservedRejected() {
        assertFalse(isPublicPreloadHost("http://100.64.0.1/"))
        assertFalse(isPublicPreloadHost("http://0.0.0.0/"))
        assertFalse(isPublicPreloadHost("http://224.0.0.1/"))
        assertFalse(isPublicPreloadHost("http://255.255.255.255/"))
    }

    @Test fun uniqueLocalIpv6Rejected() {
        assertFalse(isPublicPreloadHost("http://[fd00::1]/"))
        assertFalse(isPublicPreloadHost("http://[fc00::1]/"))
    }

    @Test fun bareSingleLabelAndMdnsRejected() {
        assertFalse(isPublicPreloadHost("http://router/"))
        assertFalse(isPublicPreloadHost("http://intranet/dashboard"))
        assertFalse(isPublicPreloadHost("http://printer.local/"))
    }

    @Test fun nonWebSchemesAndJunkRejected() {
        assertFalse(isPublicPreloadHost("file:///etc/passwd"))
        assertFalse(isPublicPreloadHost("ftp://example.com/"))
        assertFalse(isPublicPreloadHost("about:blank"))
        assertFalse(isPublicPreloadHost(null))
        assertFalse(isPublicPreloadHost("   "))
        assertFalse(isPublicPreloadHost("not a url"))
    }
}

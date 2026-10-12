package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.addons.isAddonDownloadUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security boundary: [isAddonDownloadUrl] gates the automatic page-navigation WebExtension install
 * interception, so it must accept ONLY addons.mozilla.org downloads. A `.xpi` served from any other
 * origin must be rejected, otherwise a hostile page could drive-by trigger a high-privilege
 * extension install via `location.href = "https://evil.com/x.xpi"`.
 */
class AddonDownloadUrlTest {

    @Test fun amoLatestXpi_accepted() =
        assertTrue("https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi".isAddonDownloadUrl())

    @Test fun amoDirectXpi_accepted() =
        assertTrue("https://addons.mozilla.org/firefox/downloads/file/123/ext.xpi".isAddonDownloadUrl())

    @Test fun amoSubdomainXpi_accepted() =
        assertTrue("https://services.addons.mozilla.org/firefox/downloads/latest/x/latest.xpi".isAddonDownloadUrl())

    @Test fun foreignHostXpi_rejected() =
        assertFalse("https://evil.com/malware.xpi".isAddonDownloadUrl())

    @Test fun lookalikeHostXpi_rejected() =
        assertFalse("https://addons.mozilla.org.evil.com/x.xpi".isAddonDownloadUrl())

    @Test fun foreignHostFakeDownloadPath_rejected() =
        assertFalse("https://evil.com/firefox/downloads/latest/x/latest.xpi".isAddonDownloadUrl())

    @Test fun amoNonDownloadPage_rejected() =
        assertFalse("https://addons.mozilla.org/en-US/firefox/addon/ublock-origin/".isAddonDownloadUrl())

    @Test fun schemelessInput_rejected() =
        assertFalse("evil.com/x.xpi".isAddonDownloadUrl())
    @Test fun unauthenticatedTransports_rejected() {
        for (scheme in listOf("http", "ftp", "file", "content")) {
            assertFalse("$scheme://addons.mozilla.org/x.xpi".isAddonDownloadUrl())
        }
        assertFalse("//addons.mozilla.org/x.xpi".isAddonDownloadUrl())
    }

    @Test fun nonstandardAuthority_rejected() {
        assertFalse("https://user@addons.mozilla.org/x.xpi".isAddonDownloadUrl())
        assertFalse("https://addons.mozilla.org:8443/x.xpi".isAddonDownloadUrl())
        assertTrue("HTTPS://addons.mozilla.org:443/x.xpi".isAddonDownloadUrl())
    }
}

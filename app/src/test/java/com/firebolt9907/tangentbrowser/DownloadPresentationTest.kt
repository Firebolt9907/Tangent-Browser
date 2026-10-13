package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.downloads.downloadProgress
import com.firebolt9907.tangentbrowser.ui.downloads.visibleDownloads
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.state.content.DownloadState.Status
import org.junit.Assert.*
import org.junit.Test

class DownloadPresentationTest {
    @Test fun unknownLengthsStayIndeterminateAndInvalidByteCountsCannotOverflow() {
        assertNull(downloadProgress(100, null))
        assertNull(downloadProgress(100, 0))
        assertNull(downloadProgress(100, -1))
        assertEquals(0f, downloadProgress(-20, 100)!!, 0f)
        assertEquals(1f, downloadProgress(Long.MAX_VALUE, 100)!!, 0f)
        assertEquals(0.5f, downloadProgress(50, 100)!!, 0f)
    }

    @Test fun privateFilesNeverAppearInTheNormalBrowserOrLibrary() {
        val normal = download("normal", private = false, created = 1)
        val secret = download("secret", private = true, created = 2)
        assertEquals(listOf(normal), visibleDownloads(listOf(secret, normal), private = false))
        assertEquals(listOf(secret), visibleDownloads(listOf(normal, secret), private = true))
    }

    @Test fun cancelledFilesDoNotResurfaceAndNewDownloadsTakePriority() {
        val paused = download("paused", created = 1).copy(status = Status.PAUSED)
        val recent = download("recent", created = 2)
        val cancelled = download("cancelled", created = 3).copy(status = Status.CANCELLED)
        assertEquals(listOf(recent, paused), visibleDownloads(listOf(paused, cancelled, recent), false))
    }

    private fun download(id: String, private: Boolean = false, created: Long) = DownloadState(
        url = "https://example.com/$id", id = id, private = private,
        directoryPath = "/downloads", createdTime = created,
    )
}

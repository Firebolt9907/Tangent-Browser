package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.state.blobTypeLabel
import com.firebolt9907.tangentbrowser.state.extensionForMime
import com.firebolt9907.tangentbrowser.state.isBlobUrl
import com.firebolt9907.tangentbrowser.state.sanitizeFileName
import com.firebolt9907.tangentbrowser.state.urlExtension
import com.firebolt9907.tangentbrowser.ui.content.BlobCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of blob-file support: what the address bar shows in place of an opaque
 * `blob:https://site.com/<uuid>` URL, and how a capture is named. The label is user-visible on
 * every blob page, and the filename is spliced into a real path, so both are pinned here.
 */
class BlobFilesTest {

    @Test
    fun `recognises blob urls`() {
        assertTrue(isBlobUrl("blob:https://site.com/9f2c-uuid"))
        assertFalse(isBlobUrl("https://site.com/file.pdf"))
        assertFalse(isBlobUrl(null))
        assertFalse(isBlobUrl(""))
    }

    @Test
    fun `labels by the url's own extension before any bytes are captured`() {
        // The common shape: a page names the blob after the file it generated.
        assertEquals("PDF file", blobTypeLabel(null, "blob:https://site.com/statement.pdf"))
        assertEquals("Image", blobTypeLabel(null, "blob:https://site.com/chart.png"))
        assertEquals("Spreadsheet", blobTypeLabel(null, "export.xlsx"))
    }

    @Test
    fun `falls back to the mime type when the url is a bare uuid`() {
        val uuidBlob = "blob:https://site.com/3fa85f64-5717-4562-b3fc-2c963f66afa6"
        assertEquals("PDF file", blobTypeLabel("application/pdf", uuidBlob))
        assertEquals("Image", blobTypeLabel("image/webp", uuidBlob))
        assertEquals("Video", blobTypeLabel("video/mp4;codecs=avc1", uuidBlob))
        assertEquals("CSV file", blobTypeLabel("text/csv", uuidBlob))
        assertEquals("Archive", blobTypeLabel("application/zip", uuidBlob))
        // Nothing to go on at all — still a word, never the UUID.
        assertEquals("File", blobTypeLabel(null, uuidBlob))
        assertEquals("File", blobTypeLabel("application/octet-stream", uuidBlob))
    }

    @Test
    fun `url extension ignores query strings and non-extensions`() {
        assertEquals("pdf", urlExtension("blob:https://site.com/a/report.pdf?v=2"))
        assertEquals("", urlExtension("blob:https://site.com/3fa85f64-5717-4562-b3fc-2c963f66afa6"))
        assertEquals("", urlExtension("https://site.com/path/"))
        assertEquals("", urlExtension(null))
    }

    @Test
    fun `sanitize keeps a usable name and always yields an extension`() {
        assertEquals("Q3 report.pdf", sanitizeFileName("Q3 report.pdf", "bin"))
        assertEquals("invoice.pdf", sanitizeFileName("invoice", "pdf"))
        // Path separators must never survive — the name is joined onto a directory.
        assertEquals("passwd.pdf", sanitizeFileName("../../etc/passwd", "pdf"))
        assertEquals("b_c.txt", sanitizeFileName("a/../b:c", "txt"))
        assertEquals("file.pdf", sanitizeFileName(null, "pdf"))
        assertEquals("file.pdf", sanitizeFileName("   ", "pdf"))
    }

    @Test
    fun `mime maps to a conventional extension`() {
        assertEquals("pdf", extensionForMime("application/pdf"))
        assertEquals("jpg", extensionForMime("image/jpeg"))
        assertEquals("png", extensionForMime("image/png"))
        assertEquals("bin", extensionForMime(""))
        assertEquals("bin", extensionForMime("application/octet-stream"))
    }

    @Test
    fun `capture script is nonce-tagged and one line`() {
        val script = BlobCapture.buildScript()
        assertTrue(script.startsWith("javascript:"))
        assertFalse("injected via a javascript: URL, so it must be flattened", script.contains("\n"))
        // The bridge is only addressable with this process's nonce (see BridgeGuard).
        assertTrue(script.contains(com.firebolt9907.tangentbrowser.ui.content.BridgeGuard.tag(BlobCapture.BRIDGE_MESSAGE)))
        // It must never act on a document that isn't the blob itself.
        assertTrue(script.contains("blob:"))
    }
}

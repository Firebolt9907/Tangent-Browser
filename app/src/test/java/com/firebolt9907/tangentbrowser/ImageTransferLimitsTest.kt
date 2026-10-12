package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.content.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class ImageTransferLimitsTest {
    @Test fun actualStreamSizeIsBoundedWithoutContentLength() {
        assertNull(readBoundedImage(ByteArrayInputStream(byteArrayOf()), 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), readBoundedImage(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 4))
        assertNull(readBoundedImage(ByteArrayInputStream(ByteArray(5)), 4))
        var read = 0
        val endless = object : InputStream() {
            override fun read(): Int { read++; return 42 }
        }
        assertNull(readBoundedImage(endless, 100))
        assertEquals("Read at most the limit plus one sentinel byte", 101, read)
    }

    @Test fun shortReadsDoNotBypassLimit() {
        val fragmented = object : ByteArrayInputStream(ByteArray(10) { it.toByte() }) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        assertNull(readBoundedImage(fragmented, 9))
    }

    @Test fun zeroLengthReadsStillMakeProgress() {
        val zeroReads = object : ByteArrayInputStream(byteArrayOf(1, 2)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = if (available() > 0) 0 else -1
        }
        assertArrayEquals(byteArrayOf(1, 2), readBoundedImage(zeroReads, 2))
    }

    @Test fun nonImagesAreNotRelabeledAsJpeg() {
        for (mime in listOf(null, "", "text/html", "application/octet-stream", "image/", "image/png\r\nX: value")) {
            assertNull(mime, imageTransferMime(mime))
        }
        assertEquals("image/png", imageTransferMime("IMAGE/PNG; charset=binary"))
        assertEquals("image/svg+xml", imageTransferMime("image/svg+xml"))
    }

    @Test fun filenamesFollowContentNotCollidingUrlHashes() {
        // Java's hash collision is the reason String.hashCode cannot identify shared content.
        assertEquals("https://example.test/Aa".hashCode(), "https://example.test/BB".hashCode())
        assertNotEquals(sharedImageName(byteArrayOf(1)), sharedImageName(byteArrayOf(2)))
        assertEquals(sharedImageName(byteArrayOf(1)), sharedImageName(byteArrayOf(1)))
        assertTrue(sharedImageName(byteArrayOf(1)).matches(Regex("img_[0-9a-f]{64}")))
    }

    @Test fun compressedImageDimensionsCannotCauseUnboundedOcrAllocation() {
        assertNull(ocrImageSampleSize(-1, 100))
        assertNull(ocrImageSampleSize(100, 0))
        assertEquals(1, ocrImageSampleSize(2048, 2048))
        for ((width, height) in listOf(100_000 to 100_000, Int.MAX_VALUE to Int.MAX_VALUE, 1 to Int.MAX_VALUE)) {
            val sample = requireNotNull(ocrImageSampleSize(width, height))
            val pixels = ((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample)
            assertTrue("$width x $height allocated $pixels", pixels <= 4_194_304L)
        }
    }
}

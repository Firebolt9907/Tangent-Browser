package com.firebolt9907.tangentbrowser

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.firebolt9907.tangentbrowser.ui.reader.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ReaderZoomTest {
    @Test fun `fit prevents panning including letterboxed axes`() {
        assertEquals(Offset.Zero, readerImagePanBounds(Size(400f, 800f), Size(1200f, 600f), 1f))
        assertEquals(Offset(200f, 0f), readerImagePanBounds(Size(400f, 800f), Size(1200f, 600f), 2f))
    }

    @Test fun `portrait image pans only where scaled content exceeds viewport`() {
        assertEquals(Offset(0f, 400f), readerImagePanBounds(Size(400f, 800f), Size(400f, 1600f), 2f))
        assertEquals(Offset(50f, -100f), clampReaderImagePan(Offset(90f, -300f), Offset(50f, 100f)))
    }

    @Test fun `unknown image dimensions cannot create unbounded pan`() {
        assertEquals(Offset.Zero, readerImagePanBounds(Size(400f, 800f), Size.Zero, 6f))
        assertEquals(Offset.Zero, readerImagePanBounds(Size.Zero, Size(400f, 800f), 6f))
    }

    @Test fun `gallery preserves article order and captions while removing duplicate images`() {
        val article = ReaderArticle("Title", null, listOf(
            ReaderBlock.Paragraph("Text"),
            ReaderBlock.Image("https://example.com/a.jpg", "First"),
            ReaderBlock.Figure("data:image/svg+xml,chart", "Chart"),
            ReaderBlock.Image("https://example.com/a.jpg", "Repeated"),
            ReaderBlock.Image("https://example.com/b.jpg", null),
        ))
        assertEquals(listOf(
            ReaderGalleryImage("https://example.com/a.jpg", "First"),
            ReaderGalleryImage("data:image/svg+xml,chart", "Chart", lightBackground = true),
            ReaderGalleryImage("https://example.com/b.jpg"),
        ), readerGalleryImages(article))
    }
    @Test fun `embedded SVG decodes percent escapes and preserves plus signs`() {
        assertArrayEquals("<svg>x+y</svg>".toByteArray(), readerImageData("data:image/svg+xml;utf8,%3Csvg%3Ex+y%3C%2Fsvg%3E") as ByteArray)
        assertArrayEquals("<svg/>".toByteArray(), readerImageData("data:image/svg+xml;base64,PHN2Zy8+") as ByteArray)
        assertEquals("https://example.com/a.jpg", readerImageData("https://example.com/a.jpg"))
        assertEquals("data:image/png;base64,invalid!", readerImageData("data:image/png;base64,invalid!"))
    }

}

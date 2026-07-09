package com.firebolt9907.luminabrowser

import com.firebolt9907.luminabrowser.ui.reader.ReaderArticle
import com.firebolt9907.luminabrowser.ui.reader.ReaderBlock
import com.firebolt9907.luminabrowser.ui.reader.estimateReadMinutes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reader's "N min read" estimate (extracted to ReaderViewHelpers.kt). Pins the ~220-wpm math,
 * the sub-60-word suppression, and the rule that non-prose blocks (code, images, dividers) don't
 * count toward reading time.
 */
class ReaderReadTimeTest {

    private fun article(vararg blocks: ReaderBlock) =
        ReaderArticle(title = "t", byline = null, blocks = blocks.toList())

    private fun paragraphOf(words: Int) =
        ReaderBlock.Paragraph(text = List(words) { "word" }.joinToString(" "))

    @Test
    fun `a stub under 60 words reads as zero minutes`() {
        assertEquals(0, estimateReadMinutes(article(paragraphOf(59))))
    }

    @Test
    fun `at the 60-word floor it rounds up to a 1 minute read`() {
        assertEquals(1, estimateReadMinutes(article(paragraphOf(60))))
    }

    @Test
    fun `roughly 220 words is a 1 minute read`() {
        assertEquals(1, estimateReadMinutes(article(paragraphOf(220))))
    }

    @Test
    fun `roughly 330 words rounds to a 2 minute read`() {
        assertEquals(2, estimateReadMinutes(article(paragraphOf(330))))
    }

    @Test
    fun `code, image and divider blocks do not count toward reading time`() {
        val a = article(
            ReaderBlock.Code(List(500) { "x" }.joinToString(" ")),
            ReaderBlock.Image(src = "https://e/x.png", caption = null),
            ReaderBlock.Divider,
        )
        assertEquals(0, estimateReadMinutes(a))
    }

    @Test
    fun `prose across headings, lists and tables is all counted`() {
        val a = article(
            ReaderBlock.Heading(level = 1, text = List(20) { "h" }.joinToString(" ")),
            ReaderBlock.ListItem(text = List(20) { "l" }.joinToString(" "), ordered = false),
            ReaderBlock.Table(rows = listOf(List(20) { "c" }), hasHeader = false),
        )
        // 60 prose words total -> 1 minute (proving headings/lists/tables contribute).
        assertEquals(1, estimateReadMinutes(a))
    }
}

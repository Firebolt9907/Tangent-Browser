package com.firebolt9907.luminabrowser

import com.firebolt9907.luminabrowser.ui.reader.ReaderBlock
import com.firebolt9907.luminabrowser.ui.reader.ReaderExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in ReaderExtractor.parseArticleJson — the boundary that turns the injected extractor
 * script's JSON into the reader's block model. Covers the block types added across the improvement
 * loop (code, table, image dimensions) so their wire format can't silently regress.
 */
class ReaderParseArticleJsonTest {

    private fun parse(json: String) = ReaderExtractor.parseArticleJson(json)

    @Test
    fun `parses title byline and source url`() {
        val article = parse(
            """{"title":"T","byline":"By A","url":"https://ex.com/a","blocks":[{"type":"p","text":"hi"}]}""",
        )!!
        assertEquals("T", article.title)
        assertEquals("By A", article.byline)
        assertEquals("https://ex.com/a", article.sourceUrl)
    }

    @Test
    fun `literal null strings are treated as absent`() {
        val article = parse("""{"title":"T","byline":"null","url":"null","blocks":[{"type":"p","text":"x"}]}""")!!
        assertNull(article.byline)
        assertNull(article.sourceUrl)
    }

    @Test
    fun `parses each core block type`() {
        val article = parse(
            """{"title":"T","blocks":[
              {"type":"h","level":2,"text":"Head"},
              {"type":"p","text":"Para"},
              {"type":"bq","text":"Quote"},
              {"type":"pre","text":"code line"},
              {"type":"li","text":"Item","ordered":true,"index":3}
            ]}""",
        )!!
        val blocks = article.blocks
        assertEquals(5, blocks.size)
        assertEquals(2, (blocks[0] as ReaderBlock.Heading).level)
        assertEquals("Para", (blocks[1] as ReaderBlock.Paragraph).text)
        assertEquals("Quote", (blocks[2] as ReaderBlock.Blockquote).text)
        assertEquals("code line", (blocks[3] as ReaderBlock.Code).text)
        val li = blocks[4] as ReaderBlock.ListItem
        assertTrue(li.ordered)
        assertEquals(3, li.index)
    }

    @Test
    fun `image carries intrinsic dimensions when present and null otherwise`() {
        val withDims = parse(
            """{"title":"T","blocks":[{"type":"img","src":"https://ex.com/i.jpg","caption":"c","w":800,"h":600}]}""",
        )!!.blocks[0] as ReaderBlock.Image
        assertEquals(800, withDims.width)
        assertEquals(600, withDims.height)
        assertEquals("c", withDims.caption)

        val noDims = parse(
            """{"title":"T","blocks":[{"type":"img","src":"https://ex.com/i.jpg"}]}""",
        )!!.blocks[0] as ReaderBlock.Image
        assertNull(noDims.width)
        assertNull(noDims.height)
        assertNull(noDims.caption)
    }

    @Test
    fun `empty-src image block is dropped`() {
        val article = parse("""{"title":"T","blocks":[{"type":"img","src":""},{"type":"p","text":"x"}]}""")!!
        assertEquals(1, article.blocks.size)
        assertTrue(article.blocks[0] is ReaderBlock.Paragraph)
    }

    @Test
    fun `list item carries nesting depth defaulting to zero`() {
        val nested = parse(
            """{"title":"T","blocks":[{"type":"li","text":"sub","ordered":false,"depth":2}]}""",
        )!!.blocks[0] as ReaderBlock.ListItem
        assertEquals(2, nested.depth)

        val flat = parse(
            """{"title":"T","blocks":[{"type":"li","text":"top","ordered":false}]}""",
        )!!.blocks[0] as ReaderBlock.ListItem
        assertEquals(0, flat.depth)
    }

    @Test
    fun `parses a table with header and row-major cells`() {
        val table = parse(
            """{"title":"T","blocks":[{"type":"table","hasHeader":true,"rows":[["A","B"],["1","2"],["3","4"]]}]}""",
        )!!.blocks[0] as ReaderBlock.Table
        assertTrue(table.hasHeader)
        assertEquals(3, table.rows.size)
        assertEquals(listOf("A", "B"), table.rows[0])
        assertEquals("4", table.rows[2][1])
    }

    @Test
    fun `single-row table is rejected`() {
        val article = parse(
            """{"title":"T","blocks":[{"type":"table","rows":[["only"]]},{"type":"p","text":"x"}]}""",
        )!!
        assertEquals(1, article.blocks.size)
        assertTrue(article.blocks[0] is ReaderBlock.Paragraph)
    }

    @Test
    fun `returns null when blocks array is missing`() {
        assertNull(parse("""{"title":"T"}"""))
    }

    @Test
    fun `unknown block types are skipped`() {
        val article = parse(
            """{"title":"T","blocks":[{"type":"video","src":"x"},{"type":"p","text":"kept"}]}""",
        )!!
        assertEquals(1, article.blocks.size)
        assertEquals("kept", (article.blocks[0] as ReaderBlock.Paragraph).text)
    }
}

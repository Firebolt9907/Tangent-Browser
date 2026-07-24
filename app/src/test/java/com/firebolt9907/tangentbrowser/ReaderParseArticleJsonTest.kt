package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.reader.ReaderBlock
import com.firebolt9907.tangentbrowser.ui.reader.ReaderExtractor
import com.firebolt9907.tangentbrowser.ui.reader.ReaderLinkSpan
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
    fun `pullQuote flag defaults to false when absent`() {
        val bq = parse(
            """{"title":"T","blocks":[{"type":"bq","text":"regular quote"}]}""",
        )!!.blocks[0] as ReaderBlock.Blockquote
        assertEquals("regular quote", bq.text)
        assertTrue(!bq.pullQuote)
    }

    @Test
    fun `pullQuote flag is parsed when present`() {
        val bq = parse(
            """{"title":"T","blocks":[{"type":"bq","text":"big quote","pullQuote":true}]}""",
        )!!.blocks[0] as ReaderBlock.Blockquote
        assertTrue(bq.pullQuote)
        assertEquals("big quote", bq.text)
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
    fun `figure_rich equation block carries src and equation flag`() {
        val fig = parse(
            """{"title":"T","blocks":[{"type":"figure_rich","src":"https://wikimedia.org/api/rest_v1/media/math/render/svg/abc","equation":true,"w":110,"h":28}]}""",
        )!!.blocks[0] as ReaderBlock.Figure
        assertTrue(fig.equation)
        assertEquals(110, fig.width)
        assertEquals(28, fig.height)
        assertTrue(fig.src.endsWith("/abc"))
    }

    @Test
    fun `figure_rich chart defaults equation to false and drops empty src`() {
        val chart = parse(
            """{"title":"T","blocks":[{"type":"figure_rich","src":"data:image/svg+xml;utf8,%3Csvg%3E","caption":"Fig 1"}]}""",
        )!!.blocks[0] as ReaderBlock.Figure
        assertTrue(!chart.equation)
        assertEquals("Fig 1", chart.caption)

        val dropped = parse(
            """{"title":"T","blocks":[{"type":"figure_rich","src":""},{"type":"p","text":"x"}]}""",
        )!!
        assertEquals(1, dropped.blocks.size)
        assertTrue(dropped.blocks[0] is ReaderBlock.Paragraph)
    }

    @Test
    fun `paragraph inline math parses in order with dimensions`() {
        val s = "\uE000" // MATH_SENTINEL
        val p = parse(
            """{"title":"T","blocks":[{"type":"p","text":"a${s}b${s}","imath":[{"src":"m1","w":10,"h":18},{"src":"m2"}]}]}""",
        )!!.blocks[0] as ReaderBlock.Paragraph
        assertEquals(2, p.inlineMath.size)
        assertEquals("m1", p.inlineMath[0].src)
        assertEquals(10, p.inlineMath[0].width)
        assertEquals(18, p.inlineMath[0].height)
        assertEquals("m2", p.inlineMath[1].src)
        assertNull(p.inlineMath[1].width)
        // One sentinel per inline-math entry, preserved verbatim through parsing.
        assertEquals(2, p.text.count { it == '\uE000' })
    }

    @Test
    fun `paragraph without imath has empty inline math list`() {
        val p = parse(
            """{"title":"T","blocks":[{"type":"p","text":"plain"}]}""",
        )!!.blocks[0] as ReaderBlock.Paragraph
        assertTrue(p.inlineMath.isEmpty())
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

    @Test
    fun `paragraph with links parses spans correctly`() {
        val article = parse(
            """{"title":"T","blocks":[
              {"type":"p","text":"This is a link.","spans":[
                {"text":"link","href":"https://google.com"},
                {"text":"other","href":""},
                {"text":"","href":"https://example.com"}
              ]}
            ]}""",
        )!!
        val p = article.blocks[0] as ReaderBlock.Paragraph
        assertEquals("This is a link.", p.text)
        assertEquals(1, p.links.size)
        assertEquals("link", p.links[0].text)
        assertEquals("https://google.com", p.links[0].href)
    }

    @Test
    fun `paragraph with no spans field defaults to empty list`() {
        val article = parse(
            """{"title":"T","blocks":[{"type":"p","text":"No spans here"}]}""",
        )!!
        val p = article.blocks[0] as ReaderBlock.Paragraph
        assertEquals("No spans here", p.text)
        assertTrue(p.links.isEmpty())
    }

    @Test
    fun `paragraph with empty or malformed spans array skips gracefully`() {
        val article = parse(
            """{"title":"T","blocks":[
              {"type":"p","text":"Malformed","spans":[]},
              {"type":"p","text":"NullObj","spans":[null, {"text":"ok","href":"https://ex.com"}, {"text":"notalink","href":null}]}
            ]}""",
        )!!
        val p1 = article.blocks[0] as ReaderBlock.Paragraph
        assertTrue(p1.links.isEmpty())

        val p2 = article.blocks[1] as ReaderBlock.Paragraph
        assertEquals(1, p2.links.size)
        assertEquals("ok", p2.links[0].text)
        assertEquals("https://ex.com", p2.links[0].href)
    }
}

package com.mobileharness.code.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageExtractorTest {

    @Test fun extractsStructuredJsonLdArticleBody() {
        val html = """
            <html><head>
            <script type="application/ld+json">
            {"@type":"NewsArticle","headline":"A Big Story","author":{"name":"Jane Doe"},"articleBody":"This is the full article body with enough substantive content to pass the minimum threshold and be extracted as structured page text for research."}
            </script>
            </head><body>unrelated</body></html>
        """.trimIndent()
        val structured = PageExtractor.structured(html)
        assertTrue(structured != null)
        assertTrue(structured!!.contains("A Big Story"))
        assertTrue(structured.contains("Jane Doe"))
        assertTrue(structured.contains("article body"))
    }

    @Test fun structuredReturnsNullWhenNoSubstantiveContent() {
        val html = "<html><body>just a tiny page</body></html>"
        assertNull(PageExtractor.structured(html))
    }

    @Test fun extractsTitleHeadingsLinksAndMetadata() {
        val html = """
            <html><head>
            <title>My Page &amp; More</title>
            <meta name="description" content="A description here">
            </head><body>
            <h1>Main Heading</h1>
            <h2>Sub Heading</h2>
            <p>Paragraph one.</p>
            <p>Paragraph two.</p>
            <a href="https://example.com/out">Out link</a>
            </body></html>
        """.trimIndent()
        val doc = PageExtractor.extract(html)
        assertEquals("My Page & More", doc.title)
        assertEquals(listOf("Main Heading", "Sub Heading"), doc.headings)
        assertTrue(doc.text.contains("Paragraph one."))
        assertEquals(listOf("https://example.com/out"), doc.links)
        assertEquals("A description here", doc.metadata["description"])
    }

    @Test fun mainTextStripsScriptsAndKeepsParagraphs() {
        val html = """
            <html><body>
            <script>alert('evil')</script>
            <style>.x{color:red}</style>
            <p>Hello world.</p>
            <p>Second paragraph.</p>
            </body></html>
        """.trimIndent()
        val text = PageExtractor.mainText(html)
        assertTrue(!text.contains("evil"))
        assertTrue(text.contains("Hello world."))
        assertTrue(text.contains("Second paragraph."))
    }

    @Test fun extractIsBoundedAgainstHugeInput() {
        val huge = StringBuilder("<html><body>")
        repeat(50_000) { huge.append("<p>repeated content paragraph number $it</p>") }
        huge.append("</body></html>")
        val doc = PageExtractor.extract(huge.toString())
        assertTrue(doc.text.length <= PageExtractor.MAX_TEXT_CHARS)
    }
}

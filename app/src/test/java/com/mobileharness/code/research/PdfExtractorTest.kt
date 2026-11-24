package com.mobileharness.code.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class PdfExtractorTest {

    private fun deflate(text: String): ByteArray {
        val input = text.toByteArray(Charsets.ISO_8859_1)
        val deflater = Deflater()
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    @Test fun inflateRoundTrips() {
        val text = "BT (Hello world) Tj ET"
        val deflated = deflate(text)
        val inflated = String(PdfExtractor.inflate(deflated), Charsets.ISO_8859_1)
        assertEquals(text, inflated)
    }

    @Test fun extractTextPullsTjAndTjArray() {
        val content = "BT (Hello world) Tj (Second line) ' [(An) -250 (droid)] TJ ET"
        val text = PdfExtractor.extractText(content)
        assertTrue(text.contains("Hello world"))
        assertTrue(text.contains("Second line"))
        assertTrue(text.contains("Android"))
    }

    @Test fun isPageObjectDistinguishesPageFromPages() {
        assertEquals(true, PdfExtractor.isPageObject("<< /Type /Page /Parent 2 0 R >>"))
        assertEquals(false, PdfExtractor.isPageObject("<< /Type /Pages /Kids [3 0 R] >>"))
    }

    @Test fun parseObjectsExtractsByNumber() {
        val raw = "1 0 obj\n<< /Type /Catalog >>\nendobj\n2 0 obj\n<< /Type /Page >>\nendobj"
        val objects = PdfExtractor.parseObjects(raw)
        assertEquals(2, objects.size)
        assertTrue(objects[1]!!.contains("/Catalog"))
        assertTrue(objects[2]!!.contains("/Page"))
    }

    @Test fun extractFullPdfWithFlateContentAndPageNumbers() {
        val content = "BT (Page one text) Tj ET"
        val streamBytes = deflate(content)
        val stream = String(streamBytes, Charsets.ISO_8859_1)
        val pdf = buildPdf(listOf(stream))
        val doc = PdfExtractor.extract(pdf.toByteArray(Charsets.ISO_8859_1))
        assertEquals(1, doc.pageCount)
        assertEquals(1, doc.pages.size)
        assertEquals(1, doc.pages[0].pageNumber)
        assertTrue(doc.pages[0].text.contains("Page one text"))
    }

    @Test fun extractHandlesTwoPages() {
        val stream1 = String(deflate("BT (First page) Tj ET"), Charsets.ISO_8859_1)
        val stream2 = String(deflate("BT (Second page) Tj ET"), Charsets.ISO_8859_1)
        val pdf = buildPdf(listOf(stream1, stream2), twoPages = true)
        val doc = PdfExtractor.extract(pdf.toByteArray(Charsets.ISO_8859_1))
        assertEquals(2, doc.pageCount)
        assertTrue(doc.pages[0].text.contains("First page"))
        assertTrue(doc.pages[1].text.contains("Second page"))
    }

    @Test fun extractEmptyInputReturnsEmptyDocument() {
        val doc = PdfExtractor.extract(ByteArray(0))
        assertEquals(0, doc.pageCount)
    }

    /** Builds a minimal PDF with [pageStreams] as FlateDecode content streams. */
    private fun buildPdf(pageStreams: List<String>, twoPages: Boolean = false): String {
        val sb = StringBuilder("%PDF-1.4\n")
        var objNum = 1
        val catalog = objNum++
        sb.append("$catalog 0 obj\n<< /Type /Catalog /Pages ${objNum} 0 R >>\nendobj\n")
        val pagesTree = objNum++
        // Children are pages objects (one or two).
        val pageRefs = mutableListOf<Int>()
        val streamRefs = mutableListOf<Int>()
        for (i in pageStreams.indices) {
            val page = objNum++
            val stream = objNum++
            pageRefs += page
            streamRefs += stream
        }
        val kids = pageRefs.joinToString(" ") { "$it 0 R" }
        sb.append("$pagesTree 0 obj\n<< /Type /Pages /Kids [$kids] /Count ${pageRefs.size} >>\nendobj\n")
        pageRefs.forEachIndexed { i, page ->
            sb.append("$page 0 obj\n<< /Type /Page /Parent $pagesTree 0 R /Contents ${streamRefs[i]} 0 R >>\nendobj\n")
        }
        streamRefs.forEachIndexed { i, stream ->
            val data = pageStreams[i]
            sb.append("$stream 0 obj\n<< /Length ${data.length} /Filter /FlateDecode >>\nstream\n")
            sb.append(data)
            sb.append("\nendstream\nendobj\n")
        }
        val xrefOffset = sb.length
        sb.append("xref\n0 ${objNum}\n")
        sb.append("0000000000 65535 f \n")
        sb.append("trailer\n<< /Size $objNum /Root $catalog 0 R >>\nstartxref\n$xrefOffset\n%%EOF")
        return sb.toString()
    }
}

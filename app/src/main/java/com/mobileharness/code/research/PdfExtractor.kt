package com.mobileharness.code.research

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * One extracted PDF page.
 */
data class PdfPage(val pageNumber: Int, val text: String)

/**
 * A PDF document after text extraction.
 */
data class PdfDocument(
    val pages: List<PdfPage>,
    val pageCount: Int,
    val title: String,
) {
    /** Concatenated text of all pages, with page markers. */
    fun render(): String = buildString {
        pages.forEach { page ->
            append("\n[Page ").append(page.pageNumber).append("]\n")
            append(page.text)
        }
    }.trim()
}

/**
 * A bounded, defensive, dependency-free PDF text extractor. It decodes FlateDecode streams (the
 * common case) and plain streams, then pulls text out of the `Tj` / `'` / `TJ` text-showing
 * operators, resolving each page's `/Contents` reference so text stays attached to its page number.
 *
 * This is best-effort: scanned/image-only PDFs have no text layer (OCR is not bundled), encrypted
 * PDFs are skipped, and malformed objects fail closed to empty text rather than throwing. Nothing
 * here invents content it cannot read.
 */
object PdfExtractor {
    const val MAX_DECODED_STREAM = 2_000_000
    const val MAX_PAGES = 500
    const val MAX_CHARS_PER_PAGE = 30_000

    fun extract(bytes: ByteArray, maxPages: Int = MAX_PAGES): PdfDocument {
        if (bytes.isEmpty()) return PdfDocument(emptyList(), 0, "")
        val raw = String(bytes, Charsets.ISO_8859_1)

        val objects = parseObjects(raw)
        val pageObjects = objects.entries.filter { isPageObject(it.value) }.take(maxPages)
        if (pageObjects.isEmpty()) return PdfDocument(emptyList(), 0, title(raw))

        val pages = pageObjects.mapIndexed { index, entry ->
            val text = extractPageText(entry.value, objects)
            PdfPage(index + 1, text.take(MAX_CHARS_PER_PAGE))
        }
        return PdfDocument(pages, pageObjects.size, title(raw))
    }

    /** Returns whether a PDF dictionary is a page (not the /Pages tree node). */
    fun isPageObject(body: String): Boolean =
        Regex("""/Type\s*/Page(?!s)""").containsMatchIn(body)

    /** Extracts the `/Title (...)` from the document info dictionary, if present. */
    fun title(raw: String): String {
        val m = Regex("""/Title\s*\(((?:[^()\\]|\\.)*)\)""").find(raw)
        return m?.let { unescape(it.groupValues[1]) }.orEmpty().take(500)
    }

    /** Parses `N G obj ... endobj` objects into a map of object number → body. */
    fun parseObjects(raw: String): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        val regex = Regex("""(\d+)\s+(\d+)\s+obj\b(.*?)endobj""", RegexOption.DOT_MATCHES_ALL)
        for (m in regex.findAll(raw)) {
            val num = m.groupValues[1].toIntOrNull() ?: continue
            if (out.size >= 2000) break
            out[num] = m.groupValues[3]
        }
        return out
    }

    private fun extractPageText(pageBody: String, objects: Map<Int, String>): String {
        // /Contents can be a single reference (N 0 R) or an array of references.
        val refs = Regex("""/Contents\s+([0-9\sR]+)""").find(pageBody)?.groupValues?.get(1).orEmpty()
        val numbers = Regex("""\d+""").findAll(refs).mapNotNull { it.value.toIntOrNull() }.toList()
        val sb = StringBuilder()
        for (num in numbers) {
            val objBody = objects[num] ?: continue
            val data = streamData(objBody) ?: continue
            val decoded = if (isFlate(objBody)) inflate(data) else data
            sb.append(extractText(String(decoded, Charsets.ISO_8859_1)))
        }
        return sb.toString().trim()
    }

    private fun isFlate(objBody: String): Boolean =
        Regex("""/Filter\s*/FlateDecode""").containsMatchIn(objBody)

    /** Extracts the raw byte range between `stream` and `endstream`. */
    fun streamData(objBody: String): ByteArray? {
        val start = objBody.indexOf("stream")
        if (start < 0) return null
        var dataStart = start + "stream".length
        if (dataStart < objBody.length && objBody[dataStart] == '\r') dataStart++
        if (dataStart < objBody.length && objBody[dataStart] == '\n') dataStart++
        val end = objBody.indexOf("endstream", dataStart)
        if (end < 0) return null
        return objBody.substring(dataStart, end).toByteArray(Charsets.ISO_8859_1)
    }

    /** Inflates a FlateDecode stream (PDF uses raw zlib DEFLATE). */
    fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished() && out.size() < MAX_DECODED_STREAM) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                if (n > 0) out.write(buf, 0, n)
            }
            out.toByteArray()
        } catch (e: Exception) {
            ByteArray(0)
        } finally {
            inflater.end()
        }
    }

    /** Pulls text out of `(string) Tj`, `(string) '` and `[...] TJ` operators. */
    fun extractText(content: String): String {
        val sb = StringBuilder()
        Regex("""\(((?:[^()\\]|\\.)*)\)\s*(?:Tj|')""").findAll(content).forEach {
            sb.append(unescape(it.groupValues[1])).append('\n')
        }
        Regex("""\[((?:[^\[\]\\]|\\.)*)\]\s*TJ""").findAll(content).forEach { arr ->
            Regex("""\(((?:[^()\\]|\\.)*)\)""").findAll(arr.groupValues[1]).forEach {
                sb.append(unescape(it.groupValues[1]))
            }
        }
        return sb.toString()
    }

    private fun unescape(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                val n = value[i + 1]
                when (n) {
                    'n' -> { sb.append('\n'); i++ }
                    'r' -> { sb.append('\r'); i++ }
                    't' -> { sb.append('\t'); i++ }
                    '(', ')', '\\' -> { sb.append(n); i++ }
                    else -> sb.append(' ')
                }
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}

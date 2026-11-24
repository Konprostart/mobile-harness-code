package com.mobileharness.code.research

import org.json.JSONArray
import org.json.JSONObject

/**
 * A page document after extraction: the title, headings, main text, outbound links, metadata, and
 * (when present) page-supplied structured text (JSON-LD). Everything is bounded and defensive so a
 * hostile or malformed HTML document can never crash or exhaust memory.
 */
data class ExtractedDocument(
    val title: String,
    val headings: List<String>,
    val text: String,
    val links: List<String>,
    val structured: String?,
    val metadata: Map<String, String>,
)

/**
 * Page extraction that replaces the old regex-only `stripMarkup`. It reads:
 *  - page-supplied structured text (JSON-LD `Article`/`NewsArticle`/… articleBody/headline),
 *  - `<title>` and meta description,
 *  - headings (`h1`..`h6`) and paragraphs/lists/table cells as structured text,
 *  - outbound links.
 * Embedded scripts are never evaluated.
 *
 * Structured JSON-LD extraction is ported/adapted from AndroidHarness `StructuredPageText` (MIT,
 * (c) 2026 Sanuu7); see THIRD_PARTY_NOTICES.md / NOTICE.
 */
object PageExtractor {
    const val MAX_HTML_CHARS = 2_000_000
    const val MAX_TEXT_CHARS = 200_000
    const val MAX_LINKS = 200
    const val MAX_HEADINGS = 200

    private val scripts = Regex("<script\\b([^>]*)>(.*?)</script\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val styles = Regex("<style\\b[^>]*>(.*?)</style\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ldType = Regex("\\btype\\s*=\\s*[\"']application/ld\\+json[\"']", RegexOption.IGNORE_CASE)
    private val types = setOf("Article", "NewsArticle", "BlogPosting", "DiscussionForumPosting", "SocialMediaPosting", "Comment", "Question", "Answer")

    fun extract(html: String): ExtractedDocument {
        val doc = html.take(MAX_HTML_CHARS)
        val structured = structured(doc)
        val headings = extractHeadings(doc)
        val text = mainText(doc)
        val links = extractLinks(doc)
        return ExtractedDocument(
            title = title(doc),
            headings = headings,
            text = text,
            links = links,
            structured = structured,
            metadata = metadata(doc),
        )
    }

    /** Page-supplied structured text (JSON-LD), or null when nothing substantive was found. */
    fun structured(html: String): String? {
        val sections = linkedSetOf<String>()
        var visited = 0
        var substantive = 0
        fun text(o: JSONObject, key: String): String = o.optString(key, "").trim()

        fun walk(element: Any?, depth: Int) {
            if (element == null || depth > 10 || ++visited > 400 || sections.sumOf { it.length } >= 200_000) return
            when (element) {
                is JSONArray -> for (i in 0 until minOf(element.length(), 80)) walk(element.opt(i), depth + 1)
                is JSONObject -> {
                    val schemaTypes = when (val type = element.opt("@type")) {
                        is JSONArray -> (0 until type.length()).mapNotNull { type.optString(it) }
                        is String -> listOf(type)
                        else -> emptyList()
                    }.map { it.substringAfterLast('/').substringAfterLast('#') }
                    if (schemaTypes.any { it in types }) {
                        val body = htmlToText(text(element, "articleBody").ifBlank { text(element, "text") })
                        val title = htmlToText(text(element, "headline").ifBlank { text(element, "name") })
                        val author = when (val author = element.opt("author")) {
                            is JSONObject -> htmlToText(text(author, "name"))
                            else -> htmlToText(element.optString("author", ""))
                        }
                        if (body.isNotBlank() && body !in setOf("[deleted]", "[removed]")) {
                            substantive += body.length
                            sections += listOf(title, author.takeIf { it.isNotBlank() }?.let { "Author: $it" }.orEmpty(), body)
                                .filter(String::isNotBlank).joinToString("\n")
                        }
                    }
                    for (key in listOf("@graph", "mainEntity", "mainEntityOfPage", "comment", "acceptedAnswer", "suggestedAnswer", "hasPart")) {
                        walk(element.opt(key), depth + 1)
                    }
                }
                else -> Unit
            }
        }
        scripts.findAll(html).take(24).filter { ldType.containsMatchIn(it.groupValues[1]) }.forEach { script ->
            val raw = script.groupValues[2].trim().takeIf { it.length <= 512_000 } ?: return@forEach
            runCatching {
                val parsed = JSONObject(raw)
                walk(parsed, 0)
            }.getOrElse {
                runCatching {
                    val parsed = JSONArray(raw)
                    walk(parsed, 0)
                }.getOrNull()
            }
        }
        if (substantive < 80 || sections.isEmpty()) return null
        return "[Page-supplied structured text]\n" + sections.joinToString("\n\n").take(200_000)
    }

    fun title(html: String): String {
        val m = Regex("<title[^>]*>(.*?)</title\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
        return m?.let { htmlToText(it.groupValues[1]) }.orEmpty().take(500)
    }

    fun extractHeadings(html: String): List<String> {
        val out = mutableListOf<String>()
        val regex = Regex("<h[1-6][^>]*>(.*?)</h[1-6]\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (m in regex.findAll(html)) {
            if (out.size >= MAX_HEADINGS) break
            val heading = htmlToText(m.groupValues[1])
            if (heading.isNotBlank()) out += heading.take(300)
        }
        return out
    }

    fun extractLinks(html: String): List<String> {
        val out = LinkedHashSet<String>()
        val regex = Regex("""<a\b[^>]*href=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
        for (m in regex.findAll(html)) {
            if (out.size >= MAX_LINKS) break
            val href = htmlToText(m.groupValues[1])
            if (href.startsWith("http://") || href.startsWith("https://")) out += href.take(1000)
        }
        return out.toList()
    }

    fun metadata(html: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val metaRegex = Regex("""<meta\b([^>]*)>""", RegexOption.IGNORE_CASE)
        for (m in metaRegex.findAll(html)) {
            if (out.size >= 50) break
            val attrs = m.groupValues[1]
            val name = attr(attrs, "name").ifBlank { attr(attrs, "property") }
            val content = attr(attrs, "content")
            if (name.isNotBlank() && content.isNotBlank()) out[name] = htmlToText(content).take(500)
        }
        return out
    }

    /** Fallback main-content text: strips scripts/styles, keeps headings/paragraphs/lists/tables. */
    fun mainText(html: String): String {
        var body = scripts.replace(html, " ")
        body = styles.replace(body, " ")
        body = body.replace(Regex("<!--[\\s\\S]*?-->"), " ")
        // Block boundaries become newlines so paragraph/list/table structure survives.
        body = body.replace(Regex("(?i)</(p|div|h[1-6]|li|tr|br|section|article|table|ul|ol|blockquote)>"), "\n")
        body = body.replace(Regex("(?i)<(h[1-6])[^>]*>"), "\n")
        val text = htmlToText(body)
        // Collapse runs of blank lines.
        return text.replace(Regex("\\n{3,}"), "\n\n").trim().take(MAX_TEXT_CHARS)
    }

    private fun attr(tag: String, name: String): String {
        val m = Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)
        return m?.groupValues?.get(1).orEmpty()
    }

    private fun htmlToText(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("&nbsp;|&#160;"), " ")
        .replace(Regex("&amp;"), "&")
        .replace(Regex("&lt;"), "<")
        .replace(Regex("&gt;"), ">")
        .replace(Regex("&quot;"), "\"")
        .replace(Regex("&#39;|&apos;"), "'")
        .replace(Regex("\\s+"), " ")
        .trim()
}

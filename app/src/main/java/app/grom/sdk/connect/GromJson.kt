// SPDX-License-Identifier: Apache-2.0
package app.grom.sdk.connect

/**
 * The smallest JSON reader and writer that the connect client needs (D93).
 *
 * The SDK is consumed by host applications that may already carry a JSON
 * library; adding one here would force that choice on every host and would make
 * the "no dependencies" claim false. The format Grom speaks is small and fixed,
 * so this parser covers exactly RFC 8259 and nothing else:
 *
 *   - objects, arrays, strings, numbers, `true`, `false`, `null`;
 *   - `\uXXXX` escapes (including surrogate pairs, which Kotlin strings already
 *     hold as two chars);
 *   - an input cap and a nesting cap, so a hostile or broken server cannot make
 *     the client recurse until the stack dies.
 *
 * It is `internal`: the client's public surface is the parsed models, not a
 * general-purpose JSON API. `GromConnectClientTest` pins round-trips and the
 * failure modes (trailing data, unterminated string, depth, oversized input).
 */
internal object GromJson {

    /** 1 MiB of response text is far more than any Grom document needs. */
    const val MAX_INPUT_CHARS: Int = 1_048_576
    const val MAX_DEPTH: Int = 32

    // ------------------------------------------------------------------ encode

    fun encode(value: Any?): String = StringBuilder().also { write(it, value, 0) }.toString()

    private fun write(out: StringBuilder, value: Any?, depth: Int) {
        require(depth <= MAX_DEPTH) { "json is nested too deeply" }
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Double -> writeNumber(out, value)
            is Float -> writeNumber(out, value.toDouble())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    require(key is String) { "json object keys must be strings" }
                    if (!first) out.append(',')
                    first = false
                    writeString(out, key)
                    out.append(':')
                    write(out, item, depth + 1)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    write(out, item, depth + 1)
                }
                out.append(']')
            }
            is Array<*> -> write(out, value.toList(), depth)
            else -> throw IllegalArgumentException("cannot encode ${value.javaClass.simpleName}")
        }
    }

    private fun writeNumber(out: StringBuilder, value: Double) {
        require(value.isFinite()) { "json cannot carry a non-finite number" }
        // 1234.0 is fine JSON; the server parses numbers, not a Kotlin type.
        out.append(value.toString())
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> {
                    if (ch < ' ') {
                        out.append("\\u")
                        out.append(String.format(java.util.Locale.ROOT, "%04x", ch.code))
                    } else {
                        out.append(ch)
                    }
                }
            }
        }
        out.append('"')
    }

    // ------------------------------------------------------------------- parse

    /** Returns the parsed value, or throws [IllegalArgumentException]. */
    fun parse(text: String): Any? {
        require(text.length <= MAX_INPUT_CHARS) { "json is too large" }
        val parser = Parser(text)
        val value = parser.readValue(0)
        parser.skipWhitespace()
        require(parser.atEnd()) { "trailing data after the json value" }
        return value
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?>? = parse(text) as? Map<String, Any?>

    private class Parser(private val text: String) {

        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length) {
                when (text[index]) {
                    ' ', '\t', '\n', '\r' -> index++
                    else -> return
                }
            }
        }

        fun readValue(depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "json is nested too deeply" }
            skipWhitespace()
            require(index < text.length) { "unexpected end of json" }
            return when (val ch = text[index]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    require(ch == '-' || ch.isDigit()) { "unexpected character '$ch'" }
                    readNumber()
                }
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            index++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return out
            }
            while (true) {
                skipWhitespace()
                require(peek() == '"') { "json object key must be a string" }
                val key = readString()
                skipWhitespace()
                require(peek() == ':') { "expected ':' after a json object key" }
                index++
                out[key] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return out
                    }
                    else -> throw IllegalArgumentException("expected ',' or '}' in json object")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            index++ // [
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return out
            }
            while (true) {
                out.add(readValue(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return out
                    }
                    else -> throw IllegalArgumentException("expected ',' or ']' in json array")
                }
            }
        }

        private fun readString(): String {
            require(peek() == '"') { "expected a json string" }
            index++
            val out = StringBuilder()
            while (true) {
                require(index < text.length) { "unterminated json string" }
                val ch = text[index++]
                when {
                    ch == '"' -> return out.toString()
                    ch == '\\' -> {
                        require(index < text.length) { "unterminated json escape" }
                        when (val escaped = text[index++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(index + 4 <= text.length) { "short unicode escape" }
                                val code = text.substring(index, index + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("bad unicode escape")
                                out.append(code.toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("unknown escape '\\$escaped'")
                        }
                    }
                    ch < ' ' -> throw IllegalArgumentException("control character in json string")
                    else -> out.append(ch)
                }
            }
        }

        private fun readNumber(): Any {
            val start = index
            if (peek() == '-') index++
            require(index < text.length && text[index].isDigit()) { "bad json number" }
            // JSON forbids a leading zero: `0` stands alone, `01` is not a
            // number (it is `0` followed by trailing data, which the top-level
            // parser then refuses).
            if (text[index] == '0') {
                index++
            } else {
                while (index < text.length && text[index].isDigit()) index++
            }
            var fractional = false
            if (index < text.length && text[index] == '.') {
                fractional = true
                index++
                require(index < text.length && text[index].isDigit()) { "bad json number" }
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                fractional = true
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                require(index < text.length && text[index].isDigit()) { "bad json exponent" }
                while (index < text.length && text[index].isDigit()) index++
            }
            val raw = text.substring(start, index)
            if (!fractional) {
                raw.toLongOrNull()?.let { return it }
            }
            return raw.toDouble()
        }

        /** `true`, `false` or `null`: no partial match, no trailing letters. */
        private fun literal(token: String, value: Any?): Any? {
            require(text.startsWith(token, index)) { "unknown json literal" }
            index += token.length
            return value
        }

        private fun peek(): Char {
            require(index < text.length) { "unexpected end of json" }
            return text[index]
        }
    }
}

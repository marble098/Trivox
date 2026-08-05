package com.trivox.client.config

import java.util.Locale

/**
 * Small dependency-free YAML reader for Mihomo/Clash subscription documents.
 *
 * It intentionally supports the YAML structures used by proxy providers:
 * indentation maps/lists, quoted scalars, inline maps/lists, booleans, numbers,
 * null, comments and folded/literal block scalars. It is not a general-purpose
 * serializer and never evaluates tags, anchors or arbitrary objects.
 */
object MiniYaml {
    class ParseException(message: String) : IllegalArgumentException(message)

    private data class SourceLine(
        val number: Int,
        val indent: Int,
        val text: String,
        val rawContent: String
    )

    private data class Parsed<out T>(val value: T, val next: Int)

    fun parse(input: String): Any? {
        val lines = tokenize(input)
        if (lines.isEmpty()) return linkedMapOf<String, Any?>()
        val baseIndent = lines.first().indent
        return parseBlock(lines, 0, baseIndent).value
    }

    @Suppress("UNCHECKED_CAST")
    fun parseMap(input: String): LinkedHashMap<String, Any?> =
        parse(input) as? LinkedHashMap<String, Any?>
            ?: throw ParseException("YAML root must be a map")

    fun stringMap(value: Any?): Map<String, Any?> =
        (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

    fun list(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()

    fun string(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        else -> value.toString()
    }

    fun int(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        else -> string(value).trim().toIntOrNull()
    }

    fun bool(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        else -> when (string(value).trim().lowercase(Locale.ROOT)) {
            "true", "yes", "on" -> true
            "false", "no", "off" -> false
            else -> null
        }
    }

    private fun parseBlock(lines: List<SourceLine>, start: Int, indent: Int): Parsed<Any?> {
        if (start >= lines.size) return Parsed(linkedMapOf<String, Any?>(), start)
        return if (lines[start].indent == indent && lines[start].text.startsWith("-")) {
            parseList(lines, start, indent)
        } else {
            parseMapBlock(lines, start, indent)
        }
    }

    private fun parseMapBlock(
        lines: List<SourceLine>,
        start: Int,
        indent: Int
    ): Parsed<LinkedHashMap<String, Any?>> {
        val out = linkedMapOf<String, Any?>()
        var index = start
        while (index < lines.size) {
            val line = lines[index]
            if (line.indent < indent) break
            if (line.indent > indent) {
                throw ParseException("Unexpected indentation at YAML line ${line.number}")
            }
            if (line.text.startsWith("-")) break
            val pair = splitKeyValue(line.text)
                ?: throw ParseException("Expected YAML key/value at line ${line.number}")
            val key = unquote(pair.first.trim())
            val rawValue = pair.second.trim()
            if (key.isBlank()) throw ParseException("Empty YAML key at line ${line.number}")

            if (rawValue in BLOCK_SCALARS) {
                val block = parseBlockScalar(lines, index + 1, indent, rawValue)
                out[key] = block.value
                index = block.next
                continue
            }

            if (rawValue.isNotEmpty()) {
                out[key] = parseScalar(rawValue)
                index += 1
                continue
            }

            val next = index + 1
            if (next < lines.size && lines[next].indent > indent) {
                val child = parseBlock(lines, next, lines[next].indent)
                out[key] = child.value
                index = child.next
            } else {
                out[key] = linkedMapOf<String, Any?>()
                index += 1
            }
        }
        return Parsed(out, index)
    }

    private fun parseList(
        lines: List<SourceLine>,
        start: Int,
        indent: Int
    ): Parsed<List<Any?>> {
        val out = mutableListOf<Any?>()
        var index = start
        while (index < lines.size) {
            val line = lines[index]
            if (line.indent < indent) break
            if (line.indent != indent || !line.text.startsWith("-")) break

            val item = line.text.removePrefix("-").trimStart()
            if (item.isEmpty()) {
                val next = index + 1
                if (next < lines.size && lines[next].indent > indent) {
                    val child = parseBlock(lines, next, lines[next].indent)
                    out += child.value
                    index = child.next
                } else {
                    out += null
                    index += 1
                }
                continue
            }

            if (item.startsWith("{") || item.startsWith("[")) {
                out += parseScalar(item)
                index += 1
                continue
            }

            val firstPair = splitKeyValue(item)
            if (firstPair == null) {
                out += parseScalar(item)
                index += 1
                continue
            }

            val map = linkedMapOf<String, Any?>()
            val firstKey = unquote(firstPair.first.trim())
            val firstValue = firstPair.second.trim()
            if (firstValue in BLOCK_SCALARS) {
                val block = parseBlockScalar(lines, index + 1, indent, firstValue)
                map[firstKey] = block.value
                index = block.next
            } else if (firstValue.isNotEmpty()) {
                map[firstKey] = parseScalar(firstValue)
                index += 1
            } else {
                val next = index + 1
                if (next < lines.size && lines[next].indent > indent) {
                    val child = parseBlock(lines, next, lines[next].indent)
                    map[firstKey] = child.value
                    index = child.next
                } else {
                    map[firstKey] = linkedMapOf<String, Any?>()
                    index += 1
                }
            }

            if (index < lines.size && lines[index].indent > indent) {
                val continuationIndent = lines[index].indent
                val continuation = parseMapBlock(lines, index, continuationIndent)
                continuation.value.forEach { (key, value) -> map[key] = value }
                index = continuation.next
            }
            out += map
        }
        return Parsed(out, index)
    }

    private fun parseBlockScalar(
        lines: List<SourceLine>,
        start: Int,
        parentIndent: Int,
        marker: String
    ): Parsed<String> {
        var index = start
        val parts = mutableListOf<String>()
        var childIndent: Int? = null
        while (index < lines.size && lines[index].indent > parentIndent) {
            val line = lines[index]
            if (childIndent == null) childIndent = line.indent
            val remove = childIndent.coerceAtMost(line.rawContent.length)
            parts += line.rawContent.drop(remove).trimEnd()
            index += 1
        }
        val folded = marker.startsWith(">")
        val strip = marker.endsWith("-")
        var value = if (folded) {
            parts.joinToString(" ") { it.trim() }.replace(Regex("\\s+"), " ").trim()
        } else {
            parts.joinToString("\n")
        }
        if (!strip && !folded && value.isNotEmpty()) value += "\n"
        return Parsed(value, index)
    }

    private fun parseScalar(value: String): Any? {
        val clean = value.trim()
        if (clean.isEmpty()) return ""
        if (clean.startsWith("{") && clean.endsWith("}")) return parseInlineMap(clean)
        if (clean.startsWith("[") && clean.endsWith("]")) return parseInlineList(clean)
        if (isQuoted(clean)) return unquote(clean)
        return when (clean.lowercase(Locale.ROOT)) {
            "null", "~" -> null
            "true", "yes", "on" -> true
            "false", "no", "off" -> false
            else -> clean.toLongOrNull()
                ?: clean.toDoubleOrNull()
                ?: clean
        }
    }

    private fun parseInlineMap(value: String): LinkedHashMap<String, Any?> {
        val body = value.trim().removePrefix("{").removeSuffix("}")
        val out = linkedMapOf<String, Any?>()
        splitTopLevel(body, ',').forEach { item ->
            if (item.isBlank()) return@forEach
            val pair = splitKeyValue(item)
                ?: throw ParseException("Malformed inline YAML map item: $item")
            out[unquote(pair.first.trim())] = parseScalar(pair.second.trim())
        }
        return out
    }

    private fun parseInlineList(value: String): List<Any?> {
        val body = value.trim().removePrefix("[").removeSuffix("]")
        if (body.isBlank()) return emptyList()
        return splitTopLevel(body, ',').map { parseScalar(it) }
    }

    private fun splitKeyValue(value: String): Pair<String, String>? {
        var quote: Char? = null
        var curly = 0
        var square = 0
        var escaped = false
        value.forEachIndexed { index, ch ->
            if (escaped) {
                escaped = false
                return@forEachIndexed
            }
            if (ch == '\\' && quote == '"') {
                escaped = true
                return@forEachIndexed
            }
            if (quote != null) {
                if (ch == quote) quote = null
                return@forEachIndexed
            }
            when (ch) {
                '\'', '"' -> quote = ch
                '{' -> curly += 1
                '}' -> curly -= 1
                '[' -> square += 1
                ']' -> square -= 1
                ':' -> if (curly == 0 && square == 0) {
                    return value.substring(0, index) to value.substring(index + 1)
                }
            }
        }
        return null
    }

    private fun splitTopLevel(value: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var curly = 0
        var square = 0
        var escaped = false
        value.forEach { ch ->
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                }
                ch == '\\' && quote == '"' -> {
                    current.append(ch)
                    escaped = true
                }
                quote != null -> {
                    current.append(ch)
                    if (ch == quote) quote = null
                }
                ch == '\'' || ch == '"' -> {
                    quote = ch
                    current.append(ch)
                }
                ch == '{' -> {
                    curly += 1
                    current.append(ch)
                }
                ch == '}' -> {
                    curly -= 1
                    current.append(ch)
                }
                ch == '[' -> {
                    square += 1
                    current.append(ch)
                }
                ch == ']' -> {
                    square -= 1
                    current.append(ch)
                }
                ch == delimiter && curly == 0 && square == 0 -> {
                    out += current.toString().trim()
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
        }
        out += current.toString().trim()
        return out
    }

    private fun tokenize(input: String): List<SourceLine> {
        val out = mutableListOf<SourceLine>()
        input.removePrefix("\uFEFF").lineSequence().forEachIndexed { zeroIndex, original ->
            val expanded = original.replace("\t", "    ").trimEnd()
            if (expanded.isBlank()) return@forEachIndexed
            val indent = expanded.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val rawContent = expanded
            val content = stripComment(expanded.substring(indent)).trimEnd()
            if (content.isBlank() || content.trimStart().startsWith("---")) return@forEachIndexed
            out += SourceLine(zeroIndex + 1, indent, content, rawContent)
        }
        return out
    }

    private fun stripComment(value: String): String {
        var quote: Char? = null
        var escaped = false
        value.forEachIndexed { index, ch ->
            if (escaped) {
                escaped = false
                return@forEachIndexed
            }
            if (ch == '\\' && quote == '"') {
                escaped = true
                return@forEachIndexed
            }
            if (quote != null) {
                if (ch == quote) quote = null
                return@forEachIndexed
            }
            when (ch) {
                '\'', '"' -> quote = ch
                '#' -> if (index == 0 || value[index - 1].isWhitespace()) {
                    return value.substring(0, index)
                }
            }
        }
        return value
    }

    private fun isQuoted(value: String): Boolean =
        value.length >= 2 &&
            ((value.first() == '\'' && value.last() == '\'') ||
                (value.first() == '"' && value.last() == '"'))

    private fun unquote(value: String): String {
        if (!isQuoted(value)) return value
        val body = value.substring(1, value.length - 1)
        return if (value.first() == '\'') {
            body.replace("''", "'")
        } else {
            buildString {
                var escaped = false
                body.forEach { ch ->
                    if (escaped) {
                        append(
                            when (ch) {
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                else -> ch
                            }
                        )
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else {
                        append(ch)
                    }
                }
                if (escaped) append('\\')
            }
        }
    }

    private val BLOCK_SCALARS = setOf(">", ">-", ">+", "|", "|-", "|+")
}

package dev.appolon.memoswear.core.markdown

/** A run of text with inline styling. [link] is a URL and [tag] a tag name without its hash. */
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
    val tag: String? = null,
)

sealed class Block {
    data class Heading(val level: Int, val spans: List<Span>) : Block()

    data class Paragraph(val spans: List<Span>) : Block()

    /** A Markdown task. [line] is the index of the line in the memo content, counting from 0. */
    data class TaskItem(val checked: Boolean, val spans: List<Span>, val line: Int) : Block()

    /** A list entry; [number] is null for a bullet. */
    data class ListItem(val spans: List<Span>, val number: Int?) : Block()

    data class CodeBlock(val language: String, val code: String) : Block()

    data class Quote(val spans: List<Span>) : Block()

    data class Image(val alt: String, val url: String) : Block()

    data object Divider : Block()
}

/**
 * A small Markdown reader for memo content. It covers what people write in notes on a watch
 * (headings, tasks, lists, code, quotes, images, and the usual inline marks) and leaves anything
 * else as text. It never throws: malformed input renders as itself.
 */
object MemoMarkdown {
    const val TASK_PREFIX = "- [ ] "

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~<>"

    private val FENCE = Regex("^(`{3,}|~{3,})\\s*(\\S*)\\s*$")
    private val IMAGE_LINE = Regex("""^!\[([^\]]*)]\(\s*([^\s)]+)(?:\s+"[^"]*")?\s*\)$""")
    private val HEADING = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")
    private val DIVIDER = Regex("^(?:(?:-[ \t]*){3,}|(?:\\*[ \t]*){3,}|(?:_[ \t]*){3,})$")
    private val TASK = Regex("""^\s*[-*+]\s+\[([ xX])]\s?(.*)$""")
    private val BULLET = Regex("""^\s*[-*+]\s+(.*)$""")
    private val ORDERED = Regex("""^\s*(\d{1,3})[.)]\s+(.*)$""")
    private val QUOTE = Regex("^>\\s?(.*)$")
    private val TASK_MARKER = Regex("""\[([ xX])]""")
    private val LINK = Regex("""(!?)\[([^\]]*)]\(\s*([^\s)]+)(?:\s+"[^"]*")?\s*\)""")
    private val TAG = Regex("""(?<![\p{L}\p{N}/#&])#([\p{L}\p{N}_-]+(?:/[\p{L}\p{N}_-]+)*)""")
    private val LEADING_TASK_MARKER = Regex("""^(\s*)[-*+]\s+\[[ xX]]\s?""")

    private enum class Kind { FENCE, IMAGE, HEADING, DIVIDER, TASK, BULLET, ORDERED, QUOTE, PLAIN }

    fun parse(content: String): List<Block> {
        val lines = normalize(content).split('\n')
        val size = if (lines.size > 1 && lines.last().isEmpty()) lines.size - 1 else lines.size
        val blocks = ArrayList<Block>()
        var i = 0
        while (i < size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }
            when (kindOf(line)) {
                Kind.FENCE -> {
                    val opening = FENCE.find(line)!!
                    val marker = opening.groupValues[1]
                    val end = (i + 1 until size).firstOrNull { lines[it].trim().startsWith(marker[0].toString().repeat(marker.length)) }
                    val close = end ?: size
                    blocks += Block.CodeBlock(opening.groupValues[2], lines.subList(i + 1, close).joinToString("\n"))
                    i = if (end == null) size else end + 1
                }

                Kind.IMAGE -> {
                    val match = IMAGE_LINE.find(line)!!
                    blocks += Block.Image(match.groupValues[1], match.groupValues[2])
                    i++
                }

                Kind.HEADING -> {
                    val match = HEADING.find(line)!!
                    blocks += Block.Heading(match.groupValues[1].length, parseInline(match.groupValues[2]))
                    i++
                }

                Kind.DIVIDER -> {
                    blocks += Block.Divider
                    i++
                }

                Kind.TASK -> {
                    val match = TASK.find(line)!!
                    blocks += Block.TaskItem(match.groupValues[1] != " ", parseInline(match.groupValues[2]), i)
                    i++
                }

                Kind.BULLET, Kind.ORDERED -> {
                    val ordered = kindOf(line) == Kind.ORDERED
                    val match = if (ordered) ORDERED.find(line)!! else BULLET.find(line)!!
                    val spans = if (ordered) parseInline(match.groupValues[2]) else parseInline(match.groupValues[1])
                    blocks += Block.ListItem(spans, if (ordered) match.groupValues[1].toIntOrNull() else null)
                    i++
                }

                Kind.QUOTE -> {
                    val match = QUOTE.find(line)!!
                    blocks += Block.Quote(parseInline(match.groupValues[1]))
                    i++
                }

                Kind.PLAIN -> {
                    val spans = parseInline(line.trim())
                    var j = i + 1
                    val merged = spans.toMutableList()
                    while (j < size && lines[j].isNotBlank() && kindOf(lines[j]) == Kind.PLAIN) {
                        merged += Span("\n")
                        merged += parseInline(lines[j].trim())
                        j++
                    }
                    blocks += Block.Paragraph(merged)
                    i = j
                }
            }
        }
        return blocks
    }

    /** Parses inline marks. The result concatenates to the input text, apart from escapes and link targets. */
    fun parseInline(text: String): List<Span> {
        val out = ArrayList<Span>()
        val plain = StringBuilder()
        var i = 0
        val n = text.length

        fun flush() {
            if (plain.isNotEmpty()) {
                out += Span(plain.toString())
                plain.setLength(0)
            }
        }

        fun emit(span: Span) {
            flush()
            out += span
        }

        while (i < n) {
            val c = text[i]

            if (c == '\\' && i + 1 < n && text[i + 1] in ESCAPABLE) {
                plain.append(text[i + 1])
                i += 2
                continue
            }

            if (c == '`') {
                val run = "`".repeat(backtickRun(text, i))
                val close = text.indexOf(run, i + run.length)
                if (close >= 0) {
                    emit(Span(text.substring(i + run.length, close).trim(), code = true))
                    i = close + run.length
                } else {
                    plain.append(run)
                    i += run.length
                }
                continue
            }

            val double = when {
                text.startsWith("**", i) -> "**"
                text.startsWith("__", i) -> "__"
                text.startsWith("~~", i) -> "~~"
                else -> null
            }
            if (double != null) {
                val close = text.indexOf(double, i + 2)
                val inner = if (close > i + 2) text.substring(i + 2, close) else null
                if (inner != null && inner.isNotBlank()) {
                    emit(Span(inner, bold = double != "~~", strike = double == "~~"))
                    i = close + 2
                    continue
                }
            }

            if ((c == '*' || c == '_') && i + 1 < n && !text[i + 1].isWhitespace()) {
                val close = emphasisClose(text, i + 1, c)
                if (close > i + 1) {
                    emit(Span(text.substring(i + 1, close), italic = true))
                    i = close + 1
                    continue
                }
            }

            if (c == '[' || (c == '!' && text.startsWith("![", i))) {
                val match = LINK.matchAt(text, i)
                if (match != null) {
                    emit(Span(match.groupValues[2], link = match.groupValues[3]))
                    i += match.value.length
                    continue
                }
            }

            if (c == '#') {
                val match = TAG.matchAt(text, i)
                if (match != null) {
                    emit(Span(match.value, tag = match.groupValues[1]))
                    i += match.value.length
                    continue
                }
            }

            if ((c == 'h' || c == 'H') &&
                (text.startsWith("http://", i, ignoreCase = true) || text.startsWith("https://", i, ignoreCase = true))
            ) {
                val end = bareUrlEnd(text, i)
                val raw = text.substring(i, end)
                val url = raw.trimEnd(')', ']', '}', ',', '.', ';', ':', '!', '?', '\'', '"')
                emit(Span(url, link = url))
                plain.append(raw.substring(url.length))
                i = end
                continue
            }

            plain.append(c)
            i++
        }
        flush()
        return out
    }

    /** The memo as plain text, one block per line, cut to [maxLength] characters with an ellipsis. */
    fun plainText(content: String, maxLength: Int = Int.MAX_VALUE): String {
        val builder = StringBuilder()
        for (block in parse(content)) {
            if (builder.isNotEmpty()) builder.append('\n')
            when (block) {
                is Block.Heading, is Block.Quote, is Block.Paragraph -> builder.append(textOf(block.spans()))
                is Block.TaskItem -> builder.append(if (block.checked) "✓ " else "○ ").append(textOf(block.spans))
                is Block.ListItem -> builder.append(block.number?.let { "$it. " } ?: "• ").append(textOf(block.spans))
                is Block.CodeBlock -> builder.append(block.code)
                is Block.Image -> builder.append(block.alt.ifBlank { "Picture" })
                Block.Divider -> if (builder.isNotEmpty()) builder.setLength(builder.length - 1)
            }
        }
        val text = builder.toString().replace("\n{3,}".toRegex(), "\n\n").trim()
        return if (text.length > maxLength) text.take(maxLength).trimEnd() + "…" else text
    }

    fun tags(content: String): List<String> {
        val tags = LinkedHashSet<String>()
        for (match in TAG.findAll(normalize(content))) {
            val parts = match.groupValues[1].split('/')
            var path = ""
            for (part in parts) {
                path = if (path.isEmpty()) part else "$path/$part"
                tags += path
            }
        }
        return tags.toList()
    }

    fun hasTaskList(content: String): Boolean = normalize(content).lines().any { TASK.matches(it) }

    fun hasIncompleteTasks(content: String): Boolean = normalize(content).lines().any { TASK.matches(it) && it.contains("[ ]") }

    /** Checks or unchecks the task on the given line. Other content is left byte for byte. */
    fun setTaskState(content: String, line: Int, checked: Boolean): String {
        val normalized = normalize(content)
        val lines = normalized.split('\n').toMutableList()
        if (line !in lines.indices) return content
        if (!TASK.matches(lines[line])) return content
        lines[line] = TASK_MARKER.replace(lines[line]) { if (checked) "[x]" else "[ ]" }
        return lines.joinToString("\n")
    }

    /**
     * Turns the line holding [cursor] into a task, or back into text if it already is one.
     * Returns the new text and the cursor position that matches it.
     */
    fun toggleTaskMarker(text: String, cursor: Int): Pair<String, Int> {
        val position = cursor.coerceIn(0, text.length)
        val start = text.lastIndexOf('\n', position - 1).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val line = text.substring(start, end)
        val marker = LEADING_TASK_MARKER.find(line)
        return if (marker != null) {
            // Only the marker goes; the indent stays, so text after it moves left by the marker's length.
            val indent = marker.groupValues[1].length
            val removed = marker.value.length - indent
            val next = if (position >= start + indent + removed) position - removed else start + indent
            text.removeRange(start + indent, start + indent + removed) to next
        } else {
            val indent = line.takeWhile { it == ' ' || it == '\t' }.length
            val prefix = line.substring(0, indent) + TASK_PREFIX
            text.substring(0, start + indent) + TASK_PREFIX + text.substring(start + indent) to
                (if (position >= start + indent) position + TASK_PREFIX.length else position)
        }
    }

    private fun textOf(spans: List<Span>): String = spans.joinToString("") { it.text }

    private fun Block.spans(): List<Span> = when (this) {
        is Block.Heading -> spans
        is Block.Paragraph -> spans
        is Block.Quote -> spans
        else -> emptyList()
    }

    private fun normalize(content: String): String = content.replace("\r\n", "\n").replace('\r', '\n')

    private fun backtickRun(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i] == '`') i++
        return i - start
    }

    private fun emphasisClose(text: String, from: Int, delimiter: Char): Int {
        var j = from
        while (j < text.length) {
            val c = text[j]
            if (c == '`') {
                j += backtickRun(text, j)
                continue
            }
            if (c == delimiter) {
                if (text.getOrNull(j + 1) == delimiter) {
                    j += 2
                    continue
                }
                if (!text[j - 1].isWhitespace() && (j + 1 >= text.length || !text[j + 1].isLetterOrDigit())) return j
            }
            j++
        }
        return -1
    }

    private fun bareUrlEnd(text: String, start: Int): Int {
        var i = start
        while (i < text.length && !text[i].isWhitespace() && text[i] !in "<>\"") i++
        return i
    }

    private fun kindOf(line: String): Kind {
        val trimmed = line.trim()
        return when {
            FENCE.matches(trimmed) -> Kind.FENCE
            IMAGE_LINE.matches(trimmed) -> Kind.IMAGE
            HEADING.matches(trimmed) -> Kind.HEADING
            DIVIDER.matches(trimmed) -> Kind.DIVIDER
            TASK.matches(trimmed) -> Kind.TASK
            BULLET.matches(trimmed) -> Kind.BULLET
            ORDERED.matches(trimmed) -> Kind.ORDERED
            QUOTE.matches(trimmed) -> Kind.QUOTE
            else -> Kind.PLAIN
        }
    }
}

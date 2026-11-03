package com.fieldrepository.app.transcript.data

object TranscriptMarkdown {

    enum class Style { BOLD, ITALIC, CODE }

    data class Span(val start: Int, val end: Int, val style: Style)

    data class Inline(val text: String, val spans: List<Span> = emptyList())

    sealed interface Block {
        data class Heading(val level: Int, val inline: Inline) : Block
        data class Table(val cells: List<Inline>) : Block
        data class Quote(val inline: Inline) : Block
        data class Paragraph(val inline: Inline) : Block
    }

    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()
        val quote = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) blocks += Block.Paragraph(inline(paragraph.joinToString("\n"))); paragraph.clear()
            if (quote.isNotEmpty()) blocks += Block.Quote(inline(quote.joinToString("\n"))); quote.clear()
        }
        for (raw in markdown.replace("\r\n", "\n").lines()) {
            val line = raw.trimEnd()
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
            when {
                line.isBlank() -> flush()
                heading != null -> {
                    flush(); blocks += Block.Heading(heading.groupValues[1].length, inline(heading.groupValues[2]))
                }
                line.startsWith("|") && line.endsWith("|") && line.length > 1 -> {
                    flush()
                    val cells = line.trim('|').split('|').map { it.trim() }
                    if (!cells.all { it.matches(Regex("^:?-{2,}:?$")) }) blocks += Block.Table(cells.map(::inline))
                }
                line.startsWith(">") -> {
                    if (paragraph.isNotEmpty()) { blocks += Block.Paragraph(inline(paragraph.joinToString("\n"))); paragraph.clear() }
                    quote += line.removePrefix(">").removePrefix(" ")
                }
                else -> {
                    if (quote.isNotEmpty()) { blocks += Block.Quote(inline(quote.joinToString("\n"))); quote.clear() }
                    paragraph += line
                }
            }
        }
        flush()
        return blocks
    }

    fun inline(source: String): Inline {
        val out = StringBuilder()
        val spans = mutableListOf<Span>()
        var i = 0
        fun closing(marker: String, from: Int): Int {
            val at = source.indexOf(marker, from)
            return if (at > from) at else -1
        }
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\\' && i + 1 < source.length && source[i + 1] in "*_`\\" -> { out.append(source[i + 1]); i += 2 }
                c == '`' -> {
                    val end = closing("`", i + 1)
                    if (end < 0) { out.append(c); i++ } else {
                        val start = out.length
                        out.append(source, i + 1, end)
                        spans += Span(start, out.length, Style.CODE)
                        i = end + 1
                    }
                }
                source.startsWith("**", i) -> {
                    val end = closing("**", i + 2)
                    if (end < 0) { out.append("**"); i += 2 } else {
                        val inner = inline(source.substring(i + 2, end))
                        val start = out.length
                        out.append(inner.text)
                        spans += Span(start, out.length, Style.BOLD)
                        inner.spans.forEach { spans += Span(it.start + start, it.end + start, it.style) }
                        i = end + 2
                    }
                }
                c == '*' || c == '_' -> {
                    val end = closing(c.toString(), i + 1)
                    val wordInside = c == '_' && i > 0 && source[i - 1].isLetterOrDigit()
                    if (end < 0 || wordInside || source[i + 1].isWhitespace()) { out.append(c); i++ } else {
                        val inner = inline(source.substring(i + 1, end))
                        val start = out.length
                        out.append(inner.text)
                        spans += Span(start, out.length, Style.ITALIC)
                        inner.spans.forEach { spans += Span(it.start + start, it.end + start, it.style) }
                        i = end + 1
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
        return Inline(out.toString(), spans)
    }

    fun plain(markdown: String): String = parse(markdown).joinToString("\n\n") { block ->
        when (block) {
            is Block.Heading -> block.inline.text
            is Block.Table -> block.cells.joinToString("  ") { it.text }
            is Block.Quote -> block.inline.text
            is Block.Paragraph -> block.inline.text
        }
    }
}

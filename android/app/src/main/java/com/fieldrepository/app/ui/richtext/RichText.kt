package com.fieldrepository.app.ui.richtext

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private fun isXmlLegal(cp: Int): Boolean =
    cp == 0x9 || cp == 0xA || cp == 0xD ||
        (cp in 0x20..0xD7FF) || (cp in 0xE000..0xFFFD) || (cp in 0x10000..0x10FFFF)

fun cleanText(value: Any?): String {
    if (value == null) return ""
    if (value is Boolean) return if (value) "Yes" else "No"
    val raw = value as? String ?: value.toString()
    if (raw.isEmpty()) return ""

    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\r') {
            out.append('\n')
            i++
            if (i < raw.length && raw[i] == '\n') i++
            continue
        }
        if (c == '\u2028' || c == '\u2029') {
            out.append('\n')
            i++
            continue
        }
        if (Character.isHighSurrogate(c)) {
            if (i + 1 < raw.length && Character.isLowSurrogate(raw[i + 1])) {
                if (isXmlLegal(Character.toCodePoint(c, raw[i + 1]))) {
                    out.append(c).append(raw[i + 1])
                }
                i += 2
                continue
            }
            i++
            continue
        }
        if (Character.isLowSurrogate(c)) {
            i++
            continue
        }
        if (isXmlLegal(c.code)) out.append(c)
        i++
    }
    return out.toString()
}

const val MAX_DOCUMENT_CHARS = 200_000

const val MAX_BLOCKS = 2_000
const val MAX_HEADING_LEVEL = 4
const val MAX_LIST_DEPTH = 3

private const val MAX_SPANS_PER_BLOCK = 512

private const val MAX_TABLE_ROWS = 200
private const val MAX_TABLE_COLUMNS = 12
private const val MAX_SPANS_PER_CELL = 64

const val IMAGE_DEFAULT_WIDTH_PCT = 70f
const val IMAGE_MIN_WIDTH_PCT = 10f
const val IMAGE_MAX_WIDTH_PCT = 100f
const val IMAGE_WIDTH_STEP_PCT = 15f

const val MAX_MEDIA_ID_CHARS = 64

@Serializable
enum class Align { LEFT, CENTER, RIGHT, JUSTIFY }

@Serializable
enum class Mark {
    BOLD,
    ITALIC,
    UNDERLINE,
    STRIKETHROUGH,
    CODE,

    SUPERSCRIPT,
    SUBSCRIPT,

    HIGHLIGHT,
}

@Serializable
enum class BlockKind {
    PARAGRAPH,
    HEADING,
    BULLET_ITEM,
    ORDERED_ITEM,
    QUOTE,

    TABLE,

    IMAGE;

    val isListItem: Boolean get() = this == BULLET_ITEM || this == ORDERED_ITEM

    val isStructural: Boolean get() = this == TABLE || this == IMAGE
}

@Serializable
data class RichSpan(
    val text: String,
    val marks: Set<Mark> = emptySet(),
) {
    val bold: Boolean get() = Mark.BOLD in marks
    val italic: Boolean get() = Mark.ITALIC in marks
    val underline: Boolean get() = Mark.UNDERLINE in marks
    val strike: Boolean get() = Mark.STRIKETHROUGH in marks
    val code: Boolean get() = Mark.CODE in marks
    val superscript: Boolean get() = Mark.SUPERSCRIPT in marks

    val subscript: Boolean get() = Mark.SUBSCRIPT in marks && Mark.SUPERSCRIPT !in marks
    val highlight: Boolean get() = Mark.HIGHLIGHT in marks
}

@Serializable
data class RichBlock(
    val kind: BlockKind = BlockKind.PARAGRAPH,
    val spans: List<RichSpan> = emptyList(),
    val align: Align = Align.LEFT,
    val level: Int = 0,
    val rows: List<List<List<RichSpan>>> = emptyList(),
    val media: String = "",
    val widthPct: Float = IMAGE_DEFAULT_WIDTH_PCT,
) {
    val text: String
        get() =
            if (kind == BlockKind.TABLE) {
                rows.joinToString("\n") { row ->
                    row.joinToString("\t") { cell -> cell.joinToString("") { it.text } }
                }
            } else {
                spans.joinToString("") { it.text }
            }

    val isEmpty: Boolean get() = if (kind == BlockKind.IMAGE) media.isEmpty() else text.isBlank()

    val columnCount: Int get() = rows.maxOfOrNull { it.size } ?: 0
}

@Serializable
data class RichDoc(
    val blocks: List<RichBlock> = emptyList(),
) {
    val isEmpty: Boolean get() = blocks.all { it.isEmpty }

    val text: String get() = blocks.joinToString("\n") { it.text }
}

val EMPTY_RICH_DOC = RichDoc()

private fun codePointLength(text: String): Int = text.codePointCount(0, text.length)

private fun takeCodePoints(text: String, limit: Int): String {
    if (limit <= 0) return ""
    if (text.length <= limit) return text
    val count = text.codePointCount(0, text.length)
    if (count <= limit) return text
    return text.substring(0, text.offsetByCodePoints(0, limit))
}

private fun scalarOf(element: JsonElement?): Any? = when {
    element == null -> null
    element is JsonNull -> null
    element is JsonPrimitive -> when {
        element.isString -> element.content
        element.content == "true" -> true
        element.content == "false" -> false
        else -> element.content
    }
    else -> null
}

private fun coerceMarks(raw: JsonElement?): Set<Mark> {
    if (raw !is JsonArray) return emptySet()
    val found = LinkedHashSet<Mark>()
    for (item in raw) {
        val token = (scalarOf(item) as? String)?.uppercase() ?: continue
        val mark = MARK_BY_NAME[token] ?: continue
        found.add(mark)
    }
    return found
}

private val MARK_BY_NAME: Map<String, Mark> = Mark.entries.associateBy { it.name }
private val KIND_BY_NAME: Map<String, BlockKind> = BlockKind.entries.associateBy { it.name }
private val ALIGN_BY_NAME: Map<String, Align> = Align.entries.associateBy { it.name }

private fun coerceAlign(raw: JsonElement?): Align {
    val token = (scalarOf(raw) as? String)?.uppercase() ?: return Align.LEFT
    return ALIGN_BY_NAME[token] ?: Align.LEFT
}

private fun coerceKind(raw: JsonElement?): BlockKind {
    val token = (scalarOf(raw) as? String)?.uppercase() ?: return BlockKind.PARAGRAPH
    return KIND_BY_NAME[token] ?: BlockKind.PARAGRAPH
}

private fun coerceRows(
    raw: JsonElement?,
    startBudget: Int,
): Pair<List<List<List<RichSpan>>>, Int> {
    val array = raw as? JsonArray ?: return emptyList<List<List<RichSpan>>>() to startBudget
    var budget = startBudget
    val rows = ArrayList<List<List<RichSpan>>>()
    outer@ for (rowRaw in array.take(MAX_TABLE_ROWS)) {
        val rowArray = rowRaw as? JsonArray ?: continue
        val cells = ArrayList<List<RichSpan>>()
        for (cellRaw in rowArray.take(MAX_TABLE_COLUMNS)) {
            val spans = ArrayList<RichSpan>()
            val items: List<JsonElement> = when {
                cellRaw is JsonPrimitive && cellRaw.isString -> listOf(cellRaw)
                cellRaw is JsonArray -> cellRaw
                else -> emptyList()
            }
            for (spanRaw in items.take(MAX_SPANS_PER_CELL)) {
                var text: String
                val marks: Set<Mark>
                when {
                    spanRaw is JsonPrimitive && spanRaw.isString -> {
                        text = cleanText(spanRaw.content)
                        marks = emptySet()
                    }
                    spanRaw is JsonObject -> {
                        text = cleanText(scalarOf(spanRaw["text"]))
                        marks = coerceMarks(spanRaw["marks"])
                    }
                    else -> continue
                }
                text = text.replace("\n", " ")
                if (budget <= 0) break
                text = takeCodePoints(text, budget)
                budget -= codePointLength(text)
                if (text.isNotEmpty()) spans.add(RichSpan(text = text, marks = marks))
            }
            cells.add(spans)
        }
        if (cells.isNotEmpty()) rows.add(cells)
        if (budget <= 0) break@outer
    }
    while (rows.isNotEmpty() && rows.last().none { cell -> cell.any { it.text.isNotBlank() } }) {
        rows.removeAt(rows.size - 1)
    }
    return rows to budget
}

private fun coerceLevel(raw: JsonElement?): Int {
    val primitive = raw as? JsonPrimitive ?: return 0
    if (primitive is JsonNull || primitive.isString) return 0
    val value = primitive.content.toDoubleOrNull() ?: return 0
    return value.toInt()
}

fun fromJson(raw: JsonElement?): RichDoc {
    if (raw == null || raw is JsonNull) return EMPTY_RICH_DOC
    if (raw is JsonPrimitive) return fromPlain(raw.content)

    val blocksRaw: JsonArray = when (raw) {
        is JsonObject -> raw["blocks"] as? JsonArray ?: return EMPTY_RICH_DOC
        is JsonArray -> raw
        else -> return EMPTY_RICH_DOC
    }

    val blocks = ArrayList<RichBlock>()
    var budget = MAX_DOCUMENT_CHARS
    for (entry in blocksRaw.take(MAX_BLOCKS)) {
        if (entry !is JsonObject) {
            val loose = entry as? JsonPrimitive
            if (loose != null && loose.isString) {
                val text = takeCodePoints(cleanText(loose.content), budget)
                budget -= codePointLength(text)
                if (text.isNotEmpty()) blocks.add(RichBlock(spans = listOf(RichSpan(text))))
            }
            continue
        }

        val kind = coerceKind(entry["kind"])
        val spans = ArrayList<RichSpan>()
        val spansRaw = (entry["spans"] as? JsonArray) ?: JsonArray(emptyList())
        for (spanRaw in spansRaw.take(MAX_SPANS_PER_BLOCK)) {
            var text: String
            var marks: Set<Mark>
            when {
                spanRaw is JsonPrimitive && spanRaw !is JsonNull && spanRaw.isString -> {
                    text = cleanText(spanRaw.content)
                    marks = emptySet()
                }
                spanRaw is JsonObject -> {
                    text = cleanText(scalarOf(spanRaw["text"]))
                    marks = coerceMarks(spanRaw["marks"])
                }
                else -> continue
            }
            text = text.replace("\n", " ")
            if (budget <= 0) break
            text = takeCodePoints(text, budget)
            budget -= codePointLength(text)
            if (text.isNotEmpty()) spans.add(RichSpan(text = text, marks = marks))
        }

        var level = coerceLevel(entry["level"])
        level = when {
            kind == BlockKind.HEADING -> maxOf(1, minOf(MAX_HEADING_LEVEL, if (level == 0) 1 else level))
            kind.isListItem -> maxOf(0, minOf(MAX_LIST_DEPTH, level))
            else -> 0
        }

        var media = ""
        var widthPct = IMAGE_DEFAULT_WIDTH_PCT
        if (kind == BlockKind.IMAGE) {
            media = cleanText(scalarOf(entry["media"])).trim().take(MAX_MEDIA_ID_CHARS)
            if (media.isEmpty()) continue
            val rawWidth = (entry["widthPct"] as? JsonPrimitive)?.content?.toFloatOrNull()
            if (rawWidth != null) {
                widthPct = maxOf(IMAGE_MIN_WIDTH_PCT, minOf(IMAGE_MAX_WIDTH_PCT, rawWidth))
            }
        }

        var rows: List<List<List<RichSpan>>> = emptyList()
        if (kind == BlockKind.TABLE) {
            val parsed = coerceRows(entry["rows"], budget)
            rows = parsed.first
            budget = parsed.second
            if (rows.isEmpty()) continue
        }

        blocks.add(
            RichBlock(
                kind = kind,
                spans = spans,
                align = coerceAlign(entry["align"]),
                level = level,
                rows = rows,
                media = media,
                widthPct = widthPct,
            )
        )
        if (budget <= 0) break
    }
    return RichDoc(blocks = blocks)
}

fun fromPlain(text: Any?): RichDoc {
    val cleaned = takeCodePoints(cleanText(text), MAX_DOCUMENT_CHARS)
    if (cleaned.isBlank()) return EMPTY_RICH_DOC
    val blocks = cleaned.split("\n")
        .filter { it.isNotBlank() }
        .map { RichBlock(spans = listOf(RichSpan(it.trim()))) }
    return RichDoc(blocks = blocks.take(MAX_BLOCKS))
}

fun toJson(doc: RichDoc): JsonObject = buildJsonObject {
    putJsonArray("blocks") {
        for (block in doc.blocks) {
            addJsonObject {
                put("kind", block.kind.name)
                putJsonArray("spans") {
                    for (span in block.spans) {
                        addJsonObject {
                            put("text", span.text)
                            if (span.marks.isNotEmpty()) {
                                putJsonArray("marks") {
                                    for (name in span.marks.map { it.name }.sorted()) add(name)
                                }
                            }
                        }
                    }
                }
                if (block.align != Align.LEFT) put("align", block.align.name)
                if (block.level != 0) put("level", block.level)
                if (block.kind == BlockKind.IMAGE) {
                    put("media", block.media)
                    if (block.widthPct != IMAGE_DEFAULT_WIDTH_PCT) put("widthPct", block.widthPct)
                }
                if (block.kind == BlockKind.TABLE) {
                    putJsonArray("rows") {
                        for (row in block.rows) {
                            addJsonArray {
                                for (cell in row) {
                                    addJsonArray {
                                        for (span in cell) {
                                            addJsonObject {
                                                put("text", span.text)
                                                if (span.marks.isNotEmpty()) {
                                                    putJsonArray("marks") {
                                                        for (name in span.marks.map { it.name }.sorted()) add(name)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun toPlain(doc: RichDoc): String {
    val lines = ArrayList<String>()
    var ordinal = 0
    for (block in doc.blocks) {
        if (block.kind == BlockKind.IMAGE) {
            ordinal = 0
            val caption = block.text.trim()
            if (caption.isNotEmpty()) lines.add(caption)
            continue
        }
        if (block.kind == BlockKind.TABLE) {
            ordinal = 0
            for (row in block.rows) {
                lines.add(row.joinToString(" | ") { cell ->
                    cell.joinToString("") { it.text }.trim()
                })
            }
            continue
        }
        val text = block.text.trim()
        if (block.kind == BlockKind.ORDERED_ITEM) {
            ordinal += 1
            lines.add("  ".repeat(block.level) + "$ordinal. " + text)
            continue
        }
        ordinal = 0
        if (block.kind == BlockKind.BULLET_ITEM) {
            lines.add("  ".repeat(block.level) + "• " + text)
        } else {
            lines.add(text)
        }
    }
    return lines.joinToString("\n").trim()
}

fun toPlain(raw: JsonElement?): String = toPlain(fromJson(raw))

fun isEmptyDocument(raw: JsonElement?): Boolean {
    if (raw == null || raw is JsonNull) return true
    if (raw is JsonPrimitive && raw.isString) return raw.content.isBlank()
    return fromJson(raw).isEmpty
}

fun remapInlineMedia(raw: JsonElement?, translate: (String) -> String?): JsonElement? {
    if (raw == null || raw is JsonNull || raw is JsonPrimitive) return raw
    val blocksRaw: JsonArray = when (raw) {
        is JsonObject -> raw["blocks"] as? JsonArray ?: return raw
        is JsonArray -> raw
        else -> return raw
    }

    var changed = false
    val rewritten = blocksRaw.map { entry ->
        val block = entry as? JsonObject ?: return@map entry
        val kind = (block["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.uppercase()
        if (kind != BlockKind.IMAGE.name) return@map entry
        val current = (block["media"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (current.isNullOrEmpty()) return@map entry
        val next = translate(current) ?: return@map entry
        if (next == current) return@map entry
        changed = true
        JsonObject(block + ("media" to JsonPrimitive(next)))
    }
    if (!changed) return raw

    val array = JsonArray(rewritten)
    return if (raw is JsonObject) JsonObject(raw + ("blocks" to array)) else array
}

fun summary(raw: JsonElement?, limit: Int = 160): String = summaryOf(toPlain(raw), limit)

fun summary(doc: RichDoc, limit: Int = 160): String = summaryOf(toPlain(doc), limit)

private fun summaryOf(plain: String, limit: Int): String {
    val text = plain.split(WHITESPACE_RUN).filter { it.isNotEmpty() }.joinToString(" ")
    if (codePointLength(text) <= limit) return text
    return takeCodePoints(text, limit - 1).trimEnd() + "…"
}

private val WHITESPACE_RUN = Regex("\\s+")

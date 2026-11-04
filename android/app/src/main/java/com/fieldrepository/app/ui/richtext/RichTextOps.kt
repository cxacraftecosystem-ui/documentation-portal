package com.fieldrepository.app.ui.richtext

import kotlinx.serialization.json.JsonObject

data class DocPoint(val block: Int, val offset: Int)

data class DocRange(val anchor: DocPoint, val focus: DocPoint)

data class EditResult(val doc: RichDoc, val selection: DocRange)

fun DocRange.isCollapsed(): Boolean = anchor == focus

fun collapsedAt(point: DocPoint): DocRange = DocRange(point, point)

fun comparePoints(a: DocPoint, b: DocPoint): Int =
    if (a.block != b.block) a.block - b.block else a.offset - b.offset

fun clampPoint(doc: RichDoc, point: DocPoint): DocPoint {
    if (doc.blocks.isEmpty()) return DocPoint(0, 0)
    val block = point.block.coerceIn(0, doc.blocks.size - 1)
    return DocPoint(block, point.offset.coerceIn(0, doc.blocks[block].text.length))
}

private fun clampRange(doc: RichDoc, range: DocRange): DocRange =
    DocRange(clampPoint(doc, range.anchor), clampPoint(doc, range.focus))

fun normaliseRange(doc: RichDoc, range: DocRange): DocRange {
    val clamped = clampRange(doc, range)
    return if (comparePoints(clamped.anchor, clamped.focus) <= 0) clamped
    else DocRange(clamped.focus, clamped.anchor)
}

fun blocksInRange(doc: RichDoc, range: DocRange): List<Int> {
    if (doc.blocks.isEmpty()) return emptyList()
    val ordered = normaliseRange(doc, range)
    return (ordered.anchor.block..ordered.focus.block).toList()
}

private fun blockLength(block: RichBlock): Int = block.text.length

private fun sortMarks(marks: Collection<Mark>): Set<Mark> {
    if (marks.isEmpty()) return emptySet()
    if (marks.size == 1) return setOf(marks.first())
    return marks.distinct().sortedBy { it.name }.toCollection(LinkedHashSet())
}

private fun makeSpan(text: String, marks: Collection<Mark> = emptySet()): RichSpan =
    RichSpan(text = text, marks = sortMarks(marks))

private fun mergeSpans(spans: List<RichSpan>): List<RichSpan> {
    val out = ArrayList<RichSpan>(spans.size)
    for (span in spans) {
        if (span.text.isEmpty()) continue
        val previous = out.lastOrNull()
        if (previous != null && previous.marks == span.marks) {
            out[out.size - 1] = previous.copy(text = previous.text + span.text)
            continue
        }
        out.add(RichSpan(text = span.text, marks = sortMarks(span.marks)))
    }
    return out
}

private fun sliceSpans(spans: List<RichSpan>, start: Int, end: Int): List<RichSpan> {
    if (end <= start) return emptyList()
    val out = ArrayList<RichSpan>()
    var position = 0
    for (span in spans) {
        val spanStart = position
        val spanEnd = position + span.text.length
        position = spanEnd
        if (spanEnd <= start) continue
        if (spanStart >= end) break
        val from = maxOf(0, start - spanStart)
        val to = minOf(span.text.length, end - spanStart)
        val text = span.text.substring(from, to)
        if (text.isNotEmpty()) out.add(RichSpan(text = text, marks = span.marks))
    }
    return out
}

private fun withSpans(block: RichBlock, spans: List<RichSpan>): RichBlock =
    block.copy(spans = mergeSpans(spans))

private fun replaceBlock(doc: RichDoc, index: Int, next: RichBlock): RichDoc {
    val blocks = doc.blocks.toMutableList()
    blocks[index] = next
    return RichDoc(blocks)
}

private fun MutableList<RichBlock>.splice(index: Int, remove: Int, vararg inserted: RichBlock) {
    val at = index.coerceIn(0, size)
    repeat(minOf(remove, size - at)) { removeAt(at) }
    addAll(at, inserted.toList())
}

private fun clampLevel(kind: BlockKind, raw: Int): Int = when {
    kind == BlockKind.HEADING -> (if (raw == 0) 1 else raw).coerceIn(1, MAX_HEADING_LEVEL)
    kind.isListItem -> raw.coerceIn(0, MAX_LIST_DEPTH)
    else -> 0
}

private val MARK_BY_NAME: Map<String, Mark> = Mark.entries.associateBy { it.name }
private val KIND_BY_NAME: Map<String, BlockKind> = BlockKind.entries.associateBy { it.name }
private val ALIGN_BY_NAME: Map<String, Align> = Align.entries.associateBy { it.name }

private fun marksOf(names: Collection<String>?): Set<Mark> =
    if (names == null) emptySet() else sortMarks(names.mapNotNull { MARK_BY_NAME[it.uppercase()] })

private fun markNames(marks: Collection<Mark>): List<String> = marks.map { it.name }.sorted()

private fun isJsSpace(c: Char): Boolean = when (c.code) {
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20 -> true
    0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
    else -> c.code in 0x2000..0x200A
}

fun jsTrim(text: String): String {
    var start = 0
    var end = text.length
    while (start < end && isJsSpace(text[start])) start += 1
    while (end > start && isJsSpace(text[end - 1])) end -= 1
    return text.substring(start, end)
}

fun isJsBlank(text: String): Boolean = text.all { isJsSpace(it) }

fun isEmptyDoc(doc: RichDoc): Boolean =
    doc.blocks.all { if (it.kind == BlockKind.IMAGE) it.isEmpty else isJsBlank(it.text) }

fun emittedValue(doc: RichDoc): JsonObject? = if (isEmptyDoc(doc)) null else toJson(doc)

fun docSignature(doc: RichDoc): String = emittedValue(doc)?.toString() ?: EMPTY_DOC_SIGNATURE

const val EMPTY_DOC_SIGNATURE = "EMPTY"

fun emptyDoc(): RichDoc = RichDoc(blocks = listOf(RichBlock()))

fun marksAt(block: RichBlock, offset: Int): List<String> = markNames(marksIn(block, offset))

private fun marksIn(block: RichBlock, offset: Int): Set<Mark> {
    if (block.spans.isEmpty()) return emptySet()
    var position = 0
    var previous: RichSpan? = null
    for (span in block.spans) {
        val spanEnd = position + span.text.length
        if (offset > position && offset <= spanEnd) return span.marks
        val before = previous
        if (offset == position && before != null) return before.marks
        if (offset == position) return span.marks
        position = spanEnd
        previous = span
    }
    return previous?.marks ?: emptySet()
}

fun isMarkActive(doc: RichDoc, range: DocRange, mark: String, pending: List<String>?): Boolean {
    val parsed = MARK_BY_NAME[mark.uppercase()] ?: return false
    return isMarkActiveIn(doc, range, parsed, pending?.let { marksOf(it) })
}

private fun isMarkActiveIn(doc: RichDoc, range: DocRange, mark: Mark, pending: Set<Mark>?): Boolean {
    if (doc.blocks.isEmpty()) return pending != null && mark in pending
    if (range.isCollapsed()) {
        val point = clampPoint(doc, range.focus)
        return mark in (pending ?: marksIn(doc.blocks[point.block], point.offset))
    }
    val ordered = normaliseRange(doc, range)
    val start = ordered.anchor
    val end = ordered.focus
    var sawText = false
    for (index in start.block..end.block) {
        val block = doc.blocks[index]
        val from = if (index == start.block) start.offset else 0
        val to = if (index == end.block) end.offset else blockLength(block)
        for (span in sliceSpans(block.spans, from, to)) {
            sawText = true
            if (mark !in span.marks) return false
        }
    }
    return sawText
}

fun toggleMark(doc: RichDoc, range: DocRange, mark: String): EditResult {
    val parsed = MARK_BY_NAME[mark.uppercase()] ?: return EditResult(doc, range)
    if (range.isCollapsed()) return EditResult(doc, range)
    return applyMarkIn(doc, range, parsed, !isMarkActiveIn(doc, range, parsed, null))
}

fun applyMark(doc: RichDoc, range: DocRange, mark: String, on: Boolean): EditResult {
    val parsed = MARK_BY_NAME[mark.uppercase()] ?: return EditResult(doc, range)
    return applyMarkIn(doc, range, parsed, on)
}

private fun applyMarkIn(doc: RichDoc, range: DocRange, mark: Mark, on: Boolean): EditResult {
    if (doc.blocks.isEmpty()) return EditResult(doc, range)
    val clamped = clampRange(doc, range)
    val ordered = normaliseRange(doc, range)
    val start = ordered.anchor
    val end = ordered.focus
    val blocks = doc.blocks.toMutableList()
    for (index in start.block..end.block) {
        val block = blocks[index]
        val length = blockLength(block)
        val from = if (index == start.block) start.offset else 0
        val to = if (index == end.block) end.offset else length
        if (to <= from) continue
        val middle = sliceSpans(block.spans, from, to).map { span ->
            span.copy(marks = sortMarks(if (on) span.marks + mark else span.marks - mark))
        }
        blocks[index] = withSpans(
            block,
            sliceSpans(block.spans, 0, from) + middle + sliceSpans(block.spans, to, length),
        )
    }
    return EditResult(RichDoc(blocks), clamped)
}

fun clearFormatting(doc: RichDoc, range: DocRange): EditResult {
    if (doc.blocks.isEmpty()) return EditResult(doc, range)
    val clamped = clampRange(doc, range)
    val ordered = normaliseRange(doc, range)
    val start = ordered.anchor
    val end = ordered.focus
    val blocks = doc.blocks.toMutableList()
    for (index in start.block..end.block) {
        val block = blocks[index]
        val length = blockLength(block)
        val from = if (index == start.block) start.offset else 0
        val to = if (index == end.block) end.offset else length
        val middle = sliceSpans(block.spans, from, to).map { it.copy(marks = emptySet()) }
        val cleared = withSpans(
            block,
            sliceSpans(block.spans, 0, from) + middle + sliceSpans(block.spans, to, length),
        )
        blocks[index] = if (block.kind.isStructural) {
            cleared
        } else {
            cleared.copy(kind = BlockKind.PARAGRAPH, align = Align.LEFT, level = 0)
        }
    }
    return EditResult(RichDoc(blocks), clamped)
}

fun setBlockKind(doc: RichDoc, range: DocRange, kind: String, level: Int = 0): EditResult {
    val parsed = KIND_BY_NAME[kind.uppercase()] ?: return EditResult(doc, clampRange(doc, range))
    if (parsed.isStructural) return EditResult(doc, clampRange(doc, range))
    return setBlockKindIn(doc, range, parsed, level)
}

private fun setBlockKindIn(doc: RichDoc, range: DocRange, kind: BlockKind, level: Int): EditResult {
    val clamped = clampRange(doc, range)
    val blocks = doc.blocks.toMutableList()
    for (index in blocksInRange(doc, clamped)) {
        val block = blocks[index]
        if (block.kind.isStructural) continue
        blocks[index] = block.copy(
            kind = kind,
            level = when {
                kind == BlockKind.HEADING -> clampLevel(BlockKind.HEADING, level)
                kind.isListItem -> clampLevel(kind, if (block.kind.isListItem) block.level else 0)
                else -> 0
            },
        )
    }
    return EditResult(RichDoc(blocks), clamped)
}

fun setAlign(doc: RichDoc, range: DocRange, align: String): EditResult {
    val parsed = ALIGN_BY_NAME[align.uppercase()] ?: return EditResult(doc, clampRange(doc, range))
    val clamped = clampRange(doc, range)
    val blocks = doc.blocks.toMutableList()
    for (index in blocksInRange(doc, clamped)) blocks[index] = blocks[index].copy(align = parsed)
    return EditResult(RichDoc(blocks), clamped)
}

fun toggleList(doc: RichDoc, range: DocRange, ordered: Boolean): EditResult {
    val kind = if (ordered) BlockKind.ORDERED_ITEM else BlockKind.BULLET_ITEM
    val indices = blocksInRange(doc, range)
    val allAlready = indices.isNotEmpty() && indices.all { doc.blocks[it].kind == kind }
    return setBlockKindIn(doc, range, if (allAlready) BlockKind.PARAGRAPH else kind, 0)
}

fun shiftIndent(doc: RichDoc, range: DocRange, delta: Int): EditResult {
    val clamped = clampRange(doc, range)
    val blocks = doc.blocks.toMutableList()
    var changed = false
    for (index in blocksInRange(doc, clamped)) {
        val block = blocks[index]
        if (!block.kind.isListItem) continue
        val level = (block.level + delta).coerceIn(0, MAX_LIST_DEPTH)
        if (level == block.level) continue
        blocks[index] = block.copy(level = level)
        changed = true
    }
    return EditResult(if (changed) RichDoc(blocks) else doc, clamped)
}

fun canShiftIndent(doc: RichDoc, range: DocRange, delta: Int): Boolean =
    blocksInRange(doc, range).any { index ->
        val block = doc.blocks[index]
        block.kind.isListItem && (block.level + delta) in 0..MAX_LIST_DEPTH
    }

fun deleteRange(doc: RichDoc, range: DocRange): EditResult {
    val clamped = clampRange(doc, range)
    if (clamped.isCollapsed()) return EditResult(doc, clamped)
    val ordered = normaliseRange(doc, range)
    val start = ordered.anchor
    val end = ordered.focus
    val startBlock = doc.blocks[start.block]
    val endBlock = doc.blocks[end.block]

    if (start.block == end.block) {
        val length = blockLength(startBlock)
        val next = withSpans(
            startBlock,
            sliceSpans(startBlock.spans, 0, start.offset) + sliceSpans(startBlock.spans, end.offset, length),
        )
        return EditResult(replaceBlock(doc, start.block, next), collapsedAt(start))
    }

    val merged = withSpans(
        startBlock,
        sliceSpans(startBlock.spans, 0, start.offset) + sliceSpans(endBlock.spans, end.offset, blockLength(endBlock)),
    )
    val blocks = doc.blocks.toMutableList()
    blocks.splice(start.block, end.block - start.block + 1, merged)
    return EditResult(RichDoc(blocks), collapsedAt(start))
}

private val NEWLINE_RUN = Regex("[\\r\\n]+")

fun insertText(doc: RichDoc, range: DocRange, text: String, marks: List<String> = emptyList()): EditResult {
    val cleaned = NEWLINE_RUN.replace(cleanText(text), " ")
    val afterDelete = deleteRange(doc, range)
    if (cleaned.isEmpty()) return afterDelete
    val point = afterDelete.selection.focus
    val blocks = afterDelete.doc.blocks.toMutableList()
    if (blocks.isEmpty()) blocks.add(RichBlock())
    val block = blocks[point.block]
    val length = blockLength(block)
    blocks[point.block] = withSpans(
        block,
        sliceSpans(block.spans, 0, point.offset) + makeSpan(cleaned, marksOf(marks)) +
            sliceSpans(block.spans, point.offset, length),
    )
    return EditResult(RichDoc(blocks), collapsedAt(DocPoint(point.block, point.offset + cleaned.length)))
}

fun insertDoc(doc: RichDoc, range: DocRange, incoming: RichDoc): EditResult {
    if (incoming.blocks.isEmpty()) return deleteRange(doc, range)
    val afterDelete = deleteRange(doc, range)
    val point = afterDelete.selection.focus
    val blocks = afterDelete.doc.blocks.toMutableList()
    if (blocks.isEmpty()) blocks.add(RichBlock())
    val target = blocks[point.block]
    val length = blockLength(target)
    val head = sliceSpans(target.spans, 0, point.offset)
    val tail = sliceSpans(target.spans, point.offset, length)

    if (incoming.blocks.size == 1) {
        val only = incoming.blocks[0]
        blocks[point.block] = withSpans(target, head + only.spans + tail)
        val next = RichDoc(blocks)
        val caret = DocPoint(point.block, point.offset + blockLength(only))
        return EditResult(next, collapsedAt(clampPoint(next, caret)))
    }

    val first = incoming.blocks.first()
    val last = incoming.blocks.last()
    val middle = incoming.blocks.subList(1, incoming.blocks.size - 1)
    val opening = withSpans(target, head + first.spans)
    val closing = withSpans(last, last.spans + tail)
    blocks.splice(point.block, 1, *(listOf(opening) + middle + listOf(closing)).toTypedArray())
    val next = RichDoc(blocks.take(MAX_BLOCKS))
    val caret = DocPoint(point.block + 1 + middle.size, blockLength(last))
    return EditResult(next, collapsedAt(clampPoint(next, caret)))
}

fun insertImage(
    doc: RichDoc,
    range: DocRange,
    media: String,
    caption: String = "",
    widthPct: Float = IMAGE_DEFAULT_WIDTH_PCT,
): EditResult {
    val id = jsTrim(cleanText(media)).take(MAX_MEDIA_ID_CHARS)
    if (id.isEmpty()) return EditResult(doc, range)

    val blocks = doc.blocks.toMutableList()
    if (blocks.isEmpty()) blocks.add(RichBlock())
    val point = clampPoint(RichDoc(blocks), normaliseRange(doc, range).anchor)

    val text = NEWLINE_RUN.replace(cleanText(caption), " ")
    blocks.splice(
        point.block + 1,
        0,
        RichBlock(
            kind = BlockKind.IMAGE,
            spans = if (text.isEmpty()) emptyList() else listOf(makeSpan(text)),
            media = id,
            widthPct = widthPct.coerceIn(IMAGE_MIN_WIDTH_PCT, IMAGE_MAX_WIDTH_PCT),
        ),
    )
    val next = RichDoc(blocks.take(MAX_BLOCKS))
    return EditResult(next, collapsedAt(clampPoint(next, DocPoint(point.block + 1, text.length))))
}

fun setImageWidth(doc: RichDoc, block: Int, delta: Float): EditResult {
    val target = doc.blocks.getOrNull(block)
    val selection = collapsedAt(clampPoint(doc, DocPoint(block, target?.text?.length ?: 0)))
    if (target == null || target.kind != BlockKind.IMAGE) return EditResult(doc, selection)
    val widthPct = Math.round(target.widthPct + delta).toFloat()
        .coerceIn(IMAGE_MIN_WIDTH_PCT, IMAGE_MAX_WIDTH_PCT)
    if (widthPct == target.widthPct) return EditResult(doc, selection)
    return EditResult(replaceBlock(doc, block, target.copy(widthPct = widthPct)), selection)
}

fun removeImage(doc: RichDoc, block: Int): EditResult {
    val target = doc.blocks.getOrNull(block)
    if (target == null || target.kind != BlockKind.IMAGE) {
        return EditResult(doc, collapsedAt(clampPoint(doc, DocPoint(block, 0))))
    }
    val remaining = doc.blocks.filterIndexed { index, _ -> index != block }
    val next = RichDoc(remaining.ifEmpty { listOf(RichBlock()) })
    val landing = (block - 1).coerceIn(0, next.blocks.size - 1)
    return EditResult(next, collapsedAt(DocPoint(landing, blockLength(next.blocks[landing]))))
}

fun splitBlock(doc: RichDoc, range: DocRange): EditResult {
    val afterDelete = deleteRange(doc, range)
    val point = afterDelete.selection.focus
    val blocks = afterDelete.doc.blocks.toMutableList()
    if (blocks.isEmpty()) blocks.add(RichBlock())
    val block = blocks[point.block]
    val length = blockLength(block)

    if (block.kind == BlockKind.IMAGE) {
        blocks.splice(point.block + 1, 0, RichBlock())
        val next = RichDoc(blocks.take(MAX_BLOCKS))
        return EditResult(next, collapsedAt(clampPoint(next, DocPoint(point.block + 1, 0))))
    }

    if (length == 0 && (block.kind.isListItem || block.kind == BlockKind.QUOTE)) {
        blocks[point.block] = block.copy(kind = BlockKind.PARAGRAPH, level = 0)
        return EditResult(RichDoc(blocks), collapsedAt(point))
    }

    if (block.kind == BlockKind.HEADING && point.offset == 0 && length > 0) {
        blocks.splice(point.block, 0, RichBlock(align = block.align))
        val next = RichDoc(blocks.take(MAX_BLOCKS))
        return EditResult(next, collapsedAt(clampPoint(next, DocPoint(point.block, 0))))
    }

    val left = withSpans(block, sliceSpans(block.spans, 0, point.offset))
    val rightKind = if (block.kind == BlockKind.HEADING) BlockKind.PARAGRAPH else block.kind
    val right = RichBlock(
        kind = rightKind,
        spans = mergeSpans(sliceSpans(block.spans, point.offset, length)),
        align = block.align,
        level = if (rightKind.isListItem) block.level else 0,
    )
    blocks.splice(point.block, 1, left, right)
    val next = RichDoc(blocks.take(MAX_BLOCKS))
    return EditResult(next, collapsedAt(clampPoint(next, DocPoint(point.block + 1, 0))))
}

private fun stepBack(text: String, offset: Int): Int {
    if (offset <= 0) return 0
    if (offset >= 2 && Character.isLowSurrogate(text[offset - 1]) && Character.isHighSurrogate(text[offset - 2])) {
        return offset - 2
    }
    return offset - 1
}

private fun stepForward(text: String, offset: Int): Int {
    if (offset >= text.length) return text.length
    if (offset + 1 < text.length && Character.isHighSurrogate(text[offset]) && Character.isLowSurrogate(text[offset + 1])) {
        return offset + 2
    }
    return offset + 1
}

fun deleteBackward(doc: RichDoc, range: DocRange): EditResult {
    if (!range.isCollapsed()) return deleteRange(doc, range)
    val point = clampPoint(doc, range.focus)
    val block = doc.blocks.getOrNull(point.block) ?: return EditResult(doc, range)

    if (point.offset > 0) {
        val from = stepBack(block.text, point.offset)
        return deleteRange(doc, DocRange(DocPoint(point.block, from), point))
    }

    if (block.kind.isListItem && block.level > 0) return shiftIndent(doc, collapsedAt(point), -1)
    if (block.kind == BlockKind.IMAGE) return removeImage(doc, point.block)
    if (block.kind == BlockKind.TABLE) return EditResult(doc, collapsedAt(point))
    if (block.kind != BlockKind.PARAGRAPH) {
        return EditResult(
            replaceBlock(doc, point.block, block.copy(kind = BlockKind.PARAGRAPH, level = 0)),
            collapsedAt(point),
        )
    }
    if (point.block == 0) return EditResult(doc, collapsedAt(point))

    val previous = doc.blocks[point.block - 1]
    if (previous.kind == BlockKind.TABLE) return EditResult(doc, collapsedAt(point))
    val joinAt = blockLength(previous)
    val blocks = doc.blocks.toMutableList()
    blocks.splice(point.block - 1, 2, withSpans(previous, previous.spans + block.spans))
    return EditResult(RichDoc(blocks), collapsedAt(DocPoint(point.block - 1, joinAt)))
}

fun deleteForward(doc: RichDoc, range: DocRange): EditResult {
    if (!range.isCollapsed()) return deleteRange(doc, range)
    val point = clampPoint(doc, range.focus)
    val block = doc.blocks.getOrNull(point.block) ?: return EditResult(doc, range)
    val text = block.text
    if (point.offset < text.length) {
        val to = stepForward(text, point.offset)
        return deleteRange(doc, DocRange(point, DocPoint(point.block, to)))
    }
    if (point.block >= doc.blocks.size - 1) return EditResult(doc, collapsedAt(point))
    val next = doc.blocks[point.block + 1]
    if (block.kind.isStructural || next.kind.isStructural) return EditResult(doc, collapsedAt(point))
    val blocks = doc.blocks.toMutableList()
    blocks.splice(point.block, 2, withSpans(block, block.spans + next.spans))
    return EditResult(RichDoc(blocks), collapsedAt(point))
}

private class InputRule(val pattern: Regex, val kind: BlockKind, val level: (MatchResult) -> Int)

private val INPUT_RULES = listOf(
    InputRule(Regex("(#{1,4}) "), BlockKind.HEADING) { it.groupValues[1].length },
    InputRule(Regex("[-*+] "), BlockKind.BULLET_ITEM) { 0 },
    InputRule(Regex("\\d{1,3}[.)] "), BlockKind.ORDERED_ITEM) { 0 },
    InputRule(Regex("> "), BlockKind.QUOTE) { 0 },
)

fun applyInputRule(doc: RichDoc, range: DocRange): EditResult? {
    if (!range.isCollapsed()) return null
    val point = clampPoint(doc, range.focus)
    val block = doc.blocks.getOrNull(point.block) ?: return null
    if (block.kind != BlockKind.PARAGRAPH || block.level != 0) return null
    val prefix = block.text.substring(0, point.offset)
    for (rule in INPUT_RULES) {
        val match = rule.pattern.matchEntire(prefix) ?: continue
        val length = blockLength(block)
        val trimmed = withSpans(block, sliceSpans(block.spans, prefix.length, length))
        val next = trimmed.copy(kind = rule.kind, level = rule.level(match))
        return EditResult(replaceBlock(doc, point.block, next), collapsedAt(DocPoint(point.block, 0)))
    }
    return null
}

private const val JS_SPACE_CLASS =
    "[\\t\\n\\u000B\\u000C\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

private val PASTE_BULLET = Regex("^[•‣▪●·*+-]$JS_SPACE_CLASS+")
private val PASTE_ORDERED = Regex("^\\(?(\\d{1,3})[.)]$JS_SPACE_CLASS+")
private val PASTE_HEADING = Regex("^(#{1,4})$JS_SPACE_CLASS+")
private val PASTE_QUOTE = Regex("^>$JS_SPACE_CLASS+")
private val JS_SPACE_RUN = Regex("$JS_SPACE_CLASS+")

private fun jsTrimEnd(text: String): String {
    var end = text.length
    while (end > 0 && isJsSpace(text[end - 1])) end -= 1
    return text.substring(0, end)
}

private fun takeCodePoints(text: String, limit: Int): String {
    if (limit <= 0) return ""
    if (text.length <= limit) return text
    if (text.codePointCount(0, text.length) <= limit) return text
    return text.substring(0, text.offsetByCodePoints(0, limit))
}

fun docFromPastedText(text: String): RichDoc {
    val cleaned = takeCodePoints(cleanText(text), MAX_DOCUMENT_CHARS)
    if (cleaned.isEmpty()) return RichDoc()
    val blocks = ArrayList<RichBlock>()
    for (rawLine in cleaned.split("\n").take(MAX_BLOCKS)) {
        val tabs = rawLine.takeWhile { it == '\t' }.length
        var line = jsTrimEnd(rawLine.substring(tabs))
        if (isJsBlank(line)) {
            blocks.add(RichBlock())
            continue
        }
        val level = minOf(MAX_LIST_DEPTH, tabs)
        val heading = PASTE_HEADING.find(line)
        if (heading != null) {
            blocks.add(
                RichBlock(
                    kind = BlockKind.HEADING,
                    spans = listOf(makeSpan(line.substring(heading.value.length))),
                    level = heading.groupValues[1].length,
                )
            )
            continue
        }
        if (PASTE_QUOTE.containsMatchIn(line)) {
            blocks.add(RichBlock(kind = BlockKind.QUOTE, spans = listOf(makeSpan(line.replace(PASTE_QUOTE, "")))))
            continue
        }
        val ordered = PASTE_ORDERED.find(line)
        if (ordered != null) {
            blocks.add(
                RichBlock(
                    kind = BlockKind.ORDERED_ITEM,
                    spans = listOf(makeSpan(line.substring(ordered.value.length))),
                    level = level,
                )
            )
            continue
        }
        if (PASTE_BULLET.containsMatchIn(line)) {
            line = line.replace(PASTE_BULLET, "")
            blocks.add(RichBlock(kind = BlockKind.BULLET_ITEM, spans = listOf(makeSpan(line)), level = level))
            continue
        }
        blocks.add(RichBlock(spans = listOf(makeSpan(line))))
    }
    return RichDoc(blocks)
}

data class RichMatch(val block: Int, val start: Int, val end: Int)

data class ReplaceResult(val doc: RichDoc, val count: Int)

private val WORD_CHARACTER = Regex("[\\p{L}\\p{N}_]")

private fun isWordBoundary(text: String, index: Int): Boolean {
    if (index < 0 || index >= text.length) return true
    return !WORD_CHARACTER.matches(text[index].toString())
}

fun findMatches(
    doc: RichDoc,
    query: String,
    caseSensitive: Boolean = false,
    wholeWord: Boolean = false,
): List<RichMatch> {
    if (query.isEmpty()) return emptyList()
    val needle = if (caseSensitive) query else query.lowercase()
    val out = ArrayList<RichMatch>()
    doc.blocks.forEachIndexed { index, block ->
        val raw = block.text
        val haystack = if (caseSensitive) raw else raw.lowercase()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) break
            val end = at + query.length
            if (!wholeWord || (isWordBoundary(raw, at - 1) && isWordBoundary(raw, end))) {
                out.add(RichMatch(index, at, end))
            }
            from = at + maxOf(1, query.length)
        }
    }
    return out
}

fun matchToRange(match: RichMatch): DocRange =
    DocRange(DocPoint(match.block, match.start), DocPoint(match.block, match.end))

fun replaceMatch(doc: RichDoc, match: RichMatch, replacement: String): EditResult {
    val block = doc.blocks.getOrNull(match.block) ?: return EditResult(doc, collapsedAt(DocPoint(0, 0)))
    val marks = marksAt(block, match.start + 1)
    return insertText(doc, matchToRange(match), replacement, marks)
}

fun replaceAll(
    doc: RichDoc,
    query: String,
    replacement: String,
    caseSensitive: Boolean = false,
    wholeWord: Boolean = false,
): ReplaceResult {
    val matches = findMatches(doc, query, caseSensitive, wholeWord)
    if (matches.isEmpty()) return ReplaceResult(doc, 0)
    var next = doc
    for (index in matches.indices.reversed()) next = replaceMatch(next, matches[index], replacement).doc
    return ReplaceResult(next, matches.size)
}

data class RichCounts(val words: Int, val characters: Int, val blocks: Int)

fun countDoc(doc: RichDoc): RichCounts {
    var characters = 0
    var words = 0
    for (block in doc.blocks) {
        val text = block.text
        characters += text.length
        for (token in text.split(JS_SPACE_RUN)) if (token.isNotEmpty()) words += 1
    }
    return RichCounts(words = words, characters = characters, blocks = doc.blocks.size)
}

fun orderedNumbers(doc: RichDoc): List<Int?> {
    var counter = 0
    return doc.blocks.map { block ->
        if (isJsBlank(block.text)) return@map null
        if (block.kind == BlockKind.ORDERED_ITEM) {
            counter += 1
            counter
        } else {
            counter = 0
            null
        }
    }
}

data class RichToolbarState(
    val marks: Set<String>,
    val kind: String?,
    val level: Int?,
    val align: String?,
    val canIndent: Boolean,
    val canOutdent: Boolean,
)

fun toolbarState(doc: RichDoc, range: DocRange, pending: List<String>?): RichToolbarState {
    val indices = blocksInRange(doc, range)
    val first = indices.firstOrNull()?.let { doc.blocks[it] }
    val sameKind = first != null && indices.all { doc.blocks[it].kind == first.kind }
    val sameLevel = first != null && indices.all { doc.blocks[it].level == first.level }
    val sameAlign = first != null && indices.all { doc.blocks[it].align == first.align }
    val pendingMarks = pending?.let { marksOf(it) }
    val active = LinkedHashSet<String>()
    for (mark in Mark.entries) if (isMarkActiveIn(doc, range, mark, pendingMarks)) active.add(mark.name)
    return RichToolbarState(
        marks = active,
        kind = if (sameKind) first?.kind?.name else null,
        level = if (sameLevel) first?.level else null,
        align = if (sameAlign) first?.align?.name else null,
        canIndent = canShiftIndent(doc, range, 1),
        canOutdent = canShiftIndent(doc, range, -1),
    )
}

private fun spanOf(text: String, vararg marks: Mark) = RichSpan(text, sortMarks(marks.toList()))

private fun blockOf(
    text: String,
    kind: BlockKind = BlockKind.PARAGRAPH,
    level: Int = 0,
    align: Align = Align.LEFT,
) = RichBlock(kind, listOf(RichSpan(text)).filter { it.text.isNotEmpty() }, align, level)

private fun docOf(vararg blocks: RichBlock) = RichDoc(blocks.toList())

private fun caretAt(block: Int, offset: Int) = collapsedAt(DocPoint(block, offset))

private fun selectionOf(fromBlock: Int, fromOffset: Int, toBlock: Int, toOffset: Int) =
    DocRange(DocPoint(fromBlock, fromOffset), DocPoint(toBlock, toOffset))

private fun textsOf(doc: RichDoc) = doc.blocks.map { it.text }

fun richTextOpsSelfCheck(): List<String> {
    val failures = ArrayList<String>()
    fun check(name: String, condition: Boolean) {
        if (!condition) failures.add(name)
    }

    val bold = docOf(RichBlock(spans = listOf(spanOf("ab", Mark.BOLD))))
    val typedBold = insertText(bold, caretAt(0, 2), "c", listOf("BOLD"))
    check("typing bold at the end of a bold word stays one span", typedBold.doc.blocks[0].spans.size == 1)
    check("typing bold appends the text", typedBold.doc.blocks[0].text == "abc")
    check("typing bold moves the caret", typedBold.selection == caretAt(0, 3))
    val typedPlain = insertText(bold, caretAt(0, 2), "c", emptyList())
    check("typing unmarked after bold opens a second span", typedPlain.doc.blocks[0].spans.size == 2)
    check("deleting all of a span leaves no empty span", deleteRange(bold, selectionOf(0, 0, 0, 2)).doc.blocks[0].spans.isEmpty())
    check("newlines in inserted text become spaces", insertText(docOf(blockOf("")), caretAt(0, 0), "a\nb").doc.blocks[0].text == "a b")

    val mixed = RichBlock(spans = listOf(spanOf("ab", Mark.BOLD), spanOf("cd")))
    check("marksAt reads backwards", marksAt(mixed, 2) == listOf("BOLD"))
    check("marksAt past the boundary is unmarked", marksAt(mixed, 3).isEmpty())
    check("marksAt at offset 0 reads forwards", marksAt(mixed, 0) == listOf("BOLD"))

    val split = splitBlock(docOf(blockOf("hello world")), caretAt(0, 5))
    check("Enter splits a paragraph", textsOf(split.doc) == listOf("hello", " world"))
    check("Enter lands the caret in the successor", split.selection == caretAt(1, 0))
    val emptyItem = splitBlock(docOf(RichBlock(kind = BlockKind.BULLET_ITEM, level = 1)), caretAt(0, 0))
    check("Enter in an empty list item makes no block", emptyItem.doc.blocks.size == 1)
    check("Enter in an empty list item leaves the list", emptyItem.doc.blocks[0].kind == BlockKind.PARAGRAPH)
    check("Enter in an empty list item clears the depth", emptyItem.doc.blocks[0].level == 0)
    val emptyQuote = splitBlock(docOf(RichBlock(kind = BlockKind.QUOTE)), caretAt(0, 0))
    check("Enter in an empty quote leaves the quote", emptyQuote.doc.blocks.single().kind == BlockKind.PARAGRAPH)
    val heading = docOf(blockOf("Title", BlockKind.HEADING, level = 2))
    val above = splitBlock(heading, caretAt(0, 0))
    check("Enter at the start of a heading inserts above", above.doc.blocks.size == 2 && above.doc.blocks[0].text.isEmpty())
    check("Enter at the start of a heading keeps the heading", above.doc.blocks[1].kind == BlockKind.HEADING && above.doc.blocks[1].level == 2)
    check("Enter at the start of a heading leaves the caret above", above.selection == caretAt(0, 0))
    val afterHeading = splitBlock(heading, caretAt(0, 5))
    check("the successor of a heading is a paragraph", afterHeading.doc.blocks[1].kind == BlockKind.PARAGRAPH)
    check("the successor of a heading has no level", afterHeading.doc.blocks[1].level == 0)
    val splitItem = splitBlock(docOf(blockOf("ab", BlockKind.BULLET_ITEM, level = 2)), caretAt(0, 1))
    check("the successor of a list item keeps its depth", splitItem.doc.blocks[1].kind == BlockKind.BULLET_ITEM && splitItem.doc.blocks[1].level == 2)

    val nested = docOf(blockOf("x", BlockKind.ORDERED_ITEM, level = 2))
    val outdented = deleteBackward(nested, caretAt(0, 0))
    check("rung 1 outdents a nested item", outdented.doc.blocks.single().level == 1)
    check("rung 1 keeps the kind", outdented.doc.blocks.single().kind == BlockKind.ORDERED_ITEM)
    val flatItem = deleteBackward(docOf(blockOf("x", BlockKind.BULLET_ITEM, level = 0)), caretAt(0, 0))
    check("rung 2 demotes a flat item", flatItem.doc.blocks.single().kind == BlockKind.PARAGRAPH)
    check("rung 2 keeps the text", flatItem.doc.blocks.single().text == "x")
    val flatHeading = deleteBackward(docOf(blockOf("x", BlockKind.HEADING, level = 3)), caretAt(0, 0))
    check("rung 2 demotes a heading and clears its level", flatHeading.doc.blocks.single().let { it.kind == BlockKind.PARAGRAPH && it.level == 0 })
    val atStart = docOf(blockOf("x"))
    check("rung 3 does nothing at block 0", deleteBackward(atStart, caretAt(0, 0)).doc === atStart)
    val merge = docOf(blockOf("Title", BlockKind.HEADING, level = 1), blockOf("body"))
    val merged = deleteBackward(merge, caretAt(1, 0))
    check("rung 4 merges into the previous block", textsOf(merged.doc) == listOf("Titlebody"))
    check("rung 4 keeps the previous block's kind", merged.doc.blocks[0].kind == BlockKind.HEADING && merged.doc.blocks[0].level == 1)
    check("rung 4 parks the caret at the join", merged.selection == caretAt(0, 5))
    val oneBack = deleteBackward(docOf(blockOf("ab")), caretAt(0, 2))
    check("Backspace mid-text removes one character", oneBack.doc.blocks[0].text == "a" && oneBack.selection == caretAt(0, 1))
    val emoji = "a" + Character.toChars(0x1F600).concatToString()
    val overEmoji = deleteBackward(docOf(blockOf(emoji)), caretAt(0, emoji.length))
    check("Backspace never halves a surrogate pair", overEmoji.doc.blocks[0].text == "a" && overEmoji.selection == caretAt(0, 1))

    val forward = deleteForward(docOf(blockOf("T", BlockKind.HEADING, level = 1), blockOf("x")), caretAt(0, 1))
    check("Delete at the end pulls the next block up", textsOf(forward.doc) == listOf("Tx"))
    check("Delete at the end keeps the caret's own kind", forward.doc.blocks[0].kind == BlockKind.HEADING)
    val across = docOf(blockOf("Heading", BlockKind.HEADING, level = 2), blockOf("paragraph"))
    val cut = deleteRange(across, selectionOf(0, 4, 1, 4))
    check("a cross-block delete keeps the start block's kind", cut.doc.blocks.single().kind == BlockKind.HEADING)
    check("a cross-block delete joins the two halves", cut.doc.blocks.single().text == "Headgraph")
    check("a backwards range deletes the same text", deleteRange(across, selectionOf(1, 4, 0, 4)).doc == cut.doc)
    check("a backwards range is ordered by normaliseRange", normaliseRange(across, selectionOf(1, 4, 0, 4)) == selectionOf(0, 4, 1, 4))

    val partly = docOf(RichBlock(spans = listOf(spanOf("ab", Mark.BOLD), spanOf("cd"))))
    val allBold = toggleMark(partly, selectionOf(0, 0, 0, 4), "BOLD")
    check("toggling a partly bold selection bolds all of it", allBold.doc.blocks[0].spans.single().marks == setOf(Mark.BOLD))
    val unbolded = toggleMark(allBold.doc, selectionOf(0, 0, 0, 4), "BOLD")
    check("toggling a fully bold selection clears it", unbolded.doc.blocks[0].spans.single().marks.isEmpty())
    check("a collapsed toggle changes nothing", toggleMark(partly, caretAt(0, 1), "BOLD").doc === partly)
    check("an unknown mark changes nothing", toggleMark(partly, selectionOf(0, 0, 0, 4), "RAINBOW").doc === partly)
    check("a mixed selection reports no marks", "BOLD" !in toolbarState(partly, selectionOf(0, 0, 0, 4), null).marks)
    check("a wholly bold selection reports the mark", "BOLD" in toolbarState(partly, selectionOf(0, 0, 0, 2), null).marks)
    check("a pending mark is reported at a caret", toolbarState(partly, caretAt(0, 3), listOf("ITALIC")).marks == setOf("ITALIC"))

    val nestedBullet = docOf(blockOf("a", BlockKind.BULLET_ITEM, level = 2))
    check("list to list keeps the depth", setBlockKind(nestedBullet, caretAt(0, 0), "ORDERED_ITEM").doc.blocks[0].level == 2)
    check("paragraph to list starts at depth 0", setBlockKind(docOf(blockOf("a")), caretAt(0, 0), "BULLET_ITEM").doc.blocks[0].level == 0)
    check("a heading with no level is level 1", setBlockKind(nestedBullet, caretAt(0, 0), "HEADING").doc.blocks[0].level == 1)
    check("a heading level is capped at 4", setBlockKind(nestedBullet, caretAt(0, 0), "HEADING", 9).doc.blocks[0].level == MAX_HEADING_LEVEL)
    check("an unknown kind changes nothing", setBlockKind(nestedBullet, caretAt(0, 0), "MARQUEE").doc === nestedBullet)
    check("prose cannot be re-kinded into a table", setBlockKind(nestedBullet, caretAt(0, 0), "TABLE").doc === nestedBullet)
    check("prose cannot be re-kinded into a photograph", setBlockKind(nestedBullet, caretAt(0, 0), "IMAGE").doc === nestedBullet)
    check("align applies to the touched block", setAlign(nestedBullet, caretAt(0, 0), "CENTER").doc.blocks[0].align == Align.CENTER)
    check("an unknown align changes nothing", setAlign(nestedBullet, caretAt(0, 0), "MIDDLE").doc === nestedBullet)
    val deepest = docOf(blockOf("a", BlockKind.BULLET_ITEM, level = MAX_LIST_DEPTH))
    check("indent stops at the deepest level", shiftIndent(deepest, caretAt(0, 0), 1).doc === deepest)
    val plainPara = docOf(blockOf("a"))
    check("indent ignores paragraphs", shiftIndent(plainPara, caretAt(0, 0), 1).doc === plainPara)
    val flatBullet = docOf(blockOf("a", BlockKind.BULLET_ITEM))
    check("a flat bullet can indent but not outdent", toolbarState(flatBullet, caretAt(0, 0), null).let { it.canIndent && !it.canOutdent })
    check("a paragraph can do neither", toolbarState(docOf(blockOf("a")), caretAt(0, 0), null).let { !it.canIndent && !it.canOutdent })
    val loud = docOf(RichBlock(BlockKind.HEADING, listOf(spanOf("a", Mark.BOLD)), Align.CENTER, 2))
    val cleared = clearFormatting(loud, selectionOf(0, 0, 0, 1)).doc.blocks[0]
    check("clear formatting resets the block", cleared.kind == BlockKind.PARAGRAPH && cleared.align == Align.LEFT && cleared.level == 0)
    check("clear formatting drops the marks", cleared.spans.single().marks.isEmpty())
    val two = docOf(blockOf("a"), blockOf("b", BlockKind.HEADING, level = 1))
    check("a mixed selection reports no kind", toolbarState(two, selectionOf(0, 0, 1, 1), null).kind == null)
    check("a single block reports its kind", toolbarState(two, caretAt(0, 0), null).kind == "PARAGRAPH")

    val rule = applyInputRule(docOf(blockOf("# Title")), caretAt(0, 2))
    check("hash-space makes a heading", rule?.doc?.blocks?.get(0)?.kind == BlockKind.HEADING)
    check("hash-space eats the prefix", rule?.doc?.blocks?.get(0)?.text == "Title")
    check("hash-space parks the caret at 0", rule?.selection == caretAt(0, 0))
    check("three hashes make a level 3", applyInputRule(docOf(blockOf("### x")), caretAt(0, 4))?.doc?.blocks?.get(0)?.level == 3)
    check("dash-space makes a bullet", applyInputRule(docOf(blockOf("- x")), caretAt(0, 2))?.doc?.blocks?.get(0)?.kind == BlockKind.BULLET_ITEM)
    check("digit-dot-space makes an ordered item", applyInputRule(docOf(blockOf("12. x")), caretAt(0, 4))?.doc?.blocks?.get(0)?.kind == BlockKind.ORDERED_ITEM)
    check("angle-space makes a quote", applyInputRule(docOf(blockOf("> x")), caretAt(0, 2))?.doc?.blocks?.get(0)?.kind == BlockKind.QUOTE)
    check("a rule never fires mid-line", applyInputRule(docOf(blockOf("a - b")), caretAt(0, 4)) == null)
    check("a rule never fires inside a heading", applyInputRule(docOf(blockOf("- x", BlockKind.HEADING, level = 1)), caretAt(0, 2)) == null)

    val single = insertDoc(docOf(blockOf("ac", BlockKind.BULLET_ITEM, level = 1)), caretAt(0, 1), docOf(blockOf("b")))
    check("a one-block paste keeps the target's kind", single.doc.blocks.single().let { it.kind == BlockKind.BULLET_ITEM && it.level == 1 })
    check("a one-block paste joins the text", single.doc.blocks.single().text == "abc")
    check("a one-block paste leaves the caret after it", single.selection == caretAt(0, 2))
    val multi = insertDoc(docOf(blockOf("ad")), caretAt(0, 1), docOf(blockOf("b"), blockOf("c")))
    check("a multi-block paste splices", textsOf(multi.doc) == listOf("ab", "cd"))
    check("a multi-block paste leaves the caret after the last", multi.selection == caretAt(1, 1))
    val pasted = docFromPastedText("# Head\n- one\n\t- two\n1. first\n> quote\nplain")
    check("paste reads a heading", pasted.blocks[0].let { it.kind == BlockKind.HEADING && it.level == 1 && it.text == "Head" })
    check("paste reads a bullet", pasted.blocks[1].let { it.kind == BlockKind.BULLET_ITEM && it.level == 0 && it.text == "one" })
    check("paste reads a tab as nesting", pasted.blocks[2].let { it.kind == BlockKind.BULLET_ITEM && it.level == 1 && it.text == "two" })
    check("paste reads a numbered item", pasted.blocks[3].let { it.kind == BlockKind.ORDERED_ITEM && it.text == "first" })
    check("paste reads a quote", pasted.blocks[4].let { it.kind == BlockKind.QUOTE && it.text == "quote" })
    check("paste leaves prose alone", pasted.blocks[5].let { it.kind == BlockKind.PARAGRAPH && it.text == "plain" })
    val nbsp = Char(0xA0).toString()
    check("paste accepts a no-break space after a bullet", docFromPastedText("*" + nbsp + "one").blocks[0].kind == BlockKind.BULLET_ITEM)

    check("a no-break space is an empty document", isEmptyDoc(docOf(blockOf(nbsp))))
    check("a byte-order mark is an empty document", isEmptyDoc(docOf(blockOf(Char(0xFEFF).toString()))))
    check("real text is not an empty document", !isEmptyDoc(docOf(blockOf("x"))))
    val uncaptioned = docOf(RichBlock(kind = BlockKind.IMAGE, media = "media-42"))
    check("a caption-less photograph is not an empty document", !isEmptyDoc(uncaptioned))
    check("a caption-less photograph is published, not nulled", emittedValue(uncaptioned) != null)
    check("an image with no media id is an empty document", isEmptyDoc(docOf(RichBlock(kind = BlockKind.IMAGE))))
    check("an empty paragraph is still published as null", emittedValue(emptyDoc()) == null)
    check("jsTrim strips a no-break space", jsTrim(nbsp + "x" + nbsp) == "x")
    val numbered = docOf(
        blockOf("a", BlockKind.ORDERED_ITEM),
        blockOf(""),
        blockOf("b", BlockKind.ORDERED_ITEM),
        blockOf("prose"),
        blockOf("c", BlockKind.ORDERED_ITEM),
    )
    check("an empty block does not break a numbered run", orderedNumbers(numbered) == listOf(1, null, 2, null, 1))
    val counts = countDoc(docOf(blockOf("two words"), blockOf("three more words")))
    check("words are counted as a person counts them", counts.words == 5)
    check("characters count the text only", counts.characters == "two words".length + "three more words".length)
    check("blocks are counted", counts.blocks == 2)

    val haystack = docOf(blockOf("cotton and cotton"), blockOf("Cotton"))
    check("find is case-insensitive by default", findMatches(haystack, "cotton").size == 3)
    check("find can be case-sensitive", findMatches(haystack, "cotton", caseSensitive = true).size == 2)
    check("find can require whole words", findMatches(docOf(blockOf("cot cotton")), "cot", wholeWord = true).size == 1)
    val replaced = replaceAll(haystack, "cotton", "silk")
    check("replace all reports its count", replaced.count == 3)
    check("replace all works backwards without corrupting offsets", textsOf(replaced.doc) == listOf("silk and silk", "silk"))
    check("replace keeps the marks of what it replaced", replaceAll(docOf(RichBlock(spans = listOf(spanOf("cotton", Mark.BOLD)))), "cotton", "silk").doc.blocks[0].spans.single().marks == setOf(Mark.BOLD))

    val prose = docOf(blockOf("before"), blockOf("after"))
    val placed = insertImage(prose, caretAt(0, 3), "media-42", "a loom")
    check("a photograph goes AFTER the caret's block, never splitting it", textsOf(placed.doc) == listOf("before", "a loom", "after"))
    check("the placed block is an IMAGE holding the id", placed.doc.blocks[1].let { it.kind == BlockKind.IMAGE && it.media == "media-42" })
    check("the caret lands at the end of the new caption", placed.selection == caretAt(1, "a loom".length))
    check("a blank id is refused rather than inserted", insertImage(prose, caretAt(0, 0), "   ").doc === prose)
    check("a caption's newline becomes a space", insertImage(prose, caretAt(0, 0), "m", "a\nb").doc.blocks[1].text == "a b")
    check("the width is clamped on the way in", insertImage(prose, caretAt(0, 0), "m", "", 500f).doc.blocks[1].widthPct == IMAGE_MAX_WIDTH_PCT)
    check("an empty document still gets a caption to stand in", insertImage(RichDoc(), caretAt(0, 0), "m", "c").selection == caretAt(1, 1))
    val narrowed = setImageWidth(placed.doc, 1, -IMAGE_WIDTH_STEP_PCT)
    check("the width steps down by the web's step", narrowed.doc.blocks[1].widthPct == IMAGE_DEFAULT_WIDTH_PCT - IMAGE_WIDTH_STEP_PCT)
    check("the width stops at the floor", setImageWidth(narrowed.doc, 1, -900f).doc.blocks[1].widthPct == IMAGE_MIN_WIDTH_PCT)
    check("the width command ignores prose", setImageWidth(prose, 0, 10f).doc === prose)

    val nothing = RichDoc()
    check("an empty document survives a caret query", toolbarState(nothing, caretAt(0, 0), null).kind == null)
    check("an empty document survives Backspace", deleteBackward(nothing, caretAt(0, 0)).doc.blocks.isEmpty())
    check("an empty document accepts typing", insertText(nothing, caretAt(0, 0), "x").doc.blocks.single().text == "x")
    check("an empty document accepts Enter", splitBlock(nothing, caretAt(0, 0)).doc.blocks.size == 2)
    check("an out-of-range point clamps", clampPoint(docOf(blockOf("ab")), DocPoint(9, 9)) == DocPoint(0, 2))

    return failures
}

fun main() {
    val failures = richTextOpsSelfCheck()
    if (failures.isEmpty()) {
        println("RichTextOps self-check: all checks passed")
        return
    }
    println("RichTextOps self-check: " + failures.size + " FAILED")
    for (failure in failures) println("  - " + failure)
}

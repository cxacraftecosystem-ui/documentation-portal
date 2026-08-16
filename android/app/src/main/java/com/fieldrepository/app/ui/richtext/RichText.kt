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

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *  WHERE THIS FILE CAME FROM, AND THE TWO THINGS THAT CHANGED ON THE WAY IN.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * A port of the Design Prototype Workshop application's `report/RichText.kt`, which is itself a port
 * of that repository's `backend/app/services/rich_text.py`. It arrives here to answer one
 * requirement — *"in the existing pages apart from the designer workshop as well, the dictate option
 * along with the rich text formatting for the bigger fields should be there"* — on the record forms
 * of THIS application, which has no design workshops in it at all.
 *
 * COPIED RATHER THAN REWRITTEN, DELIBERATELY. The same columns are edited from this app and from
 * `frontend/lib/richText.ts` in this repository, whichever wrote last wins, and the two must produce
 * the SAME JSON for the same document or a field edited on a phone reads as changed the moment a
 * browser reopens it. A second, smaller model written from the docstrings would agree on the common
 * cases and disagree on exactly the ones nobody tests: empty spans, a mark set's sort order, an
 * omitted default. Keeping this a line-for-line port is what makes the two comparable at all, and it
 * is why the sections below still name behaviours (TABLE, IMAGE, `MAX_DOCUMENT_CHARS`) this
 * application's own editor cannot yet produce — they exist so that a document written by the browser
 * survives a round trip through the phone instead of being silently destroyed by it.
 *
 * THE TWO CHANGES:
 *   1. The package. The sibling keeps the model in `report/` beside its .docx and PDF writers. This
 *      application has no report package, so it lives under `ui/richtext/` with the editor it feeds.
 *   2. `cleanText` travelled with it — see the section directly below. In the sibling it belongs to
 *      `ReportModel.kt`; here there is no such file, and a document model without its sanitiser is a
 *      model that will eventually put a lone surrogate in a column.
 *
 * References below to `[PdfWriter]`, `[DocxWriter]`, `ReportModel.kt` and `backend/tests/
 * test_rich_text.py` are the sibling's, kept because they are the ARGUMENT for the shape of this
 * model rather than decoration. Nothing in this repository imports them and nothing here should.
 */

/**
 * The portable rich-text document: one representation, no HTML anywhere.
 *
 * A researcher writing up a craft's description, a product's remarks or a tool's suggestions needs
 * bold, italic, underline, alignment, and numbered and bulleted lists — the ordinary furniture of a
 * document. That text has to survive being typed on a phone, saved into a `String?` column shared
 * with the web form, and read back by both.
 *
 * **It is stored as a structured document, never as HTML.** HTML would have been the quick answer and
 * is the wrong one, for three reasons that all end in the same place:
 *
 * 1. *The phone cannot render it.* [PdfWriter] draws onto a `Canvas` with a `TextPaint`. Handing it a
 *    fragment of HTML means shipping an HTML parser and a CSS cascade to a field phone, or it means
 *    the offline report silently loses every mark the designer applied. This file is the reason it
 *    needs neither.
 * 2. *It is an injection surface.* Stored HTML is attacker-controlled markup that four surfaces later
 *    interpret. A structured document has no `<script>` to smuggle, because there is no tag
 *    vocabulary at all — a mark is an enum member or it does not exist.
 * 3. *It cannot be validated.* `<b><i>x</b></i>` is a thing a contenteditable will produce, and no
 *    amount of care downstream makes it well-defined. The model below cannot express it.
 *
 * The shape is deliberately the smallest one that covers the requirement:
 *
 *     RichDoc
 *       └── blocks: RichBlock[]        PARAGRAPH | HEADING | BULLET_ITEM | ORDERED_ITEM | QUOTE
 *             ├── align                LEFT | CENTER | RIGHT | JUSTIFY
 *             ├── level                heading level (1-4), or list nesting depth (0-3)
 *             └── spans: RichSpan[]    text + marks
 *                   └── marks          BOLD | ITALIC | UNDERLINE | STRIKETHROUGH | CODE
 *                                      | SUPERSCRIPT | SUBSCRIPT | HIGHLIGHT
 *
 * That is a *flat* block list with a nesting depth, not a tree. A tree is what every rich-text library
 * produces and it is the wrong shape here: the DOCX and PDF writers both lay out one paragraph at a
 * time, so a tree would be flattened at the start of all four renderers and the four flattenings would
 * disagree about the edge cases. Flattening once, at the point the editor saves, means the renderers
 * cannot drift.
 *
 * [toJson] and [fromJson] are inverse. [toPlain] throws the marks away, for search, for CSV export and
 * for the completeness gate — which counts a field as filled on its text, never on its formatting.
 *
 * Every string entering this file passes [cleanText] — the section directly below, which travelled
 * here with the model. A lone surrogate from a phone that cut an emoji in half is not a character at
 * all, and the browser writing the same column will not have produced one, so a phone that lets one
 * through writes a value the other client can never write and nothing downstream expects.
 *
 * THE POINT OF THIS FILE BEING A PORT RATHER THAN AN INDEPENDENT DESIGN: a document typed on the phone
 * and one typed in the browser must be the SAME JSON, byte for byte, because the same field is edited
 * from both and whichever wrote last wins.
 */

// ═══════════════════════════════════════════════════════════════════════════════════════════════
//  THE SINGLE DOOR EVERY STRING IN THIS FILE PASSES THROUGH
// ═══════════════════════════════════════════════════════════════════════════════════════════════
//
// PORTED IN WITH THE MODEL, and it is NOT a convenience copy. In the sibling repository `cleanText`
// lives in `report/ReportModel.kt` beside the .docx and PDF writers, and this file's own header
// points at it as "the one `cleanText` in this app". This application has no report writers and no
// `report` package, so the door had to travel with the document or the document would arrive
// without one — and a document without it is a document that can carry a lone surrogate.
//
// The rule stays byte-identical to the one the sibling ships even though nothing here writes XML,
// because the SAME COLUMN is written by three clients: this app, `frontend/lib/richText.ts`, and
// whatever reads them afterwards. A document sanitised differently on the phone is a document that
// comes back from the browser with different characters in it, and "whichever wrote last wins"
// turns that into a silent edit nobody made. Do not relax it on the grounds that Android draws to a
// Canvas.

/**
 * Is [cp] a codepoint XML 1.0 §2.2 will carry?
 *
 * Everything outside these ranges — the C0 controls other than tab/LF/CR, the surrogate block
 * D800-DFFF, and the two noncharacters FFFE/FFFF — makes the part not well-formed. A Kotlin String
 * can hold all of them; a document part cannot carry any.
 *
 * Note what the last range does for free: a codepoint at or above 0x10000 can only have been
 * assembled from a VALID surrogate pair, so every astral character (an emoji, a rare CJK ideograph)
 * is legal and kept. The surrogate block itself is absent from every range, so a LONE surrogate —
 * the half-emoji a truncating text field produces — can never pass.
 */
private fun isXmlLegal(cp: Int): Boolean =
    cp == 0x9 || cp == 0xA || cp == 0xD ||
        (cp in 0x20..0xD7FF) || (cp in 0xE000..0xFFFD) || (cp in 0x10000..0x10FFFF)

/**
 * Coerce [value] to a string that can be written into a document part.
 *
 * Non-strings are stringified (`null` becomes an empty string, not the word "null" — a missing
 * optional field must leave a blank cell, and every renderer would otherwise print "null" into the
 * report). Line endings collapse to `\n`. Codepoints XML cannot carry are dropped rather than
 * replaced, because a replacement glyph in a government report reads as a data-entry error while a
 * dropped half-emoji reads as nothing at all.
 *
 * THE SURROGATE HANDLING BELOW IS THE SINGLE MOST IMPORTANT DETAIL IN THIS FILE. Python strings are
 * sequences of codepoints and its version of this function is a one-line regular expression; Kotlin
 * strings are UTF-16, so an astral character is TWO `Char`s and a naive per-`Char` filter deletes
 * both halves of every legal emoji (they are in the surrogate block) while a naive per-`Char` pass
 * that keeps the surrogate block lets a LONE surrogate through into the XML. Both were shipped, in
 * that order. The loop iterates by CODE POINT: a valid pair is judged as the one character it is
 * and kept whole, an unpaired half is dropped, and the two halves of a kept pair are re-emitted
 * together so the output is still well-formed UTF-16.
 *
 * `Boolean` is intercepted before the `toString()` branch because "true"/"false" is never what a
 * report wants — Yes/No is what the form showed the researcher. (In Python this ordering is
 * load-bearing for a second reason: `bool` is an `int` subclass there.)
 */
fun cleanText(value: Any?): String {
    if (value == null) return ""
    if (value is Boolean) return if (value) "Yes" else "No"
    val raw = value as? String ?: value.toString()
    if (raw.isEmpty()) return ""

    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        // Line endings first. Word renders a lone CR and a CRLF differently inside a run;
        // normalising here means no renderer ever has to agree on which one it was handed.
        // U+2028/U+2029 arrive in pasted web text and are separators no document format reflows
        // correctly, so they become ordinary newlines too.
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
                // A legal astral character. Judge the assembled codepoint, keep both halves.
                if (isXmlLegal(Character.toCodePoint(c, raw[i + 1]))) {
                    out.append(c).append(raw[i + 1])
                }
                i += 2
                continue
            }
            // A high surrogate with nothing after it: not a character at all. Dropped.
            i++
            continue
        }
        if (Character.isLowSurrogate(c)) {
            // A low surrogate reached here means the high half was missing or already consumed.
            i++
            continue
        }
        if (isXmlLegal(c.code)) out.append(c)
        i++
    }
    return out.toString()
}

// --------------------------------------------------------------------------------------
// Budgets
// --------------------------------------------------------------------------------------

/**
 * A stored document larger than this is not prose a human typed, and letting it through means a report
 * generation that never finishes — on a phone, that is an export dialog that spins until the process
 * is killed. The limit is per FIELD and generous: the longest narrative field in the registry is a
 * cluster history, and 200 000 characters is around forty pages of it.
 */
const val MAX_DOCUMENT_CHARS = 200_000

const val MAX_BLOCKS = 2_000
const val MAX_HEADING_LEVEL = 4
const val MAX_LIST_DEPTH = 3

/**
 * Spans per block. A block with more than this many spans is not a paragraph, it is an editor that
 * emitted one span per keystroke; the cap bounds the work before the character budget can.
 */
private const val MAX_SPANS_PER_BLOCK = 512

/**
 * Table bounds. A table bigger than this is a spreadsheet somebody pasted, not prose with a grid in
 * it, and both are enforced on the way IN so no renderer ever meets a 400-column table. They must
 * match `rich_text.py`: a phone that accepted 20 columns where the server keeps 12 would write a
 * document the server then silently truncated.
 */
private const val MAX_TABLE_ROWS = 200
private const val MAX_TABLE_COLUMNS = 12
private const val MAX_SPANS_PER_CELL = 64

/**
 * An inline photograph's printed width, as a percentage of the text column.
 *
 * Named constants rather than the literals that used to sit in [fromJson] and [toJson], because the
 * editor now PLACES a photograph as well as reading one, and three surfaces have to agree about the
 * same four numbers: `IMAGE_DEFAULT_WIDTH_PCT` is the value [toJson] omits, so a phone defaulting to
 * 70 against a browser defaulting to 65 would write a different document for the same picture. They
 * are the `IMAGE_*_PCT` exports of `frontend/lib/richText.ts` and the same bounds `rich_text.py`
 * clamps to.
 *
 * ALL FOUR, AND THE STEP IS THE ONE THAT LOOKS OPTIONAL AND IS NOT. The web exports these in a
 * single block for a reason: `widthPct` is not a display preference, it is STORED in the document
 * and printed by five renderers. A phone stepping by 10 against a browser stepping by 15 gives the
 * same photograph, widened once by two people, 80% on one surface and 85% on the other — a real
 * difference in the .docx, written by a control whose label ("Wider") promises the two are the same
 * gesture. Nothing fails, no test that does not name the number notices, and the two copies of the
 * report simply lay the figure out differently. See `RichTextEditor.tsx`'s `image-narrower` /
 * `image-wider`, which pass exactly this constant.
 */
const val IMAGE_DEFAULT_WIDTH_PCT = 70f
const val IMAGE_MIN_WIDTH_PCT = 10f
const val IMAGE_MAX_WIDTH_PCT = 100f
const val IMAGE_WIDTH_STEP_PCT = 15f

/** The stored media id is truncated to this on the way in, on all three surfaces. */
const val MAX_MEDIA_ID_CHARS = 64

// --------------------------------------------------------------------------------------
// The model
// --------------------------------------------------------------------------------------

/**
 * A block's horizontal alignment.
 *
 * TRAVELLED WITH THE MODEL, like `cleanText` above and for the same reason: in the sibling
 * repository this enum belongs to `report/ReportModel.kt`, because the .docx and PDF writers align
 * paragraphs with it too. There is no report model on this side, and a document whose `align` was a
 * bare string would be a document the browser's `LEFT | CENTER | RIGHT | JUSTIFY` could disagree
 * with silently.
 *
 * LEFT is the default everywhere and is the value `isPlainProse` treats as "nothing interesting is
 * set" — see `ui/RecordProseText.kt`. The other three make a document out of an otherwise plain
 * answer, deliberately: an alignment is lost by `toPlain`, so storing one as prose would drop it.
 */
@Serializable
enum class Align { LEFT, CENTER, RIGHT, JUSTIFY }

/** An inline mark. The whole vocabulary — there is no mechanism for adding one at runtime. */
@Serializable
enum class Mark {
    BOLD,
    ITALIC,
    UNDERLINE,
    STRIKETHROUGH,
    CODE,

    /**
     * The two a workshop report actually needs and that every renderer can carry: a footnote
     * marker, "m²", "H₂O", the ordinal in "3ʳᵈ warp".
     *
     * A CHARACTER CANNOT BE BOTH. Both marks on one span is a document no editor here can produce
     * and a hand-written value could; [RichSpan.subscript] resolves it by letting SUPERSCRIPT win,
     * in one place, so [DocxWriter] and [PdfWriter] cannot disagree about which one it was — the
     * same rule `rich_text.py` applies on the server.
     */
    SUPERSCRIPT,
    SUBSCRIPT,

    /**
     * One highlight, not a palette, and the colour is not the designer's to choose.
     *
     * `w:highlight` takes a CLOSED vocabulary of sixteen named colours — it is not an RGB value —
     * so a picker offering an arbitrary colour would store something Word cannot express and each
     * renderer would then approximate it differently. One fixed yellow ([HIGHLIGHT_FILL]) is what
     * all four renderers draw identically, and it is the only thing a highlight in a submitted
     * report ever means: "look at this".
     */
    HIGHLIGHT,
}

@Serializable
enum class BlockKind {
    PARAGRAPH,
    HEADING,
    BULLET_ITEM,
    ORDERED_ITEM,
    QUOTE,

    /**
     * A grid the designer built inside the prose. Its content lives in [RichBlock.rows], not in
     * [RichBlock.spans] — the one kind whose text is not a run of spans.
     *
     * IT IS HERE PRIMARILY SO THE PHONE CANNOT DESTROY ONE. `coerceKind` degrades an unrecognised
     * kind to PARAGRAPH, and the parser reads only `spans`, which a table leaves empty — so a
     * build that did not know this constant would open a field containing a table, find nothing in
     * it, and write the field back with the whole grid gone. That is silent data loss on a
     * document a designer may have spent an afternoon on, and it is triggered by the ordinary act
     * of opening the stage. Round-tripping it is not optional; editing it on the handset is.
     */
    TABLE,

    /**
     * A photograph placed WHERE THE DESIGNER PUT IT, rather than in the stage's gallery. The id is
     * in [RichBlock.media] and the caption is the block's [RichBlock.spans].
     *
     * Here for the same reason [TABLE] is: `coerceKind` degrades an unknown kind to PARAGRAPH and
     * the parser reads only `spans`, so a build that did not know this constant would open a field
     * containing an inline photograph, keep the caption, throw the picture away, and write the
     * field back. The designer would see a caption under nothing.
     */
    IMAGE;

    val isListItem: Boolean get() = this == BULLET_ITEM || this == ORDERED_ITEM

    /**
     * A kind whose CONTENT IS NOT ITS SPANS, and which therefore cannot be re-kinded without
     * destroying it — the port of `isStructuralKind` in `frontend/lib/richText.ts`.
     *
     * [TABLE] keeps its grid in [RichBlock.rows] and [IMAGE] keeps its picture in
     * [RichBlock.media], and `toStored` writes each of those fields ONLY for the kind that owns it.
     * So the moment either becomes a PARAGRAPH the next save omits the field, and the grid or the
     * photograph is gone from the record on every surface, permanently.
     *
     * The two enum constants above already say that a build which does not KNOW these kinds
     * destroys them. This is the other half of the same rule: a build that knows them can still
     * destroy one by re-kinding it, and the gesture that does it is not a destructive-looking gesture
     * at all — "Clear formatting", or the Paragraph button, pressed over a selection that happens to
     * run through a table on its way to a sentence three blocks away. A designer stripping a stray
     * bold run out of a caption would have found the photograph gone and the caption floating under
     * nothing.
     *
     * The structural kinds are removed by their OWN controls (delete the table, remove the picture),
     * which is where a designer expects to lose one.
     */
    val isStructural: Boolean get() = this == TABLE || this == IMAGE
}

/** A run of text carrying zero or more marks. */
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

    // SUPERSCRIPT wins a span carrying both — see the note on the enum. Resolved on the READER
    // rather than on the way in, so the designer's marks are stored exactly as they applied them
    // and only the rendering is made single-valued.
    val subscript: Boolean get() = Mark.SUBSCRIPT in marks && Mark.SUPERSCRIPT !in marks
    val highlight: Boolean get() = Mark.HIGHLIGHT in marks
}

@Serializable
data class RichBlock(
    val kind: BlockKind = BlockKind.PARAGRAPH,
    val spans: List<RichSpan> = emptyList(),
    val align: Align = Align.LEFT,
    /**
     * Heading level 1-4 for [BlockKind.HEADING]; list nesting depth 0-3 for the two item kinds;
     * ignored otherwise and forced to 0 on the way in, so a paragraph can never carry one.
     */
    val level: Int = 0,
    /**
     * [BlockKind.TABLE] only, empty for every other kind. Rows of cells of spans, the FIRST row
     * being the header — the same reading the server gives it, which is what lets both sides map
     * the block onto one [TableBlock].
     */
    val rows: List<List<List<RichSpan>>> = emptyList(),
    /** [BlockKind.IMAGE] only: the media id, resolved through the same resolver as any picture. */
    val media: String = "",
    /** [BlockKind.IMAGE] only: printed width as a percentage of the text column, clamped 10-100. */
    val widthPct: Float = IMAGE_DEFAULT_WIDTH_PCT,
) {
    val text: String
        get() =
            // A table's text is its cells, read left to right and top to bottom. It must be
            // defined: `isEmpty` reads it and the completeness gate reads `isEmpty`, so a stage
            // whose only content was a filled-in table would otherwise report as unfilled.
            if (kind == BlockKind.TABLE) {
                rows.joinToString("\n") { row ->
                    row.joinToString("\t") { cell -> cell.joinToString("") { it.text } }
                }
            } else {
                spans.joinToString("") { it.text }
            }

    // An IMAGE is never empty while it holds a media id, even with no caption: judging it on text
    // would make a field whose only content is a photograph read as unfilled to the gate.
    val isEmpty: Boolean get() = if (kind == BlockKind.IMAGE) media.isEmpty() else text.isBlank()

    /** The widest row. Rows are padded to this when the table is drawn, never before. */
    val columnCount: Int get() = rows.maxOfOrNull { it.size } ?: 0
}

@Serializable
data class RichDoc(
    val blocks: List<RichBlock> = emptyList(),
) {
    val isEmpty: Boolean get() = blocks.all { it.isEmpty }

    val text: String get() = blocks.joinToString("\n") { it.text }
}

/** The canonical empty document. Shared, because every miss in [fromJson] returns it. */
val EMPTY_RICH_DOC = RichDoc()

// --------------------------------------------------------------------------------------
// Codepoint arithmetic — why the budget is not `String.take`
// --------------------------------------------------------------------------------------

/**
 * The number of Unicode codepoints in [text], which is what the server counts.
 *
 * Python strings are sequences of codepoints, so `len(s)` there is a codepoint count. A Kotlin String
 * is UTF-16, so `s.length` counts an emoji as TWO. Counting the Kotlin way would make the same
 * document truncate at a different place on the phone than on the server — and two clients disagreeing
 * about where a 200 000-character narrative ends is a field that changes every time it is saved from
 * the other surface.
 */
private fun codePointLength(text: String): Int = text.codePointCount(0, text.length)

/**
 * The first [limit] CODEPOINTS of [text].
 *
 * `text.take(n)` would cut at a UTF-16 index, and a cut that lands between the two halves of a
 * surrogate pair leaves a LONE SURROGATE at the end of the string — precisely the character
 * [cleanText] exists to remove, reintroduced downstream of it, in a value that then goes straight into
 * `word/document.xml`. Word's response to that is to refuse the whole file. Cutting on a codepoint
 * boundary cannot produce one.
 */
private fun takeCodePoints(text: String, limit: Int): String {
    if (limit <= 0) return ""
    // A string whose UTF-16 length already fits cannot have more codepoints than that.
    if (text.length <= limit) return text
    val count = text.codePointCount(0, text.length)
    if (count <= limit) return text
    return text.substring(0, text.offsetByCodePoints(0, limit))
}

// --------------------------------------------------------------------------------------
// Parsing — every path in is forgiving, because three clients write these
// --------------------------------------------------------------------------------------

/**
 * A JSON scalar as the plain Kotlin value [cleanText] expects.
 *
 * A composite (an object or an array) where a string was expected is a client bug with no salvageable
 * text in it, so it becomes `null` and therefore an empty string. The server stringifies it instead
 * and would print a Python dict repr into a government report; that is the one place this port is
 * deliberately less faithful, and it is less faithful in the direction of printing nothing.
 */
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

/**
 * The marks in [raw] that this build recognises; anything else is dropped in silence.
 *
 * Dropping rather than raising is the same rule the stage registry follows for unknown field keys: a
 * phone one release ahead may apply a mark this build has never heard of, and losing the mark is a
 * cosmetic regression while losing the paragraph is data loss.
 */
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

/**
 * Rows of cells of spans, bounded, forgiving, and sharing the document's character budget.
 *
 * The port of the server's `_coerce_rows`, and forgiving in the same way as everything else here: a
 * row that is not an array is skipped, a cell that is a bare string becomes one unmarked span, and
 * a ragged table stays ragged rather than being rejected — `columnCount` squares it off at drawing
 * time. Returns the rows and what is left of the budget, because the budget is the DOCUMENT's and a
 * table must not be able to spend more of it than the paragraphs around it.
 */
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
    // Drop trailing rows that are entirely blank — what an editor leaves behind when a designer
    // adds a row and then thinks better of it.
    while (rows.isNotEmpty() && rows.last().none { cell -> cell.any { it.text.isNotBlank() } }) {
        rows.removeAt(rows.size - 1)
    }
    return rows to budget
}

/**
 * A stored `level`, or 0 when the value is not a number.
 *
 * The `isString` guard is not pedantry: `{"level": "3"}` is what a form control that never coerced its
 * input produces, and the server's `isinstance(level, (int, float))` rejects it. Accepting it here
 * would give the phone a level-3 heading where the server has a level-1 one, in the same field.
 * `true` is rejected on both sides too — in Python because `bool` is an `int` subclass and the check
 * excludes it explicitly, here because a boolean has no numeric value.
 */
private fun coerceLevel(raw: JsonElement?): Int {
    val primitive = raw as? JsonPrimitive ?: return 0
    if (primitive is JsonNull || primitive.isString) return 0
    val value = primitive.content.toDoubleOrNull() ?: return 0
    // Python's int() truncates toward zero, and so does Kotlin's toInt().
    return value.toInt()
}

/**
 * Build a document from whatever a client stored, never throwing.
 *
 * Accepts three shapes, because all three genuinely occur:
 *
 * * the canonical `{"blocks": [...]}` this file emits;
 * * a bare list of blocks, which is what a client that stored `doc.blocks` produces;
 * * a plain string, which is EVERY value written before a field was promoted from LONG_TEXT to
 *   RICH_TEXT. That last one is the important case: promoting a field must not blank the prose already
 *   stored under it, so a string is read as an unformatted document, with its blank lines becoming
 *   paragraph breaks exactly as the plain-text renderer treated them. On this device that migration is
 *   not a server-side one-off — a draft sitting in `WorkshopDraftStore` from before the promotion is
 *   read back by whatever build the phone has now.
 */
fun fromJson(raw: JsonElement?): RichDoc {
    if (raw == null || raw is JsonNull) return EMPTY_RICH_DOC
    // A scalar is preserved as text rather than discarded. A number where a document was expected is a
    // client bug, but the value is still something a designer typed, and silently dropping it loses
    // data to fix a formatting mistake.
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
            // A bare string in the block list is a paragraph; anything else is skipped.
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
            // A newline inside a span would make one block render as two, which no renderer expects;
            // the editor emits a new block for a new line, so this only fires on hand-written or
            // migrated data. It has to happen BEFORE the budget is charged, or the same document costs
            // a different number of characters depending on how its line breaks were spelled.
            text = text.replace("\n", " ")
            if (budget <= 0) break
            text = takeCodePoints(text, budget)
            budget -= codePointLength(text)
            if (text.isNotEmpty()) spans.add(RichSpan(text = text, marks = marks))
        }

        var level = coerceLevel(entry["level"])
        level = when {
            // A heading with no level is a level 1, not a level 0 that no renderer has a style for —
            // both writers index a four-element array of sizes by `level - 1`.
            kind == BlockKind.HEADING -> maxOf(1, minOf(MAX_HEADING_LEVEL, if (level == 0) 1 else level))
            kind.isListItem -> maxOf(0, minOf(MAX_LIST_DEPTH, level))
            else -> 0
        }

        var media = ""
        var widthPct = IMAGE_DEFAULT_WIDTH_PCT
        if (kind == BlockKind.IMAGE) {
            media = cleanText(scalarOf(entry["media"])).trim().take(MAX_MEDIA_ID_CHARS)
            // An IMAGE with no id is not a picture — dropped, exactly as an empty TABLE is.
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
            // A TABLE with nothing in it is not a table. Dropped rather than kept as an empty grid
            // because `isEmpty` judges on text: a document whose only block was a 3x3 of blank
            // cells would otherwise count as filled and pass the completeness gate.
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

/**
 * A plain string as an unformatted document, one paragraph per line.
 *
 * Blank lines separate paragraphs and are not themselves kept, matching how [DocumentBuilder.para] has
 * always split a textarea's contents.
 */
fun fromPlain(text: Any?): RichDoc {
    val cleaned = takeCodePoints(cleanText(text), MAX_DOCUMENT_CHARS)
    if (cleaned.isBlank()) return EMPTY_RICH_DOC
    val blocks = cleaned.split("\n")
        .filter { it.isNotBlank() }
        .map { RichBlock(spans = listOf(RichSpan(it.trim()))) }
    return RichDoc(blocks = blocks.take(MAX_BLOCKS))
}

// --------------------------------------------------------------------------------------
// Serialising
// --------------------------------------------------------------------------------------

/**
 * The canonical storage form. Defaults are omitted to keep the JSON column small.
 *
 * The key ORDER matters more than it looks: this JSON is compared against the server's byte for byte
 * by the port's own harness, and it is what a sync conflict resolver diffs. `kind`, `spans`, then the
 * optional `align` and `level` — the same order `to_json` writes them in.
 *
 * Marks are sorted by NAME, not by declaration order, because Python emits `sorted(...)` of the mark
 * values and an unsorted set would produce `["ITALIC","BOLD"]` on the phone against `["BOLD","ITALIC"]`
 * on the server for the same document — two different strings in the column for the same formatting.
 */
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
                    // Written only when it differs from the default, matching `to_json`.
                    if (block.widthPct != IMAGE_DEFAULT_WIDTH_PCT) put("widthPct", block.widthPct)
                }
                if (block.kind == BlockKind.TABLE) {
                    // Emitted in the same position and the same shape as the server's `to_json`,
                    // and `spans` above stays present-and-empty rather than being omitted: the
                    // oracle compares these objects key for key, and an absent key is a different
                    // document from an empty one.
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

/**
 * The text with every mark discarded.
 *
 * List items keep a visible marker, because this string is what a CSV export and a search index
 * receive, and a bulleted list flattened to bare lines reads as one run-on sentence.
 *
 * The ordered counter RESETS on any block that is not an ordered item. Two separate numbered lists with
 * a paragraph between them must both start at 1; a counter that only ever climbed printed the second
 * list as "4. 5. 6." and made it read as a continuation of the first.
 */
fun toPlain(doc: RichDoc): String {
    val lines = ArrayList<String>()
    var ordinal = 0
    for (block in doc.blocks) {
        if (block.kind == BlockKind.IMAGE) {
            // The CAPTION is the text of a photograph; a caption-less picture contributes nothing.
            ordinal = 0
            val caption = block.text.trim()
            if (caption.isNotEmpty()) lines.add(caption)
            continue
        }
        if (block.kind == BlockKind.TABLE) {
            // One line per row, cells joined by " | ". A tab would be invisible in a search index
            // and would make two adjacent cells read as one word in a CSV export.
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

/**
 * Whether a stored value counts as unfilled, for the completeness gate.
 *
 * A document of empty paragraphs is empty. This is why the gate reads the TEXT and not the presence of
 * the JSON: an editor that has been focused and left alone still saves
 * `{"blocks":[{"kind":"PARAGRAPH","spans":[]}]}`, and counting that as a filled required field would
 * let a designer submit a report with a blank introduction at 100% complete.
 */
fun isEmptyDocument(raw: JsonElement?): Boolean {
    if (raw == null || raw is JsonNull) return true
    if (raw is JsonPrimitive && raw.isString) return raw.content.isBlank()
    return fromJson(raw).isEmpty
}

// --------------------------------------------------------------------------------------
// The media ids buried in a document
//
// A photograph placed inside a narrative does not put its id in the FIELD's value; it puts it in a
// block, several levels down in the document JSON. Anything that walks a record looking for
// photographs therefore has to be told to look here, and the server already is
// (`rich_text.media_ids`, called from `design_workshops._media_ids`).
//
// THE PHONE NEEDS ONLY THE REWRITE, WHICH IS WHY ONLY THE REWRITE IS HERE. A read-only twin of
// `rich_text.media_ids` was written alongside it and had no caller: the sync layer needs to SWAP the
// ids, not list them, and the report resolver is handed one id at a time by `toReportBlocks`. R8
// deletes an unreachable function from the release build, so a port kept "for parity" is a port that
// is not in the product while reading as though it were. Add it back the day something asks.
// --------------------------------------------------------------------------------------

/**
 * The same stored document with every inline media id passed through [translate] — the phone's own
 * id swapped for the server's, on the way to the wire.
 *
 * ── WHY THIS IS SURGERY ON THE RAW TREE AND NOT `fromJson` THEN `toJson` ──────────────────────
 *
 * Because the round trip is not the identity for a document this build did not write. A field
 * carrying a mark a later release added, a block kind this build has never heard of, a table with a
 * ragged row — all of them survive in the column and none of them survives being re-serialised here.
 * More immediately: the stage's payload is HASHED to decide whether it needs sending at all, so
 * re-serialising every narrative on every pass would change the signature of stages nobody has
 * touched and re-upload the whole workshop.
 *
 * So the value is returned UNCHANGED — the same instance — unless an IMAGE block's `media` actually
 * moves. [translate] returning null means "leave this one alone", which is what a caller says both
 * for an id that is already the server's and for one whose upload has not finished; the second case
 * is the caller's cue to hold the stage back rather than to send a broken reference.
 */
fun remapInlineMedia(raw: JsonElement?, translate: (String) -> String?): JsonElement? {
    // A plain string is the pre-promotion shape and has no blocks in it; null is nothing at all.
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
        // `plus` on a JsonObject's backing LinkedHashMap REPLACES `media` in place rather than
        // appending it, so the key order the oracle compares against is unchanged.
        JsonObject(block + ("media" to JsonPrimitive(next)))
    }
    if (!changed) return raw

    val array = JsonArray(rewritten)
    return if (raw is JsonObject) JsonObject(raw + ("blocks" to array)) else array
}

/** A one-line plain-text preview, for a list row or a collection's label field. */
fun summary(raw: JsonElement?, limit: Int = 160): String = summaryOf(toPlain(raw), limit)

fun summary(doc: RichDoc, limit: Int = 160): String = summaryOf(toPlain(doc), limit)

private fun summaryOf(plain: String, limit: Int): String {
    val text = plain.split(WHITESPACE_RUN).filter { it.isNotEmpty() }.joinToString(" ")
    if (codePointLength(text) <= limit) return text
    // Truncated on a CODEPOINT boundary: a label cut through an emoji is a lone surrogate, and this
    // string is used as a collection row's title, which is written straight into the report.
    return takeCodePoints(text, limit - 1).trimEnd() + "…"
}

private val WHITESPACE_RUN = Regex("\\s+")

// ═══════════════════════════════════════════════════════════════════════════════════════════════
//  WHAT WAS LEFT BEHIND: `toReportBlocks`
// ═══════════════════════════════════════════════════════════════════════════════════════════════
//
// The sibling repository's copy of this file ends with ~170 lines that turn a [RichDoc] into its
// report model — `ParagraphBlock`, `BulletListBlock`, `TableBlock`, `ImageBlock`, and a per-run
// script split so a bolded phrase containing both English and Odia reaches Word as two runs with
// two fonts rather than as one run of boxes.
//
// NONE OF IT CAME ACROSS, because this application has no report writers, no `ReportModel.kt`, no
// .docx and no PDF — there is nothing on this side for those blocks to be rendered by. Porting them
// would have meant porting the report model and its script detection to give a phone a document
// format it cannot produce.
//
// IT IS RECORDED HERE RATHER THAN SIMPLY DELETED so that the next person diffing this file against
// the sibling's finds the gap explained instead of assuming a bad paste. If this application ever
// grows an on-device export, that is the section to bring over, and it will need `Script`,
// `splitByScript` and the report model with it.

package com.fieldrepository.app.ui.richtext

import kotlinx.serialization.json.JsonObject

/**
 * The editing half of the rich-text model: every command is a pure function of a document and a
 * selection, and returns a new document and a new selection.
 *
 * This is a port of `frontend/lib/richText.ts` — the copy sitting in THIS repository, beside the
 * browser editor that writes the same columns — by way of the Design Prototype Workshop
 * application's `ui/designworkshop/RichTextOps.kt`, from which it was copied line for line. The
 * document model — [RichDoc], [RichBlock], [RichSpan], [Mark], [BlockKind] — is in `RichText.kt` in
 * this package and is NOT restated here; this file adds only the verbs. The same field is edited
 * from a phone and from a browser and whichever wrote last wins, so the two editors have to agree
 * about what Enter does inside a heading, about whether Backspace at the start of a nested bullet
 * outdents or merges, and about what a partly bold selection does when Bold is pressed. Agreeing
 * means one of them being a transcription of the other rather than an independent design, because
 * two independent designs disagree at exactly the edges a researcher notices: they reformat a
 * paragraph on the phone, reopen the record in a browser, and the browser re-serialises it
 * differently, so the next person to open that record sees a change nobody made.
 *
 * A NOTE ON THE VOCABULARY BELOW, BEFORE SOMEBODY "CORRECTS" IT. The comments talk about designers,
 * stages, workshop reports and .docx writers. Those are the SIBLING'S screens and artifacts, and
 * they are kept verbatim because each one is the evidence for a rule — "an off-by-one here loses a
 * paragraph of somebody's report" is why the ladder is written the way it is, and paraphrasing it
 * into this application's nouns would leave the rule with no reason attached. Nothing in this file
 * imports any of them; where a rule needed restating for THIS application it has been restated
 * explicitly and says so. On this side the person is a researcher, the screen is a record form, and
 * the "renderer" that must agree with this file is `frontend/lib/richText.ts`.
 *
 * KEPT WHOLE, INCLUDING THE VERBS THIS APPLICATION'S EDITOR DOES NOT CALL. [insertImage],
 * [setImageWidth], [findMatches] and [replaceAll] have no button on a record form. They stay because
 * they are what make a document written in the browser survive being opened and re-saved here: the
 * ops that MAINTAIN a table or a photograph are the same ops that stop a phone edit from quietly
 * dropping one. Deleting the unreached ones would be the change that turns "the phone cannot insert
 * a picture" into "the phone destroys pictures".
 *
 * WHY THIS FILE HAS NO COMPOSE IMPORTS AND MUST NEVER ACQUIRE ANY. `RichTextEditor.kt` owns the
 * `BasicTextField`s, the focus, the IME and the toolbar; it owns nothing about what a document IS.
 * Everything here is ordinary Kotlin over immutable data, so "what does Backspace do at offset 0 of
 * an indented ordered item" is answerable by reading a function instead of by driving an emulator,
 * and [richTextOpsSelfCheck] can exercise every ladder on a plain JVM. The alternative — letting the
 * text field edit and reading the answer back out of it — is how the web version of this feature
 * used to store `<b><i>x</b></i>`, and it is why the model layer exists at all.
 *
 * TWO INVARIANTS EVERY FUNCTION HERE MAINTAINS, because breaking either produces a document that
 * survives a round trip through this file but not through the server's:
 *
 * 1. **A span's marks are deduplicated, and serialised in alphabetical order.** The Kotlin model
 *    holds them in a `Set`, so equality is already order-independent and `toJson` sorts on the way
 *    out — but every set this file builds is built in alphabetical order anyway, so that a future
 *    writer that forgets to sort still emits what Python's `sorted(...)` emits. A field whose stored
 *    JSON differs only in mark order reads as a change to every diff and to every conflict resolver.
 * 2. **No span is empty and no two adjacent spans carry the same marks.** Typing one character at a
 *    time otherwise produces one span per keystroke: a paragraph written in the morning reaches the
 *    .docx writer as four hundred runs, which Word opens slowly, and the stored JSON becomes
 *    unreadable in a database console on the day something has gone wrong. [mergeSpans] runs after
 *    every span-level edit, without exception.
 *
 * Offsets are UTF-16 code units into a BLOCK'S TEXT — not into a span, and not into a text field's
 * transformed string. A span boundary moves every time a mark is applied and a text field is rebuilt
 * wholesale on every structural command; a block index plus a text offset survives both, which is
 * what lets a command run, re-render the surface, and put the caret back where the designer left it.
 * UTF-16 is also what `String.length` and `AnnotatedString` count in, so no conversion is needed at
 * the Compose boundary. (The model's parsing budget counts CODEPOINTS instead, because that is what
 * Python counts; the two never meet, because parsing happens before editing starts.)
 */

// --------------------------------------------------------------------------------------
// Selection
// --------------------------------------------------------------------------------------

/** A caret position: which block, and how many UTF-16 code units into that block's text. */
data class DocPoint(val block: Int, val offset: Int)

/** An anchor/focus pair, in the order the user made it — [anchor] may follow [focus]. */
data class DocRange(val anchor: DocPoint, val focus: DocPoint)

/** What every command returns: the new document, and where the caret ends up in it. */
data class EditResult(val doc: RichDoc, val selection: DocRange)

fun DocRange.isCollapsed(): Boolean = anchor == focus

fun collapsedAt(point: DocPoint): DocRange = DocRange(point, point)

/** Negative when [a] precedes [b], as `compareTo` does. */
fun comparePoints(a: DocPoint, b: DocPoint): Int =
    if (a.block != b.block) a.block - b.block else a.offset - b.offset

/**
 * A point that is guaranteed to exist in [doc].
 *
 * Every command clamps its result through this. An off-the-end point is not a cosmetic problem: the
 * Compose layer maps a model point to a `TextRange` inside a real text field, and a point naming
 * block 7 of a five-block document is an `IndexOutOfBoundsException` on the composition thread —
 * which does not look to a designer like a bad caret, it looks like the app closing while they type.
 */
fun clampPoint(doc: RichDoc, point: DocPoint): DocPoint {
    if (doc.blocks.isEmpty()) return DocPoint(0, 0)
    val block = point.block.coerceIn(0, doc.blocks.size - 1)
    return DocPoint(block, point.offset.coerceIn(0, doc.blocks[block].text.length))
}

/**
 * Both ends clamped into [doc], with the user's drag DIRECTION preserved.
 *
 * Deliberately not the same thing as [normaliseRange]. A selection dragged upwards has its anchor
 * after its focus, and that is not a detail to tidy away: pressing Bold on a backwards selection and
 * getting a forwards one back means the next Shift+Left the designer types extends the wrong end of
 * their own selection. Commands that merely need the ends in document order call [normaliseRange]
 * locally and return this instead.
 */
private fun clampRange(doc: RichDoc, range: DocRange): DocRange =
    DocRange(clampPoint(doc, range.anchor), clampPoint(doc, range.focus))

/**
 * The range in document order and clamped into [doc]: `anchor` is the start, `focus` is the end.
 *
 * This is the form every loop over "the blocks the selection touches" wants. It is also what the
 * Compose layer should hand to anything that highlights, because a backwards range asked to paint
 * from anchor to focus paints a negative width and Compose draws nothing at all.
 */
fun normaliseRange(doc: RichDoc, range: DocRange): DocRange {
    val clamped = clampRange(doc, range)
    return if (comparePoints(clamped.anchor, clamped.focus) <= 0) clamped
    else DocRange(clamped.focus, clamped.anchor)
}

/** The indices of every block the range touches, inclusive at both ends. */
fun blocksInRange(doc: RichDoc, range: DocRange): List<Int> {
    if (doc.blocks.isEmpty()) return emptyList()
    val ordered = normaliseRange(doc, range)
    return (ordered.anchor.block..ordered.focus.block).toList()
}

// --------------------------------------------------------------------------------------
// Span algebra — private, because every one of these names is a name the Compose layer
// might reasonably want for a local helper of its own, and two top-level declarations with
// the same signature in the same package do not compile.
// --------------------------------------------------------------------------------------

private fun blockLength(block: RichBlock): Int = block.text.length

/**
 * A mark set in alphabetical order, deduplicated — invariant 1.
 *
 * `LinkedHashSet` rather than the enum's own ordering, because [Mark] is declared BOLD, ITALIC,
 * UNDERLINE, STRIKETHROUGH, CODE and alphabetical is BOLD, CODE, ITALIC, STRIKETHROUGH, UNDERLINE.
 * Set equality does not care, and `toJson` sorts by name on the way out, so nothing downstream
 * depends on this today — it is here so that nothing downstream ever has to.
 */
private fun sortMarks(marks: Collection<Mark>): Set<Mark> {
    if (marks.isEmpty()) return emptySet()
    if (marks.size == 1) return setOf(marks.first())
    return marks.distinct().sortedBy { it.name }.toCollection(LinkedHashSet())
}

private fun makeSpan(text: String, marks: Collection<Mark> = emptySet()): RichSpan =
    RichSpan(text = text, marks = sortMarks(marks))

/**
 * Drop empty spans and fuse adjacent ones carrying identical marks — invariant 2.
 *
 * Run after EVERY span-level edit. Without it a paragraph accumulates one span per keystroke, and
 * `runsFor` in `report/RichText.kt` turns each of those into at least one `Run`: a 900-word
 * narrative becomes several thousand runs in `word/document.xml`, which Word opens slowly, and the
 * stored JSON grows by an order of magnitude for text that renders identically.
 */
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

/** The slice of a block's spans covering `[start, end)` of its text. */
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

/** The block with new spans, merged — its kind, alignment and level are untouched. */
private fun withSpans(block: RichBlock, spans: List<RichSpan>): RichBlock =
    block.copy(spans = mergeSpans(spans))

private fun replaceBlock(doc: RichDoc, index: Int, next: RichBlock): RichDoc {
    val blocks = doc.blocks.toMutableList()
    blocks[index] = next
    return RichDoc(blocks)
}

/**
 * `Array.prototype.splice`, because the port's structural commands are written in terms of it.
 *
 * Transcribing a splice into `removeAt` loops by hand is where an off-by-one gets in, and an
 * off-by-one here does not throw — it duplicates or loses a paragraph of somebody's report. The JS
 * clamping rules are reproduced exactly: a delete count larger than what remains removes what
 * remains, and inserting at the end appends.
 */
private fun MutableList<RichBlock>.splice(index: Int, remove: Int, vararg inserted: RichBlock) {
    val at = index.coerceIn(0, size)
    repeat(minOf(remove, size - at)) { removeAt(at) }
    addAll(at, inserted.toList())
}

/**
 * The level a block of [kind] may legally carry — headings 1..4, list items 0..3, nothing else.
 *
 * A level on a paragraph is not harmless: both report writers index a four-element array of heading
 * sizes by `level - 1`, and a stray level that survived into a HEADING is the difference between a
 * 20pt title and an `ArrayIndexOutOfBoundsException` inside the .docx writer.
 */
private fun clampLevel(kind: BlockKind, raw: Int): Int = when {
    // A heading with no level is a level 1, not a level 0 no renderer has a style for.
    kind == BlockKind.HEADING -> (if (raw == 0) 1 else raw).coerceIn(1, MAX_HEADING_LEVEL)
    kind.isListItem -> raw.coerceIn(0, MAX_LIST_DEPTH)
    else -> 0
}

// --------------------------------------------------------------------------------------
// Marks as strings — the boundary the Compose layer speaks
// --------------------------------------------------------------------------------------

private val MARK_BY_NAME: Map<String, Mark> = Mark.entries.associateBy { it.name }
private val KIND_BY_NAME: Map<String, BlockKind> = BlockKind.entries.associateBy { it.name }
private val ALIGN_BY_NAME: Map<String, Align> = Align.entries.associateBy { it.name }

/**
 * Unknown tokens are dropped in silence, exactly as the model's `coerceMarks` drops them.
 *
 * The toolbar hands these functions string names rather than enum members so that the Compose layer
 * can hold a pending-mark set without importing the report package's model. An unrecognised name is
 * therefore a caller bug, not attacker input — but dropping it loses a mark, while throwing loses
 * the keystroke, and one of those two is recoverable by pressing the button again.
 */
private fun marksOf(names: Collection<String>?): Set<Mark> =
    if (names == null) emptySet() else sortMarks(names.mapNotNull { MARK_BY_NAME[it.uppercase()] })

private fun markNames(marks: Collection<Mark>): List<String> = marks.map { it.name }.sorted()

// --------------------------------------------------------------------------------------
// Emptiness, by JavaScript's rule rather than Kotlin's
// --------------------------------------------------------------------------------------

/**
 * Is [c] whitespace to `String.prototype.trim`?
 *
 * `Char.isWhitespace()` and JavaScript's `\s` disagree about exactly two characters that occur in
 * real prose, and both of them arrive by the same route — a paste out of Word. U+00A0 NO-BREAK SPACE
 * is what Word puts between a number and its unit, and U+FEFF is the byte-order mark a .txt file
 * pasted through the clipboard carries at its head. JavaScript trims both; `Char.isWhitespace` trims
 * neither. A field holding only one of them is therefore EMPTY on the web — stored as `null`, shown
 * as an unfilled required field — and NON-EMPTY on the phone, so the completeness gate reads 100% on
 * one surface and 98% on the other for the same workshop, and the field ping-pongs between `null`
 * and a document every time it is saved from the other client.
 */
private fun isJsSpace(c: Char): Boolean = when (c.code) {
    // Written as code points rather than as character literals so that the set is readable and so
    // that U+2028 and U+2029 — which a Kotlin source file treats as line terminators — can be named
    // here at all.
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20 -> true   // tab, LF, VT, FF, CR, space
    0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
    // U+2000..U+200A are the typographic spaces, EN QUAD through HAIR SPACE, which arrive in text
    // pasted out of a page-layout program and which JavaScript covers as one range.
    else -> c.code in 0x2000..0x200A
}

/** `String.prototype.trim`, character for character. See [isJsSpace] for why not `trim()`. */
fun jsTrim(text: String): String {
    var start = 0
    var end = text.length
    while (start < end && isJsSpace(text[start])) start += 1
    while (end > start && isJsSpace(text[end - 1])) end -= 1
    return text.substring(start, end)
}

fun isJsBlank(text: String): Boolean = text.all { isJsSpace(it) }

/**
 * Whether the document holds no CONTENT — the rule the server's `_is_filled` applies.
 *
 * A document of empty paragraphs is empty. An editor that was focused and left alone still holds
 * `{"blocks":[{"kind":"PARAGRAPH","spans":[]}]}`, and counting that as a filled required field would
 * let a designer submit a report with a blank introduction while the completeness gate read 100%.
 * The Compose layer must emit `null` — not `{"blocks":[]}` — for a document this returns true for.
 *
 * ── AN IMAGE IS JUDGED ON ITS MEDIA ID, NEVER ON ITS TEXT. DO NOT "SIMPLIFY" THIS BACK ──────────
 *
 * This function used to read `doc.blocks.all { isJsBlank(it.text) }`, and that one missing arm cost
 * the handset the whole inline-photograph feature, silently and behind a success message. An IMAGE
 * block's [RichBlock.text] is its CAPTION (the concatenation of its spans), so a photograph nobody
 * captioned has `text == ""` and the old predicate called the document empty. Two consequences,
 * both of which a designer walks into on an ordinary path:
 *
 *  * **The photograph was never written.** A blank field's last emitted signature is "EMPTY"; the
 *    designer taps Photograph, `insertImage` splices an IMAGE block with `spans = emptyList()`, the
 *    new document also signs as "EMPTY", `emit` sees no change and never calls `onChange`. The
 *    bytes were imported and a descriptor registered, the toast said "Photograph placed", and the
 *    field value stayed null — so nothing in the report referenced the picture and it printed on no
 *    page. See [emittedValue] and `RichTextEditor.emit`.
 *  * **Clearing a caption deleted the picture.** A field whose only block is a captioned IMAGE went
 *    from non-empty to "EMPTY" the moment the caption's last character was deleted, so the editor
 *    published `onChange(null)` — which `StageScreen`'s `put` turns into `this - key`, removing the
 *    field from the payload and dropping the stored document at the next save.
 *
 * The two predicates really did disagree about the same object, measured rather than reasoned: run
 * against the release classes built before this fix, a `RichDoc` holding one `RichBlock(kind=IMAGE,
 * media="media-42")` and nothing else reported `block.text == ""`, `block.isEmpty == false` and
 * `isEmptyDoc(doc) == true`.
 *
 * The IMAGE arm is delegated to [RichBlock.isEmpty] (report/RichText.kt) rather than restated as
 * `it.media.isEmpty()`, because that model property is the definition the completeness gate, the
 * server's `RichBlock.is_empty` and `frontend/lib/richText.ts` all already use — writing the rule
 * out a second time here is precisely how the two definitions drifted apart in the first place.
 *
 * The TEXT arm stays [isJsBlank] and must not be folded into `doc.isEmpty`, tempting as that reads:
 * `RichBlock.isEmpty` uses Kotlin's `String.isBlank`, which is `Character.isWhitespace ||
 * isSpaceChar` and therefore does NOT cover U+FEFF (category Cf, neither of those) — while
 * `String.prototype.trim`, which the browser and the server apply, does. Measured on this tree:
 * `Char(0xFEFF).toString().isBlank()` is false where `isJsBlank` of it is true, so swapping the arm
 * would make a field holding one pasted byte-order mark count as filled on the handset and blank in
 * the browser — and would break the U+00A0/U+FEFF cases already asserted in [richTextOpsSelfCheck]
 * and in `RichTextEmitContractTest`.
 */
fun isEmptyDoc(doc: RichDoc): Boolean =
    doc.blocks.all { if (it.kind == BlockKind.IMAGE) it.isEmpty else isJsBlank(it.text) }

/**
 * What the editor publishes for [doc] — `null` for an empty document, its stored JSON otherwise.
 *
 * ONE DEFINITION, USED FOR BOTH DECISIONS THE EDITOR MAKES. `RichTextEditor.emit` has to answer two
 * questions about every edit: "is this different from what I last published" (via [docSignature])
 * and "what do I hand to `onChange`". They used to be two expressions over [isEmptyDoc] written a
 * few lines apart, which is survivable — until the predicate itself is wrong, at which point the
 * two answers disagree in the worst possible direction: the signature says "nothing changed, don't
 * publish" for the very same document the value says is `null`, so a field can only ever LOSE
 * content through that pair, never gain it. Deriving the signature from this function keeps them
 * one decision.
 *
 * It lives here, beside [isEmptyDoc], and not in `RichTextEditor.kt`, for the reason the file
 * header gives: this is a fact about what a document IS, it needs no Compose, and a rule that
 * decides whether a designer's photograph is written must be assertable on a plain JVM.
 */
fun emittedValue(doc: RichDoc): JsonObject? = if (isEmptyDoc(doc)) null else toJson(doc)

/**
 * The canonical form under which two stored values are the same document — the editor's change
 * detector, exposed here so it can be exercised without an emulator.
 *
 * `null`, an absent value, an empty object and a document of empty paragraphs are four spellings of
 * "nothing has been written here", and they all sign as [EMPTY_DOC_SIGNATURE]. That word is not a
 * document any [toJson] can produce (it is not JSON at all), so it can never collide with one.
 */
fun docSignature(doc: RichDoc): String = emittedValue(doc)?.toString() ?: EMPTY_DOC_SIGNATURE

/** The signature every empty document shares. See [docSignature]. */
const val EMPTY_DOC_SIGNATURE = "EMPTY"

/**
 * A document with one empty paragraph in it: the smallest document that can be typed into.
 *
 * Never `RichDoc()` with no blocks. The editor draws one text field per block, so a document with no
 * blocks draws no fields, and a field the designer cannot put a caret into is indistinguishable from
 * a broken screen. This serialises — via [isEmptyDoc] and the caller's `null` — to exactly what the
 * server stores for an unfilled rich-text field, so opening a stage and leaving it alone does not
 * mark anything as answered.
 */
fun emptyDoc(): RichDoc = RichDoc(blocks = listOf(RichBlock()))

// --------------------------------------------------------------------------------------
// What the caret is standing in
// --------------------------------------------------------------------------------------

/**
 * The marks a character typed at [offset] should inherit, in alphabetical order.
 *
 * Reads the character BEFORE the caret, falling back to the one after it at the very start of a
 * block. That is the behaviour every word processor has, and it is not arbitrary: typing at the end
 * of a bold word continues bold, while typing at the start of a line does not silently pick up the
 * formatting of whatever happens to follow the caret. Getting it backwards produces the single most
 * irritating bug an editor can have — a designer un-bolds a heading, types, and the new text comes
 * out bold anyway, with no control showing as active to explain why.
 */
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

/**
 * Is [mark] on EVERY character of the selection?
 *
 * Every, not any. A toolbar that lights Bold because one word of a paragraph is bold tells the
 * designer they can un-bold the paragraph with one press, and the press bolds the rest of it
 * instead. The "all of it" reading is also what makes the toggle its own inverse, which is the
 * property that lets somebody undo a mistake by pressing the same button again.
 *
 * [pending] is the Compose layer's uncommitted mark set — what Ctrl+B before typing sets. It is
 * consulted only for a collapsed caret, and `null` (no pending set) is meaningfully different from
 * an empty one: null means "read the document", empty means "the designer has explicitly turned
 * everything off for the next character".
 */
fun isMarkActive(doc: RichDoc, range: DocRange, mark: String, pending: List<String>?): Boolean {
    val parsed = MARK_BY_NAME[mark.uppercase()] ?: return false
    return isMarkActiveIn(doc, range, parsed, pending?.let { marksOf(it) })
}

private fun isMarkActiveIn(doc: RichDoc, range: DocRange, mark: Mark, pending: Set<Mark>?): Boolean {
    // An empty document has no characters to carry a mark, so only a pending set can answer. The web
    // indexes `doc.blocks[0]` here and throws on a document with no blocks; the phone must not, and
    // the state is reachable — it is what the editor holds for the instant between a Select All and
    // the Backspace that follows it.
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
    // A selection that covers no text at all — three empty paragraphs, say — is not "all bold".
    return sawText
}

// --------------------------------------------------------------------------------------
// Formatting commands
// --------------------------------------------------------------------------------------

/**
 * Add or remove a mark across the selection.
 *
 * A COLLAPSED selection cannot change any text, so this returns the document untouched and the
 * caller keeps the result as a PENDING mark that the next inserted character will carry. That is
 * what makes pressing Bold and then typing work the way it does in every other editor, and it is
 * why [isMarkActive] and [toolbarState] take a pending set: the toolbar has to show Bold as on while
 * there is not one bold character in the document yet.
 *
 * An unrecognised mark name leaves the document alone rather than throwing. A crash in a toolbar
 * handler takes the whole stage down and loses every unsaved field on it; a button that does nothing
 * loses one press.
 */
fun toggleMark(doc: RichDoc, range: DocRange, mark: String): EditResult {
    val parsed = MARK_BY_NAME[mark.uppercase()] ?: return EditResult(doc, range)
    if (range.isCollapsed()) return EditResult(doc, range)
    return applyMarkIn(doc, range, parsed, !isMarkActiveIn(doc, range, parsed, null))
}

/** [toggleMark] with the direction chosen by the caller, for a select-all-then-clear-bold path. */
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
    // The selection is returned with the drag direction it arrived with, not in document order.
    return EditResult(RichDoc(blocks), clamped)
}

/**
 * Remove every mark from the selection AND return its blocks to a left-aligned paragraph.
 *
 * Both halves, deliberately. "Clear formatting" is pressed by somebody looking at a paragraph that
 * came out of a paste or an experiment and wanting it to look like the rest of the document; a
 * control that stripped the bold but left the text centred, indented and still a heading would send
 * them hunting through four more buttons for the rest of it. The list nesting goes for the same
 * reason. Note there is no `to <= from` guard here, unlike [applyMarkIn]: a block the selection
 * merely touches at its very edge still loses its kind, because the designer selected it.
 */
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
        // A TABLE and an IMAGE KEEP THEIR KIND. "Clear formatting" means "make this look like the
        // rest of the document", not "delete my grid" — and re-kinding either to PARAGRAPH does
        // delete it, because `toStored` writes `rows` and `media` only for the kind that owns them.
        // The marks still come off the caption, which is what the designer actually asked for.
        blocks[index] = if (block.kind.isStructural) {
            cleared
        } else {
            cleared.copy(kind = BlockKind.PARAGRAPH, align = Align.LEFT, level = 0)
        }
    }
    return EditResult(RichDoc(blocks), clamped)
}

/**
 * Make every block the selection touches a block of [kind].
 *
 * [level] is the heading level, and is ignored for every other kind. A list item KEEPS the depth it
 * had when it becomes another kind of list item, so switching a nested bullet run to numbers does
 * not flatten it; anything else resets to 0, because a heading at depth 2 has no meaning in this
 * model and no renderer has a style for it.
 */
fun setBlockKind(doc: RichDoc, range: DocRange, kind: String, level: Int = 0): EditResult {
    val parsed = KIND_BY_NAME[kind.uppercase()] ?: return EditResult(doc, clampRange(doc, range))
    // PROSE CANNOT BE RE-KINDED INTO A STRUCTURAL BLOCK EITHER — the guard below the loop protects
    // the SOURCE block, this one protects the TARGET kind.
    //
    // A DELIBERATE DIVERGENCE FROM THE WEB, and it is written down as one because the rule in this
    // repo is that the TypeScript wins. `RichBlockKind` (richText.ts:84) is the seven-member union
    // INCLUDING "TABLE" and "IMAGE", so `setBlockKind(doc, range, "TABLE")` type-checks there too:
    // the web has the identical hole and is saved from it only by the same thing that saves this
    // port — no control passes either token. What the two clients would do if one ever did is
    // re-kind a bulleted item to TABLE and produce a TABLE with no `rows`, which `toJson` writes as
    // an empty grid and `fromJson` then DROPS on the next read, because a table with nothing in it
    // is not a table. The designer's sentence is on screen until they reopen the stage and then
    // simply is not there.
    //
    // Closing it here rather than upstream is safe precisely BECAUSE no caller passes those tokens:
    // the refusal is observable to nothing that exists. It is worth stating because this signature
    // takes a STRING — the toolbar reports a press as one, see `RichTextToolbar` — and `KIND_BY_NAME`
    // has resolved both tokens since the constants were added to stop the phone destroying a
    // photograph. Whoever finds this divergence should fix `setBlockKind` in the TypeScript rather
    // than delete the guard here.
    if (parsed.isStructural) return EditResult(doc, clampRange(doc, range))
    return setBlockKindIn(doc, range, parsed, level)
}

private fun setBlockKindIn(doc: RichDoc, range: DocRange, kind: BlockKind, level: Int): EditResult {
    val clamped = clampRange(doc, range)
    val blocks = doc.blocks.toMutableList()
    for (index in blocksInRange(doc, clamped)) {
        val block = blocks[index]
        // The same protection as [clearFormatting]: a selection running from a paragraph THROUGH a
        // table or a photograph and back must restyle the prose and leave the structural kinds
        // alone. Their own controls are how they are removed, and a heading button that silently
        // discarded a grid would be a very expensive misclick.
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

/**
 * Turn the selection into a list of that kind — or, if it already is one, back into paragraphs.
 *
 * The "already one" test is ALL of the touched blocks, matching how [isMarkActive] reads a mark: a
 * mixed selection becomes a list, and only a selection that is entirely a bulleted list is turned
 * back into prose by the bullet button. Any other rule makes the button's second press unpredictable.
 */
fun toggleList(doc: RichDoc, range: DocRange, ordered: Boolean): EditResult {
    val kind = if (ordered) BlockKind.ORDERED_ITEM else BlockKind.BULLET_ITEM
    val indices = blocksInRange(doc, range)
    val allAlready = indices.isNotEmpty() && indices.all { doc.blocks[it].kind == kind }
    return setBlockKindIn(doc, range, if (allAlready) BlockKind.PARAGRAPH else kind, 0)
}

/**
 * One step of list nesting, in or out.
 *
 * Only list items move. A paragraph has no level in this model — there is no indented-paragraph
 * block kind — so indenting one would either do nothing or invent a kind the .docx writer cannot
 * print. Doing nothing, visibly, with the toolbar control disabled by [canShiftIndent], is the
 * honest option. The document is returned UNCHANGED by identity when nothing moved, so a Tab press
 * on a paragraph does not mark the stage dirty and does not trigger a save.
 */
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

/** ANY of the touched blocks can move — unlike every other query here, which is all-of. */
fun canShiftIndent(doc: RichDoc, range: DocRange, delta: Int): Boolean =
    blocksInRange(doc, range).any { index ->
        val block = doc.blocks[index]
        block.kind.isListItem && (block.level + delta) in 0..MAX_LIST_DEPTH
    }

// --------------------------------------------------------------------------------------
// Structural commands
// --------------------------------------------------------------------------------------

/** Delete everything between the two ends of the range, merging the blocks they sit in. */
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

    // The surviving block is the one the selection STARTED in, and it keeps its kind, alignment and
    // level. Selecting from the middle of a heading into the middle of the paragraph below and
    // pressing Delete leaves a heading, which is what a designer means by it; keeping the END
    // block's kind instead would silently demote the heading, and the demotion would not be visible
    // until the report was generated with a section title in body text.
    val merged = withSpans(
        startBlock,
        sliceSpans(startBlock.spans, 0, start.offset) + sliceSpans(endBlock.spans, end.offset, blockLength(endBlock)),
    )
    val blocks = doc.blocks.toMutableList()
    blocks.splice(start.block, end.block - start.block + 1, merged)
    return EditResult(RichDoc(blocks), collapsedAt(start))
}

private val NEWLINE_RUN = Regex("[\\r\\n]+")

/**
 * Insert a run of text at the selection, carrying [marks].
 *
 * Any newline in [text] is flattened to a space rather than split into blocks. Callers that mean
 * "several paragraphs" call [insertDoc]; this is what a keystroke and a find-and-replace use, and a
 * newline reaching a span is the one thing the model's parser has to repair on the way back in — a
 * span holding one would make a single block render as two paragraphs in the .docx and as one line
 * with a stray gap in the PDF, which is two renderers disagreeing about the same document.
 */
fun insertText(doc: RichDoc, range: DocRange, text: String, marks: List<String> = emptyList()): EditResult {
    val cleaned = NEWLINE_RUN.replace(cleanText(text), " ")
    val afterDelete = deleteRange(doc, range)
    if (cleaned.isEmpty()) return afterDelete
    val point = afterDelete.selection.focus
    val blocks = afterDelete.doc.blocks.toMutableList()
    // Typing into a document with no blocks has to produce a document with one, not an exception.
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

/**
 * Insert a whole document at the selection — the paste path.
 *
 * The first incoming block joins the TAIL of the block the caret is in and the last one joins its
 * HEAD, which is what makes pasting a sentence into the middle of a paragraph produce one paragraph
 * rather than three. A single-block paste therefore never changes the target block's kind: pasting a
 * line into a bulleted item leaves it a bulleted item, which is what somebody assembling a list out
 * of an email expects, and the alternative — the paste's own kind winning — turns their list into
 * loose paragraphs one item at a time.
 */
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
    // The block cap is applied to the RESULT, and the caret is then clamped into what survived. A
    // paste of three thousand lines into the last block of a full document otherwise leaves the
    // caret naming a block that the cap removed, and the next keystroke indexes past the end.
    val next = RichDoc(blocks.take(MAX_BLOCKS))
    val caret = DocPoint(point.block + 1 + middle.size, blockLength(last))
    return EditResult(next, collapsedAt(clampPoint(next, caret)))
}

// --------------------------------------------------------------------------------------
// Inline photographs
//
// The block's [RichBlock.media] is the picture and its spans are the CAPTION, which is why these
// three are the only image-specific commands there are: everything else a designer does to a caption
// — typing in it, bolding a word of it, moving the caret through it — is the ordinary prose path,
// because a caption IS prose and `RichBlock.text` returns it.
//
// The other half of the feature is the commands that must not DESTROY one, and those are not here
// because they are not image commands: see the IMAGE rungs of [deleteBackward] and [splitBlock], the
// `isStructural` guards in [clearFormatting] and [setBlockKind], and the two tests that pin them.
// --------------------------------------------------------------------------------------

/**
 * Place a photograph at the caret, and leave the caret in its caption — the port of `insertImage` in
 * `frontend/lib/richText.ts`.
 *
 * AFTER the caret's block rather than splitting it. A photograph dropped into the middle of a
 * sentence would cut the sentence in two, and nobody who presses "Photograph" means that.
 *
 * NO TRAILING PARAGRAPH, which is the one place this differs from how a table would be inserted. A
 * table needs one because the model's caret cannot address a cell, so no keystroke inside a table can
 * ever create the block after it. An image block's own text IS its caption and IS addressable, so
 * Enter in the caption opens a paragraph below (see [splitBlock]) — and inserting a blank one here
 * instead would leave a stray empty line under every photograph a designer chose not to caption.
 *
 * A BLANK ID IS REFUSED RATHER THAN INSERTED. Both parsers drop an IMAGE block with no id, so a
 * placeholder would vanish at the next reload and the designer would blame the upload for a picture
 * that was never referenced. The caller finds out here, while it can still say so.
 */
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
    // An empty document has no block to insert AFTER, and the web's own answer here is a caret
    // naming block 1 of a one-block document — harmless in a DOM and an IndexOutOfBoundsException
    // when Compose maps it onto a real text field. One empty paragraph is added so the figure has
    // somewhere to be second to, which is also what `forEditing` would have produced anyway.
    if (blocks.isEmpty()) blocks.add(RichBlock())
    val point = clampPoint(RichDoc(blocks), normaliseRange(doc, range).anchor)

    // A newline in a caption is the one thing the model's parser has to repair: a span holding one
    // makes a single block print as two paragraphs in the .docx and as one gapped line in the PDF.
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

/**
 * Widen or narrow the photograph at [block] by one step, clamped to the model's own bounds — the
 * port of `setImageWidth`.
 *
 * A STEP RATHER THAN A NUMBER, because this is the only size control there is and a box asking a
 * designer for a percentage of a text column they cannot see is a question nobody can answer. The
 * clamp is the parser's own: a client that stored 120 would show one width on screen and print
 * another, because `fromJson` would pull it back to 100 on the next read.
 */
fun setImageWidth(doc: RichDoc, block: Int, delta: Float): EditResult {
    val target = doc.blocks.getOrNull(block)
    val selection = collapsedAt(clampPoint(doc, DocPoint(block, target?.text?.length ?: 0)))
    if (target == null || target.kind != BlockKind.IMAGE) return EditResult(doc, selection)
    // `Math.round` on a Float is floor(x + 0.5), which is what JavaScript's `Math.round` does for
    // the halves too — the two clients must not disagree about 65.5.
    val widthPct = Math.round(target.widthPct + delta).toFloat()
        .coerceIn(IMAGE_MIN_WIDTH_PCT, IMAGE_MAX_WIDTH_PCT)
    if (widthPct == target.widthPct) return EditResult(doc, selection)
    return EditResult(replaceBlock(doc, block, target.copy(widthPct = widthPct)), selection)
}

/**
 * Remove the photograph at [block], its caption with it. The caret lands at the end of the block
 * before — the port of `removeImage` in `frontend/lib/richText.ts`.
 *
 * BOTH HALVES GO, AND THAT IS THE POINT. The alternative — dropping the picture and leaving the
 * caption — is what the demotion ladder in [deleteBackward] used to do, and it reads to a designer
 * as a photograph that failed to load rather than one they deleted: a line of italic text sitting
 * under nothing, in a document that has already been approved.
 *
 * A document with nothing left in it becomes one empty paragraph, never zero blocks: a field with
 * no block has nowhere to put a caret, and the next keystroke would index past the end.
 */
fun removeImage(doc: RichDoc, block: Int): EditResult {
    val target = doc.blocks.getOrNull(block)
    // CLAMPED even on the no-op path, unlike the web's, which returns the raw index. The DOM
    // tolerates a caret naming a block that is not there; Compose maps a model point to a
    // `TextRange` inside a real field, and an off-the-end point is an IndexOutOfBoundsException on
    // the composition thread — which does not look like a bad caret, it looks like the app closing.
    if (target == null || target.kind != BlockKind.IMAGE) {
        return EditResult(doc, collapsedAt(clampPoint(doc, DocPoint(block, 0))))
    }
    val remaining = doc.blocks.filterIndexed { index, _ -> index != block }
    val next = RichDoc(remaining.ifEmpty { listOf(RichBlock()) })
    val landing = (block - 1).coerceIn(0, next.blocks.size - 1)
    return EditResult(next, collapsedAt(DocPoint(landing, blockLength(next.blocks[landing]))))
}

/**
 * Enter.
 *
 * Four special cases, each of which is a thing every designer expects and no editing surface agrees
 * about on its own:
 *
 * - **Enter in an EMPTY list item or quote leaves it** rather than adding another empty one. Without
 *   this the only way out of a list is to reach for the toolbar, and the two presses of Enter that
 *   everybody types at the end of a list produce two empty bullets in the generated report.
 * - **Enter at the START of a non-empty heading opens a paragraph ABOVE it** and leaves the heading
 *   alone. Splitting a heading at offset 0 the ordinary way would leave an empty heading above the
 *   real one, which is the opposite of what somebody making room above a title is asking for.
 * - **Enter anywhere else in a heading starts a PARAGRAPH.** Nobody writes two consecutive headings
 *   by pressing Enter; they write a heading and then the text underneath it.
 * - **Enter in a CAPTION opens a paragraph UNDER the figure** rather than splitting it. The generic
 *   split would build the continuation block with `RichBlock(kind = IMAGE, …)` and no `media`, so
 *   the tail of the caption became an IMAGE block carrying an empty id — which `fromJson` and the
 *   server's `from_json` both DROP on the next read. A designer who pressed Enter halfway through a
 *   caption watched the second half of it survive until they reopened the stage. Splitting it the
 *   other obvious way, by copying `media` across, is worse: it prints the same photograph twice
 *   from one press of Enter, and there is no reading of "Enter in a caption" that means either.
 */
fun splitBlock(doc: RichDoc, range: DocRange): EditResult {
    val afterDelete = deleteRange(doc, range)
    val point = afterDelete.selection.focus
    val blocks = afterDelete.doc.blocks.toMutableList()
    if (blocks.isEmpty()) blocks.add(RichBlock())
    val block = blocks[point.block]
    val length = blockLength(block)

    // Checked FIRST, matching the web's order: a caption is prose, so it would otherwise reach the
    // list/quote and heading arms below and be split like any other run of text.
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
        // The new paragraph inherits the heading's ALIGNMENT only. A centred title with a paragraph
        // opening above it left-aligned looks like a layout bug to the person who typed it.
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
        // `rightKind` can never be HEADING — the line above turns a split heading into a paragraph -
        // so the continuation block's level is a list nesting depth or nothing at all.
        level = if (rightKind.isListItem) block.level else 0,
    )
    blocks.splice(point.block, 1, left, right)
    val next = RichDoc(blocks.take(MAX_BLOCKS))
    return EditResult(next, collapsedAt(clampPoint(next, DocPoint(point.block + 1, 0))))
}

/**
 * One code point back from [offset], so a deletion never cuts a surrogate pair in half.
 *
 * Half an emoji is a LONE SURROGATE, and a lone surrogate makes `word/document.xml` not well-formed
 * — Word's response to which is to refuse the entire file rather than drop the character. The model
 * strips them on the way in and the report writers strip them on the way out; not creating one in
 * the first place is cheaper than both.
 *
 * This steps by CODE POINT, not by grapheme cluster, which is what the web does and is therefore
 * what the phone must do. It is a real limitation in Odia and Hindi — one Backspace removes one code
 * point of a conjunct rather than the whole cluster — but the two clients behaving differently would
 * be worse than both behaving the same imperfect way.
 */
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

/**
 * Backspace.
 *
 * At the start of a block it does NOT immediately merge with the block above. It walks the block's
 * own formatting back one rung at a time first, and the order of the four rungs is the whole point:
 *
 * 1. **Outdent a nested list item.** Depth 2 becomes depth 1 and nothing else changes.
 * 2. **Remove the whole figure, at the start of a CAPTION.** See below — this rung must come before
 *    the demotion, because the demotion is what destroys a photograph.
 * 3. **Do nothing at the start of a TABLE.**
 * 4. **Demote any other non-paragraph to a level-0 paragraph.** A top-level bullet, a heading and a
 *    quote all become plain paragraphs, keeping their text and their alignment.
 * 5. **Do nothing at block 0.** There is nothing above the first block to merge into, and a command
 *    that quietly deleted the block instead would lose the designer's first sentence.
 * 6. **Merge into the previous block**, which KEEPS its own kind, alignment and level — so
 *    backspacing a paragraph into the heading above it puts the text into the heading. Unless that
 *    block is a TABLE, which is the one thing prose cannot be merged into.
 *
 * That ladder is what makes Backspace the universal "undo the structure I just made" key: a designer
 * who presses Enter at the end of a bulleted list and gets another bullet backs out of it with one
 * keystroke, instead of eating the last word of the previous item.
 *
 * ── THE TWO RUNGS THAT ARE ABOUT NOT DESTROYING SOMETHING ─────────────────────────────────────
 *
 * Rungs 2 and 3 are not symmetry with the web for its own sake; each closes a way of losing a
 * designer's work that this ladder USED to have, and neither loss was visible on screen.
 *
 * A photograph placed in a narrative on the web arrives on the handset as an IMAGE block, and the
 * editor draws it as a figure whose caption is an ordinary text field. Backspace at the start of
 * that caption fell to the demotion rung: the block became a PARAGRAPH, and [toJson] writes `media`
 * ONLY for an IMAGE block — so the very next save omitted it and the photograph was gone from the
 * record on every surface, permanently, while the caption stayed behind looking like a stray line.
 * Removing the whole figure is what the designer meant, it is what the web does, and it is one undo.
 *
 * Rung 3 and the TABLE clause on rung 6 are the same defect from the two sides of a grid. Nothing
 * reads a TABLE block's `spans`, so prose merged into one disappears from the screen AND from the
 * file to a single press of Backspace; demoting the table to a paragraph loses `rows` the same way
 * `media` was lost. A table's caret cannot reach model offset 0 today — the phone has no table
 * editor — so rung 3 is unreachable, and it is written down anyway because the rung after it is the
 * one that would take the whole grid the day that changes.
 */
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
    // An IMAGE is safe to join into and a TABLE is not: an image block's spans ARE its caption, so
    // the text stays visible and stays in the file, while a table's spans are read by nothing.
    if (previous.kind == BlockKind.TABLE) return EditResult(doc, collapsedAt(point))
    val joinAt = blockLength(previous)
    val blocks = doc.blocks.toMutableList()
    blocks.splice(point.block - 1, 2, withSpans(previous, previous.spans + block.spans))
    return EditResult(RichDoc(blocks), collapsedAt(DocPoint(point.block - 1, joinAt)))
}

/**
 * Forward delete.
 *
 * Deliberately NOT the mirror image of [deleteBackward]: there is no formatting ladder here. Delete
 * at the end of a heading pulls the following paragraph up INTO the heading and the heading survives
 * — the caret has not moved, so the block the designer is standing in is the one that wins.
 *
 * THE ONE THING IT REFUSES is a merge in which either side is structural, and all four combinations
 * are refused rather than the obvious two. Pulling a TABLE up into a paragraph keeps the paragraph
 * and silently drops the grid; pulling a paragraph up into a caption keeps the caption and drops the
 * photograph, because the merge writes its result into the block the caret is in and [toJson]
 * carries `media` and `rows` only for the kind that owns them. Neither is what Delete at the end of
 * a line means to anybody, and the loss shows up only when the report is generated.
 */
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

// --------------------------------------------------------------------------------------
// Markdown-style input rules
// --------------------------------------------------------------------------------------

private class InputRule(val pattern: Regex, val kind: BlockKind, val level: (MatchResult) -> Int)

/**
 * The four prefixes that turn themselves into structure when the space is typed.
 *
 * Matched with `matchEntire` rather than with anchors, and that is not a style preference: Java's
 * `$` also matches BEFORE a final line terminator, so a pattern written the way the web writes it
 * would fire on text the web leaves alone. `matchEntire` is what JavaScript's `^...$` without the
 * multiline flag actually means.
 *
 * They fire only at the very start of a level-0 PARAGRAPH, never mid-line and never inside a block
 * that is already something else. That restriction is the difference between a convenience and a
 * trap: a designer typing "1. Cotton, 2. Silk" as prose in the middle of a sentence must not have
 * the sentence turn into a numbered list under them, and somebody typing "> " inside an existing
 * quote means the character, not another quote.
 */
private val INPUT_RULES = listOf(
    InputRule(Regex("(#{1,4}) "), BlockKind.HEADING) { it.groupValues[1].length },
    InputRule(Regex("[-*+] "), BlockKind.BULLET_ITEM) { 0 },
    InputRule(Regex("\\d{1,3}[.)] "), BlockKind.ORDERED_ITEM) { 0 },
    InputRule(Regex("> "), BlockKind.QUOTE) { 0 },
)

/**
 * Apply a markdown-style rule if the caret is sitting just after one of the prefixes above.
 *
 * Returns null when nothing matched, so the caller can leave the document and the text field exactly
 * as the keyboard left them. That matters more here than on the web: rebuilding a `TextFieldValue`
 * from the model cancels any IME composition in progress, so an editor that re-seeded its field
 * after every space bar would break every conjunct an Odia or Hindi keyboard is in the middle of
 * composing. Null means "I did nothing, do not touch the field".
 */
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

// --------------------------------------------------------------------------------------
// Paste — plain text in, structure inferred, markup NEVER interpreted
// --------------------------------------------------------------------------------------

/**
 * JavaScript's `\s`, spelled out.
 *
 * Java's own `\s` is the six ASCII whitespace characters and nothing else, so a bullet that Word
 * followed with a NO-BREAK SPACE — which is exactly what Word puts after a list marker — would be
 * recognised as a bullet by the browser and left as literal "* " text by the phone. The same paste
 * would then produce a bulleted list on one client and a paragraph beginning with an asterisk on the
 * other.
 */
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

/**
 * The first [limit] CODE POINTS of [text].
 *
 * `take(n)` cuts at a UTF-16 index, and a cut landing between the two halves of a surrogate pair
 * leaves a lone surrogate at the end of the string — the character the whole sanitising pipeline
 * exists to remove, reintroduced downstream of it. The model file does the same thing for the same
 * reason; its copy is private, which is the only reason there are two.
 */
private fun takeCodePoints(text: String, limit: Int): String {
    if (limit <= 0) return ""
    if (text.length <= limit) return text
    if (text.codePointCount(0, text.length) <= limit) return text
    return text.substring(0, text.offsetByCodePoints(0, limit))
}

/**
 * A pasted clipboard as a document.
 *
 * **[text] is the clipboard's PLAIN TEXT flavour, and that is deliberate to the point of being the
 * whole design.** A paste out of Word carries an HTML flavour that is several hundred kilobytes of
 * vendor-specific span markup wrapped around forty words, and every editor that has tried to
 * sanitise that markup has ended up with an allowlist that leaks. There is nothing to leak here
 * because the markup is never read: the words are taken, and the structure is inferred from the
 * characters Word itself puts in the plain-text flavour — a bullet character, "1.", a leading tab
 * for a nested item. What the designer loses is the source's bold and italic. What they cannot lose
 * is a script, a stylesheet, a font stack, a colour that is invisible in dark mode, or a class name
 * that means something to some other application.
 *
 * The indent is read from LEADING TABS rather than from leading spaces: spaces are how prose is
 * indented and tabs are how a list is, and treating four spaces as a nesting level turns an indented
 * quotation into a sub-bullet.
 */
fun docFromPastedText(text: String): RichDoc {
    val cleaned = takeCodePoints(cleanText(text), MAX_DOCUMENT_CHARS)
    if (cleaned.isEmpty()) return RichDoc()
    val blocks = ArrayList<RichBlock>()
    for (rawLine in cleaned.split("\n").take(MAX_BLOCKS)) {
        val tabs = rawLine.takeWhile { it == '\t' }.length
        var line = jsTrimEnd(rawLine.substring(tabs))
        if (isJsBlank(line)) {
            // A blank line between two paragraphs is the designer's own spacing and is kept as an
            // empty paragraph: the report writers drop it when they render, but dropping it HERE
            // would silently reflow the text a designer is looking at while they paste it.
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

// --------------------------------------------------------------------------------------
// Find and replace
// --------------------------------------------------------------------------------------

data class RichMatch(val block: Int, val start: Int, val end: Int)

data class ReplaceResult(val doc: RichDoc, val count: Int)

private val WORD_CHARACTER = Regex("[\\p{L}\\p{N}_]")

private fun isWordBoundary(text: String, index: Int): Boolean {
    if (index < 0 || index >= text.length) return true
    return !WORD_CHARACTER.matches(text[index].toString())
}

/**
 * Every occurrence of [query], in document order.
 *
 * Matching is per BLOCK and never across a block boundary, because the blocks are separate
 * paragraphs in the printed document: a phrase that happens to span the end of one paragraph and the
 * start of the next is not a phrase, and replacing it would silently join the two paragraphs.
 */
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
            // Advance by one so overlapping occurrences of a repeated string are all reported; the
            // caller replaces from the END backwards, so overlaps cannot corrupt earlier offsets.
            from = at + maxOf(1, query.length)
        }
    }
    return out
}

fun matchToRange(match: RichMatch): DocRange =
    DocRange(DocPoint(match.block, match.start), DocPoint(match.block, match.end))

/** Replace one match, keeping the marks of the text it replaced. */
fun replaceMatch(doc: RichDoc, match: RichMatch, replacement: String): EditResult {
    val block = doc.blocks.getOrNull(match.block) ?: return EditResult(doc, collapsedAt(DocPoint(0, 0)))
    val marks = marksAt(block, match.start + 1)
    return insertText(doc, matchToRange(match), replacement, marks)
}

/**
 * Replace every occurrence, working from the END of the document backwards.
 *
 * Backwards is not a stylistic choice: replacing forwards shifts every later offset by the
 * difference in length between the needle and the replacement, so the second replacement onwards
 * lands in the wrong place — subtly, invisibly, and worse the longer the document.
 */
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

// --------------------------------------------------------------------------------------
// Counts and list numbering — the readouts the editor draws
// --------------------------------------------------------------------------------------

data class RichCounts(val words: Int, val characters: Int, val blocks: Int)

/**
 * Words and characters as a person counts them, not as a parser does.
 *
 * [RichCounts.characters] counts the TEXT — not the JSON, not the marks. A designer working to "no
 * more than 500 words for the executive summary" is counting what will be printed, and a figure that
 * included the formatting would be a number nobody can act on. Words are runs of non-whitespace,
 * which is what every word processor means by the word and is right for Devanagari and Odia prose,
 * where the script separates words with spaces exactly as Latin does.
 */
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

/**
 * The number the report will print beside each ORDERED_ITEM — null for every other block.
 *
 * THE EDITOR MUST NOT LET THE LIST NUMBER ITSELF, because a Compose column that counted its own
 * ordered rows and the report writers count differently, and the designer would be reading one set
 * of numbers on screen and shipping another in the .docx. This reproduces `toReportBlocks` exactly:
 * consecutive ordered items are one list, an EMPTY block between two of them does not break the run
 * (the writers skip empty blocks before they decide), and anything else — a paragraph, a heading, a
 * bulleted item — flushes the list and restarts the count at one.
 *
 * Nesting depth is deliberately ignored, and that is not an oversight: the report model's list block
 * carries items and an ordered flag and has no per-item depth at all, so the generated file numbers
 * a sub-item in sequence with its parents. Numbering the editor any other way would be the editor
 * lying about the document.
 */
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

// --------------------------------------------------------------------------------------
// The toolbar's view of the selection
// --------------------------------------------------------------------------------------

/**
 * Everything the toolbar needs to draw itself against the current selection.
 *
 * A MIXED selection reports null rather than the first block's value. A toolbar showing "Heading 2"
 * while three paragraphs and a heading are selected is telling the designer something untrue about
 * their own document, and the next press — which applies to all four blocks — then comes as a
 * surprise. [canIndent] and [canOutdent] are the one exception and are ANY-of rather than all-of,
 * because a control that greys out when one paragraph is caught at the end of a selected list is a
 * control the designer cannot use on the list.
 */
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

// --------------------------------------------------------------------------------------
// Self-check
// --------------------------------------------------------------------------------------

/*
 * WHY THE CHECKS ARE IN THE SOURCE SET RATHER THAN IN A TEST ONE. This module has no `src/test`
 * directory and no test dependency in `app/build.gradle.kts`, and adding JUnit to reach these would
 * be a new dependency in a project that has said no new dependencies. Everything above is pure
 * Kotlin over immutable data with no Android class anywhere in it, so the checks below run on a
 * plain JVM against the compiled classes — `main` is callable with `java -cp` over the module's
 * output and the kotlin-stdlib — and they are the evidence that the ladders behave as the web's do.
 * The cost is a few kilobytes of dead code in the APK. The alternative was claiming, untested, that
 * a four-rung Backspace ladder ported correctly on the first attempt.
 */

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

/**
 * Every ladder in this file, exercised. Returns the names of the checks that failed.
 *
 * Empty means the port matches the behaviour `frontend/lib/richText.ts` is pinned to.
 */
fun richTextOpsSelfCheck(): List<String> {
    val failures = ArrayList<String>()
    fun check(name: String, condition: Boolean) {
        if (!condition) failures.add(name)
    }

    // --- span merging, the invariant every other check depends on ---------------------
    val bold = docOf(RichBlock(spans = listOf(spanOf("ab", Mark.BOLD))))
    val typedBold = insertText(bold, caretAt(0, 2), "c", listOf("BOLD"))
    check("typing bold at the end of a bold word stays one span", typedBold.doc.blocks[0].spans.size == 1)
    check("typing bold appends the text", typedBold.doc.blocks[0].text == "abc")
    check("typing bold moves the caret", typedBold.selection == caretAt(0, 3))
    val typedPlain = insertText(bold, caretAt(0, 2), "c", emptyList())
    check("typing unmarked after bold opens a second span", typedPlain.doc.blocks[0].spans.size == 2)
    check("deleting all of a span leaves no empty span", deleteRange(bold, selectionOf(0, 0, 0, 2)).doc.blocks[0].spans.isEmpty())
    check("newlines in inserted text become spaces", insertText(docOf(blockOf("")), caretAt(0, 0), "a\nb").doc.blocks[0].text == "a b")

    // --- marksAt: the character BEFORE the caret --------------------------------------
    val mixed = RichBlock(spans = listOf(spanOf("ab", Mark.BOLD), spanOf("cd")))
    check("marksAt reads backwards", marksAt(mixed, 2) == listOf("BOLD"))
    check("marksAt past the boundary is unmarked", marksAt(mixed, 3).isEmpty())
    check("marksAt at offset 0 reads forwards", marksAt(mixed, 0) == listOf("BOLD"))

    // --- Enter ------------------------------------------------------------------------
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

    // --- Backspace, rung by rung -------------------------------------------------------
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

    // --- forward delete and ranged delete ----------------------------------------------
    val forward = deleteForward(docOf(blockOf("T", BlockKind.HEADING, level = 1), blockOf("x")), caretAt(0, 1))
    check("Delete at the end pulls the next block up", textsOf(forward.doc) == listOf("Tx"))
    check("Delete at the end keeps the caret's own kind", forward.doc.blocks[0].kind == BlockKind.HEADING)
    val across = docOf(blockOf("Heading", BlockKind.HEADING, level = 2), blockOf("paragraph"))
    val cut = deleteRange(across, selectionOf(0, 4, 1, 4))
    check("a cross-block delete keeps the start block's kind", cut.doc.blocks.single().kind == BlockKind.HEADING)
    check("a cross-block delete joins the two halves", cut.doc.blocks.single().text == "Headgraph")
    check("a backwards range deletes the same text", deleteRange(across, selectionOf(1, 4, 0, 4)).doc == cut.doc)
    check("a backwards range is ordered by normaliseRange", normaliseRange(across, selectionOf(1, 4, 0, 4)) == selectionOf(0, 4, 1, 4))

    // --- marks are all-of, never any-of ------------------------------------------------
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

    // --- block kinds, alignment, indentation --------------------------------------------
    val nestedBullet = docOf(blockOf("a", BlockKind.BULLET_ITEM, level = 2))
    check("list to list keeps the depth", setBlockKind(nestedBullet, caretAt(0, 0), "ORDERED_ITEM").doc.blocks[0].level == 2)
    check("paragraph to list starts at depth 0", setBlockKind(docOf(blockOf("a")), caretAt(0, 0), "BULLET_ITEM").doc.blocks[0].level == 0)
    check("a heading with no level is level 1", setBlockKind(nestedBullet, caretAt(0, 0), "HEADING").doc.blocks[0].level == 1)
    check("a heading level is capped at 4", setBlockKind(nestedBullet, caretAt(0, 0), "HEADING", 9).doc.blocks[0].level == MAX_HEADING_LEVEL)
    check("an unknown kind changes nothing", setBlockKind(nestedBullet, caretAt(0, 0), "MARQUEE").doc === nestedBullet)
    // "TABLE" used to BE the unknown token in the check above, and stopped being one the day the
    // constant was added. It resolved, the block was re-kinded, and the result was a TABLE with no
    // rows — which `fromJson` drops entirely, taking the designer's line with it.
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

    // --- input rules --------------------------------------------------------------------
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

    // --- paste ---------------------------------------------------------------------------
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

    // --- emptiness, numbering, counts ------------------------------------------------------
    check("a no-break space is an empty document", isEmptyDoc(docOf(blockOf(nbsp))))
    check("a byte-order mark is an empty document", isEmptyDoc(docOf(blockOf(Char(0xFEFF).toString()))))
    check("real text is not an empty document", !isEmptyDoc(docOf(blockOf("x"))))
    // The two arms of the IMAGE rule. A photograph with no caption IS content — judging it on its
    // text is what made the Photograph button emit nothing at all — while an IMAGE that lost its id
    // is not, because both parsers drop such a block on the next read.
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

    // --- find and replace --------------------------------------------------------------------
    val haystack = docOf(blockOf("cotton and cotton"), blockOf("Cotton"))
    check("find is case-insensitive by default", findMatches(haystack, "cotton").size == 3)
    check("find can be case-sensitive", findMatches(haystack, "cotton", caseSensitive = true).size == 2)
    check("find can require whole words", findMatches(docOf(blockOf("cot cotton")), "cot", wholeWord = true).size == 1)
    val replaced = replaceAll(haystack, "cotton", "silk")
    check("replace all reports its count", replaced.count == 3)
    check("replace all works backwards without corrupting offsets", textsOf(replaced.doc) == listOf("silk and silk", "silk"))
    check("replace keeps the marks of what it replaced", replaceAll(docOf(RichBlock(spans = listOf(spanOf("cotton", Mark.BOLD)))), "cotton", "silk").doc.blocks[0].spans.single().marks == setOf(Mark.BOLD))

    // --- inline photographs -----------------------------------------------------------------
    val prose = docOf(blockOf("before"), blockOf("after"))
    val placed = insertImage(prose, caretAt(0, 3), "media-42", "a loom")
    check("a photograph goes AFTER the caret's block, never splitting it", textsOf(placed.doc) == listOf("before", "a loom", "after"))
    check("the placed block is an IMAGE holding the id", placed.doc.blocks[1].let { it.kind == BlockKind.IMAGE && it.media == "media-42" })
    check("the caret lands at the end of the new caption", placed.selection == caretAt(1, "a loom".length))
    check("a blank id is refused rather than inserted", insertImage(prose, caretAt(0, 0), "   ").doc === prose)
    check("a caption's newline becomes a space", insertImage(prose, caretAt(0, 0), "m", "a\nb").doc.blocks[1].text == "a b")
    check("the width is clamped on the way in", insertImage(prose, caretAt(0, 0), "m", "", 500f).doc.blocks[1].widthPct == IMAGE_MAX_WIDTH_PCT)
    check("an empty document still gets a caption to stand in", insertImage(RichDoc(), caretAt(0, 0), "m", "c").selection == caretAt(1, 1))
    // Stepped by the constant the toolbar actually passes, not by a round number written here: a
    // check that says "-10 moves it by 10" passes whatever the two clients' buttons do.
    val narrowed = setImageWidth(placed.doc, 1, -IMAGE_WIDTH_STEP_PCT)
    check("the width steps down by the web's step", narrowed.doc.blocks[1].widthPct == IMAGE_DEFAULT_WIDTH_PCT - IMAGE_WIDTH_STEP_PCT)
    check("the width stops at the floor", setImageWidth(narrowed.doc, 1, -900f).doc.blocks[1].widthPct == IMAGE_MIN_WIDTH_PCT)
    check("the width command ignores prose", setImageWidth(prose, 0, 10f).doc === prose)

    // --- degenerate documents, which the web crashes on ------------------------------------
    val nothing = RichDoc()
    check("an empty document survives a caret query", toolbarState(nothing, caretAt(0, 0), null).kind == null)
    check("an empty document survives Backspace", deleteBackward(nothing, caretAt(0, 0)).doc.blocks.isEmpty())
    check("an empty document accepts typing", insertText(nothing, caretAt(0, 0), "x").doc.blocks.single().text == "x")
    check("an empty document accepts Enter", splitBlock(nothing, caretAt(0, 0)).doc.blocks.size == 2)
    check("an out-of-range point clamps", clampPoint(docOf(blockOf("ab")), DocPoint(9, 9)) == DocPoint(0, 2))

    return failures
}

/**
 * `java -cp <classes>;<kotlin-stdlib> com.fieldrepository.app.ui.richtext.RichTextOpsKt`
 *
 * Kept from the port, and it is not dead weight: `RecordProseTest` calls [richTextOpsSelfCheck]
 * directly, so the same ladders are exercised by `gradlew testDebugUnitTest` without anybody
 * remembering this entry point exists. It stays because a failure here is faster to read as a list
 * of named checks on a terminal than as a JUnit stack.
 */
fun main() {
    val failures = richTextOpsSelfCheck()
    if (failures.isEmpty()) {
        println("RichTextOps self-check: all checks passed")
        return
    }
    println("RichTextOps self-check: " + failures.size + " FAILED")
    for (failure in failures) println("  - " + failure)
}

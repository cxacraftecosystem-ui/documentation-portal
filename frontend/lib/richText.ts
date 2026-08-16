/**
 * The portable rich-text document, in TypeScript — the model half of the editor, with no DOM in it.
 *
 * This file is the browser's copy of `backend/app/services/rich_text.py`. Read that file first: it
 * explains why the stored form is a structured document and never HTML, and the three reasons are
 * all still true on this side of the wire. The short version is that FIVE renderers read what this
 * module emits — the .docx writer, the server PDF writer, the on-device Kotlin PDF writer, the
 * report preview and this editor — and only one of them is a browser. A mark that only a browser
 * can interpret is a mark that vanishes from the file a ministry receives.
 *
 * WHY THE MODEL AND THE DOM ARE IN SEPARATE FILES. `RichTextEditor.tsx` owns a `contenteditable`,
 * which is the least predictable API in the platform: three engines disagree about what Enter does
 * inside a heading, about whether a deletion at the start of a list item outdents or merges, and
 * about what markup a paste leaves behind. Everything in THIS file is a pure function of a document
 * and a selection, so the answer to "what does Enter do here" is decided once, in code that can be
 * read and reasoned about, and the DOM layer's only job is to reflect the result. The alternative —
 * letting the browser edit and reading the answer back — is how `<b><i>x</b></i>` gets stored.
 *
 * THE SHAPE, identical to the Python dataclasses:
 *
 *     RichDoc
 *       └── blocks: RichBlock[]      PARAGRAPH | HEADING | BULLET_ITEM | ORDERED_ITEM | QUOTE
 *             │                      | TABLE | IMAGE
 *             ├── align              LEFT | CENTER | RIGHT | JUSTIFY
 *             ├── level              heading level 1-4, or list nesting depth 0-3
 *             ├── rows               TABLE only — cells of spans, first row the header
 *             ├── media / widthPct   IMAGE only — the media id and its printed width
 *             └── spans: RichSpan[]  text + marks (the CAPTION, on an IMAGE)
 *                   └── marks        BOLD | ITALIC | UNDERLINE | STRIKETHROUGH | CODE
 *
 * A FLAT block list with a depth, not a tree. Every rich-text library produces a tree and it is the
 * wrong shape here, for the reason the Python file gives: the DOCX and PDF writers lay out one
 * paragraph at a time, so a tree would be flattened at the start of four renderers and the four
 * flattenings would disagree at the edges. It is flattened once, here, at the point of editing.
 *
 * TWO INVARIANTS EVERY FUNCTION IN THIS FILE MAINTAINS, because breaking either produces a document
 * that survives a round trip through this module but not through the server's:
 *
 * 1. **`marks` is deduplicated and sorted alphabetically.** `to_json` in Python emits
 *    `sorted(m.value for m in span.marks)`, so BOLD/CODE/ITALIC/STRIKETHROUGH/UNDERLINE in that
 *    order. {@link storedSignature} compares two documents by their serialised JSON — the whole
 *    controlled-value caret fix rests on that comparison — and an unsorted mark list would make a
 *    document unequal to itself the moment it came back from the server.
 * 2. **No span is empty and no two adjacent spans carry the same marks.** Typing one character at a
 *    time otherwise produces one span per keystroke, and a paragraph a designer wrote in the
 *    morning arrives at the .docx writer as four hundred runs — which Word opens, slowly, and which
 *    makes every diff of the stored JSON unreadable. {@link mergeSpans} is applied after every edit.
 *
 * WHAT THIS FILE DELIBERATELY DOES NOT CONTAIN: any notion of a link or a colour. The mark
 * vocabulary is closed by design — "a mark is an enum member or it does not exist" — and adding one
 * here without adding it to the Python enum, the two writers and the Kotlin pair means offering a
 * control whose result disappears when the file is generated.
 *
 * TABLE AND IMAGE ARE THE TWO EXCEPTIONS TO THAT LIST, and each earned it the same way: by being
 * added to all five renderers at once, and by mapping onto a block the report model ALREADY has, so
 * that no renderer needed a new drawing path.
 *
 *  - `TABLE` keeps its content in `rows` and becomes `report_model.TableBlock`.
 *  - `IMAGE` keeps a media id in `media` and becomes `report_model.ImageBlock`, resolved by the same
 *    `MediaResolver` a gallery photograph goes through. Its `spans` are the CAPTION, so a caption is
 *    ordinary prose that can be bolded and can carry an Odia phrase like any other sentence — which
 *    is also why the caret arithmetic below needs no image-specific case at all.
 *
 * They are the only two kinds whose content is not fully in `spans`, which is why the text-shaped
 * helpers below carry explicit arms for them rather than falling through. `tests/test_report_parity.py`
 * fails if either side of the port loses either constant, and that guard is not decoration: a client
 * that does not know a kind reads it as an empty paragraph and writes the grid — or the photograph —
 * away, by the ordinary act of opening the stage.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Types and limits — mirrors of the Python module's own constants
 * ──────────────────────────────────────────────────────────────────────────── */

export type RichMark =
  | "BOLD"
  | "ITALIC"
  | "UNDERLINE"
  | "STRIKETHROUGH"
  | "CODE"
  | "SUPERSCRIPT"
  | "SUBSCRIPT"
  | "HIGHLIGHT";
export type RichBlockKind =
  | "PARAGRAPH"
  | "HEADING"
  | "BULLET_ITEM"
  | "ORDERED_ITEM"
  | "QUOTE"
  | "TABLE"
  | "IMAGE";
export type RichAlign = "LEFT" | "CENTER" | "RIGHT" | "JUSTIFY";

export type RichSpan = {
  text: string;
  /** Deduplicated and sorted alphabetically. See invariant 1 in the file header. */
  marks: RichMark[];
};

/**
 * One table cell: a run of spans, and deliberately NOT a list of blocks.
 *
 * A cell that could hold blocks could hold a table, and a table inside a table is a shape the
 * .docx writer, both PDF writers and this editor would each have to agree about — including how
 * deep to stop. Every real use is a grid of short values, so a cell is one paragraph's worth of
 * marked text and the model cannot express the case nobody asked for.
 */
export type RichCell = RichSpan[];
/** One row: its cells, left to right. */
export type RichRow = RichCell[];

export type RichBlock = {
  kind: RichBlockKind;
  spans: RichSpan[];
  align: RichAlign;
  /** Heading level 1-4 for HEADING; list nesting depth 0-3 for the two item kinds; 0 otherwise. */
  level: number;
  /**
   * TABLE only, empty for every other kind. The FIRST row is the header — the same reading the
   * server gives it, which is what lets the block map onto one `report_model.TableBlock`.
   */
  rows: RichRow[];
  /**
   * IMAGE only: the media id, and "" for every other kind.
   *
   * A MEDIA ID, NEVER A URL. Every media route is bearer-authenticated and a signed bucket URL
   * expires while the document does not, so a URL stored here would be a picture that works this
   * afternoon and is a broken frame next week — in a value three clients read. The id is resolved
   * to something an `<img>` can load at the moment of drawing, and to bytes by the SAME
   * `MediaResolver` a gallery photograph goes through when the file is generated.
   */
  media: string;
  /** IMAGE only: the printed width as a percentage of the text column, clamped 10-100. */
  widthPct: number;
};

export type RichDoc = { blocks: RichBlock[] };

/** The JSON shape that is stored in the field's value, exactly as `rich_text.to_json` writes it. */
export type StoredRichSpan = { text: string; marks?: RichMark[] };
export type StoredRichBlock = {
  kind: RichBlockKind;
  spans: StoredRichSpan[];
  align?: RichAlign;
  level?: number;
  rows?: StoredRichSpan[][][];
  media?: string;
  widthPct?: number;
};
export type StoredRichDoc = { blocks: StoredRichBlock[] };

/**
 * The same four limits the server enforces.
 *
 * They are restated rather than fetched because the editor has to stop a paste BEFORE it is applied
 * — a designer who pastes a 300-page thesis into a narrative field and is told "saved" has lost the
 * tail of it, and being told at Save time, forty fields later, is barely better than not being told.
 */
export const MAX_DOCUMENT_CHARS = 200_000;
export const MAX_BLOCKS = 2_000;
export const MAX_HEADING_LEVEL = 4;
export const MAX_LIST_DEPTH = 3;
const MAX_SPANS_PER_BLOCK = 512;

/**
 * Table bounds, matching `rich_text.py`. A phone or a browser that accepted 20 columns where the
 * server keeps 12 would write a document the server then silently truncated.
 */
export const MAX_TABLE_ROWS = 200;
export const MAX_TABLE_COLUMNS = 12;
const MAX_SPANS_PER_CELL = 64;

export const RICH_MARKS: readonly RichMark[] = [
  "BOLD",
  "ITALIC",
  "UNDERLINE",
  "STRIKETHROUGH",
  "CODE",
  "SUPERSCRIPT",
  "SUBSCRIPT",
  "HIGHLIGHT"
];
const MARK_LOOKUP = new Set<string>(RICH_MARKS);

/**
 * The one colour a highlight is, everywhere.
 *
 * Not a choice, and not this file's to make: `w:highlight` takes a CLOSED vocabulary of sixteen
 * named colours rather than an RGB value, so the .docx writer names "yellow" and Word picks the
 * pixels. `report_model.HIGHLIGHT_FILL` is the hex Word draws for that name, both PDF writers fill
 * it, and this is the editor's copy — a different yellow here would be an editor that disagrees
 * with the file about a mark whose entire content is its colour.
 */
export const HIGHLIGHT_FILL = "#FFFF00";

/**
 * Marks that cannot share a character, keyed by the one being applied.
 *
 * A character is raised or lowered or neither; `<w:vertAlign>` takes ONE value, and a document that
 * claimed both would be resolved by every renderer's own tie-break. The server does have a
 * tie-break (`rich_text.RichSpan.subscript` lets SUPERSCRIPT win) but it exists to make a
 * hand-written value renderable, not to be relied on: applying one here REMOVES the other, so the
 * document this editor stores never contains the ambiguous case at all — and the toolbar's two
 * buttons behave like the pair of radio-ish toggles a designer already expects them to be.
 */
const MARK_EXCLUDES: Partial<Record<RichMark, RichMark>> = {
  SUPERSCRIPT: "SUBSCRIPT",
  SUBSCRIPT: "SUPERSCRIPT"
};

/** The mark `mark` displaces when it is applied, or null when it shares happily with everything. */
export function markExcludes(mark: RichMark): RichMark | null {
  return MARK_EXCLUDES[mark] ?? null;
}

const BLOCK_KINDS = new Set<string>([
  "PARAGRAPH",
  "HEADING",
  "BULLET_ITEM",
  "ORDERED_ITEM",
  "QUOTE",
  "TABLE",
  "IMAGE"
]);
const ALIGNS = new Set<string>(["LEFT", "CENTER", "RIGHT", "JUSTIFY"]);

/**
 * The width an inline photograph is inserted at, and the step the toolbar's two width controls move
 * it by — both mirroring `rich_text.RichBlock.width_pct`, whose default is 70 and whose bounds are
 * 10 and 100. A value outside those is clamped by the server on the way in, so a client that
 * offered 120% would show the designer one width and print another.
 */
export const IMAGE_DEFAULT_WIDTH_PCT = 70;
export const IMAGE_MIN_WIDTH_PCT = 10;
export const IMAGE_MAX_WIDTH_PCT = 100;
export const IMAGE_WIDTH_STEP_PCT = 15;

export function isListKind(kind: RichBlockKind): boolean {
  return kind === "BULLET_ITEM" || kind === "ORDERED_ITEM";
}

/* ────────────────────────────────────────────────────────────────────────────
 * Selection, expressed against the MODEL and never against the DOM
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A caret position: which block, and how many UTF-16 code units into its text.
 *
 * Offsets are into the block's TEXT, not into a span and not into a DOM node. That is the whole
 * point: a span boundary is an implementation detail that moves every time a mark is applied, and a
 * DOM node is re-created wholesale on every structural command. A block index plus a text offset
 * survives both, which is what lets a command run, re-render the surface and put the caret back
 * exactly where the designer left it.
 */
export type DocPoint = { block: number; offset: number };

/** An anchor/focus pair, in the order the user made it — `anchor` may follow `focus`. */
export type DocRange = { anchor: DocPoint; focus: DocPoint };

export function pointsEqual(a: DocPoint, b: DocPoint): boolean {
  return a.block === b.block && a.offset === b.offset;
}

export function comparePoints(a: DocPoint, b: DocPoint): number {
  if (a.block !== b.block) return a.block - b.block;
  return a.offset - b.offset;
}

export function isCollapsed(range: DocRange): boolean {
  return pointsEqual(range.anchor, range.focus);
}

/** The range in document order, whichever way round the user dragged. */
export function orderedRange(range: DocRange): { start: DocPoint; end: DocPoint } {
  return comparePoints(range.anchor, range.focus) <= 0
    ? { start: range.anchor, end: range.focus }
    : { start: range.focus, end: range.anchor };
}

export function collapsedAt(point: DocPoint): DocRange {
  return { anchor: point, focus: point };
}

/**
 * A point that is guaranteed to exist in `doc`.
 *
 * Every command clamps its result through this. An off-the-end point is not a cosmetic problem: the
 * DOM layer maps a model point to a real node, and a point naming block 7 of a five-block document
 * throws inside `Selection.setBaseAndExtent`, which leaves the editor with no caret at all and looks
 * to a designer like the keyboard stopped working.
 */
export function clampPoint(doc: RichDoc, point: DocPoint): DocPoint {
  if (!doc.blocks.length) return { block: 0, offset: 0 };
  const block = Math.max(0, Math.min(doc.blocks.length - 1, point.block));
  const length = blockLength(doc.blocks[block]);
  return { block, offset: Math.max(0, Math.min(length, point.offset)) };
}

export function clampRange(doc: RichDoc, range: DocRange): DocRange {
  return { anchor: clampPoint(doc, range.anchor), focus: clampPoint(doc, range.focus) };
}

/** What every command returns: the new document and where the caret ends up in it. */
export type EditResult = { doc: RichDoc; selection: DocRange };

/* ────────────────────────────────────────────────────────────────────────────
 * Construction and small readers
 * ──────────────────────────────────────────────────────────────────────────── */

export function makeSpan(text: string, marks: readonly RichMark[] = []): RichSpan {
  return { text, marks: sortMarks(marks) };
}

export function makeBlock(partial: Partial<RichBlock> = {}): RichBlock {
  return {
    kind: partial.kind ?? "PARAGRAPH",
    spans: partial.spans ?? [],
    align: partial.align ?? "LEFT",
    level: partial.level ?? 0,
    rows: partial.rows ?? [],
    media: partial.media ?? "",
    widthPct: partial.widthPct ?? IMAGE_DEFAULT_WIDTH_PCT
  };
}

/** Whether this block's content lives in `rows` rather than in `spans`. */
export function isTableKind(kind: RichBlockKind): boolean {
  return kind === "TABLE";
}

/**
 * Whether this block carries a picture — a block whose content is a media id AND a run of spans.
 *
 * The pair is what makes IMAGE unlike every other kind: its `spans` are the CAPTION, so the text
 * helpers below can treat it as ordinary prose (which is what lets the caret sit in a caption and
 * be moved by the same arithmetic as any paragraph), while `media` is content the text helpers
 * cannot see at all — hence {@link isBlockEmpty}, which the completeness gate depends on.
 */
export function isImageKind(kind: RichBlockKind): boolean {
  return kind === "IMAGE";
}

/**
 * The kinds whose content is NOT in their spans, and which therefore must never be re-kinded by a
 * command that only means to change how prose is styled.
 *
 * Pressing "Paragraph" over a selection that includes a table used to set `kind: "PARAGRAPH"` on
 * it, and a table that is not a TABLE loses its `rows` at the next {@link toStored} — the whole
 * grid, gone, to a button the designer pressed to un-bold a sentence three blocks away. The same
 * press over an inline photograph would drop `media` and leave the caption floating under nothing.
 */
function isStructuralKind(kind: RichBlockKind): boolean {
  return isTableKind(kind) || isImageKind(kind);
}

/** The widest row. Rows are padded to this when the table is drawn, never before. */
export function tableColumnCount(block: RichBlock): number {
  return block.rows.reduce((widest, row) => Math.max(widest, row.length), 0);
}

/** A blank table of the given shape, its first row being the header. */
export function makeTable(rows: number, columns: number): RichBlock {
  const r = Math.max(2, Math.min(MAX_TABLE_ROWS, rows));
  const c = Math.max(1, Math.min(MAX_TABLE_COLUMNS, columns));
  return makeBlock({
    kind: "TABLE",
    rows: Array.from({ length: r }, () => Array.from({ length: c }, () => [] as RichCell))
  });
}

/**
 * A document with one empty paragraph in it.
 *
 * Never `{ blocks: [] }`: a `contenteditable` with no block element has nowhere to put a caret, so
 * the first keystroke lands in the host element itself and the browser invents a wrapper of its own
 * choosing — a `<div>` in Chrome, a bare text node in Safari. One empty paragraph is the smallest
 * document that can be typed into. It serialises to the exact value the server's `is_empty` reads
 * as unfilled, so an editor that was opened and left alone does not count towards completeness.
 */
export function emptyDoc(): RichDoc {
  return { blocks: [makeBlock()] };
}

export function blockText(block: RichBlock): string {
  // A table's text is its cells, read left to right and top to bottom. It has to be defined,
  // because `isEmptyDoc` reads it and the completeness gate reads that: a field whose only content
  // was a filled-in table would otherwise count as unfilled and the designer would be told so.
  if (block.kind === "TABLE") {
    return block.rows
      .map((row) => row.map((cell) => cell.map((span) => span.text).join("")).join("\t"))
      .join("\n");
  }
  let text = "";
  for (const span of block.spans) text += span.text;
  return text;
}

export function blockLength(block: RichBlock): number {
  let total = 0;
  for (const span of block.spans) total += span.text.length;
  return total;
}

/**
 * Whether one block counts as holding nothing — the exact rule `RichBlock.is_empty` applies.
 *
 * AN IMAGE IS NEVER EMPTY WHILE IT HOLDS A MEDIA ID, even with no caption. Judging it on text
 * would make a field whose only content is a photograph read as unfilled, and the completeness
 * gate reads exactly this: the designer would be told they had left a required narrative blank
 * while a picture of the loom sat in it. It is also what {@link orderedNumbers} tests, so a
 * caption-less photograph between two numbered items restarts the count on screen the same way
 * `to_report_blocks` restarts it in the file.
 */
export function isBlockEmpty(block: RichBlock): boolean {
  if (isImageKind(block.kind)) return !block.media;
  return !blockText(block).trim();
}

export function isEmptyDoc(doc: RichDoc): boolean {
  return doc.blocks.every(isBlockEmpty);
}

/** Deduplicated, alphabetically sorted — invariant 1. `sort()` matches Python's `sorted()`. */
export function sortMarks(marks: readonly RichMark[]): RichMark[] {
  if (marks.length < 2) return [...marks];
  return [...new Set(marks)].sort();
}

function marksKey(marks: readonly RichMark[]): string {
  return marks.join("|");
}

export function marksEqual(a: readonly RichMark[], b: readonly RichMark[]): boolean {
  return marksKey(a) === marksKey(b);
}

/**
 * Drop empty spans and fuse adjacent ones with identical marks — invariant 2.
 *
 * Run after EVERY span-level edit. Without it a paragraph accumulates one span per keystroke, and
 * `_runs_for` in the Python turns each of those into at least one Run: a 900-word narrative becomes
 * several thousand runs in `word/document.xml`, which Word opens slowly and which makes the stored
 * JSON impossible to read in a database console when something has gone wrong.
 */
export function mergeSpans(spans: readonly RichSpan[]): RichSpan[] {
  const out: RichSpan[] = [];
  for (const span of spans) {
    if (!span.text) continue;
    const previous = out[out.length - 1];
    if (previous && marksEqual(previous.marks, span.marks)) {
      out[out.length - 1] = { text: previous.text + span.text, marks: previous.marks };
      continue;
    }
    out.push({ text: span.text, marks: sortMarks(span.marks) });
  }
  return out;
}

/** The slice of a block's spans covering `[start, end)` of its text. */
export function sliceSpans(spans: readonly RichSpan[], start: number, end: number): RichSpan[] {
  if (end <= start) return [];
  const out: RichSpan[] = [];
  let position = 0;
  for (const span of spans) {
    const spanStart = position;
    const spanEnd = position + span.text.length;
    position = spanEnd;
    if (spanEnd <= start) continue;
    if (spanStart >= end) break;
    const from = Math.max(0, start - spanStart);
    const to = Math.min(span.text.length, end - spanStart);
    const text = span.text.slice(from, to);
    if (text) out.push({ text, marks: span.marks });
  }
  return out;
}

/**
 * The marks a character typed at `offset` should inherit.
 *
 * Reads the character BEFORE the caret, falling back to the one after it at the very start of a
 * block. That is the behaviour every word processor has: typing at the end of a bold word continues
 * bold, and typing at the start of a line does not silently pick up the formatting of the line
 * above. Getting this backwards produces the single most irritating editor bug there is — a
 * designer un-bolds a heading, types, and the new text comes out bold anyway.
 */
export function marksAt(block: RichBlock, offset: number): RichMark[] {
  if (!block.spans.length) return [];
  let position = 0;
  let previous: RichSpan | null = null;
  for (const span of block.spans) {
    const spanEnd = position + span.text.length;
    if (offset > position && offset <= spanEnd) return [...span.marks];
    if (offset === position && previous) return [...previous.marks];
    if (offset === position) return [...span.marks];
    position = spanEnd;
    previous = span;
  }
  return previous ? [...previous.marks] : [];
}

/* ────────────────────────────────────────────────────────────────────────────
 * Parsing — every path in is forgiving, mirroring `rich_text.from_json`
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Strip what would make a document unrenderable somewhere downstream.
 *
 * The Python side runs `report_model.clean_text` for a reason worth repeating here, because the
 * browser is where these characters get in: a LONE SURROGATE — half of an emoji, produced by a
 * phone keyboard, a truncating paste, or a `slice()` through an astral character — makes
 * `word/document.xml` not well-formed, and Word refuses the ENTIRE file rather than dropping the
 * one character. Control characters other than tab and newline are stripped for the same reason.
 * Doing it on the way in as well as on the server means the editor never displays a character it
 * is going to lose.
 */
const NEEDS_CLEANING = /[\u0000-\u0008\u000b-\u001f\u007f-\u009f\ud800-\udfff]/;

export function cleanText(raw: unknown): string {
  if (raw === null || raw === undefined) return "";
  const text = typeof raw === "string" ? raw : String(raw);
  // The fast path matters: this runs over every text node of the document on every keystroke, and
  // the overwhelming majority of prose contains nothing to clean. A regex test is one pass in
  // native code; the loop below is a per-character pass in JavaScript, and on a forty-page
  // narrative that difference is the gap between a responsive editor and a laggy one.
  if (!NEEDS_CLEANING.test(text)) return text;
  let out = "";
  for (let index = 0; index < text.length; index += 1) {
    const code = text.charCodeAt(index);
    // A high surrogate must be followed by a low surrogate; anything else is half a character.
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = text.charCodeAt(index + 1);
      if (next >= 0xdc00 && next <= 0xdfff) {
        out += text[index] + text[index + 1];
        index += 1;
        continue;
      }
      continue;
    }
    if (code >= 0xdc00 && code <= 0xdfff) continue;
    // Tab and newline survive; every other C0/C1 control does not. \r is normalised by the caller.
    if (code < 0x20 && code !== 0x09 && code !== 0x0a) continue;
    if (code >= 0x7f && code <= 0x9f) continue;
    out += text[index];
  }
  return out;
}

function coerceMarks(raw: unknown): RichMark[] {
  if (!Array.isArray(raw)) return [];
  const found: RichMark[] = [];
  for (const item of raw) {
    const token = String(item).toUpperCase();
    // Unknown marks are dropped in silence, exactly as `_coerce_marks` drops them: a phone one
    // release ahead may apply a mark this build has never heard of, and losing the mark is a
    // cosmetic regression while refusing the document is data loss.
    if (MARK_LOOKUP.has(token)) found.push(token as RichMark);
  }
  return sortMarks(found);
}

function coerceKind(raw: unknown): RichBlockKind {
  const token = String(raw ?? "").toUpperCase();
  return BLOCK_KINDS.has(token) ? (token as RichBlockKind) : "PARAGRAPH";
}

function coerceAlign(raw: unknown): RichAlign {
  const token = String(raw ?? "").toUpperCase();
  return ALIGNS.has(token) ? (token as RichAlign) : "LEFT";
}

function clampLevel(kind: RichBlockKind, raw: unknown): number {
  const value = typeof raw === "number" && Number.isFinite(raw) ? Math.trunc(raw) : 0;
  if (kind === "HEADING") return Math.max(1, Math.min(MAX_HEADING_LEVEL, value || 1));
  if (isListKind(kind)) return Math.max(0, Math.min(MAX_LIST_DEPTH, value));
  return 0;
}

/**
 * A document out of whatever is stored under the field — the exact counterpart of `from_json`.
 *
 * Three shapes are accepted because all three genuinely occur, and the third one is the important
 * one: **a plain string is every value written before a field was promoted from LONG_TEXT to
 * RICH_TEXT.** Promoting a field must not blank the prose already stored under it, so a string is
 * read as an unformatted document with its lines as paragraphs. A registry change is then a
 * one-line edit on the server rather than a migration, and no designer opens a stage to find the
 * introduction they wrote last season replaced by an empty box.
 */
export function fromStored(raw: unknown): RichDoc {
  if (raw === null || raw === undefined) return { blocks: [] };
  if (typeof raw === "string") return fromPlainText(raw);
  const rawBlocks = Array.isArray(raw)
    ? raw
    : typeof raw === "object" && Array.isArray((raw as { blocks?: unknown }).blocks)
      ? ((raw as { blocks: unknown[] }).blocks)
      : null;
  if (!rawBlocks) return { blocks: [] };

  const blocks: RichBlock[] = [];
  let budget = MAX_DOCUMENT_CHARS;
  for (const entry of rawBlocks.slice(0, MAX_BLOCKS)) {
    if (typeof entry === "string") {
      const text = cleanText(entry).slice(0, budget);
      budget -= text.length;
      if (text) blocks.push(makeBlock({ spans: [makeSpan(text)] }));
      continue;
    }
    if (!entry || typeof entry !== "object") continue;
    const record = entry as Record<string, unknown>;
    const kind = coerceKind(record.kind);
    const rawSpans = Array.isArray(record.spans) ? record.spans.slice(0, MAX_SPANS_PER_BLOCK) : [];
    const spans: RichSpan[] = [];
    for (const rawSpan of rawSpans) {
      let text: string;
      let marks: RichMark[];
      if (typeof rawSpan === "string") {
        text = cleanText(rawSpan);
        marks = [];
      } else if (rawSpan && typeof rawSpan === "object") {
        const spanRecord = rawSpan as Record<string, unknown>;
        text = cleanText(spanRecord.text);
        marks = coerceMarks(spanRecord.marks);
      } else {
        continue;
      }
      // A newline inside a span would make one block render as two, which no renderer expects. The
      // editor emits a new block for a new line, so this only fires on hand-written or migrated
      // data — but it fires there, and a stray "\n" reaching the DOCX writer splits a paragraph.
      text = text.replace(/\n/g, " ");
      if (budget <= 0) break;
      text = text.slice(0, budget);
      budget -= text.length;
      if (text) spans.push(makeSpan(text, marks));
    }
    let media = "";
    let widthPct = IMAGE_DEFAULT_WIDTH_PCT;
    if (kind === "IMAGE") {
      media = cleanText(record.media).trim().slice(0, 64);
      // An IMAGE with no media id is not a picture. Dropped rather than kept, exactly as an empty
      // TABLE is: keeping it would put an un-resolvable placeholder into the document and make
      // `isBlockEmpty` answer "filled" for a block that prints nothing.
      if (!media) continue;
      if (typeof record.widthPct === "number" && Number.isFinite(record.widthPct)) {
        widthPct = Math.max(IMAGE_MIN_WIDTH_PCT, Math.min(IMAGE_MAX_WIDTH_PCT, record.widthPct));
      }
    }

    let rows: RichRow[] = [];
    if (kind === "TABLE") {
      const parsed = coerceRows(record.rows, budget);
      rows = parsed.rows;
      budget = parsed.budget;
      // A TABLE with nothing in it is not a table. Dropped rather than kept as an empty grid,
      // because `isEmptyDoc` judges on text and a document whose only block was a 3x3 of blank
      // cells would otherwise count as filled and pass the completeness gate.
      if (!rows.length) continue;
    }
    blocks.push(makeBlock({
      kind,
      spans: mergeSpans(spans),
      align: coerceAlign(record.align),
      level: clampLevel(kind, record.level),
      rows,
      media,
      widthPct
    }));
    if (budget <= 0) break;
  }
  return { blocks };
}

/**
 * Rows of cells of spans, bounded, forgiving, and sharing the document's character budget.
 *
 * The port of the server's `_coerce_rows`, and forgiving in the same way as everything around it: a
 * row that is not an array is skipped, a cell that is a bare string becomes one unmarked span, and
 * a ragged table stays ragged rather than being rejected — `tableColumnCount` squares it off at
 * drawing time. The budget is the DOCUMENT's, so a table cannot spend more of it than the
 * paragraphs around it can.
 */
function coerceRows(raw: unknown, startBudget: number): { rows: RichRow[]; budget: number } {
  if (!Array.isArray(raw)) return { rows: [], budget: startBudget };
  let budget = startBudget;
  const rows: RichRow[] = [];
  for (const rowRaw of raw.slice(0, MAX_TABLE_ROWS)) {
    if (!Array.isArray(rowRaw)) continue;
    const cells: RichCell[] = [];
    for (const cellRaw of rowRaw.slice(0, MAX_TABLE_COLUMNS)) {
      const items: unknown[] = typeof cellRaw === "string" ? [cellRaw] : Array.isArray(cellRaw) ? cellRaw : [];
      const spans: RichSpan[] = [];
      for (const spanRaw of items.slice(0, MAX_SPANS_PER_CELL)) {
        let text: string;
        let marks: RichMark[];
        if (typeof spanRaw === "string") {
          text = cleanText(spanRaw);
          marks = [];
        } else if (spanRaw && typeof spanRaw === "object") {
          const record = spanRaw as Record<string, unknown>;
          text = cleanText(record.text);
          marks = coerceMarks(record.marks);
        } else {
          continue;
        }
        // Same rule as a paragraph span: a newline inside one would make a single cell render as
        // two lines in the browser and one in the .docx.
        text = text.replace(/\n/g, " ");
        if (budget <= 0) break;
        text = text.slice(0, budget);
        budget -= text.length;
        if (text) spans.push(makeSpan(text, marks));
      }
      cells.push(mergeSpans(spans));
    }
    if (cells.length) rows.push(cells);
    if (budget <= 0) break;
  }
  // Drop trailing rows that are entirely blank — what this editor leaves behind when a designer
  // adds a row and then thinks better of it.
  while (rows.length && !rows[rows.length - 1].some((cell) => cell.some((span) => span.text.trim()))) {
    rows.pop();
  }
  return { rows, budget };
}

/** A plain string as an unformatted document, one paragraph per non-blank line. */
export function fromPlainText(raw: unknown): RichDoc {
  const cleaned = cleanText(raw).replace(/\r\n?/g, "\n").slice(0, MAX_DOCUMENT_CHARS);
  if (!cleaned.trim()) return { blocks: [] };
  const blocks = cleaned
    .split("\n")
    .filter((line) => line.trim())
    .slice(0, MAX_BLOCKS)
    .map((line) => makeBlock({ spans: [makeSpan(line.trim())] }));
  return { blocks };
}

/**
 * Seed a document for a field that is a LIST by nature — the ones whose help says
 * "One deliverable per line", "One objective per line", "One problem per line".
 *
 * Those fields are `report_role=BULLETS` in the registry and the report prints them as a list, so
 * the editor should put the designer INSIDE a list from the moment the field opens: the first line
 * is item 1, and Enter starts item 2 without anyone typing "1. " first. `splitBlock` already
 * carries the list kind across an Enter and already drops out of the list on an empty item, so
 * seeding the first block is genuinely all that is required.
 *
 * WHAT THIS DELIBERATELY DOES NOT DO is reshape a document the designer has already built. Only
 * two inputs are converted:
 *
 *   - EMPTY — there is nothing to lose, and one empty list item is the useful starting state.
 *   - A PLAIN STRING — the pre-promotion value, where the newlines the help text asked for ARE the
 *     list. Turning each line into an item is a faithful reading of what was typed.
 *
 * A stored block document is returned untouched, because by then the blocks are the designer's own
 * choices. Rewriting their paragraph into a list on every re-seed would be the editor arguing with
 * them, and they would have no way to win.
 */
export function seedListDocument(raw: unknown, kind: RichBlockKind): RichDoc {
  const wasStoredDocument = raw !== null && raw !== undefined && typeof raw !== "string";
  const doc = fromStored(raw);
  if (wasStoredDocument) return doc;
  if (!doc.blocks.length) return { blocks: [makeBlock({ kind, level: 0 })] };
  return {
    blocks: doc.blocks.map((block) =>
      block.kind === "PARAGRAPH" ? { ...block, kind, level: 0 } : block
    )
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Paste — plain text in, structure inferred, markup NEVER interpreted
 * ──────────────────────────────────────────────────────────────────────────── */

const PASTE_BULLET = /^[•‣▪●·*+-]\s+/;
const PASTE_ORDERED = /^\(?(\d{1,3})[.)]\s+/;
const PASTE_HEADING = /^(#{1,4})\s+/;
const PASTE_QUOTE = /^>\s+/;

/**
 * A pasted clipboard as a document.
 *
 * **`text` is the clipboard's `text/plain`, and that is deliberate to the point of being the whole
 * design.** A paste out of Word carries a `text/html` flavour that is several hundred kilobytes of
 * `<span style="mso-fareast-font-family:...">` wrapped around forty words, and every editor that
 * has ever tried to sanitise that markup has ended up with an allowlist that leaks. There is nothing
 * to leak here because the markup is never read: the words are taken, and the structure is inferred
 * from the characters Word itself puts in the plain-text flavour — a bullet character, "1.", a leading
 * tab for a nested item. What the designer loses is the source's bold and italic. What they cannot
 * lose is a `<script>`, a stylesheet, a font stack, a colour that is invisible in dark mode, or a
 * class name that means something to some other application.
 *
 * The indent is read from LEADING TABS rather than from leading spaces: spaces are how prose is
 * indented and tabs are how a list is, and treating four spaces as a nesting level turns an indented
 * quotation into a sub-bullet.
 */
export function docFromPastedText(text: string): RichDoc {
  const cleaned = cleanText(text).replace(/\r\n?/g, "\n").slice(0, MAX_DOCUMENT_CHARS);
  if (!cleaned) return { blocks: [] };
  const blocks: RichBlock[] = [];
  for (const rawLine of cleaned.split("\n").slice(0, MAX_BLOCKS)) {
    const tabs = /^\t*/.exec(rawLine)?.[0].length ?? 0;
    let line = rawLine.slice(tabs).trimEnd();
    if (!line.trim()) {
      // A blank line between two paragraphs is the designer's own spacing and is kept as an empty
      // paragraph: the server drops it when it renders, but dropping it HERE would silently
      // reflow the text a designer is looking at while they paste it.
      blocks.push(makeBlock());
      continue;
    }
    const level = Math.min(MAX_LIST_DEPTH, tabs);
    const heading = PASTE_HEADING.exec(line);
    if (heading) {
      blocks.push(makeBlock({ kind: "HEADING", level: heading[1].length, spans: [makeSpan(line.slice(heading[0].length))] }));
      continue;
    }
    if (PASTE_QUOTE.test(line)) {
      blocks.push(makeBlock({ kind: "QUOTE", spans: [makeSpan(line.replace(PASTE_QUOTE, ""))] }));
      continue;
    }
    const ordered = PASTE_ORDERED.exec(line);
    if (ordered) {
      blocks.push(makeBlock({ kind: "ORDERED_ITEM", level, spans: [makeSpan(line.slice(ordered[0].length))] }));
      continue;
    }
    if (PASTE_BULLET.test(line)) {
      line = line.replace(PASTE_BULLET, "");
      blocks.push(makeBlock({ kind: "BULLET_ITEM", level, spans: [makeSpan(line)] }));
      continue;
    }
    blocks.push(makeBlock({ spans: [makeSpan(line)] }));
  }
  return { blocks };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Serialising
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The canonical storage form — byte-for-byte what `rich_text.to_json` produces.
 *
 * Defaults are omitted (`align` only when it is not LEFT, `level` only when it is not 0, `marks`
 * only when there are any) for two reasons: it keeps the JSON column small, and — the one that
 * matters here — it makes {@link storedSignature} a reliable equality test between a document this
 * editor emitted and the same document handed back by the server. An extra `"align":"LEFT"` on this
 * side would make every field look changed the moment it was reloaded.
 */
export function toStored(doc: RichDoc): StoredRichDoc {
  return {
    blocks: doc.blocks.map((block) => {
      const storedSpan = (span: RichSpan): StoredRichSpan =>
        span.marks.length ? { text: span.text, marks: sortMarks(span.marks) } : { text: span.text };
      const entry: StoredRichBlock = {
        kind: block.kind,
        spans: block.spans.map(storedSpan)
      };
      if (block.align !== "LEFT") entry.align = block.align;
      if (block.level) entry.level = block.level;
      // Emitted in the same position and shape as the server's `to_json`, with `spans` left
      // present-and-empty rather than omitted: `storedSignature` compares two documents by their
      // serialised JSON, and an absent key is a different string from an empty array.
      if (block.kind === "TABLE") {
        entry.rows = block.rows.map((row) => row.map((cell) => cell.map(storedSpan)));
      }
      if (block.kind === "IMAGE") {
        entry.media = block.media;
        // Written only when it differs from the default, like `align` and `level` above — and in
        // the same ORDER and on the same condition as `rich_text.to_json`, because
        // `storedSignature` compares two documents by their serialised JSON and an extra key on
        // this side would make every field look changed the moment the server handed it back.
        if (block.widthPct !== IMAGE_DEFAULT_WIDTH_PCT) entry.widthPct = block.widthPct;
      }
      return entry;
    })
  };
}

/**
 * The value to write into the field, or null when the document holds no text.
 *
 * Null rather than a document of empty paragraphs, because that is exactly what `coerce_value` on
 * the server stores for an empty rich-text field (`if is_empty(raw): return None, None`). Emitting
 * the same thing the server would store is what makes the round trip stable: the value that comes
 * back after a save is identical to the value that went out, so {@link storedSignature} matches and
 * the editor does not re-seed itself — see the caret note in `RichTextEditor.tsx`.
 */
export function toStoredValue(doc: RichDoc): StoredRichDoc | null {
  return isEmptyDoc(doc) ? null : toStored(doc);
}

/**
 * The text with every mark discarded — the counterpart of `to_plain`, list markers included.
 *
 * The markers matter: this string is what a CSV export, a search index and a collection row's title
 * receive, and a bulleted list flattened to bare lines reads as one run-on sentence.
 */
export function toPlain(doc: RichDoc): string {
  const lines: string[] = [];
  let ordinal = 0;
  for (const block of doc.blocks) {
    if (block.kind === "IMAGE") {
      // The CAPTION is the text of a photograph. A search index and a collection row's title want
      // the words a person wrote, and "[image]" is not one of them; a caption-less picture
      // contributes nothing, which is correct — it has no text.
      ordinal = 0;
      const caption = blockText(block).trim();
      if (caption) lines.push(caption);
      continue;
    }
    if (block.kind === "TABLE") {
      // One line per row, cells joined by " | ". A tab would be invisible in a search index and
      // would make two adjacent cells read as one word in a CSV export.
      ordinal = 0;
      for (const row of block.rows) {
        lines.push(row.map((cell) => cell.map((span) => span.text).join("").trim()).join(" | "));
      }
      continue;
    }
    const text = blockText(block).trim();
    if (block.kind === "ORDERED_ITEM") {
      ordinal += 1;
      lines.push(`${"  ".repeat(block.level)}${ordinal}. ${text}`);
      continue;
    }
    ordinal = 0;
    if (block.kind === "BULLET_ITEM") lines.push(`${"  ".repeat(block.level)}• ${text}`);
    else lines.push(text);
  }
  return lines.join("\n").trim();
}

/**
 * The number the report will print beside each ORDERED_ITEM — null for every other block.
 *
 * THE EDITOR MUST NOT LET THE BROWSER NUMBER ITS OWN LISTS, because the browser and the report
 * writers count differently and the designer would be reading one set of numbers on screen and
 * shipping another in the .docx. This function reproduces `to_report_blocks` exactly: consecutive
 * ordered items are one list, an EMPTY block between two of them does not break the run (the server
 * skips empty blocks before it decides), and anything else — a paragraph, a heading, a bulleted
 * item — flushes the list and restarts the count at one.
 *
 * Nesting level is deliberately ignored here, and that is not an oversight on this side: the report
 * model's `BulletListBlock` carries `items` and `ordered` and has no per-item depth at all, so the
 * generated file numbers a sub-item in sequence with its parents. Numbering the editor any other
 * way would be the editor lying about the document.
 */
export function orderedNumbers(doc: RichDoc): Array<number | null> {
  let counter = 0;
  return doc.blocks.map((block) => {
    // Through `isBlockEmpty` and not `blockText`, so a photograph with no caption is seen as the
    // content it is: `to_report_blocks` flushes the pending list when it meets one, restarting the
    // count at 1, and an editor that read it as an empty block would show the designer "3." beside
    // an item the file prints as "1.".
    if (isBlockEmpty(block)) return null;
    if (block.kind === "ORDERED_ITEM") {
      counter += 1;
      return counter;
    }
    counter = 0;
    return null;
  });
}

/** A one-line plain-text preview, for a list row or a collection's label — mirrors `summary`. */
export function richSummary(raw: unknown, limit = 160): string {
  const text = toPlain(fromStored(raw)).split(/\s+/).filter(Boolean).join(" ");
  return text.length <= limit ? text : `${text.slice(0, limit - 1).trimEnd()}…`;
}

/**
 * Whether a stored value counts as unfilled — the exact rule the server's `_is_filled` applies.
 *
 * A document of empty paragraphs is empty. An editor that was focused and left alone still holds
 * `{"blocks":[{"kind":"PARAGRAPH","spans":[]}]}`, and counting that as a filled required field would
 * let a designer submit a report with a blank introduction while the completeness gate read 100%.
 */
export function isStoredRichTextEmpty(raw: unknown): boolean {
  if (raw === null || raw === undefined) return true;
  if (typeof raw === "string") return !raw.trim();
  return isEmptyDoc(fromStored(raw));
}

/**
 * A stable string identifying a stored value's DOCUMENT, independent of how it was spelled.
 *
 * This is the single comparison the controlled-value caret fix rests on. `null`, `""`,
 * `{"blocks":[]}` and `{"blocks":[{"kind":"PARAGRAPH","spans":[]}]}` are four spellings of an empty
 * field and all four must compare equal, or a save that normalises one into another re-seeds the
 * editor and throws the caret to position zero mid-sentence. Both sides of every comparison go
 * through `fromStored` and then `toStored`, so the spelling cannot matter.
 */
export function storedSignature(raw: unknown): string {
  const doc = fromStored(raw);
  // An empty document has exactly one signature whatever shape it arrived in.
  if (isEmptyDoc(doc)) return "EMPTY";
  return JSON.stringify(toStored(doc));
}

/* ────────────────────────────────────────────────────────────────────────────
 * Counts — the live readout under the editor
 * ──────────────────────────────────────────────────────────────────────────── */

export type RichCounts = { words: number; characters: number; blocks: number };

/**
 * Words and characters as a person counts them, not as a parser does.
 *
 * `characters` counts the TEXT — not the JSON, not the marks. A designer working to "no more than
 * 500 words for the executive summary" is counting what will be printed, and a figure that included
 * the formatting would be a number nobody can act on. Words are runs of non-whitespace, which is
 * what every word processor means by the word and is close enough to right for Devanagari and Odia
 * prose, where the script separates words with spaces exactly as Latin does.
 */
export function countDoc(doc: RichDoc): RichCounts {
  let characters = 0;
  let words = 0;
  for (const block of doc.blocks) {
    const text = blockText(block);
    characters += text.length;
    for (const token of text.split(/\s+/)) if (token) words += 1;
  }
  return { words, characters, blocks: doc.blocks.length };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Commands — pure functions of (document, selection)
 * ──────────────────────────────────────────────────────────────────────────── */

function replaceBlock(doc: RichDoc, index: number, next: RichBlock): RichDoc {
  const blocks = doc.blocks.slice();
  blocks[index] = next;
  return { blocks };
}

function withSpans(block: RichBlock, spans: readonly RichSpan[]): RichBlock {
  return { ...block, spans: mergeSpans(spans) };
}

/** The indices of every block the range touches, inclusive at both ends. */
export function blocksInRange(doc: RichDoc, range: DocRange): number[] {
  if (!doc.blocks.length) return [];
  const { start, end } = orderedRange(clampRange(doc, range));
  const out: number[] = [];
  for (let index = start.block; index <= end.block; index += 1) out.push(index);
  return out;
}

/**
 * Is `mark` on every character of the selection?
 *
 * EVERY character, not any: a toolbar that lights Bold because one word in a paragraph is bold
 * tells the designer they can un-bold with one press, and the press bolds the rest instead. The
 * "all of it" reading is also what makes the toggle its own inverse.
 */
export function isMarkActive(doc: RichDoc, range: DocRange, mark: RichMark, pending: readonly RichMark[] | null): boolean {
  if (isCollapsed(range)) {
    const point = clampPoint(doc, range.focus);
    const marks = pending ?? marksAt(doc.blocks[point.block], point.offset);
    return marks.includes(mark);
  }
  const { start, end } = orderedRange(clampRange(doc, range));
  let sawText = false;
  for (let index = start.block; index <= end.block; index += 1) {
    const block = doc.blocks[index];
    const from = index === start.block ? start.offset : 0;
    const to = index === end.block ? end.offset : blockLength(block);
    for (const span of sliceSpans(block.spans, from, to)) {
      sawText = true;
      if (!span.marks.includes(mark)) return false;
    }
  }
  return sawText;
}

/**
 * Add or remove a mark across the selection.
 *
 * A COLLAPSED selection cannot change any text, so it returns the document untouched and the caller
 * keeps the result as a "pending mark" that the next inserted character will carry. That is what
 * makes Ctrl+B before typing work the way it does in every other editor, and it is why
 * {@link isMarkActive} takes the pending set: the toolbar has to show Bold as on while there is not
 * one bold character in the document yet.
 */
export function toggleMark(doc: RichDoc, range: DocRange, mark: RichMark): EditResult {
  if (isCollapsed(range)) return { doc, selection: range };
  const active = isMarkActive(doc, range, mark, null);
  return applyMark(doc, range, mark, !active);
}

export function applyMark(doc: RichDoc, range: DocRange, mark: RichMark, on: boolean): EditResult {
  const clamped = clampRange(doc, range);
  const { start, end } = orderedRange(clamped);
  const excluded = markExcludes(mark);
  const blocks = doc.blocks.slice();
  for (let index = start.block; index <= end.block; index += 1) {
    const block = blocks[index];
    const length = blockLength(block);
    const from = index === start.block ? start.offset : 0;
    const to = index === end.block ? end.offset : length;
    if (to <= from) continue;
    const middle = sliceSpans(block.spans, from, to).map((span) => ({
      text: span.text,
      // Superscript displaces subscript and the other way round — see {@link markExcludes}. Applied
      // on the way ON only: turning superscript off must not quietly turn subscript off with it.
      marks: on
        ? sortMarks([...span.marks.filter((item) => item !== excluded), mark])
        : span.marks.filter((item) => item !== mark)
    }));
    blocks[index] = withSpans(block, [...sliceSpans(block.spans, 0, from), ...middle, ...sliceSpans(block.spans, to, length)]);
  }
  return { doc: { blocks }, selection: clamped };
}

/**
 * Remove every mark from the selection AND return its blocks to a left-aligned paragraph.
 *
 * Both halves, deliberately. "Clear formatting" is pressed by somebody looking at a paragraph that
 * came out of a paste or an experiment and wanting it to look like the rest of the document, and a
 * control that stripped the bold but left the text centred, indented and still a heading would send
 * them hunting through four more buttons. The list nesting goes with it for the same reason.
 */
export function clearFormatting(doc: RichDoc, range: DocRange): EditResult {
  const clamped = clampRange(doc, range);
  const { start, end } = orderedRange(clamped);
  const blocks = doc.blocks.slice();
  for (let index = start.block; index <= end.block; index += 1) {
    const block = blocks[index];
    const length = blockLength(block);
    const from = index === start.block ? start.offset : 0;
    const to = index === end.block ? end.offset : length;
    const middle = sliceSpans(block.spans, from, to).map((span) => ({ text: span.text, marks: [] as RichMark[] }));
    const cleared = withSpans(block, [
      ...sliceSpans(block.spans, 0, from),
      ...middle,
      ...sliceSpans(block.spans, to, length)
    ]);
    // A TABLE and an IMAGE keep their kind. "Clear formatting" means "make this look like the rest
    // of the document", not "delete my grid" — and re-kinding either to PARAGRAPH does delete it,
    // because `toStored` writes `rows` and `media` only for the kind that owns them. A designer who
    // pressed this to strip a bold run out of a caption would have found the photograph gone.
    blocks[index] = isStructuralKind(block.kind)
      ? cleared
      : { ...cleared, kind: "PARAGRAPH", align: "LEFT", level: 0 };
  }
  return { doc: { blocks }, selection: clamped };
}

export function setBlockKind(doc: RichDoc, range: DocRange, kind: RichBlockKind, level = 0): EditResult {
  const clamped = clampRange(doc, range);
  const blocks = doc.blocks.slice();
  for (const index of blocksInRange(doc, clamped)) {
    const block = blocks[index];
    // Same protection as `clearFormatting`: a selection that runs from a paragraph through a table
    // or a photograph and back must restyle the prose and leave the two structural kinds alone.
    // Their own controls (Delete the table / Remove the picture) are how they are removed, and a
    // heading button that silently discarded a grid would be a very expensive misclick.
    if (isStructuralKind(block.kind)) continue;
    blocks[index] = {
      ...block,
      kind,
      // A list item keeps the depth it had when it becomes another kind of list item, so switching a
      // nested bullet run to numbers does not flatten it. Anything else resets to 0, because a
      // heading at depth 2 has no meaning in this model.
      level: kind === "HEADING" ? clampLevel("HEADING", level || 1) : isListKind(kind) ? clampLevel(kind, isListKind(block.kind) ? block.level : 0) : 0
    };
  }
  return { doc: { blocks }, selection: clamped };
}

export function setAlign(doc: RichDoc, range: DocRange, align: RichAlign): EditResult {
  const clamped = clampRange(doc, range);
  const blocks = doc.blocks.slice();
  for (const index of blocksInRange(doc, clamped)) blocks[index] = { ...blocks[index], align };
  return { doc: { blocks }, selection: clamped };
}

/**
 * Turn the selection into a list of that kind — or, if it already is one, back into paragraphs.
 *
 * The "already one" test is ALL of the touched blocks, matching {@link isMarkActive}'s reading: a
 * mixed selection becomes a list, and only a selection that is entirely a bulleted list is turned
 * back into prose by the bullet button.
 */
export function toggleList(doc: RichDoc, range: DocRange, ordered: boolean): EditResult {
  const kind: RichBlockKind = ordered ? "ORDERED_ITEM" : "BULLET_ITEM";
  const indices = blocksInRange(doc, range);
  const allAlready = indices.length > 0 && indices.every((index) => doc.blocks[index].kind === kind);
  return setBlockKind(doc, range, allAlready ? "PARAGRAPH" : kind);
}

/**
 * One step of list nesting, in or out.
 *
 * Only list items move. A paragraph has no `level` in this model — there is no indented-paragraph
 * block kind — so indenting one would either do nothing or invent a block kind the .docx writer
 * cannot print. Doing nothing, visibly (the toolbar disables the control), is the honest option.
 */
export function shiftIndent(doc: RichDoc, range: DocRange, delta: number): EditResult {
  const clamped = clampRange(doc, range);
  const blocks = doc.blocks.slice();
  let changed = false;
  for (const index of blocksInRange(doc, clamped)) {
    const block = blocks[index];
    if (!isListKind(block.kind)) continue;
    const level = Math.max(0, Math.min(MAX_LIST_DEPTH, block.level + delta));
    if (level === block.level) continue;
    blocks[index] = { ...block, level };
    changed = true;
  }
  return { doc: changed ? { blocks } : doc, selection: clamped };
}

export function canShiftIndent(doc: RichDoc, range: DocRange, delta: number): boolean {
  return blocksInRange(doc, range).some((index) => {
    const block = doc.blocks[index];
    if (!isListKind(block.kind)) return false;
    const level = block.level + delta;
    return level >= 0 && level <= MAX_LIST_DEPTH;
  });
}

/** Delete everything between the two ends of the range, merging the blocks they sit in. */
export function deleteRange(doc: RichDoc, range: DocRange): EditResult {
  const clamped = clampRange(doc, range);
  if (isCollapsed(clamped)) return { doc, selection: clamped };
  const { start, end } = orderedRange(clamped);
  const startBlock = doc.blocks[start.block];
  const endBlock = doc.blocks[end.block];

  if (start.block === end.block) {
    const length = blockLength(startBlock);
    const next = withSpans(startBlock, [...sliceSpans(startBlock.spans, 0, start.offset), ...sliceSpans(startBlock.spans, end.offset, length)]);
    return { doc: replaceBlock(doc, start.block, next), selection: collapsedAt(start) };
  }

  // The surviving block is the one the selection STARTED in, and it keeps its kind, alignment and
  // level. Selecting from the middle of a heading into the middle of the paragraph below and
  // pressing Delete leaves a heading — which is what a designer means by it. Keeping the END
  // block's kind instead would silently demote the heading.
  const merged = withSpans(startBlock, [
    ...sliceSpans(startBlock.spans, 0, start.offset),
    ...sliceSpans(endBlock.spans, end.offset, blockLength(endBlock))
  ]);
  const blocks = doc.blocks.slice();
  blocks.splice(start.block, end.block - start.block + 1, merged);
  return { doc: { blocks }, selection: collapsedAt(start) };
}

/**
 * Insert a run of text at the selection, carrying `marks`.
 *
 * Any newline in `text` is flattened to a space rather than split into blocks. Callers that mean
 * "several paragraphs" call {@link insertDoc}; this one is what a keystroke and a find-and-replace
 * use, and a "\n" reaching a span is the one thing `from_json` has to repair on the server.
 */
export function insertText(doc: RichDoc, range: DocRange, text: string, marks: readonly RichMark[] = []): EditResult {
  const cleaned = cleanText(text).replace(/[\r\n]+/g, " ");
  const afterDelete = deleteRange(doc, range);
  if (!cleaned) return afterDelete;
  const point = afterDelete.selection.focus;
  const block = afterDelete.doc.blocks[point.block] ?? makeBlock();
  const length = blockLength(block);
  const next = withSpans(block, [
    ...sliceSpans(block.spans, 0, point.offset),
    makeSpan(cleaned, marks),
    ...sliceSpans(block.spans, point.offset, length)
  ]);
  const blocks = afterDelete.doc.blocks.length ? afterDelete.doc.blocks.slice() : [next];
  blocks[point.block] = next;
  return { doc: { blocks }, selection: collapsedAt({ block: point.block, offset: point.offset + cleaned.length }) };
}

/**
 * Insert a whole document at the selection — the paste path.
 *
 * The first incoming block joins the tail of the block the caret is in and the last one joins its
 * head, which is what makes pasting a sentence into the middle of a paragraph produce one paragraph
 * rather than three. A single-block paste therefore never changes the target block's kind: pasting
 * a line into a bulleted item leaves it a bulleted item.
 */
export function insertDoc(doc: RichDoc, range: DocRange, incoming: RichDoc): EditResult {
  if (!incoming.blocks.length) return deleteRange(doc, range);
  const afterDelete = deleteRange(doc, range);
  const point = afterDelete.selection.focus;
  const target = afterDelete.doc.blocks[point.block] ?? makeBlock();
  const length = blockLength(target);
  const head = sliceSpans(target.spans, 0, point.offset);
  const tail = sliceSpans(target.spans, point.offset, length);

  if (incoming.blocks.length === 1) {
    const only = incoming.blocks[0];
    const next = withSpans(target, [...head, ...only.spans, ...tail]);
    const blocks = afterDelete.doc.blocks.slice();
    blocks[point.block] = next;
    return {
      doc: { blocks },
      selection: collapsedAt({ block: point.block, offset: point.offset + blockLength(only) })
    };
  }

  const first = incoming.blocks[0];
  const last = incoming.blocks[incoming.blocks.length - 1];
  const middle = incoming.blocks.slice(1, -1);
  const opening = withSpans(target, [...head, ...first.spans]);
  const closing = withSpans({ ...last }, [...last.spans, ...tail]);
  const blocks = afterDelete.doc.blocks.slice();
  blocks.splice(point.block, 1, opening, ...middle.map((block) => ({ ...block })), closing);
  return {
    doc: { blocks: blocks.slice(0, MAX_BLOCKS) },
    selection: collapsedAt({ block: point.block + 1 + middle.length, offset: blockLength(last) })
  };
}

/**
 * Enter.
 *
 * Three special cases, each of which is a thing every designer expects and no browser agrees about:
 *
 * - **Enter in an EMPTY list item leaves the list** rather than adding another empty one. Without
 *   it the only way out of a list is to reach for the toolbar, and the two presses of Enter that
 *   everyone types at the end of a list produce two empty bullets in the report.
 * - **Enter at the START of a heading opens a paragraph above it** and leaves the heading alone.
 *   Splitting a heading at offset 0 the ordinary way would demote it to a paragraph, which is the
 *   opposite of what somebody making room above a heading is asking for.
 * - **Enter anywhere else in a heading starts a PARAGRAPH.** Nobody writes two consecutive headings
 *   by pressing Enter; they write a heading and then its text.
 */
/**
 * Put a blank table into the document at the caret, and a paragraph after it.
 *
 * THE TRAILING PARAGRAPH IS NOT COSMETIC. A table is edited by the browser rather than by this
 * model (see the note in `RichTextEditor.tsx`), so there is no keystroke that can create a block
 * AFTER it — Enter inside a cell moves to the next cell, and there is nothing below the last row
 * to click into. A designer who inserted a table as the last thing in a field would have no way to
 * write another sentence, in a field whose whole purpose is prose.
 *
 * The caret is left on that paragraph rather than in the first cell: the first cell is one click
 * away and obvious, whereas a caret parked invisibly inside a `<th>` reads as the editor having
 * done nothing.
 */
export function insertTable(doc: RichDoc, range: DocRange, rows = 3, columns = 3): EditResult {
  const point = clampPoint(doc, orderedRange(range).start);
  const blocks = doc.blocks.slice();
  const table = makeTable(rows, columns);
  const after = makeBlock();
  // After the caret's block, so inserting mid-document does not cut a paragraph in half.
  blocks.splice(point.block + 1, 0, table, after);
  return {
    doc: { blocks: blocks.slice(0, MAX_BLOCKS) },
    selection: collapsedAt({ block: Math.min(point.block + 2, MAX_BLOCKS - 1), offset: 0 })
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Table structure
 *
 * These take an explicit `block`, `row` and `column` rather than reading the selection, because
 * the caret inside a table lives in the DOM and not in the model — see the note in
 * `RichTextEditor.tsx`. The editor finds the cell the caret is in and tells these functions; they
 * stay pure, and the awkwardness is confined to the one place that already owns it.
 * ──────────────────────────────────────────────────────────────────────────── */

/** Every row squared off to the widest, so an insert cannot produce a ragged grid. */
function squared(rows: RichRow[]): RichRow[] {
  const width = rows.reduce((widest, row) => Math.max(widest, row.length), 0);
  return rows.map((row) => {
    const next = row.slice();
    while (next.length < width) next.push([]);
    return next;
  });
}

function withRows(doc: RichDoc, block: number, rows: RichRow[]): EditResult {
  const target = doc.blocks[block];
  if (!target || target.kind !== "TABLE") return { doc, selection: collapsedAt({ block, offset: 0 }) };
  return {
    doc: { blocks: doc.blocks.map((b, i) => (i === block ? { ...b, rows } : b)) },
    selection: collapsedAt({ block, offset: 0 })
  };
}

/** Insert a blank row. `at` is the index it will occupy. */
export function insertTableRow(doc: RichDoc, block: number, at: number): EditResult {
  const target = doc.blocks[block];
  if (!target || target.kind !== "TABLE") return withRows(doc, block, []);
  const rows = squared(target.rows);
  if (rows.length >= MAX_TABLE_ROWS) return withRows(doc, block, rows);
  const width = rows[0]?.length ?? 1;
  const index = Math.max(0, Math.min(rows.length, at));
  rows.splice(index, 0, Array.from({ length: width }, () => [] as RichCell));
  return withRows(doc, block, rows);
}

/**
 * Remove a row.
 *
 * THE HEADER CANNOT BE THE LAST THING LEFT. A table needs its header row — it is what becomes
 * `TableColumn.header` in the report — so the last two rows are protected: removing down to a bare
 * header would print a table with column titles and nothing under them, and removing the header
 * itself would silently promote the first data row into the column headings.
 */
export function removeTableRow(doc: RichDoc, block: number, at: number): EditResult {
  const target = doc.blocks[block];
  if (!target || target.kind !== "TABLE") return withRows(doc, block, []);
  const rows = squared(target.rows);
  if (rows.length <= 2 || at < 0 || at >= rows.length) return withRows(doc, block, rows);
  rows.splice(at, 1);
  return withRows(doc, block, rows);
}

/** Insert a blank column. `at` is the index it will occupy in every row. */
export function insertTableColumn(doc: RichDoc, block: number, at: number): EditResult {
  const target = doc.blocks[block];
  if (!target || target.kind !== "TABLE") return withRows(doc, block, []);
  const rows = squared(target.rows);
  const width = rows[0]?.length ?? 0;
  if (width >= MAX_TABLE_COLUMNS) return withRows(doc, block, rows);
  const index = Math.max(0, Math.min(width, at));
  return withRows(doc, block, rows.map((row) => {
    const next = row.slice();
    next.splice(index, 0, []);
    return next;
  }));
}

/** Remove a column. The last one is protected — a table with no columns is not a table. */
export function removeTableColumn(doc: RichDoc, block: number, at: number): EditResult {
  const target = doc.blocks[block];
  if (!target || target.kind !== "TABLE") return withRows(doc, block, []);
  const rows = squared(target.rows);
  const width = rows[0]?.length ?? 0;
  if (width <= 1 || at < 0 || at >= width) return withRows(doc, block, rows);
  return withRows(doc, block, rows.map((row) => row.filter((_, i) => i !== at)));
}

/** Remove the whole table, leaving the document with one fewer block. */
export function removeTable(doc: RichDoc, block: number): EditResult {
  const target = doc.blocks[block];
  if (!target || target.kind !== "TABLE") return { doc, selection: collapsedAt({ block, offset: 0 }) };
  const blocks = doc.blocks.filter((_, i) => i !== block);
  return {
    doc: { blocks: blocks.length ? blocks : [makeBlock()] },
    selection: collapsedAt({ block: Math.max(0, block - 1), offset: 0 })
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Inline photographs
 *
 * The block's `media` is the picture and its `spans` are the CAPTION, which is why these three are
 * the only image-specific commands there are: everything else a designer does to a caption —
 * typing in it, bolding a word of it, moving the caret through it — is the ordinary prose path,
 * because a caption IS prose and `blockText` returns it.
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Place a photograph at the caret, and leave the caret in its caption.
 *
 * AFTER the caret's block rather than splitting it, exactly as {@link insertTable} does: a
 * photograph dropped into the middle of a sentence would cut the sentence in two, and nobody who
 * presses "insert picture" means that.
 *
 * NO TRAILING PARAGRAPH, and that is the one place this differs from `insertTable`. A table needs
 * one because the model's caret cannot address a cell, so no keystroke inside a table can ever
 * create the block after it and a table inserted last would end the field. An image block's own
 * text is its caption and IS addressable, so Enter in the caption opens a paragraph below (see
 * {@link splitBlock}) — and inserting a blank paragraph here instead would leave a stray empty
 * line under every photograph a designer chose not to caption.
 */
export function insertImage(
  doc: RichDoc,
  range: DocRange,
  media: string,
  caption = "",
  widthPct = IMAGE_DEFAULT_WIDTH_PCT
): EditResult {
  const id = cleanText(media).trim().slice(0, 64);
  // Refused rather than inserted blank. A block with no id is dropped by both parsers, so a
  // placeholder here would vanish at the next reload and the designer would blame the upload.
  if (!id) return { doc, selection: range };
  const point = clampPoint(doc, orderedRange(range).start);
  const blocks = doc.blocks.slice();
  const text = cleanText(caption).replace(/[\r\n]+/g, " ");
  blocks.splice(point.block + 1, 0, makeBlock({
    kind: "IMAGE",
    media: id,
    widthPct: Math.max(IMAGE_MIN_WIDTH_PCT, Math.min(IMAGE_MAX_WIDTH_PCT, widthPct)),
    spans: text ? [makeSpan(text)] : []
  }));
  return {
    doc: { blocks: blocks.slice(0, MAX_BLOCKS) },
    selection: collapsedAt({ block: Math.min(point.block + 1, MAX_BLOCKS - 1), offset: text.length })
  };
}

/** Remove the photograph at `block`, its caption with it. The caret lands on the block before. */
export function removeImage(doc: RichDoc, block: number): EditResult {
  const target = doc.blocks[block];
  if (!target || target.kind !== "IMAGE") return { doc, selection: collapsedAt({ block, offset: 0 }) };
  const blocks = doc.blocks.filter((_, index) => index !== block);
  const next = blocks.length ? blocks : [makeBlock()];
  const landing = Math.max(0, Math.min(next.length - 1, block - 1));
  return { doc: { blocks: next }, selection: collapsedAt({ block: landing, offset: blockLength(next[landing]) }) };
}

/**
 * Widen or narrow the photograph at `block` by one step, clamped to the model's own bounds.
 *
 * A step rather than a free number, because this is the only size control there is and a text box
 * asking a designer for a percentage of a text column they cannot see is a question nobody can
 * answer. The clamp matches the server's: a client that stored 120 would show one width and print
 * another.
 */
export function setImageWidth(doc: RichDoc, block: number, delta: number): EditResult {
  const target = doc.blocks[block];
  const selection = collapsedAt({ block, offset: target ? blockLength(target) : 0 });
  if (!target || target.kind !== "IMAGE") return { doc, selection };
  const widthPct = Math.max(
    IMAGE_MIN_WIDTH_PCT,
    Math.min(IMAGE_MAX_WIDTH_PCT, Math.round(target.widthPct + delta))
  );
  if (widthPct === target.widthPct) return { doc, selection };
  return { doc: { blocks: doc.blocks.map((b, i) => (i === block ? { ...b, widthPct } : b)) }, selection };
}

export function splitBlock(doc: RichDoc, range: DocRange): EditResult {
  const afterDelete = deleteRange(doc, range);
  const point = afterDelete.selection.focus;
  const blocks = afterDelete.doc.blocks.slice();
  const block = blocks[point.block] ?? makeBlock();
  const length = blockLength(block);

  // Enter in a CAPTION opens a paragraph under the figure rather than splitting it. Splitting
  // would produce two IMAGE blocks holding the SAME media id — the picture printed twice, from one
  // press of Enter — and there is no reading of "Enter at the end of a caption" that means that.
  if (block.kind === "IMAGE") {
    blocks.splice(point.block + 1, 0, makeBlock());
    return {
      doc: { blocks: blocks.slice(0, MAX_BLOCKS) },
      selection: collapsedAt({ block: Math.min(point.block + 1, MAX_BLOCKS - 1), offset: 0 })
    };
  }

  if (length === 0 && (isListKind(block.kind) || block.kind === "QUOTE")) {
    blocks[point.block] = { ...block, kind: "PARAGRAPH", level: 0 };
    return { doc: { blocks }, selection: collapsedAt(point) };
  }

  if (block.kind === "HEADING" && point.offset === 0 && length > 0) {
    blocks.splice(point.block, 0, makeBlock({ align: block.align }));
    return { doc: { blocks: blocks.slice(0, MAX_BLOCKS) }, selection: collapsedAt({ block: point.block, offset: 0 }) };
  }

  const left = withSpans(block, sliceSpans(block.spans, 0, point.offset));
  const rightKind: RichBlockKind = block.kind === "HEADING" ? "PARAGRAPH" : block.kind;
  const right = makeBlock({
    kind: rightKind,
    align: block.align,
    // `rightKind` can never be HEADING — the line above turns a split heading into a paragraph,
    // because pressing Enter at the end of a title starts body text and not a second title. So the
    // continuation block's `level` is a list nesting depth or nothing at all; the HEADING arm this
    // expression used to carry was a branch the compiler could prove unreachable.
    level: isListKind(rightKind) ? block.level : 0,
    spans: mergeSpans(sliceSpans(block.spans, point.offset, length))
  });
  blocks.splice(point.block, 1, left, right);
  return { doc: { blocks: blocks.slice(0, MAX_BLOCKS) }, selection: collapsedAt({ block: point.block + 1, offset: 0 }) };
}

/** One code point back from `offset`, so a deletion never cuts a surrogate pair in half. */
function stepBack(text: string, offset: number): number {
  if (offset <= 0) return 0;
  const code = text.charCodeAt(offset - 1);
  if (code >= 0xdc00 && code <= 0xdfff && offset >= 2) {
    const high = text.charCodeAt(offset - 2);
    if (high >= 0xd800 && high <= 0xdbff) return offset - 2;
  }
  return offset - 1;
}

function stepForward(text: string, offset: number): number {
  if (offset >= text.length) return text.length;
  const code = text.charCodeAt(offset);
  if (code >= 0xd800 && code <= 0xdbff && offset + 1 < text.length) {
    const low = text.charCodeAt(offset + 1);
    if (low >= 0xdc00 && low <= 0xdfff) return offset + 2;
  }
  return offset + 1;
}

/**
 * Backspace.
 *
 * At the start of a block it does NOT immediately merge with the block above. It walks the block's
 * own formatting back one step first — outdent a nested list item, then turn the item (or the
 * heading, or the quote) into a paragraph, and only then join the previous block. That ladder is
 * what makes Backspace the universal "undo the structure I just made" key: a designer who presses
 * Enter at the end of a bulleted list and gets another bullet backs out of it with one keystroke
 * instead of losing the last word of the previous item.
 */
export function deleteBackward(doc: RichDoc, range: DocRange): EditResult {
  if (!isCollapsed(range)) return deleteRange(doc, range);
  const point = clampPoint(doc, range.focus);
  const block = doc.blocks[point.block];
  if (!block) return { doc, selection: range };

  if (point.offset > 0) {
    const text = blockText(block);
    const from = stepBack(text, point.offset);
    return deleteRange(doc, { anchor: { block: point.block, offset: from }, focus: point });
  }

  if (isListKind(block.kind) && block.level > 0) return shiftIndent(doc, collapsedAt(point), -1);
  // Backspace at the start of a CAPTION removes the whole figure, picture and caption together.
  // This is the only keyboard route to deleting an inline photograph — the toolbar's own control
  // is the other — and the ladder below cannot be used for it: turning the block into a PARAGRAPH
  // would drop `media` and leave the caption sitting under nothing, which reads as a picture that
  // failed to load rather than one that was deleted. It is a single undo step.
  if (block.kind === "IMAGE") return removeImage(doc, point.block);
  // A table is edited by the browser and its caret never reaches model offset 0, so this is
  // unreachable today; it is written down because the ladder's next rung would re-kind the block
  // to PARAGRAPH and take the whole grid with it if that ever changed.
  if (block.kind === "TABLE") return { doc, selection: collapsedAt(point) };
  if (block.kind !== "PARAGRAPH") {
    return {
      doc: replaceBlock(doc, point.block, { ...block, kind: "PARAGRAPH", level: 0 }),
      selection: collapsedAt(point)
    };
  }
  if (point.block === 0) return { doc, selection: collapsedAt(point) };

  const previous = doc.blocks[point.block - 1];
  // Joining into a TABLE would append the paragraph's spans to a block whose spans nothing reads —
  // the words would disappear from the screen and from the file, to one press of Backspace. An
  // IMAGE is safe to join into, because its spans ARE its caption and the text stays visible.
  if (previous.kind === "TABLE") return { doc, selection: collapsedAt(point) };
  const joinAt = blockLength(previous);
  const blocks = doc.blocks.slice();
  blocks.splice(point.block - 1, 2, withSpans(previous, [...previous.spans, ...block.spans]));
  return { doc: { blocks }, selection: collapsedAt({ block: point.block - 1, offset: joinAt }) };
}

export function deleteForward(doc: RichDoc, range: DocRange): EditResult {
  if (!isCollapsed(range)) return deleteRange(doc, range);
  const point = clampPoint(doc, range.focus);
  const block = doc.blocks[point.block];
  if (!block) return { doc, selection: range };
  const text = blockText(block);
  if (point.offset < text.length) {
    const to = stepForward(text, point.offset);
    return deleteRange(doc, { anchor: point, focus: { block: point.block, offset: to } });
  }
  if (point.block >= doc.blocks.length - 1) return { doc, selection: collapsedAt(point) };
  const next = doc.blocks[point.block + 1];
  // The same two structural cases as `deleteBackward`, from the other side. Pulling a TABLE up into
  // a paragraph would keep the paragraph and silently drop the grid, and pulling a paragraph up
  // into a caption — or a caption up into a paragraph, losing the picture — is not what Delete at
  // the end of a line means to anybody.
  if (block.kind === "IMAGE" || block.kind === "TABLE" || next.kind === "IMAGE" || next.kind === "TABLE") {
    return { doc, selection: collapsedAt(point) };
  }
  const blocks = doc.blocks.slice();
  blocks.splice(point.block, 2, withSpans(block, [...block.spans, ...next.spans]));
  return { doc: { blocks }, selection: collapsedAt(point) };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Markdown-style input rules
 * ──────────────────────────────────────────────────────────────────────────── */

type InputRule = { pattern: RegExp; apply: (block: RichBlock, match: RegExpExecArray) => Partial<RichBlock> };

/**
 * The four prefixes that turn themselves into structure when the space is typed.
 *
 * They fire only at the very start of a PARAGRAPH, never mid-line and never inside a block that is
 * already something else. That restriction is the difference between a convenience and a trap: a
 * designer typing "1. Cotton, 2. Silk" as prose in the middle of a sentence must not have the
 * sentence turn into a numbered list under them, and somebody writing "> " inside an existing quote
 * means the character, not another quote.
 */
const INPUT_RULES: InputRule[] = [
  { pattern: /^(#{1,4}) $/, apply: (_block, match) => ({ kind: "HEADING", level: match[1].length }) },
  { pattern: /^[-*+] $/, apply: () => ({ kind: "BULLET_ITEM", level: 0 }) },
  { pattern: /^\d{1,3}[.)] $/, apply: () => ({ kind: "ORDERED_ITEM", level: 0 }) },
  { pattern: /^> $/, apply: () => ({ kind: "QUOTE", level: 0 }) }
];

/**
 * Apply a markdown-style rule if the caret is sitting just after one of the prefixes above.
 *
 * Returns null when nothing matched, so the caller can leave the document exactly as the browser
 * left it — which matters, because the alternative is re-rendering the editing surface after every
 * space bar and putting the caret back by hand a hundred times a paragraph.
 */
export function applyInputRule(doc: RichDoc, range: DocRange): EditResult | null {
  if (!isCollapsed(range)) return null;
  const point = clampPoint(doc, range.focus);
  const block = doc.blocks[point.block];
  if (!block || block.kind !== "PARAGRAPH" || block.level !== 0) return null;
  const prefix = blockText(block).slice(0, point.offset);
  for (const rule of INPUT_RULES) {
    const match = rule.pattern.exec(prefix);
    if (!match) continue;
    const length = blockLength(block);
    const trimmed = withSpans(block, sliceSpans(block.spans, prefix.length, length));
    const next: RichBlock = { ...trimmed, ...rule.apply(block, match) };
    return { doc: replaceBlock(doc, point.block, next), selection: collapsedAt({ block: point.block, offset: 0 }) };
  }
  return null;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Find and replace
 * ──────────────────────────────────────────────────────────────────────────── */

export type RichMatch = { block: number; start: number; end: number };
export type FindOptions = { caseSensitive?: boolean; wholeWord?: boolean };

const WORD_CHARACTER = /[\p{L}\p{N}_]/u;

function isWordBoundary(text: string, index: number): boolean {
  if (index < 0 || index >= text.length) return true;
  return !WORD_CHARACTER.test(text[index]);
}

/**
 * Every occurrence of `query`, in document order.
 *
 * Matching is per BLOCK and never across a block boundary, because the blocks are separate
 * paragraphs in the printed document — a phrase that happens to span the end of one paragraph and
 * the start of the next is not a phrase, and replacing it would silently join two paragraphs.
 */
export function findMatches(doc: RichDoc, query: string, options: FindOptions = {}): RichMatch[] {
  if (!query) return [];
  const needle = options.caseSensitive ? query : query.toLowerCase();
  const out: RichMatch[] = [];
  doc.blocks.forEach((block, index) => {
    const raw = blockText(block);
    const haystack = options.caseSensitive ? raw : raw.toLowerCase();
    let from = 0;
    for (;;) {
      const at = haystack.indexOf(needle, from);
      if (at === -1) break;
      const end = at + query.length;
      if (!options.wholeWord || (isWordBoundary(raw, at - 1) && isWordBoundary(raw, end))) {
        out.push({ block: index, start: at, end });
      }
      // Advance by one so overlapping occurrences of a repeated string are all reported; the caller
      // replaces from the END backwards, so overlaps cannot corrupt the offsets of earlier matches.
      from = at + Math.max(1, query.length);
    }
  });
  return out;
}

export function matchToRange(match: RichMatch): DocRange {
  return { anchor: { block: match.block, offset: match.start }, focus: { block: match.block, offset: match.end } };
}

/** Replace one match, keeping the marks of the text it replaced. */
export function replaceMatch(doc: RichDoc, match: RichMatch, replacement: string): EditResult {
  const block = doc.blocks[match.block];
  if (!block) return { doc, selection: collapsedAt({ block: 0, offset: 0 }) };
  const marks = marksAt(block, match.start + 1);
  return insertText(doc, matchToRange(match), replacement, marks);
}

/**
 * Replace every occurrence, working from the END of the document backwards.
 *
 * Backwards is not a stylistic choice: replacing forwards shifts every later offset by the
 * difference in length between the needle and the replacement, so the second replacement onwards
 * would land in the wrong place — subtly, invisibly, and worse the longer the document.
 */
export function replaceAll(doc: RichDoc, query: string, replacement: string, options: FindOptions = {}): { doc: RichDoc; count: number } {
  const matches = findMatches(doc, query, options);
  if (!matches.length) return { doc, count: 0 };
  let next = doc;
  for (let index = matches.length - 1; index >= 0; index -= 1) {
    next = replaceMatch(next, matches[index], replacement).doc;
  }
  return { doc: next, count: matches.length };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The toolbar's view of the selection
 * ──────────────────────────────────────────────────────────────────────────── */

export type RichToolbarState = {
  marks: RichMark[];
  /** Null when the selection spans blocks of different kinds — no button lights in that case. */
  kind: RichBlockKind | null;
  level: number | null;
  align: RichAlign | null;
  canIndent: boolean;
  canOutdent: boolean;
};

/**
 * Everything the toolbar needs to draw itself against the current selection.
 *
 * A MIXED selection reports null rather than the first block's value. A toolbar that showed
 * "Heading 2" while three paragraphs and a heading were selected would be telling the designer
 * something untrue about their own document, and the next press — which applies to all four blocks
 * — would come as a surprise.
 */
export function toolbarState(doc: RichDoc, range: DocRange, pending: readonly RichMark[] | null): RichToolbarState {
  const indices = blocksInRange(doc, range);
  const first = indices.length ? doc.blocks[indices[0]] : null;
  const sameKind = first !== null && indices.every((index) => doc.blocks[index].kind === first.kind);
  const sameLevel = first !== null && indices.every((index) => doc.blocks[index].level === first.level);
  const sameAlign = first !== null && indices.every((index) => doc.blocks[index].align === first.align);
  return {
    marks: RICH_MARKS.filter((mark) => isMarkActive(doc, range, mark, pending)),
    kind: sameKind && first ? first.kind : null,
    level: sameLevel && first ? first.level : null,
    align: sameAlign && first ? first.align : null,
    canIndent: canShiftIndent(doc, range, 1),
    canOutdent: canShiftIndent(doc, range, -1)
  };
}

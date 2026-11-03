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
  marks: RichMark[];
};

export type RichCell = RichSpan[];
export type RichRow = RichCell[];

export type RichBlock = {
  kind: RichBlockKind;
  spans: RichSpan[];
  align: RichAlign;
  level: number;
  rows: RichRow[];
  media: string;
  widthPct: number;
};

export type RichDoc = { blocks: RichBlock[] };

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

export const MAX_DOCUMENT_CHARS = 200_000;
export const MAX_BLOCKS = 2_000;
export const MAX_HEADING_LEVEL = 4;
export const MAX_LIST_DEPTH = 3;
const MAX_SPANS_PER_BLOCK = 512;

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

export const HIGHLIGHT_FILL = "#FFFF00";

const MARK_EXCLUDES: Partial<Record<RichMark, RichMark>> = {
  SUPERSCRIPT: "SUBSCRIPT",
  SUBSCRIPT: "SUPERSCRIPT"
};

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

export const IMAGE_DEFAULT_WIDTH_PCT = 70;
export const IMAGE_MIN_WIDTH_PCT = 10;
export const IMAGE_MAX_WIDTH_PCT = 100;
export const IMAGE_WIDTH_STEP_PCT = 15;

export function isListKind(kind: RichBlockKind): boolean {
  return kind === "BULLET_ITEM" || kind === "ORDERED_ITEM";
}

export type DocPoint = { block: number; offset: number };

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

export function orderedRange(range: DocRange): { start: DocPoint; end: DocPoint } {
  return comparePoints(range.anchor, range.focus) <= 0
    ? { start: range.anchor, end: range.focus }
    : { start: range.focus, end: range.anchor };
}

export function collapsedAt(point: DocPoint): DocRange {
  return { anchor: point, focus: point };
}

export function clampPoint(doc: RichDoc, point: DocPoint): DocPoint {
  if (!doc.blocks.length) return { block: 0, offset: 0 };
  const block = Math.max(0, Math.min(doc.blocks.length - 1, point.block));
  const length = blockLength(doc.blocks[block]);
  return { block, offset: Math.max(0, Math.min(length, point.offset)) };
}

export function clampRange(doc: RichDoc, range: DocRange): DocRange {
  return { anchor: clampPoint(doc, range.anchor), focus: clampPoint(doc, range.focus) };
}

export type EditResult = { doc: RichDoc; selection: DocRange };

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

export function isTableKind(kind: RichBlockKind): boolean {
  return kind === "TABLE";
}

export function isImageKind(kind: RichBlockKind): boolean {
  return kind === "IMAGE";
}

function isStructuralKind(kind: RichBlockKind): boolean {
  return isTableKind(kind) || isImageKind(kind);
}

export function tableColumnCount(block: RichBlock): number {
  return block.rows.reduce((widest, row) => Math.max(widest, row.length), 0);
}

export function makeTable(rows: number, columns: number): RichBlock {
  const r = Math.max(2, Math.min(MAX_TABLE_ROWS, rows));
  const c = Math.max(1, Math.min(MAX_TABLE_COLUMNS, columns));
  return makeBlock({
    kind: "TABLE",
    rows: Array.from({ length: r }, () => Array.from({ length: c }, () => [] as RichCell))
  });
}

export function emptyDoc(): RichDoc {
  return { blocks: [makeBlock()] };
}

export function blockText(block: RichBlock): string {
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

export function isBlockEmpty(block: RichBlock): boolean {
  if (isImageKind(block.kind)) return !block.media;
  return !blockText(block).trim();
}

export function isEmptyDoc(doc: RichDoc): boolean {
  return doc.blocks.every(isBlockEmpty);
}

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

const NEEDS_CLEANING = /[\u0000-\u0008\u000b-\u001f\u007f-\u009f\ud800-\udfff]/;

export function cleanText(raw: unknown): string {
  if (raw === null || raw === undefined) return "";
  const text = typeof raw === "string" ? raw : String(raw);
  if (!NEEDS_CLEANING.test(text)) return text;
  let out = "";
  for (let index = 0; index < text.length; index += 1) {
    const code = text.charCodeAt(index);
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
  while (rows.length && !rows[rows.length - 1].some((cell) => cell.some((span) => span.text.trim()))) {
    rows.pop();
  }
  return { rows, budget };
}

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

const PASTE_BULLET = /^[•‣▪●·*+-]\s+/;
const PASTE_ORDERED = /^\(?(\d{1,3})[.)]\s+/;
const PASTE_HEADING = /^(#{1,4})\s+/;
const PASTE_QUOTE = /^>\s+/;

export function docFromPastedText(text: string): RichDoc {
  const cleaned = cleanText(text).replace(/\r\n?/g, "\n").slice(0, MAX_DOCUMENT_CHARS);
  if (!cleaned) return { blocks: [] };
  const blocks: RichBlock[] = [];
  for (const rawLine of cleaned.split("\n").slice(0, MAX_BLOCKS)) {
    const tabs = /^\t*/.exec(rawLine)?.[0].length ?? 0;
    let line = rawLine.slice(tabs).trimEnd();
    if (!line.trim()) {
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
      if (block.kind === "TABLE") {
        entry.rows = block.rows.map((row) => row.map((cell) => cell.map(storedSpan)));
      }
      if (block.kind === "IMAGE") {
        entry.media = block.media;
        if (block.widthPct !== IMAGE_DEFAULT_WIDTH_PCT) entry.widthPct = block.widthPct;
      }
      return entry;
    })
  };
}

export function toStoredValue(doc: RichDoc): StoredRichDoc | null {
  return isEmptyDoc(doc) ? null : toStored(doc);
}

export function toPlain(doc: RichDoc): string {
  const lines: string[] = [];
  let ordinal = 0;
  for (const block of doc.blocks) {
    if (block.kind === "IMAGE") {
      ordinal = 0;
      const caption = blockText(block).trim();
      if (caption) lines.push(caption);
      continue;
    }
    if (block.kind === "TABLE") {
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

export function orderedNumbers(doc: RichDoc): Array<number | null> {
  let counter = 0;
  return doc.blocks.map((block) => {
    if (isBlockEmpty(block)) return null;
    if (block.kind === "ORDERED_ITEM") {
      counter += 1;
      return counter;
    }
    counter = 0;
    return null;
  });
}

export function richSummary(raw: unknown, limit = 160): string {
  const text = toPlain(fromStored(raw)).split(/\s+/).filter(Boolean).join(" ");
  return text.length <= limit ? text : `${text.slice(0, limit - 1).trimEnd()}…`;
}

export function isStoredRichTextEmpty(raw: unknown): boolean {
  if (raw === null || raw === undefined) return true;
  if (typeof raw === "string") return !raw.trim();
  return isEmptyDoc(fromStored(raw));
}

export function storedSignature(raw: unknown): string {
  const doc = fromStored(raw);
  if (isEmptyDoc(doc)) return "EMPTY";
  return JSON.stringify(toStored(doc));
}

export type RichCounts = { words: number; characters: number; blocks: number };

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

function replaceBlock(doc: RichDoc, index: number, next: RichBlock): RichDoc {
  const blocks = doc.blocks.slice();
  blocks[index] = next;
  return { blocks };
}

function withSpans(block: RichBlock, spans: readonly RichSpan[]): RichBlock {
  return { ...block, spans: mergeSpans(spans) };
}

export function blocksInRange(doc: RichDoc, range: DocRange): number[] {
  if (!doc.blocks.length) return [];
  const { start, end } = orderedRange(clampRange(doc, range));
  const out: number[] = [];
  for (let index = start.block; index <= end.block; index += 1) out.push(index);
  return out;
}

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
      marks: on
        ? sortMarks([...span.marks.filter((item) => item !== excluded), mark])
        : span.marks.filter((item) => item !== mark)
    }));
    blocks[index] = withSpans(block, [...sliceSpans(block.spans, 0, from), ...middle, ...sliceSpans(block.spans, to, length)]);
  }
  return { doc: { blocks }, selection: clamped };
}

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
    if (isStructuralKind(block.kind)) continue;
    blocks[index] = {
      ...block,
      kind,
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

export function toggleList(doc: RichDoc, range: DocRange, ordered: boolean): EditResult {
  const kind: RichBlockKind = ordered ? "ORDERED_ITEM" : "BULLET_ITEM";
  const indices = blocksInRange(doc, range);
  const allAlready = indices.length > 0 && indices.every((index) => doc.blocks[index].kind === kind);
  return setBlockKind(doc, range, allAlready ? "PARAGRAPH" : kind);
}

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

  const merged = withSpans(startBlock, [
    ...sliceSpans(startBlock.spans, 0, start.offset),
    ...sliceSpans(endBlock.spans, end.offset, blockLength(endBlock))
  ]);
  const blocks = doc.blocks.slice();
  blocks.splice(start.block, end.block - start.block + 1, merged);
  return { doc: { blocks }, selection: collapsedAt(start) };
}

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

export function insertTable(doc: RichDoc, range: DocRange, rows = 3, columns = 3): EditResult {
  const point = clampPoint(doc, orderedRange(range).start);
  const blocks = doc.blocks.slice();
  const table = makeTable(rows, columns);
  const after = makeBlock();
  blocks.splice(point.block + 1, 0, table, after);
  return {
    doc: { blocks: blocks.slice(0, MAX_BLOCKS) },
    selection: collapsedAt({ block: Math.min(point.block + 2, MAX_BLOCKS - 1), offset: 0 })
  };
}

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

export function removeTableRow(doc: RichDoc, block: number, at: number): EditResult {
  const target = doc.blocks[block];
  if (!target || target.kind !== "TABLE") return withRows(doc, block, []);
  const rows = squared(target.rows);
  if (rows.length <= 2 || at < 0 || at >= rows.length) return withRows(doc, block, rows);
  rows.splice(at, 1);
  return withRows(doc, block, rows);
}

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

export function removeTableColumn(doc: RichDoc, block: number, at: number): EditResult {
  const target = doc.blocks[block];
  if (!target || target.kind !== "TABLE") return withRows(doc, block, []);
  const rows = squared(target.rows);
  const width = rows[0]?.length ?? 0;
  if (width <= 1 || at < 0 || at >= width) return withRows(doc, block, rows);
  return withRows(doc, block, rows.map((row) => row.filter((_, i) => i !== at)));
}

export function removeTable(doc: RichDoc, block: number): EditResult {
  const target = doc.blocks[block];
  if (!target || target.kind !== "TABLE") return { doc, selection: collapsedAt({ block, offset: 0 }) };
  const blocks = doc.blocks.filter((_, i) => i !== block);
  return {
    doc: { blocks: blocks.length ? blocks : [makeBlock()] },
    selection: collapsedAt({ block: Math.max(0, block - 1), offset: 0 })
  };
}

export function insertImage(
  doc: RichDoc,
  range: DocRange,
  media: string,
  caption = "",
  widthPct = IMAGE_DEFAULT_WIDTH_PCT
): EditResult {
  const id = cleanText(media).trim().slice(0, 64);
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

export function removeImage(doc: RichDoc, block: number): EditResult {
  const target = doc.blocks[block];
  if (!target || target.kind !== "IMAGE") return { doc, selection: collapsedAt({ block, offset: 0 }) };
  const blocks = doc.blocks.filter((_, index) => index !== block);
  const next = blocks.length ? blocks : [makeBlock()];
  const landing = Math.max(0, Math.min(next.length - 1, block - 1));
  return { doc: { blocks: next }, selection: collapsedAt({ block: landing, offset: blockLength(next[landing]) }) };
}

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
    level: isListKind(rightKind) ? block.level : 0,
    spans: mergeSpans(sliceSpans(block.spans, point.offset, length))
  });
  blocks.splice(point.block, 1, left, right);
  return { doc: { blocks: blocks.slice(0, MAX_BLOCKS) }, selection: collapsedAt({ block: point.block + 1, offset: 0 }) };
}

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
  if (block.kind === "IMAGE") return removeImage(doc, point.block);
  if (block.kind === "TABLE") return { doc, selection: collapsedAt(point) };
  if (block.kind !== "PARAGRAPH") {
    return {
      doc: replaceBlock(doc, point.block, { ...block, kind: "PARAGRAPH", level: 0 }),
      selection: collapsedAt(point)
    };
  }
  if (point.block === 0) return { doc, selection: collapsedAt(point) };

  const previous = doc.blocks[point.block - 1];
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
  if (block.kind === "IMAGE" || block.kind === "TABLE" || next.kind === "IMAGE" || next.kind === "TABLE") {
    return { doc, selection: collapsedAt(point) };
  }
  const blocks = doc.blocks.slice();
  blocks.splice(point.block, 2, withSpans(block, [...block.spans, ...next.spans]));
  return { doc: { blocks }, selection: collapsedAt(point) };
}

type InputRule = { pattern: RegExp; apply: (block: RichBlock, match: RegExpExecArray) => Partial<RichBlock> };

const INPUT_RULES: InputRule[] = [
  { pattern: /^(#{1,4}) $/, apply: (_block, match) => ({ kind: "HEADING", level: match[1].length }) },
  { pattern: /^[-*+] $/, apply: () => ({ kind: "BULLET_ITEM", level: 0 }) },
  { pattern: /^\d{1,3}[.)] $/, apply: () => ({ kind: "ORDERED_ITEM", level: 0 }) },
  { pattern: /^> $/, apply: () => ({ kind: "QUOTE", level: 0 }) }
];

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

export type RichMatch = { block: number; start: number; end: number };
export type FindOptions = { caseSensitive?: boolean; wholeWord?: boolean };

const WORD_CHARACTER = /[\p{L}\p{N}_]/u;

function isWordBoundary(text: string, index: number): boolean {
  if (index < 0 || index >= text.length) return true;
  return !WORD_CHARACTER.test(text[index]);
}

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
      from = at + Math.max(1, query.length);
    }
  });
  return out;
}

export function matchToRange(match: RichMatch): DocRange {
  return { anchor: { block: match.block, offset: match.start }, focus: { block: match.block, offset: match.end } };
}

export function replaceMatch(doc: RichDoc, match: RichMatch, replacement: string): EditResult {
  const block = doc.blocks[match.block];
  if (!block) return { doc, selection: collapsedAt({ block: 0, offset: 0 }) };
  const marks = marksAt(block, match.start + 1);
  return insertText(doc, matchToRange(match), replacement, marks);
}

export function replaceAll(doc: RichDoc, query: string, replacement: string, options: FindOptions = {}): { doc: RichDoc; count: number } {
  const matches = findMatches(doc, query, options);
  if (!matches.length) return { doc, count: 0 };
  let next = doc;
  for (let index = matches.length - 1; index >= 0; index -= 1) {
    next = replaceMatch(next, matches[index], replacement).doc;
  }
  return { doc: next, count: matches.length };
}

export type RichToolbarState = {
  marks: RichMark[];
  kind: RichBlockKind | null;
  level: number | null;
  align: RichAlign | null;
  canIndent: boolean;
  canOutdent: boolean;
};

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

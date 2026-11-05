"use client";

import { useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from "react";
import {
  Bold,
  Code,
  Eraser,
  Expand,
  Heading1,
  Heading2,
  Heading3,
  Heading4,
  Highlighter,
  ImagePlus,
  Info,
  Italic,
  List,
  ListIndentDecrease,
  ListIndentIncrease,
  ListOrdered,
  Maximize2,
  Minimize2,
  Pilcrow,
  Quote,
  Redo2,
  Replace,
  ReplaceAll,
  Search,
  Shrink,
  Strikethrough,
  Subscript,
  Superscript,
  Table,
  Rows2,
  Rows3,
  Columns2,
  Columns3,
  Trash2,
  TextAlignCenter,
  TextAlignEnd,
  TextAlignJustify,
  TextAlignStart,
  Underline,
  Undo2,
  X
} from "lucide-react";

import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";
import { apiFetch } from "@/lib/api";
import { uploadMediaBatch } from "@/lib/media";
import type { MediaFile } from "@/lib/types";
import { cn } from "@/lib/utils";
import {
  applyInputRule,
  blockLength,
  clampRange,
  clearFormatting,
  collapsedAt,
  countDoc,
  deleteBackward,
  deleteForward,
  deleteRange,
  docFromPastedText,
  emptyDoc,
  findMatches,
  fromStored,
  seedListDocument,
  insertDoc,
  insertImage,
  removeImage,
  setImageWidth,
  IMAGE_WIDTH_STEP_PCT,
  insertTable,
  insertTableColumn,
  insertTableRow,
  removeTable,
  removeTableColumn,
  removeTableRow,
  insertText,
  isCollapsed,
  isEmptyDoc,
  isListKind,
  blockText,
  markExcludes,
  marksAt,
  matchToRange,
  MAX_BLOCKS,
  MAX_DOCUMENT_CHARS,
  mergeSpans,
  orderedNumbers,
  replaceAll as replaceAllInDoc,
  replaceMatch,
  setAlign,
  setBlockKind,
  shiftIndent,
  sortMarks,
  splitBlock,
  storedSignature,
  toggleList,
  toggleMark,
  toStoredValue,
  toolbarState,
  cleanText,
  makeBlock,
  makeSpan,
  tableColumnCount,
  type DocPoint,
  type DocRange,
  type EditResult,
  type RichAlign,
  type RichBlock,
  type RichBlockKind,
  type RichCell,
  type RichRow,
  type RichCounts,
  type RichDoc,
  type RichMark,
  type RichSpan,
  type RichToolbarState,
  type StoredRichDoc
} from "@/lib/richText";
import { Toggle } from "@/components/ui/Toggle";

const PARAGRAPH_CLASS = "text-sm leading-6 text-ink-900";
const QUOTE_CLASS = "rounded-lg bg-purple-50 px-3 py-1.5 text-sm italic leading-6 text-ink-700 dark:bg-purple-950/30";

const HEADING_CLASS: Record<number, string> = {
  1: "font-display text-3xl font-bold leading-9 text-purple-800 dark:text-purple-300 border-b border-line-300 pb-1 mt-4",
  2: "font-display text-xl font-bold leading-7 text-purple-800 dark:text-purple-300 mt-3",
  3: "font-display text-base font-bold leading-6 text-purple-600 mt-2",
  4: "font-display text-sm font-bold uppercase tracking-wide leading-6 text-ink-500 mt-2"
};

const ALIGN_CLASS: Record<RichAlign, string> = {
  LEFT: "text-left",
  CENTER: "text-center",
  RIGHT: "text-right",
  JUSTIFY: "text-justify"
};

const LEVEL_CLASS: Record<number, string> = {
  0: "",
  1: "ml-6",
  2: "ml-12",
  3: "ml-18"
};

const FIGURE_CLASS = "my-3 grid gap-1";
const FIGURE_IMAGE_CLASS = "max-w-full rounded-md border border-line-200 object-contain";
const FIGURE_IMAGE_PENDING_CLASS =
  "block h-28 max-w-full rounded-md border border-dashed border-line-300 bg-surface-50";
const CAPTION_CLASS = "text-xs italic leading-5 text-ink-600";
const CAPTION_EMPTY_CLASS =
  "rounded-sm border border-dashed border-line-300 px-2 py-1 text-xs italic leading-5 text-ink-600";

const MARK_CLASS: Partial<Record<RichMark, string>> = {
  UNDERLINE: "underline",
  STRIKETHROUGH: "line-through",
  CODE: "rounded-sm bg-field-100 px-1 py-0.5 font-mono text-[0.9em] text-ink-900",
  SUPERSCRIPT: "align-super text-[0.62em] leading-none",
  SUBSCRIPT: "align-sub text-[0.62em] leading-none",
  HIGHLIGHT: "rounded-sm bg-[#FFFF00] px-0.5 text-black"
};

const MARK_TAG: Record<RichMark, string> = {
  BOLD: "strong",
  ITALIC: "em",
  UNDERLINE: "u",
  STRIKETHROUGH: "s",
  CODE: "code",
  SUPERSCRIPT: "sup",
  SUBSCRIPT: "sub",
  HIGHLIGHT: "mark"
};

const TAG_MARK: Record<string, RichMark> = {
  STRONG: "BOLD",
  B: "BOLD",
  EM: "ITALIC",
  I: "ITALIC",
  U: "UNDERLINE",
  INS: "UNDERLINE",
  S: "STRIKETHROUGH",
  STRIKE: "STRIKETHROUGH",
  DEL: "STRIKETHROUGH",
  CODE: "CODE",
  KBD: "CODE",
  SAMP: "CODE",
  SUP: "SUPERSCRIPT",
  SUB: "SUBSCRIPT",
  MARK: "HIGHLIGHT"
};

const NEST_ORDER: readonly RichMark[] = [
  "BOLD",
  "ITALIC",
  "UNDERLINE",
  "STRIKETHROUGH",
  "CODE",
  "HIGHLIGHT",
  "SUPERSCRIPT",
  "SUBSCRIPT"
];

const BLOCK_ATTRIBUTE = "data-rte-block";

function spanNode(span: RichSpan): Node {
  let node: Node = document.createTextNode(span.text);
  const applied = NEST_ORDER.filter((mark) => span.marks.includes(mark));
  for (let index = applied.length - 1; index >= 0; index -= 1) {
    const element = document.createElement(MARK_TAG[applied[index]]);
    const className = MARK_CLASS[applied[index]];
    if (className) element.className = className;
    element.appendChild(node);
    node = element;
  }
  return node;
}

function imageAlign(align: RichAlign): RichAlign {
  return align === "LEFT" ? "CENTER" : align;
}

function blockElement(
  block: RichBlock,
  index: number,
  ordinal: number | null,
  mediaUrls: ReadonlyMap<string, string | null>
): HTMLElement {
  const align = ALIGN_CLASS[block.align];
  let element: HTMLElement;

  if (isListKind(block.kind)) {
    element = document.createElement("li");
    element.className = cn(PARAGRAPH_CLASS, align, LEVEL_CLASS[block.level] ?? "");
    if (ordinal !== null) (element as HTMLLIElement).value = ordinal;
  } else if (block.kind === "HEADING") {
    element = document.createElement("p");
    element.setAttribute("role", "heading");
    element.setAttribute("aria-level", String(Math.min(6, block.level + 2)));
    element.className = cn(HEADING_CLASS[block.level] ?? HEADING_CLASS[4], align);
  } else if (block.kind === "QUOTE") {
    element = document.createElement("blockquote");
    element.className = cn(QUOTE_CLASS, align);
  } else if (block.kind === "TABLE") {
    const table = document.createElement("table");
    table.className = "my-2 w-full table-fixed border-collapse overflow-hidden rounded-md text-sm";
    const body = document.createElement("tbody");
    const width = tableColumnCount(block);
    block.rows.forEach((row, rowIndex) => {
      const tr = document.createElement("tr");
      if (rowIndex > 0 && rowIndex % 2 === 0) tr.className = "bg-surface-50";
      for (let column = 0; column < width; column += 1) {
        const cell = document.createElement(rowIndex === 0 ? "th" : "td");
        cell.className = cn(
          "border border-line-300 px-2 py-1 align-top",
          rowIndex === 0 ? "bg-ink-800 text-left font-semibold text-white" : ""
        );
        const spans = row[column] ?? [];
        if (!spans.length) cell.appendChild(document.createElement("br"));
        else for (const span of spans) cell.appendChild(spanNode(span));
        tr.appendChild(cell);
      }
      body.appendChild(tr);
    });
    table.appendChild(body);
    element = table;
  } else if (block.kind === "IMAGE") {
    const figure = document.createElement("figure");
    figure.className = FIGURE_CLASS;

    const effective = imageAlign(block.align);
    const image = document.createElement("img");
    image.setAttribute("data-media", block.media);
    image.setAttribute("contenteditable", "false");
    image.draggable = false;
    image.alt = "";
    image.style.width = `${block.widthPct}%`;
    if (effective === "CENTER") {
      image.style.marginLeft = "auto";
      image.style.marginRight = "auto";
    } else if (effective === "RIGHT") {
      image.style.marginLeft = "auto";
    }
    const url = mediaUrls.get(block.media);
    if (url) {
      image.className = FIGURE_IMAGE_CLASS;
      image.src = url;
    } else {
      image.className = FIGURE_IMAGE_PENDING_CLASS;
      image.setAttribute(
        "aria-label",
        url === null
          ? "Photo can’t be shown here — it’s still in the file."
          : "Loading the photograph"
      );
    }
    figure.appendChild(image);

    const caption = document.createElement("figcaption");
    caption.className = cn(
      block.spans.length ? CAPTION_CLASS : CAPTION_EMPTY_CLASS,
      ALIGN_CLASS[effective]
    );
    if (!block.spans.length) caption.appendChild(document.createElement("br"));
    else for (const span of block.spans) caption.appendChild(spanNode(span));
    figure.appendChild(caption);

    element = figure;
  } else {
    element = document.createElement("p");
    element.className = cn(PARAGRAPH_CLASS, align);
  }

  element.setAttribute(BLOCK_ATTRIBUTE, String(index));
  element.setAttribute("data-kind", block.kind);
  element.setAttribute("data-align", block.align);
  element.setAttribute("data-level", String(block.level));

  if (block.kind === "TABLE") return element;

  if (block.kind === "IMAGE") {
    element.setAttribute("data-media", block.media);
    element.setAttribute("data-width", String(block.widthPct));
    return element;
  }

  if (!block.spans.length) {
    element.appendChild(document.createElement("br"));
    return element;
  }
  for (const span of block.spans) element.appendChild(spanNode(span));
  return element;
}

function renderDocInto(
  host: HTMLElement,
  doc: RichDoc,
  mediaUrls: ReadonlyMap<string, string | null>
): void {
  const fragment = document.createDocumentFragment();
  const ordinals = orderedNumbers(doc);
  let index = 0;
  while (index < doc.blocks.length) {
    const block = doc.blocks[index];
    if (!isListKind(block.kind)) {
      fragment.appendChild(blockElement(block, index, null, mediaUrls));
      index += 1;
      continue;
    }
    const ordered = block.kind === "ORDERED_ITEM";
    const list = document.createElement(ordered ? "ol" : "ul");
    list.className = ordered ? "my-1 ml-5 list-decimal" : "my-1 ml-5 list-disc";
    while (index < doc.blocks.length && doc.blocks[index].kind === block.kind) {
      list.appendChild(blockElement(doc.blocks[index], index, ordinals[index], mediaUrls));
      index += 1;
    }
    fragment.appendChild(list);
  }
  host.replaceChildren(fragment);
}

function nodeText(node: Node): string {
  return cleanText((node.textContent ?? "").replace(/ /g, " ").replace(/[\r\n]/g, " "));
}

function collectSpans(node: Node, marks: RichMark[], out: RichSpan[]): void {
  for (const child of Array.from(node.childNodes)) {
    if (child.nodeType === Node.TEXT_NODE) {
      const text = nodeText(child);
      if (text) out.push(makeSpan(text, marks));
      continue;
    }
    if (!(child instanceof HTMLElement)) continue;
    if (child.tagName === "BR") continue;
    const mark = TAG_MARK[child.tagName];
    collectSpans(child, mark ? sortMarks([...marks, mark]) : marks, out);
  }
}

function readAlign(element: HTMLElement): RichAlign {
  const raw = element.getAttribute("data-align");
  return raw === "CENTER" || raw === "RIGHT" || raw === "JUSTIFY" ? raw : "LEFT";
}

function readTableElement(element: HTMLElement): RichBlock {
  const rows: RichRow[] = [];
  for (const tr of Array.from(element.querySelectorAll("tr"))) {
    const cells: RichCell[] = [];
    for (const cell of Array.from(tr.children)) {
      if (!(cell instanceof HTMLElement)) continue;
      if (cell.tagName !== "TD" && cell.tagName !== "TH") continue;
      const spans: RichSpan[] = [];
      collectSpans(cell, [], spans);
      cells.push(mergeSpans(spans));
    }
    if (cells.length) rows.push(cells);
  }
  return makeBlock({ kind: "TABLE", rows });
}

function readImageElement(element: HTMLElement): RichBlock {
  const spans: RichSpan[] = [];
  const caption = element.querySelector("figcaption");
  if (caption) collectSpans(caption, [], spans);
  const media = (element.getAttribute("data-media") ?? "").trim();
  const align = readAlign(element);
  if (!media) return makeBlock({ spans: mergeSpans(spans), align });
  const width = Number(element.getAttribute("data-width"));
  return makeBlock({
    kind: "IMAGE",
    spans: mergeSpans(spans),
    align,
    media,
    widthPct: Number.isFinite(width) && width > 0 ? width : undefined
  });
}

function readBlockElement(element: HTMLElement, kind: RichBlockKind): RichBlock {
  if (kind === "TABLE") return readTableElement(element);
  if (kind === "IMAGE") return readImageElement(element);
  const spans: RichSpan[] = [];
  collectSpans(element, [], spans);
  const rawLevel = Number(element.getAttribute("data-level"));
  const level = Number.isFinite(rawLevel) ? rawLevel : 0;
  return makeBlock({
    kind,
    spans: mergeSpans(spans),
    align: readAlign(element),
    level: kind === "HEADING" ? Math.max(1, Math.min(4, level || 1)) : isListKind(kind) ? Math.max(0, Math.min(3, level)) : 0
  });
}

function kindOf(element: HTMLElement): RichBlockKind {
  const declared = element.getAttribute("data-kind");
  if (
    declared === "HEADING" ||
    declared === "QUOTE" ||
    declared === "PARAGRAPH" ||
    declared === "TABLE" ||
    declared === "IMAGE"
  ) {
    return declared;
  }
  if (element.tagName === "TABLE") return "TABLE";
  if (element.tagName === "FIGURE") return "IMAGE";
  if (element.tagName === "BLOCKQUOTE") return "QUOTE";
  return "PARAGRAPH";
}

function collectBlocks(node: Node, out: RichBlock[]): void {
  if (node.nodeType === Node.TEXT_NODE) {
    const text = nodeText(node);
    if (text) out.push(makeBlock({ spans: [makeSpan(text)] }));
    return;
  }
  if (!(node instanceof HTMLElement)) return;
  if (node.tagName === "UL" || node.tagName === "OL") {
    const kind: RichBlockKind = node.tagName === "OL" ? "ORDERED_ITEM" : "BULLET_ITEM";
    for (const child of Array.from(node.childNodes)) {
      if (child instanceof HTMLElement && child.tagName === "LI") out.push(readBlockElement(child, kind));
      else collectBlocks(child, out);
    }
    return;
  }
  if (node.tagName === "BR") return;
  out.push(readBlockElement(node, kindOf(node)));
}

function readDocFrom(host: HTMLElement): RichDoc {
  const blocks: RichBlock[] = [];
  for (const child of Array.from(host.childNodes)) collectBlocks(child, blocks);
  return { blocks };
}

function paintCaptionStates(host: HTMLElement): void {
  for (const node of Array.from(host.querySelectorAll("figcaption"))) {
    if (!(node instanceof HTMLElement)) continue;
    const figure = node.closest("figure");
    const align = imageAlign(figure instanceof HTMLElement ? readAlign(figure) : "LEFT");
    node.className = cn(
      (node.textContent ?? "").trim() ? CAPTION_CLASS : CAPTION_EMPTY_CLASS,
      ALIGN_CLASS[align]
    );
  }
}

function normaliseForEditing(doc: RichDoc): RichDoc {
  if (!doc.blocks.length) return emptyDoc();
  if (doc.blocks.length <= MAX_BLOCKS) return doc;
  return { blocks: doc.blocks.slice(0, MAX_BLOCKS) };
}

function blockElementAt(host: HTMLElement, index: number): HTMLElement | null {
  return host.querySelector<HTMLElement>(`[${BLOCK_ATTRIBUTE}="${index}"]`);
}

function docPointToDom(host: HTMLElement, point: DocPoint): { node: Node; offset: number } | null {
  const block = blockElementAt(host, point.block);
  if (!block) return null;
  const element =
    block.tagName === "FIGURE" ? (block.querySelector("figcaption") ?? block) : block;
  const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
  let node = walker.nextNode();
  if (!node) return { node: element, offset: 0 };
  let remaining = point.offset;
  for (;;) {
    const length = node.textContent?.length ?? 0;
    if (remaining <= length) return { node, offset: remaining };
    remaining -= length;
    const next = walker.nextNode();
    if (!next) return { node, offset: length };
    node = next;
  }
}

function textLengthBefore(blockElement: HTMLElement, target: Node): number {
  if (target === blockElement) return 0;
  const walker = document.createTreeWalker(blockElement, NodeFilter.SHOW_TEXT);
  let total = 0;
  let current = walker.nextNode();
  while (current) {
    if (target.contains(current)) break;
    total += current.textContent?.length ?? 0;
    current = walker.nextNode();
  }
  return total;
}

function domPointToDoc(host: HTMLElement, node: Node, offset: number): DocPoint | null {
  const startElement = node.nodeType === Node.TEXT_NODE ? node.parentElement : (node as Element);
  const element = startElement?.closest(`[${BLOCK_ATTRIBUTE}]`) as HTMLElement | null;
  if (!element || !host.contains(element)) return null;
  const block = Number(element.getAttribute(BLOCK_ATTRIBUTE));
  if (!Number.isFinite(block)) return null;

  if (node.nodeType === Node.TEXT_NODE) {
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
    let total = 0;
    let current = walker.nextNode();
    while (current && current !== node) {
      total += current.textContent?.length ?? 0;
      current = walker.nextNode();
    }
    return { block, offset: total + offset };
  }

  let total = textLengthBefore(element, node);
  const children = Array.from(node.childNodes).slice(0, offset);
  for (const child of children) total += (child.textContent ?? "").length;
  return { block, offset: total };
}

function readSelection(host: HTMLElement): DocRange | null {
  const selection = window.getSelection();
  if (!selection || selection.rangeCount === 0) return null;
  const { anchorNode, focusNode } = selection;
  if (!anchorNode || !focusNode) return null;
  if (!host.contains(anchorNode) || !host.contains(focusNode)) return null;
  const anchor = domPointToDoc(host, anchorNode, selection.anchorOffset);
  const focus = domPointToDoc(host, focusNode, selection.focusOffset);
  if (!anchor || !focus) return null;
  return { anchor, focus };
}

function writeSelection(host: HTMLElement, range: DocRange): void {
  const selection = window.getSelection();
  if (!selection) return;
  const anchor = docPointToDom(host, range.anchor);
  const focus = docPointToDom(host, range.focus);
  if (!anchor || !focus) return;
  try {
    selection.setBaseAndExtent(anchor.node, anchor.offset, focus.node, focus.offset);
  } catch {
  }
}

function rangesEqual(a: DocRange, b: DocRange): boolean {
  return (
    a.anchor.block === b.anchor.block &&
    a.anchor.offset === b.anchor.offset &&
    a.focus.block === b.focus.block &&
    a.focus.offset === b.focus.offset
  );
}

type HistoryEntry = { doc: RichDoc; selection: DocRange };
type HistoryState = { entries: HistoryEntry[]; index: number; lastAt: number; lastLabel: string };

const HISTORY_COALESCE_MS = 600;
const HISTORY_LIMIT = 200;

type ToolItem =
  | {
      kind: "button";
      id: string;
      label: string;
      hint?: string;
      icon: React.ReactNode;
      active?: boolean;
      disabled?: boolean;
      run: () => void;
    }
  | { kind: "separator"; id: string };

const TOOL_BUTTON_BASE =
  "grid h-8 w-8 shrink-0 place-items-center rounded-md border text-ink-700 transition disabled:cursor-not-allowed disabled:opacity-40";
const TOOL_BUTTON_IDLE = "border-transparent hover:border-purple-300 hover:bg-purple-50 hover:text-ink-900";
const TOOL_BUTTON_ACTIVE = "border-purple-300 bg-purple-50 text-purple-700";

function Toolbar({
  items,
  editorId,
  onDismiss,
  containerRef
}: {
  items: ToolItem[];
  editorId: string;
  onDismiss: () => void;
  containerRef: React.RefObject<HTMLDivElement | null>;
}) {
  const buttons = useMemo(() => items.filter((item): item is Extract<ToolItem, { kind: "button" }> => item.kind === "button"), [items]);
  const [focusIndex, setFocusIndex] = useState(0);
  const nodes = useRef<Record<string, HTMLButtonElement | null>>({});

  const enabled = buttons.filter((button) => !button.disabled);
  const activeId = enabled[Math.min(focusIndex, Math.max(0, enabled.length - 1))]?.id ?? "";

  function move(delta: number) {
    if (!enabled.length) return;
    const next = (focusIndex + delta + enabled.length) % enabled.length;
    setFocusIndex(next);
    nodes.current[enabled[next].id]?.focus();
  }

  return (
    <div
      ref={containerRef}
      role="toolbar"
      aria-label="Text formatting"
      aria-controls={editorId}
      aria-orientation="horizontal"
      onMouseDown={(event) => event.preventDefault()}
      onKeyDown={(event) => {
        if (event.key === "ArrowRight") {
          event.preventDefault();
          move(1);
        } else if (event.key === "ArrowLeft") {
          event.preventDefault();
          move(-1);
        } else if (event.key === "Home") {
          event.preventDefault();
          setFocusIndex(0);
          nodes.current[enabled[0]?.id ?? ""]?.focus();
        } else if (event.key === "End") {
          event.preventDefault();
          setFocusIndex(enabled.length - 1);
          nodes.current[enabled[enabled.length - 1]?.id ?? ""]?.focus();
        } else if (event.key === "Escape") {
          event.preventDefault();
          onDismiss();
        }
      }}
      className="flex w-full flex-wrap items-center justify-between gap-0.5 rounded-md border border-line-200 bg-card p-1 shadow-md"
    >
      {items.map((item) =>
        item.kind === "separator" ? (
          <span key={item.id} aria-hidden className="mx-0.5 h-5 w-px shrink-0 bg-line-200" />
        ) : (
          <button
            key={item.id}
            ref={(node) => {
              nodes.current[item.id] = node;
            }}
            type="button"
            aria-pressed={item.active ?? undefined}
            aria-label={item.label}
            title={item.hint ? `${item.label} — ${item.hint}` : item.label}
            disabled={item.disabled}
            tabIndex={item.id === activeId ? 0 : -1}
            onMouseDown={(event) => event.preventDefault()}
            onFocus={() => {
              const position = enabled.findIndex((button) => button.id === item.id);
              if (position >= 0) setFocusIndex(position);
            }}
            onClick={item.run}
            className={cn(TOOL_BUTTON_BASE, item.active ? TOOL_BUTTON_ACTIVE : TOOL_BUTTON_IDLE)}
          >
            {item.icon}
          </button>
        )
      )}
    </div>
  );
}

export type RichTextEditorProps = {
  value: unknown;
  onChange: (value: StoredRichDoc | null) => void;
  disabled?: boolean;
  ariaLabelledBy?: string;
  ariaLabel?: string;
  explainWhenUnavailable?: boolean;
  placeholder?: string;
  maxLength?: number;
  listKind?: RichBlockKind;
  upload?: { recordType: string; recordId: string };
};

const ICON = "h-4 w-4";

export function RichTextEditor({
  value,
  onChange,
  disabled,
  ariaLabelledBy,
  ariaLabel,
  explainWhenUnavailable,
  placeholder = "Write here…",
  maxLength,
  listKind,
  upload
}: RichTextEditorProps) {
  const reactId = useId();
  const editorId = `rte-${reactId}`;
  const countId = `${editorId}-count`;

  const hostRef = useRef<HTMLDivElement | null>(null);
  const toolbarRef = useRef<HTMLDivElement | null>(null);
  const fileRef = useRef<HTMLInputElement | null>(null);

  const mediaUrlsRef = useRef<Map<string, string | null>>(new Map());
  const mediaPendingRef = useRef<Set<string>>(new Set());
  const imageAnchorRef = useRef<DocRange | null>(null);

  const [seed] = useState(() => ({
    doc: normaliseForEditing(listKind ? seedListDocument(value, listKind) : fromStored(value)),
    selection: collapsedAt({ block: 0, offset: 0 }),
    signature: storedSignature(value)
  }));

  const docRef = useRef<RichDoc>(seed.doc);
  const selectionRef = useRef<DocRange>(seed.selection);
  const pendingMarksRef = useRef<RichMark[] | null>(null);
  const composingRef = useRef(false);
  const pendingExternalRef = useRef<{ value: unknown } | null>(null);
  const emittedSignatureRef = useRef<string>(seed.signature);
  const lastInputRef = useRef<{ type: string; data: string | null }>({ type: "", data: null });
  const historyRef = useRef<HistoryState>({
    entries: [{ doc: seed.doc, selection: seed.selection }],
    index: 0,
    lastAt: 0,
    lastLabel: ""
  });

  const propsRef = useRef({ onChange, disabled: !!disabled, maxLength, upload, ariaLabel });
  useEffect(() => {
    propsRef.current = { onChange, disabled: !!disabled, maxLength, upload, ariaLabel };
  });

  type ChromeState = {
    signature: string;
    bar: RichToolbarState;
    counts: RichCounts;
    canUndo: boolean;
    canRedo: boolean;
    showPlaceholder: boolean;
  };

  const [chrome, setChrome] = useState<ChromeState>(() => ({
    signature: "",
    bar: toolbarState(seed.doc, seed.selection, null),
    counts: countDoc(seed.doc),
    canUndo: false,
    canRedo: false,
    showPlaceholder: isEmptyDoc(seed.doc)
  }));

  const [active, setActive] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [savedPanelOpen, setSavedPanelOpen] = useState(false);

  const surfaceTop = useRef<number | null>(null);

  const holdSurfaceStill = useCallback(() => {
    surfaceTop.current = hostRef.current?.getBoundingClientRect().top ?? null;
  }, []);

  const [find, setFind] = useState({
    open: false,
    query: "",
    replacement: "",
    caseSensitive: false,
    wholeWord: false,
    index: 0,
    count: 0
  });
  const findRef = useRef(find);
  useEffect(() => {
    findRef.current = find;
  });

  useLayoutEffect(() => {
    const expected = surfaceTop.current;
    surfaceTop.current = null;
    const host = hostRef.current;
    if (expected === null || !host || fullscreen) return;
    const shift = host.getBoundingClientRect().top - expected;
    if (Math.abs(shift) >= 1) window.scrollBy({ top: shift, behavior: "instant" as ScrollBehavior });
  }, [active, find.open, fullscreen]);

  const refreshChrome = useCallback(() => {
    const doc = docRef.current;
    const range = selectionRef.current;
    const bar = toolbarState(doc, range, pendingMarksRef.current);
    const counts = countDoc(doc);
    const history = historyRef.current;
    const canUndo = history.index > 0;
    const canRedo = history.index < history.entries.length - 1;
    const showPlaceholder = isEmptyDoc(doc);
    const signature = [
      bar.marks.join(","),
      bar.kind ?? "-",
      bar.level ?? "-",
      bar.align ?? "-",
      bar.canIndent ? "1" : "0",
      bar.canOutdent ? "1" : "0",
      counts.words,
      counts.characters,
      counts.blocks,
      canUndo ? "1" : "0",
      canRedo ? "1" : "0",
      showPlaceholder ? "1" : "0"
    ].join("|");
    setChrome((current) =>
      current.signature === signature ? current : { signature, bar, counts, canUndo, canRedo, showPlaceholder }
    );

    const search = findRef.current;
    if (search.open && search.query) {
      const matches = findMatches(doc, search.query, { caseSensitive: search.caseSensitive, wholeWord: search.wholeWord });
      setFind((current) => (current.count === matches.length ? current : { ...current, count: matches.length }));
    }
  }, []);

  const ensureMediaUrls = useCallback((doc: RichDoc) => {
    const paint = (id: string, url: string | null) => {
      const host = hostRef.current;
      if (!host) return;
      for (const node of Array.from(host.querySelectorAll("img[data-media]"))) {
        if (!(node instanceof HTMLImageElement) || node.getAttribute("data-media") !== id) continue;
        if (url) {
          node.className = FIGURE_IMAGE_CLASS;
          node.removeAttribute("aria-label");
          node.src = url;
        } else {
          node.setAttribute(
            "aria-label",
            "Photo can’t be shown here — it’s still in the file."
          );
        }
      }
    };

    for (const block of doc.blocks) {
      if (block.kind !== "IMAGE" || !block.media) continue;
      const id = block.media;
      if (mediaPendingRef.current.has(id)) continue;
      if (mediaUrlsRef.current.has(id)) {
        paint(id, mediaUrlsRef.current.get(id) ?? null);
        continue;
      }
      mediaPendingRef.current.add(id);
      void (async () => {
        let url: string | null = null;
        try {
          url = (await apiFetch<MediaFile>(`/media/${id}`)).url ?? null;
        } catch {
          url = null;
        }
        mediaPendingRef.current.delete(id);
        mediaUrlsRef.current.set(id, url);
        paint(id, url);
      })();
    }
  }, []);

  const emit = useCallback((doc: RichDoc): boolean => {
    const stored = toStoredValue(doc);
    const signature = storedSignature(stored);
    if (signature === emittedSignatureRef.current) return false;
    emittedSignatureRef.current = signature;
    propsRef.current.onChange(stored);
    return true;
  }, []);

  const pushHistory = useCallback((doc: RichDoc, selection: DocRange, label: string) => {
    const history = historyRef.current;
    const now = Date.now();
    if (label === "type" && history.lastLabel === "type" && history.index >= 0 && now - history.lastAt < HISTORY_COALESCE_MS) {
      history.entries[history.index] = { doc, selection };
      history.lastAt = now;
      return;
    }
    history.entries = history.entries.slice(0, history.index + 1);
    history.entries.push({ doc, selection });
    if (history.entries.length > HISTORY_LIMIT) history.entries.shift();
    history.index = history.entries.length - 1;
    history.lastAt = now;
    history.lastLabel = label;
  }, []);

  const commit = useCallback(
    (result: EditResult, label: string) => {
      if (composingRef.current) return;
      const host = hostRef.current;
      const doc = normaliseForEditing(result.doc);
      const selection = clampRange(doc, result.selection);
      docRef.current = doc;
      selectionRef.current = selection;
      if (host) {
        renderDocInto(host, doc, mediaUrlsRef.current);
        ensureMediaUrls(doc);
        const activeElement = document.activeElement;
        if (activeElement === document.body || host.contains(activeElement)) host.focus({ preventScroll: true });
        writeSelection(host, selection);
      }
      if (emit(doc)) pushHistory(doc, selection, label);
      refreshChrome();
    },
    [emit, ensureMediaUrls, pushHistory, refreshChrome]
  );

  const applyHistoryEntry = useCallback(
    (entry: HistoryEntry) => {
      if (composingRef.current) return;
      const host = hostRef.current;
      docRef.current = entry.doc;
      selectionRef.current = clampRange(entry.doc, entry.selection);
      if (host) {
        renderDocInto(host, entry.doc, mediaUrlsRef.current);
        ensureMediaUrls(entry.doc);
        host.focus({ preventScroll: true });
        writeSelection(host, selectionRef.current);
      }
      emit(entry.doc);
      refreshChrome();
    },
    [emit, ensureMediaUrls, refreshChrome]
  );

  const undo = useCallback(() => {
    const history = historyRef.current;
    if (history.index <= 0) return;
    history.index -= 1;
    history.lastLabel = "";
    applyHistoryEntry(history.entries[history.index]);
  }, [applyHistoryEntry]);

  const redo = useCallback(() => {
    const history = historyRef.current;
    if (history.index >= history.entries.length - 1) return;
    history.index += 1;
    history.lastLabel = "";
    applyHistoryEntry(history.entries[history.index]);
  }, [applyHistoryEntry]);

  const syncFromDom = useCallback(() => {
    const host = hostRef.current;
    if (!host) return;
    const doc = normaliseForEditing(readDocFrom(host));
    docRef.current = doc;
    const range = readSelection(host);
    if (range) selectionRef.current = clampRange(doc, range);
    pendingMarksRef.current = null;
    if (doc.blocks.some((block) => block.kind === "IMAGE")) paintCaptionStates(host);

    const last = lastInputRef.current;
    if (last.type === "insertText" && last.data === " ") {
      const rule = applyInputRule(doc, selectionRef.current);
      if (rule) {
        commit(rule, "rule");
        return;
      }
    }

    if (emit(doc)) pushHistory(doc, selectionRef.current, "type");
    refreshChrome();
  }, [commit, emit, pushHistory, refreshChrome]);

  const seedFrom = useCallback(
    (incoming: unknown) => {
      const host = hostRef.current;
      const doc = normaliseForEditing(
        listKind ? seedListDocument(incoming, listKind) : fromStored(incoming)
      );
      const previous = selectionRef.current;
      docRef.current = doc;
      emittedSignatureRef.current = storedSignature(incoming);
      selectionRef.current = clampRange(doc, previous);
      if (host) {
        renderDocInto(host, doc, mediaUrlsRef.current);
        ensureMediaUrls(doc);
        if (host.contains(document.activeElement)) writeSelection(host, selectionRef.current);
      }
      historyRef.current = { entries: [{ doc, selection: selectionRef.current }], index: 0, lastAt: 0, lastLabel: "" };
      refreshChrome();
    },
    [ensureMediaUrls, refreshChrome, listKind]
  );

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    renderDocInto(host, docRef.current, mediaUrlsRef.current);
    ensureMediaUrls(docRef.current);
    refreshChrome();
  }, [ensureMediaUrls, refreshChrome]);

  useEffect(() => {
    const incoming = storedSignature(value);
    if (incoming === emittedSignatureRef.current) return;
    if (composingRef.current) {
      pendingExternalRef.current = { value };
      return;
    }
    seedFrom(value);
  }, [value, seedFrom]);

  const currentRange = useCallback((): DocRange => {
    const host = hostRef.current;
    const fromDom = host ? readSelection(host) : null;
    if (fromDom) return clampRange(docRef.current, fromDom);
    return clampRange(docRef.current, selectionRef.current);
  }, []);

  const runMark = useCallback(
    (mark: RichMark) => {
      if (propsRef.current.disabled) return;
      const range = currentRange();
      selectionRef.current = range;
      if (isCollapsed(range)) {
        const block = docRef.current.blocks[range.focus.block];
        const current = pendingMarksRef.current ?? (block ? marksAt(block, range.focus.offset) : []);
        const excluded = markExcludes(mark);
        pendingMarksRef.current = current.includes(mark)
          ? current.filter((item) => item !== mark)
          : sortMarks([...current.filter((item) => item !== excluded), mark]);
        refreshChrome();
        return;
      }
      commit(toggleMark(docRef.current, range, mark), "mark");
    },
    [commit, currentRange, refreshChrome]
  );

  const runBlock = useCallback(
    (kind: RichBlockKind, level = 0) => {
      if (propsRef.current.disabled) return;
      const range = currentRange();
      selectionRef.current = range;
      const state = toolbarState(docRef.current, range, null);
      const alreadyThis = state.kind === kind && (kind !== "HEADING" || state.level === level);
      commit(setBlockKind(docRef.current, range, alreadyThis ? "PARAGRAPH" : kind, level), "block");
    },
    [commit, currentRange]
  );

  const runAlign = useCallback(
    (align: RichAlign) => {
      if (propsRef.current.disabled) return;
      const range = currentRange();
      selectionRef.current = range;
      commit(setAlign(docRef.current, range, align), "align");
    },
    [commit, currentRange]
  );

  const runList = useCallback(
    (ordered: boolean) => {
      if (propsRef.current.disabled) return;
      const range = currentRange();
      selectionRef.current = range;
      commit(toggleList(docRef.current, range, ordered), "list");
    },
    [commit, currentRange]
  );

  const runTable = useCallback(() => {
    if (propsRef.current.disabled) return;
    const range = currentRange();
    selectionRef.current = range;
    commit(insertTable(docRef.current, range), "table");
  }, [commit, currentRange]);

  const tableCursor = useCallback((): { block: number; row: number; column: number } | null => {
    const host = hostRef.current;
    const node = window.getSelection()?.focusNode;
    if (!host || !node || !host.contains(node)) return null;
    const element = node.nodeType === Node.TEXT_NODE ? node.parentElement : (node as Element);
    const cell = element?.closest("td, th");
    const tr = cell?.closest("tr");
    const table = cell?.closest<HTMLElement>("table");
    if (!cell || !tr || !table) return null;
    const block = Number(table.getAttribute(BLOCK_ATTRIBUTE));
    if (!Number.isFinite(block)) return null;
    return {
      block,
      row: Array.from(table.querySelectorAll("tr")).indexOf(tr),
      column: Array.from(tr.children).indexOf(cell)
    };
  }, []);

  const runTableEdit = useCallback(
    (
      edit: (doc: RichDoc, block: number, index: number) => EditResult,
      axis: "row" | "column",
      offset = 0
    ) => {
      if (propsRef.current.disabled) return;
      const cursor = tableCursor();
      if (!cursor) return;
      const index = (axis === "row" ? cursor.row : cursor.column) + offset;
      commit(edit(docRef.current, cursor.block, index), "table-structure");

      requestAnimationFrame(() => {
        const host = hostRef.current;
        const table = host?.querySelector<HTMLElement>(`table[${BLOCK_ATTRIBUTE}="${cursor.block}"]`);
        if (!table) return;
        const rows = Array.from(table.querySelectorAll("tr"));
        const row = rows[Math.min(cursor.row, rows.length - 1)];
        const cells = Array.from(row?.children ?? []);
        const cell = cells[Math.min(cursor.column, cells.length - 1)];
        if (!(cell instanceof HTMLElement)) return;
        const domRange = document.createRange();
        domRange.setStart(cell, 0);
        domRange.collapse(true);
        const selection = window.getSelection();
        selection?.removeAllRanges();
        selection?.addRange(domRange);
      });
    },
    [commit, tableCursor]
  );

  const commitDictated = useCallback(
    (text: string) => {
      const phrase = text.trim();
      if (!phrase) return;
      const range = selectionRef.current;
      const block = docRef.current.blocks[range.focus.block];
      const before = block ? blockText(block).slice(0, range.focus.offset) : "";
      const joiner = !before || /\s$/.test(before) ? "" : " ";
      const marks = pendingMarksRef.current ?? (block ? marksAt(block, range.focus.offset) : []);
      commit(insertText(docRef.current, range, `${joiner}${phrase}`, marks), "dictate");
    },
    [commit]
  );

  const runImagePick = useCallback(() => {
    if (propsRef.current.disabled || !propsRef.current.upload) return;
    imageAnchorRef.current = currentRange();
    selectionRef.current = imageAnchorRef.current;
    if (fileRef.current) fileRef.current.value = "";
    fileRef.current?.click();
  }, [currentRange]);

  const runImageFile = useCallback(
    async (file: File) => {
      const target = propsRef.current.upload;
      if (!target || propsRef.current.disabled) return;
      if (!file.type.startsWith("image/")) {
        setNotice(`“${file.name}” isn’t a photo. Add other files under media.`);
        return;
      }
      setNotice(null);
      setUploading(true);
      try {
        const { uploaded, failed } = await uploadMediaBatch({
          files: [file],
          linkedRecordType: target.recordType,
          linkedRecordId: target.recordId,
          caption: propsRef.current.ariaLabel ?? undefined
        });
        const media = uploaded[0];
        if (!media) {
          setNotice(
            failed[0]?.error
              ? `Photo didn’t upload: ${failed[0].error}`
              : "Photo didn’t upload. Try again."
          );
          return;
        }
        mediaUrlsRef.current.set(media.id, media.url ?? null);
        const range = imageAnchorRef.current ?? selectionRef.current;
        hostRef.current?.focus({ preventScroll: true });
        commit(insertImage(docRef.current, range, media.id), "image");
        setNotice(
          "Photo added. Type its caption below it."
        );
      } catch (error) {
        setNotice(
          error instanceof Error
            ? `That photograph did not upload: ${error.message}`
            : "That photograph did not upload."
        );
      } finally {
        setUploading(false);
      }
    },
    [commit]
  );

  const runImageEdit = useCallback(
    (edit: (doc: RichDoc, block: number) => EditResult) => {
      if (propsRef.current.disabled) return;
      const range = currentRange();
      selectionRef.current = range;
      const index = range.focus.block;
      if (docRef.current.blocks[index]?.kind !== "IMAGE") return;
      commit(edit(docRef.current, index), "image-structure");
    },
    [commit, currentRange]
  );

  const runIndent = useCallback(
    (delta: number) => {
      if (propsRef.current.disabled) return;
      const range = currentRange();
      selectionRef.current = range;
      commit(shiftIndent(docRef.current, range, delta), "indent");
    },
    [commit, currentRange]
  );

  const runClear = useCallback(() => {
    if (propsRef.current.disabled) return;
    const range = currentRange();
    selectionRef.current = range;
    pendingMarksRef.current = null;
    commit(clearFormatting(docRef.current, range), "clear");
  }, [commit, currentRange]);

  const revealMatchAt = useCallback((position: number, matches: ReturnType<typeof findMatches>) => {
    const host = hostRef.current;
    if (!host || !matches.length) return;
    const match = matches[((position % matches.length) + matches.length) % matches.length];
    const range = matchToRange(match);
    selectionRef.current = range;
    host.focus({ preventScroll: true });
    writeSelection(host, range);
    blockElementAt(host, match.block)?.scrollIntoView({ block: "nearest", behavior: "auto" });
  }, []);

  const stepMatch = useCallback(
    (delta: number) => {
      const state = findRef.current;
      const matches = findMatches(docRef.current, state.query, {
        caseSensitive: state.caseSensitive,
        wholeWord: state.wholeWord
      });
      if (!matches.length) {
        setFind((current) => ({ ...current, count: 0, index: 0 }));
        return;
      }
      const next = ((state.index + delta) % matches.length + matches.length) % matches.length;
      setFind((current) => ({ ...current, count: matches.length, index: next }));
      revealMatchAt(next, matches);
    },
    [revealMatchAt]
  );

  const runReplaceOne = useCallback(() => {
    if (propsRef.current.disabled) return;
    const state = findRef.current;
    const options = { caseSensitive: state.caseSensitive, wholeWord: state.wholeWord };
    const matches = findMatches(docRef.current, state.query, options);
    if (!matches.length) return;
    const position = ((state.index % matches.length) + matches.length) % matches.length;
    commit(replaceMatch(docRef.current, matches[position], state.replacement), "replace");
    const remaining = findMatches(docRef.current, state.query, options);
    setFind((current) => ({ ...current, count: remaining.length, index: Math.min(position, Math.max(0, remaining.length - 1)) }));
    if (remaining.length) revealMatchAt(Math.min(position, remaining.length - 1), remaining);
  }, [commit, revealMatchAt]);

  const runReplaceAll = useCallback(() => {
    if (propsRef.current.disabled) return;
    const state = findRef.current;
    const result = replaceAllInDoc(docRef.current, state.query, state.replacement, {
      caseSensitive: state.caseSensitive,
      wholeWord: state.wholeWord
    });
    if (!result.count) return;
    commit({ doc: result.doc, selection: clampRange(result.doc, selectionRef.current) }, "replace-all");
    setFind((current) => ({ ...current, count: 0, index: 0 }));
    setNotice(`Replaced ${result.count} occurrence${result.count === 1 ? "" : "s"}.`);
  }, [commit]);

  useEffect(() => {
    if (!find.open || !find.query) return;
    const matches = findMatches(docRef.current, find.query, {
      caseSensitive: find.caseSensitive,
      wholeWord: find.wholeWord
    });
    setFind((current) => (current.count === matches.length ? current : { ...current, count: matches.length, index: 0 }));
  }, [find.open, find.query, find.caseSensitive, find.wholeWord]);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;

    function onBeforeInput(event: InputEvent) {
      if (propsRef.current.disabled) {
        event.preventDefault();
        return;
      }
      const surface = hostRef.current;
      if (!surface) return;
      const range = readSelection(surface) ?? selectionRef.current;
      selectionRef.current = clampRange(docRef.current, range);
      const current = selectionRef.current;
      lastInputRef.current = { type: event.inputType, data: event.data };

      if (docRef.current.blocks[current.focus.block]?.kind === "TABLE") return;

      switch (event.inputType) {
        case "insertText": {
          if (composingRef.current) return;
          if (isCollapsed(current) && pendingMarksRef.current === null) return;
          event.preventDefault();
          const block = docRef.current.blocks[current.focus.block];
          const marks = pendingMarksRef.current ?? (block ? marksAt(block, current.focus.offset) : []);
          commit(insertText(docRef.current, current, event.data ?? "", marks), "type");
          return;
        }

        case "insertCompositionText":
        case "insertReplacementText":
        case "insertTranspose":
          return;

        case "insertParagraph":
        case "insertLineBreak":
          event.preventDefault();
          commit(splitBlock(docRef.current, current), "split");
          return;

        case "deleteContentBackward":
        case "deleteWordBackward":
        case "deleteSoftLineBackward":
        case "deleteHardLineBackward": {
          if (composingRef.current) return;
          if (isCollapsed(current) && current.focus.offset > 0) return;
          event.preventDefault();
          commit(isCollapsed(current) ? deleteBackward(docRef.current, current) : deleteRange(docRef.current, current), "delete");
          return;
        }

        case "deleteContentForward":
        case "deleteWordForward":
        case "deleteSoftLineForward":
        case "deleteHardLineForward": {
          if (composingRef.current) return;
          const block = docRef.current.blocks[current.focus.block];
          if (isCollapsed(current) && block && current.focus.offset < blockLength(block)) return;
          event.preventDefault();
          commit(isCollapsed(current) ? deleteForward(docRef.current, current) : deleteRange(docRef.current, current), "delete");
          return;
        }

        case "deleteByCut":
        case "deleteByDrag":
          event.preventDefault();
          commit(deleteRange(docRef.current, current), "delete");
          return;

        case "formatBold":
          event.preventDefault();
          runMark("BOLD");
          return;
        case "formatItalic":
          event.preventDefault();
          runMark("ITALIC");
          return;
        case "formatUnderline":
          event.preventDefault();
          runMark("UNDERLINE");
          return;
        case "formatStrikeThrough":
          event.preventDefault();
          runMark("STRIKETHROUGH");
          return;

        case "historyUndo":
          event.preventDefault();
          undo();
          return;
        case "historyRedo":
          event.preventDefault();
          redo();
          return;

        default:
          if (event.inputType.startsWith("format") || event.inputType.startsWith("insert")) event.preventDefault();
      }
    }

    function onInput() {
      if (composingRef.current) return;
      syncFromDom();
    }

    function onCompositionStart() {
      composingRef.current = true;
    }

    function onCompositionEnd() {
      composingRef.current = false;
      syncFromDom();
      const parked = pendingExternalRef.current;
      if (parked) {
        pendingExternalRef.current = null;
        if (storedSignature(parked.value) !== emittedSignatureRef.current) seedFrom(parked.value);
      }
    }

    function onPaste(event: ClipboardEvent) {
      if (propsRef.current.disabled) return;
      event.preventDefault();
      const data = event.clipboardData;
      if (!data) return;
      const plain = data.getData("text/plain");
      let text = plain;
      let hadMarkup = false;
      if (!text) {
        const html = data.getData("text/html");
        if (html) {
          hadMarkup = true;
          text = new DOMParser().parseFromString(html, "text/html").body.textContent ?? "";
        }
      } else if (data.types.includes("text/html")) {
        hadMarkup = true;
      }
      if (!text) return;

      const incoming = docFromPastedText(text);
      if (!incoming.blocks.length) return;

      const surface = hostRef.current;
      const range = surface ? clampRange(docRef.current, readSelection(surface) ?? selectionRef.current) : selectionRef.current;
      const result = insertDoc(docRef.current, range, incoming);
      const size = countDoc(result.doc);
      if (size.characters > MAX_DOCUMENT_CHARS || result.doc.blocks.length > MAX_BLOCKS) {
        setNotice(
          `Too long to paste — ${size.characters.toLocaleString()} of ${MAX_DOCUMENT_CHARS.toLocaleString()} characters.`
        );
        return;
      }
      commit(result, "paste");
      setNotice(
        hadMarkup
          ? "Pasted as plain text."
          : null
      );
    }

    function onKeyDown(event: KeyboardEvent) {
      const modifier = event.metaKey || event.ctrlKey;

      if (event.key === "Escape") {
        if (findRef.current.open) {
          event.preventDefault();
          holdSurfaceStill();
          setFind((current) => ({ ...current, open: false }));
          hostRef.current?.focus({ preventScroll: true });
          return;
        }
        event.preventDefault();
        const firstButton = toolbarRef.current?.querySelector<HTMLButtonElement>('button[tabindex="0"], button:not([disabled])');
        firstButton?.focus();
        return;
      }

      const tableCell = (() => {
        const selection = window.getSelection();
        const node = selection?.focusNode;
        if (!node || !hostRef.current?.contains(node)) return null;
        const element = node.nodeType === Node.TEXT_NODE ? node.parentElement : (node as Element);
        return element?.closest("td, th") ?? null;
      })();

      if (tableCell && (event.key === "Tab" || event.key === "Enter") && !modifier) {
        event.preventDefault();
        const cells = Array.from(tableCell.closest("table")?.querySelectorAll("td, th") ?? []);
        const position = cells.indexOf(tableCell);
        const width = tableCell.closest("tr")?.children.length ?? 1;
        const step = event.key === "Enter" ? width : 1;
        const next = cells[position + (event.shiftKey ? -step : step)];
        if (next instanceof HTMLElement) {
          const walker = document.createTreeWalker(next, NodeFilter.SHOW_TEXT);
          const text = walker.nextNode();
          const range = document.createRange();
          if (text) range.setStart(text, text.textContent?.length ?? 0);
          else range.setStart(next, 0);
          range.collapse(true);
          const selection = window.getSelection();
          selection?.removeAllRanges();
          selection?.addRange(range);
        }
        return;
      }

      if (event.key === "Tab" && !modifier) {
        const range = currentRange();
        const block = docRef.current.blocks[range.focus.block];
        if (!block || !isListKind(block.kind)) return;
        event.preventDefault();
        runIndent(event.shiftKey ? -1 : 1);
        return;
      }

      if (!modifier) return;

      const key = event.key.toLowerCase();
      if (key === "b") {
        event.preventDefault();
        runMark("BOLD");
      } else if (key === "i") {
        event.preventDefault();
        runMark("ITALIC");
      } else if (key === "u") {
        event.preventDefault();
        runMark("UNDERLINE");
      } else if (key === "x" && event.shiftKey) {
        event.preventDefault();
        runMark("STRIKETHROUGH");
      } else if (key === "7" && event.shiftKey) {
        event.preventDefault();
        runList(true);
      } else if (key === "8" && event.shiftKey) {
        event.preventDefault();
        runList(false);
      } else if (key === "z" && !event.shiftKey) {
        event.preventDefault();
        undo();
      } else if ((key === "z" && event.shiftKey) || key === "y") {
        event.preventDefault();
        redo();
      } else if (key === "f") {
        event.preventDefault();
        holdSurfaceStill();
        setFind((current) => ({ ...current, open: true }));
      } else if (key === "\\") {
        event.preventDefault();
        runClear();
      }
    }

    function onSelectionChange() {
      const surface = hostRef.current;
      if (!surface) return;
      const next = readSelection(surface);
      if (!next) return;
      const moved = !rangesEqual(next, selectionRef.current);
      selectionRef.current = next;
      if (moved) pendingMarksRef.current = null;
      refreshChrome();
    }

    host.addEventListener("beforeinput", onBeforeInput as EventListener);
    host.addEventListener("input", onInput);
    host.addEventListener("compositionstart", onCompositionStart);
    host.addEventListener("compositionend", onCompositionEnd);
    host.addEventListener("paste", onPaste as EventListener);
    host.addEventListener("keydown", onKeyDown);
    document.addEventListener("selectionchange", onSelectionChange);
    return () => {
      host.removeEventListener("beforeinput", onBeforeInput as EventListener);
      host.removeEventListener("input", onInput);
      host.removeEventListener("compositionstart", onCompositionStart);
      host.removeEventListener("compositionend", onCompositionEnd);
      host.removeEventListener("paste", onPaste as EventListener);
      host.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("selectionchange", onSelectionChange);
    };
  }, [commit, currentRange, holdSurfaceStill, redo, refreshChrome, runClear, runIndent, runList, runMark, seedFrom, syncFromDom, undo]);

  useEffect(() => {
    function evaluate() {
      const element = document.activeElement;
      const inside = !!element && (hostRef.current?.contains(element) === true || toolbarRef.current?.contains(element) === true);
      setActive((current) => {
        if (current !== inside) holdSurfaceStill();
        return inside;
      });
    }
    function onFocusOut() {
      window.requestAnimationFrame(evaluate);
    }
    document.addEventListener("focusin", evaluate);
    document.addEventListener("focusout", onFocusOut);
    return () => {
      document.removeEventListener("focusin", evaluate);
      document.removeEventListener("focusout", onFocusOut);
    };
  }, [holdSurfaceStill]);

  useEffect(() => {
    if (!fullscreen) return;
    const root = document.documentElement;
    const gutter = window.innerWidth - root.clientWidth;
    root.style.setProperty("--nav-scroll-gutter", `${gutter}px`);
    root.classList.add("nav-scroll-locked");
    return () => {
      root.classList.remove("nav-scroll-locked");
      root.style.removeProperty("--nav-scroll-gutter");
    };
  }, [fullscreen]);

  useEffect(() => {
    if (!fullscreen) return;
    function onKey(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      if (hostRef.current?.contains(document.activeElement)) return;
      setFullscreen(false);
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [fullscreen]);

  const bar = chrome.bar;
  const items: ToolItem[] = useMemo(() => {
    const markButton = (mark: RichMark, label: string, hint: string, icon: React.ReactNode): ToolItem => ({
      kind: "button",
      id: `mark-${mark}`,
      label,
      hint,
      icon,
      active: bar.marks.includes(mark),
      run: () => runMark(mark)
    });
    const headingButton = (level: number, icon: React.ReactNode): ToolItem => ({
      kind: "button",
      id: `heading-${level}`,
      label: `Heading ${level}`,
      icon,
      active: bar.kind === "HEADING" && bar.level === level,
      run: () => runBlock("HEADING", level)
    });
    const alignButton = (align: RichAlign, label: string, icon: React.ReactNode): ToolItem => ({
      kind: "button",
      id: `align-${align}`,
      label,
      icon,
      active: bar.align === align,
      run: () => runAlign(align)
    });

    return [
      markButton("BOLD", "Bold", "Ctrl+B", <Bold className={ICON} aria-hidden />),
      markButton("ITALIC", "Italic", "Ctrl+I", <Italic className={ICON} aria-hidden />),
      markButton("UNDERLINE", "Underline", "Ctrl+U", <Underline className={ICON} aria-hidden />),
      markButton("STRIKETHROUGH", "Strikethrough", "Ctrl+Shift+X", <Strikethrough className={ICON} aria-hidden />),
      markButton("CODE", "Inline code", "prints in the body face in the .docx", <Code className={ICON} aria-hidden />),
      markButton("SUPERSCRIPT", "Superscript", "m², a footnote marker", <Superscript className={ICON} aria-hidden />),
      markButton("SUBSCRIPT", "Subscript", "H₂O", <Subscript className={ICON} aria-hidden />),
      markButton("HIGHLIGHT", "Highlight", "yellow, the one Word draws", <Highlighter className={ICON} aria-hidden />),
      { kind: "separator", id: "sep-1" },
      {
        kind: "button",
        id: "block-paragraph",
        label: "Paragraph",
        icon: <Pilcrow className={ICON} aria-hidden />,
        active: bar.kind === "PARAGRAPH",
        run: () => runBlock("PARAGRAPH")
      },
      headingButton(1, <Heading1 className={ICON} aria-hidden />),
      headingButton(2, <Heading2 className={ICON} aria-hidden />),
      headingButton(3, <Heading3 className={ICON} aria-hidden />),
      headingButton(4, <Heading4 className={ICON} aria-hidden />),
      {
        kind: "button",
        id: "block-quote",
        label: "Quote",
        icon: <Quote className={ICON} aria-hidden />,
        active: bar.kind === "QUOTE",
        run: () => runBlock("QUOTE")
      },
      {
        kind: "button",
        id: "insert-table",
        label: "Insert table",
        hint: "3×3, first row is the header",
        icon: <Table className={ICON} aria-hidden />,
        active: bar.kind === "TABLE",
        run: () => runTable()
      },
      ...(upload
        ? ([
            {
              kind: "button",
              id: "insert-image",
              label: uploading ? "Placing the photograph…" : "Place a photograph",
              hint: "with a caption",
              icon: <ImagePlus className={ICON} aria-hidden />,
              disabled: uploading,
              run: runImagePick
            }
          ] as ToolItem[])
        : []),
      ...(bar.kind === "IMAGE"
        ? ([
            { kind: "separator", id: "sep-image" },
            {
              kind: "button",
              id: "image-narrower",
              label: "Narrower",
              icon: <Shrink className={ICON} aria-hidden />,
              run: () => runImageEdit((doc, block) => setImageWidth(doc, block, -IMAGE_WIDTH_STEP_PCT))
            },
            {
              kind: "button",
              id: "image-wider",
              label: "Wider",
              icon: <Expand className={ICON} aria-hidden />,
              run: () => runImageEdit((doc, block) => setImageWidth(doc, block, IMAGE_WIDTH_STEP_PCT))
            },
            {
              kind: "button",
              id: "image-remove",
              label: "Remove the photograph",
              hint: "the caption goes with it",
              icon: <Trash2 className={ICON} aria-hidden />,
              run: () => runImageEdit(removeImage)
            }
          ] as ToolItem[])
        : []),
      ...(bar.kind === "TABLE"
        ? ([
            { kind: "separator", id: "sep-table" },
            {
              kind: "button",
              id: "table-row-add",
              label: "Insert row below",
              icon: <Rows3 className={ICON} aria-hidden />,
              run: () => runTableEdit(insertTableRow, "row", 1)
            },
            {
              kind: "button",
              id: "table-row-remove",
              label: "Delete this row",
              icon: <Rows2 className={ICON} aria-hidden />,
              run: () => runTableEdit(removeTableRow, "row")
            },
            {
              kind: "button",
              id: "table-column-add",
              label: "Insert column right",
              icon: <Columns3 className={ICON} aria-hidden />,
              run: () => runTableEdit(insertTableColumn, "column", 1)
            },
            {
              kind: "button",
              id: "table-column-remove",
              label: "Delete this column",
              icon: <Columns2 className={ICON} aria-hidden />,
              run: () => runTableEdit(removeTableColumn, "column")
            },
            {
              kind: "button",
              id: "table-remove",
              label: "Delete the table",
              icon: <Trash2 className={ICON} aria-hidden />,
              run: () => runTableEdit((doc, block) => removeTable(doc, block), "row")
            }
          ] as ToolItem[])
        : []),
      { kind: "separator", id: "sep-2" },
      {
        kind: "button",
        id: "list-bullet",
        label: "Bulleted list",
        hint: "Ctrl+Shift+8",
        icon: <List className={ICON} aria-hidden />,
        active: bar.kind === "BULLET_ITEM",
        run: () => runList(false)
      },
      {
        kind: "button",
        id: "list-ordered",
        label: "Numbered list",
        hint: "Ctrl+Shift+7",
        icon: <ListOrdered className={ICON} aria-hidden />,
        active: bar.kind === "ORDERED_ITEM",
        run: () => runList(true)
      },
      {
        kind: "button",
        id: "outdent",
        label: "Outdent",
        hint: "Shift+Tab",
        icon: <ListIndentDecrease className={ICON} aria-hidden />,
        disabled: !bar.canOutdent,
        run: () => runIndent(-1)
      },
      {
        kind: "button",
        id: "indent",
        label: "Indent",
        hint: "Tab",
        icon: <ListIndentIncrease className={ICON} aria-hidden />,
        disabled: !bar.canIndent,
        run: () => runIndent(1)
      },
      { kind: "separator", id: "sep-3" },
      alignButton("LEFT", "Align left", <TextAlignStart className={ICON} aria-hidden />),
      alignButton("CENTER", "Align centre", <TextAlignCenter className={ICON} aria-hidden />),
      alignButton("RIGHT", "Align right", <TextAlignEnd className={ICON} aria-hidden />),
      alignButton("JUSTIFY", "Justify", <TextAlignJustify className={ICON} aria-hidden />),
      { kind: "separator", id: "sep-4" },
      {
        kind: "button",
        id: "clear",
        label: "Clear formatting",
        hint: "Ctrl+\\",
        icon: <Eraser className={ICON} aria-hidden />,
        run: runClear
      },
      {
        kind: "button",
        id: "undo",
        label: "Undo",
        hint: "Ctrl+Z",
        icon: <Undo2 className={ICON} aria-hidden />,
        disabled: !chrome.canUndo,
        run: undo
      },
      {
        kind: "button",
        id: "redo",
        label: "Redo",
        hint: "Ctrl+Shift+Z",
        icon: <Redo2 className={ICON} aria-hidden />,
        disabled: !chrome.canRedo,
        run: redo
      },
      { kind: "separator", id: "sep-5" },
      {
        kind: "button",
        id: "find",
        label: "Find and replace",
        hint: "Ctrl+F",
        icon: <Search className={ICON} aria-hidden />,
        active: find.open,
        run: () => setFind((current) => ({ ...current, open: !current.open }))
      },
      {
        kind: "button",
        id: "fullscreen",
        label: fullscreen ? "Leave full screen" : "Full screen",
        hint: "Escape leaves",
        icon: fullscreen ? <Minimize2 className={ICON} aria-hidden /> : <Maximize2 className={ICON} aria-hidden />,
        active: fullscreen,
        run: () => setFullscreen((current) => !current)
      }
    ];
  }, [bar, chrome.canUndo, chrome.canRedo, find.open, fullscreen, redo, runAlign, runBlock, runClear, runImageEdit, runImagePick, runIndent, runList, runMark, runTable, runTableEdit, undo, upload, uploading]);

  const dismissToolbar = useCallback(() => {
    hostRef.current?.focus({ preventScroll: true });
  }, []);

  const toolbar =
    disabled || !active ? null : (
      <Toolbar items={items} editorId={editorId} onDismiss={dismissToolbar} containerRef={toolbarRef} />
    );

  const overLimit = typeof maxLength === "number" && maxLength > 0 && chrome.counts.characters > maxLength;

  return (
    <div
      className={
        fullscreen
          ? "fixed inset-0 z-100 flex flex-col gap-2 overflow-y-auto bg-bg-0 p-4 sm:p-6"
          : "grid min-w-0 gap-2"
      }
      style={fullscreen ? { paddingRight: "calc(1rem + var(--nav-scroll-gutter, 0px))" } : undefined}
    >
      {fullscreen ? (
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-sm font-medium text-ink-900">{ariaLabel ?? "Full screen"}</p>
          <button type="button" className="field-button-secondary" onClick={() => setFullscreen(false)}>
            <Minimize2 className={ICON} aria-hidden />
            Leave full screen
          </button>
        </div>
      ) : null}

      {toolbar}

      {find.open ? (
        <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-2 sm:grid-cols-[1fr_1fr_auto]">
          <input
            className="field-input"
            type="text"
            aria-label="Find"
            placeholder="Find"
            value={find.query}
            onChange={(event) => setFind((current) => ({ ...current, query: event.target.value, index: 0 }))}
            onKeyDown={(event) => {
              if (event.key !== "Enter") return;
              event.preventDefault();
              event.stopPropagation();
              stepMatch(event.shiftKey ? -1 : 1);
            }}
          />
          <input
            className="field-input"
            type="text"
            aria-label="Replace with"
            placeholder="Replace with"
            value={find.replacement}
            onChange={(event) => setFind((current) => ({ ...current, replacement: event.target.value }))}
            onKeyDown={(event) => {
              if (event.key !== "Enter") return;
              event.preventDefault();
              event.stopPropagation();
              runReplaceOne();
            }}
          />
          <div className="flex flex-wrap items-center gap-1">
            <button type="button" className="field-button-secondary min-h-8 px-2 py-1 text-xs" onClick={() => stepMatch(-1)}>
              Previous
            </button>
            <button type="button" className="field-button-secondary min-h-8 px-2 py-1 text-xs" onClick={() => stepMatch(1)}>
              Next
            </button>
            <button
              type="button"
              className="field-button-secondary min-h-8 px-2 py-1 text-xs"
              disabled={!find.count}
              onClick={runReplaceOne}
            >
              <Replace className="h-3.5 w-3.5" aria-hidden />
              Replace
            </button>
            <button
              type="button"
              className="field-button-secondary min-h-8 px-2 py-1 text-xs"
              disabled={!find.count}
              onClick={runReplaceAll}
            >
              <ReplaceAll className="h-3.5 w-3.5" aria-hidden />
              All
            </button>
            <button
              type="button"
              aria-label="Close find and replace"
              className="grid h-8 w-8 place-items-center rounded-md border border-line-200 text-ink-700 transition hover:bg-card"
              onClick={() => {
                holdSurfaceStill();
                setFind((current) => ({ ...current, open: false }));
              }}
            >
              <X className="h-3.5 w-3.5" aria-hidden />
            </button>
          </div>
          <div className="sm:col-span-3 flex flex-wrap items-center gap-3 text-xs text-ink-500">
            <Toggle
              className="w-auto gap-2"
              label="Match case"
              checked={find.caseSensitive}
              onChange={(next) => setFind((current) => ({ ...current, caseSensitive: next, index: 0 }))}
            />
            <Toggle
              className="w-auto gap-2"
              label="Whole words"
              checked={find.wholeWord}
              onChange={(next) => setFind((current) => ({ ...current, wholeWord: next, index: 0 }))}
            />
            <span aria-live="polite">
              {find.query ? (find.count ? `${Math.min(find.index + 1, find.count)} of ${find.count}` : "No matches") : ""}
            </span>
          </div>
        </div>
      ) : null}

      <div className="relative min-w-0">
        {chrome.showPlaceholder && !disabled ? (
          <p aria-hidden className="pointer-events-none absolute left-3.5 top-2.5 text-sm leading-6 text-ink-300">
            {placeholder}
          </p>
        ) : null}
        <div
          id={editorId}
          ref={hostRef}
          role="textbox"
          aria-multiline="true"
          aria-label={ariaLabelledBy ? undefined : ariaLabel}
          aria-labelledby={ariaLabelledBy}
          aria-describedby={countId}
          aria-readonly={disabled || undefined}
          contentEditable={!disabled}
          suppressContentEditableWarning
          spellCheck
          tabIndex={0}
          className={cn(
            "field-input min-h-32 whitespace-pre-wrap wrap-break-word",
            fullscreen ? "min-h-0 flex-1 overflow-y-auto" : "max-h-128 overflow-y-auto",
            disabled ? "cursor-not-allowed opacity-70" : ""
          )}
        />
      </div>

      {upload ? (
        <input
          ref={fileRef}
          type="file"
          accept="image/*"
          hidden
          tabIndex={-1}
          data-rte-image-input=""
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) void runImageFile(file);
          }}
        />
      ) : null}

      <div className="flex flex-wrap items-center justify-between gap-2">
        <p id={countId} className={cn("text-xs", overLimit ? "font-medium text-error-600" : "text-ink-500")}>
          {chrome.counts.words.toLocaleString()} word{chrome.counts.words === 1 ? "" : "s"} ·{" "}
          {chrome.counts.characters.toLocaleString()} character{chrome.counts.characters === 1 ? "" : "s"}
          {typeof maxLength === "number" && maxLength > 0 ? ` of ${maxLength.toLocaleString()} suggested` : ""}
        </p>
        {!disabled ? <OnDeviceDictationButton fieldLabel={ariaLabel ?? "this field"} explainWhenUnavailable={explainWhenUnavailable} onCommit={commitDictated} /> : null}
        <button
          type="button"
          className="inline-flex items-center gap-1.5 text-xs font-medium text-purple-700 dark:text-purple-300 transition hover:text-purple-800"
          aria-expanded={savedPanelOpen}
          aria-controls={savedPanelOpen ? `${editorId}-saved` : undefined}
          onClick={() => setSavedPanelOpen((current) => !current)}
        >
          <Info className="h-3.5 w-3.5" aria-hidden />
          What gets saved
        </button>
      </div>

      {savedPanelOpen ? (
        <div id={`${editorId}-saved`} className="rounded-md border border-line-200 bg-surface-50 p-3 text-xs leading-5 text-ink-700">
          <p>Formatting, tables and photos are kept. Pasted text keeps only words and lists.</p>
        </div>
      ) : null}

      {notice ? (
        <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">{notice}</p>
      ) : null}

    </div>
  );
}

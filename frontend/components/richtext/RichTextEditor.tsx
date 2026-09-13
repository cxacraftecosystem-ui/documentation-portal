"use client";

/**
 * The rich-text editing surface — the DOM half. `lib/richText.ts` is the other half and owns the
 * document, the selection arithmetic and every command; read that file's header first.
 *
 * WHERE IT CAME FROM, AND WHAT IS DIFFERENT HERE — READ THIS BEFORE THE COMMENTS BELOW.
 * This file and `lib/richText.ts` are a port from the Design Prototype Workshop application, which
 * shares this codebase's lineage. There, a stored RichDoc is read back by a Python model
 * (`rich_text.py`), an Android model (`RichText.kt`) and a report writer that prints it into .docx
 * and PDF, and many of the sizing, table-styling and capability arguments below are justified by
 * what that writer can draw. **Field Repository has no report writer and no server-side model at
 * all.** It has exactly two readers of a stored document: `frontend/lib/richText.ts`, which arrived
 * with this file, and the Android port in `android/…/ui/richtext/`, done in the same batch of work
 * and holding the same rules on purpose.
 *
 * The .docx arguments are kept, not deleted, and deliberately: they are the reason the model has the
 * shape it has, and the shape is what a future backend or Android port has to match. Read "the
 * .docx" below as "the printed document this model exists to be printable into". What you must NOT
 * do is conclude from them that anything here is already rendered server-side — it is not, and the
 * consequence of that is spelt out in `components/richtext/storedRichText.ts`: a document is written
 * into a `String?` column ONLY when the researcher has actually formatted something, because nothing
 * outside this browser tab can read one back.
 *
 * WHAT THIS IS. A `contenteditable` whose children are NOT managed by React, plus a CONTEXTUAL
 * toolbar that appears only while this editor holds the focus or the selection and disappears when
 * it does not. The product form carries four narrative fields; a toolbar drawn permanently above
 * each of them is four toolbars on one screen, which is both unusable and not what was asked for.
 * One bar, anchored to the editor being written in, is the shape.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THE SIX THINGS THAT BREAK A CONTENTEDITABLE, AND WHAT THIS FILE DOES ABOUT EACH
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * 1. **THE TOOLBAR MUST NOT TAKE THE FOCUS.** Every button calls `preventDefault()` on
 *    `mousedown`, and so does the bar itself. Without it the browser moves focus to the button
 *    before the click resolves, the document selection collapses on the way, and by the time the
 *    handler runs there is nothing selected to embolden — the press appears to do nothing at all,
 *    which is the single most reported bug in every hand-rolled editor. `mousedown` and not
 *    `click`: focus moves on the down stroke, so cancelling it later is too late. Touch is covered
 *    by the same handler, because a tap synthesises a `mousedown` before it moves focus.
 *
 * 2. **PASTE IS SANITISED INTO THE MODEL, NEVER INSERTED AS MARKUP.** The `paste` listener cancels
 *    the event outright and reads `text/plain`. A paste out of Word carries a `text/html` flavour
 *    that is several hundred kilobytes of `mso-` styling around forty words; there is no allowlist
 *    that survives contact with it, so it is never parsed. `docFromPastedText` recovers the
 *    STRUCTURE from characters Word itself puts in the plain-text flavour — a bullet character, an
 *    "1.", a leading tab — and the researcher is told, on screen, that the source's own formatting
 *    did not come with it. If the clipboard carried only HTML, its text is extracted with
 *    `DOMParser` into a detached document and only the `textContent` is read; nothing from that
 *    parse is ever attached to the page.
 *
 * 3. **IME COMPOSITION IS NEVER INTERRUPTED.** `composingRef` is raised on `compositionstart` and
 *    lowered on `compositionend`, and while it is up this file will not re-render the surface, will
 *    not read the DOM back into the model, and will not apply an incoming value from the form. A
 *    re-render mid-composition destroys the node the input method is composing into: a researcher
 *    typing a Devanagari conjunct on any Indic keyboard — or anyone typing on any phone keyboard,
 *    which composes every word — gets three separate letters instead of the character they meant.
 *    An external value that arrives mid-composition is parked in `pendingExternalRef` and applied
 *    the instant composition finishes.
 *
 * 4. **UNDO IS OURS, NOT THE BROWSER'S.** `document.execCommand` is deprecated and its history
 *    stack differs per engine; worse, it is a stack of DOM mutations, and this editor's commands
 *    rewrite the surface wholesale, so the browser's idea of "the previous state" stops matching
 *    the document after the first list toggle. `historyRef` holds snapshots of the MODEL with the
 *    selection that produced them, consecutive typing is coalesced into one step, and the
 *    `historyUndo` / `historyRedo` input types are intercepted so Ctrl+Z reaches our stack whether
 *    it came from the keyboard, the Edit menu or a trackpad gesture.
 *
 * 5. **A CONTROLLED VALUE MUST NOT MOVE THE CARET.** This is the classic controlled-contenteditable
 *    bug and it is solved deliberately, in two layers:
 *
 *      a. Every value this editor emits is remembered as a canonical signature
 *         (`storedSignature`, which normalises null / "" / an empty block list / a document of
 *         empty paragraphs to one string). When the `value` prop changes, its signature is compared
 *         against the last one emitted; if they match, the prop is this editor's own value making a
 *         round trip through the form's state and the surface is LEFT ALONE. A re-render of the
 *         whole form — an upload progress tick, a sibling picker loading its options — therefore
 *         cannot touch the caret, because nothing about the document changed.
 *      b. When the signature genuinely differs — a fresh load, a form reset, another editor's save
 *         — the surface IS re-rendered, and the previous selection is re-applied afterwards, clamped
 *         into the new document. So even a real external change keeps the caret near where it was
 *         rather than throwing it to position zero.
 *
 *    The third layer is that ordinary typing never re-renders at all: the browser edits the text
 *    inside a block, `input` reads the DOM back into the model, and the DOM is already correct, so
 *    there is nothing to write back and no caret to restore.
 *
 * 6. **STRUCTURE IS DECIDED BY US, NOT BY THE ENGINE.** `beforeinput` intercepts Enter, Backspace
 *    at a block boundary, forward delete at a block end, cut, drop and the four `format*` commands,
 *    runs the matching pure function from `lib/richText`, re-renders and restores the selection.
 *    Chrome, Firefox and Safari each do something different with Enter inside a heading and with
 *    Backspace at the start of a list item; none of the three is what the model wants. What the
 *    browser is still allowed to do is insert and delete characters INSIDE one block, because that
 *    is the path an input method and a spell-checker need and the one place the engines agree.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHAT IS SAVED, AND WHAT IS NOT — the honest list, also shown to the researcher
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Every control on this toolbar maps onto a construct the stored RichDoc carries, and there is
 * deliberately no control for anything it does not. There is still NO hyperlink and NO text colour:
 * the model has no mark for either, so each would be a control whose result is visible in the
 * browser and absent from every export the record appears in — which is worse than not offering it,
 * because the researcher believes it worked.
 *
 * A TABLE and an INLINE PHOTOGRAPH are offered because the MODEL carries both (`BlockKind.TABLE`,
 * `BlockKind.IMAGE` in `lib/richText.ts`) and both round-trip losslessly through it. The picture
 * goes up through this app's OWN media pipeline — `uploadMediaBatch`, linked to the record being
 * edited exactly as a form's own photographs are — so an inline image is a real `MediaFile` row with
 * a URL the resolver loads, not a data URI smuggled into a text column. `upload` is therefore
 * required for that button to appear at all; see the prop.
 *
 * WHAT DEGRADES, STATED PLAINLY BECAUSE IT IS THE WHOLE COST OF SHIPPING WITHOUT A SERVER CHANGE.
 * Formatting is faithful in this editor and on the Android record forms, and nowhere else. Until the
 * model reaches `backend/`, a record that has been FORMATTED shows as a stored document in the CSV
 * exports, in the XLSX workbook and in the review panel. An UNFORMATTED record — which is almost all
 * of them, including every dictated one — is byte-identical to what it would have been before this
 * change, and no reader anywhere can tell the difference. That asymmetry is not an accident and it
 * is not slack: it is enforced by `encodeStoredRichText` in
 * `components/richtext/storedRichText.ts`, whose header argues it at length. Do not remove the
 * condition to "make storage consistent".
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THE CONVENIENCES, AND WHY EACH ONE IS FREE
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Find and replace, the live word and character count, the distraction-free full-screen mode and
 * the markdown-style input rules are all editor-side conveniences: every one of them produces
 * ordinary RichDoc blocks and spans, and none of them adds a single key to the stored value. That
 * is the test a new feature has to pass here — the stored document may not diverge from what the
 * other two platforms will eventually have to parse, so a feature is free exactly when it changes
 * how a researcher arrives at a document rather than what the document is.
 *
 * NO LIBRARY WAS ADDED. TipTap/ProseMirror, Lexical and Slate were all permitted and all three
 * would have been reasonable; what they buy is selection handling, IME and paste, and what they
 * cost here is 90-200 KB gzipped plus a schema-conversion layer between their tree-shaped document
 * and this flat one — a layer that would have to be written and tested anyway, and that is most of
 * the work. The three hard parts are handled above and the bundle cost of this feature is the two
 * files themselves. If that trade is ever revisited, `lib/richText.ts` is the seam: it has no DOM
 * in it, so a library could be dropped in beneath it without any stored value changing.
 */

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

/* ────────────────────────────────────────────────────────────────────────────
 * The class ladders
 *
 * Written as COMPLETE LITERAL STRINGS in this file, never assembled, because Tailwind's content
 * scanner reads `./app`, `./components` and `./lib` as text: a class built by concatenation is
 * purged from the stylesheet and the editor renders as unstyled prose in production while looking
 * perfect in development. Every neutral goes through the themed ladders, so the surface inverts
 * with the rest of the app when a researcher switches the theme in a dim room at the end of a
 * field day — which is exactly when the day's records get written up.
 * ──────────────────────────────────────────────────────────────────────────── */

const PARAGRAPH_CLASS = "text-sm leading-6 text-ink-900";
const QUOTE_CLASS = "border-l-2 border-purple-300 pl-3 text-sm italic leading-6 text-ink-700";

/**
 * The four heading levels, scaled and coloured as the REPORT draws them.
 *
 * The previous scale was 20/18/16/14px — four steps of two pixels, all in the same colour, which
 * a researcher reads as one heading style with a rounding error. They could not tell an H1 from an
 * H2 on screen, and the document they were writing has a very visible difference between them.
 *
 * The .docx sets headings at 18/14/12/11pt over a 10.5pt body, and colours them accent, accent,
 * accent-soft, muted — a hierarchy you can see from across a desk. These classes are that same
 * hierarchy in the editor's own units: a big drop from H1 to H2, then two smaller ones, with the
 * colour carrying the last two levels apart rather than the size alone. H1 also takes the rule
 * beneath it that `.rp-h1` has in the preview, because that rule is most of what makes an H1 read
 * as a section break rather than as a large sentence.
 */
const HEADING_CLASS: Record<number, string> = {
  1: "font-display text-3xl font-bold leading-9 text-purple-800 border-b border-line-300 pb-1 mt-4",
  2: "font-display text-xl font-bold leading-7 text-purple-800 mt-3",
  3: "font-display text-base font-bold leading-6 text-purple-600 mt-2",
  4: "font-display text-sm font-bold uppercase tracking-wide leading-6 text-ink-500 mt-2"
};

const ALIGN_CLASS: Record<RichAlign, string> = {
  LEFT: "text-left",
  CENTER: "text-center",
  RIGHT: "text-right",
  JUSTIFY: "text-justify"
};

/** Nesting depth as margin. Four literal strings, one per legal `level`. */
const LEVEL_CLASS: Record<number, string> = {
  0: "",
  1: "ml-6",
  2: "ml-12",
  3: "ml-[4.5rem]"
};

/**
 * The inline figure, in complete literal strings like everything else in this file.
 *
 * `CAPTION_EMPTY_CLASS` draws a dashed box where the caption will go. Without it an un-captioned
 * photograph has an empty italic line under it that is invisible on screen, so a researcher has no
 * way of knowing there is anywhere to type — and the caption is the only text a photograph
 * contributes to the field's own prose, and so the only part of it a search will ever match.
 */
const FIGURE_CLASS = "my-3 grid gap-1";
const FIGURE_IMAGE_CLASS = "max-w-full rounded-md border border-line-200 object-contain";
/** Before the media id has been exchanged for a URL, or when it cannot be — see `ensureMediaUrls`. */
const FIGURE_IMAGE_PENDING_CLASS =
  "block h-28 max-w-full rounded-md border border-dashed border-line-300 bg-surface-50";
const CAPTION_CLASS = "text-xs italic leading-5 text-ink-600";
const CAPTION_EMPTY_CLASS =
  "rounded-sm border border-dashed border-line-300 px-2 py-1 text-xs italic leading-5 text-ink-600";

const MARK_CLASS: Partial<Record<RichMark, string>> = {
  // Preflight strips the default decoration from neither `u` nor `s`, but stating them means the
  // mark still reads correctly if a future reset does.
  UNDERLINE: "underline",
  STRIKETHROUGH: "line-through",
  CODE: "rounded-sm bg-field-100 px-1 py-0.5 font-mono text-[0.9em] text-ink-900",
  // `<sup>` and `<sub>` already raise and lower; what the browser does NOT do is shrink to the
  // 62% the sibling application's .docx and .pdf writers draw (`report_pdf.VERTICAL_SCALE` there;
  // nothing here reads it, which is exactly why the number has to be written down). Stating it
  // keeps the editor from showing a raised character LARGER on screen than in a printed document —
  // and the zero line-height is what stops a footnote marker opening up the line.
  SUPERSCRIPT: "align-super text-[0.62em] leading-none",
  SUBSCRIPT: "align-sub text-[0.62em] leading-none",
  // The ink is forced BLACK rather than left on the themed ladder. Every other class in this file
  // inverts with the theme, and this one must not: the fill is a fixed #FFFF00 in all four
  // renderers (Word's own `w:highlight w:val="yellow"`), so a light ink on it in dark mode would be
  // unreadable on screen while printing perfectly legibly in the file.
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

/**
 * Tag to mark, for reading the DOM back.
 *
 * Deliberately generous on the way IN and strict on the way OUT: the surface only ever emits the
 * five tags above, but a browser's own "insert replacement text", a drag inside the field or an
 * assistive tool can leave a `<b>` or a `<del>` behind, and reading those as the mark they mean is
 * better than dropping the formatting. Anything NOT in this table — a `<span style>`, a `<font>`,
 * a `<div class="MsoNormal">` — contributes its text and nothing else, which is the whole
 * sanitisation policy in one line.
 */
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

/**
 * Outermost first. Fixed, so the same document always produces the same DOM and the same diff.
 *
 * SUPERSCRIPT and SUBSCRIPT are INNERMOST, and that ordering is load-bearing rather than
 * arbitrary: the class on them sets a font size relative to its parent (`text-[0.62em]`), so a
 * `<sup>` wrapped around a `<code>` would compound the two reductions and print a marker at 56% of
 * the body size in one place and 62% in another, depending only on which mark the researcher applied
 * first.
 */
const NEST_ORDER: readonly RichMark[] = [
  "BOLD",
  "ITALIC",
  "UNDERLINE",
  "STRIKETHROUGH",
  "CODE",
  // Outside the two vertical marks for the same reason they are innermost: a `<mark>` inside a
  // `<sup>` would take the superscript's reduced size as its own font context, so a highlighted
  // footnote marker would be padded and rounded at 62% of 62%.
  "HIGHLIGHT",
  "SUPERSCRIPT",
  "SUBSCRIPT"
];

const BLOCK_ATTRIBUTE = "data-rte-block";

/* ────────────────────────────────────────────────────────────────────────────
 * Model → DOM
 * ──────────────────────────────────────────────────────────────────────────── */

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

/**
 * Where an inline photograph actually sits on the page.
 *
 * LEFT IS DRAWN CENTRED, and that is not a bug. The model treats a LEFT-aligned IMAGE as centred —
 * a photograph narrower than the text column reads as a plate, and every document template centres
 * one — so an editor that pushed it to the left margin would be showing the researcher a layout no
 * renderer of this model produces. The other three alignments are honoured as typed.
 */
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
    // The number is set explicitly rather than left to the browser's own count, so the editor
    // prints the same ordinal the .docx will — see `orderedNumbers`.
    if (ordinal !== null) (element as HTMLLIElement).value = ordinal;
  } else if (block.kind === "HEADING") {
    // A `<p>` carrying `role="heading"`, not an `<h1>`. The page already owns h1 (the page header)
    // and h2 (the record panel), and a real `<h1>` inside a narrative field would put a researcher's
    // prose above the page's own title in every screen reader's document outline. `aria-level` is
    // offset by two so a heading typed inside a field is announced as subordinate to the section
    // that contains it, which is what it is.
    element = document.createElement("p");
    element.setAttribute("role", "heading");
    element.setAttribute("aria-level", String(Math.min(6, block.level + 2)));
    element.className = cn(HEADING_CLASS[block.level] ?? HEADING_CLASS[4], align);
  } else if (block.kind === "QUOTE") {
    element = document.createElement("blockquote");
    element.className = cn(QUOTE_CLASS, align);
  } else if (block.kind === "TABLE") {
    /*
      A REAL `<table>`, edited BY THE BROWSER.

      Every other block in this editor is edited by the model: `beforeinput` cancels the browser's
      attempt and applies a pure function instead, because three engines disagree about what Enter
      does in a heading. A table is the one place that trade goes the other way. The model's caret
      is a `{block, offset}` pair, which cannot address a CELL — so routing a keystroke typed in
      the third cell of the second row through `insertText` would append it to the block's
      concatenated text and the character would land somewhere else entirely.

      Rather than extend the selection model everywhere for one block kind, table cells are left to
      the browser's own (perfectly good) cell editing and read back through `readDocFrom` on the
      next `input`. The keystroke handler below therefore lets an edit inside a table fall through
      untouched, and the marks a researcher applies inside a cell still work because `collectSpans`
      reads `<strong>`/`<em>` back the same way it does in a paragraph.
    */
    /*
      STYLED AS THE .docx DRAWS IT, not as a bare HTML table.

      The document writer in the sibling application (`report_docx`) gives every TableBlock a solid
      header row, a hairline border on every cell and zebra shading on alternate body rows. That
      shape is copied here on purpose even though this repository has no such writer yet: the model
      is the same model, the writer is a port away, and a researcher who builds a grid in this box
      should be judging the object that will eventually be printed rather than a bare HTML table.

      The colours are the app's own tokens and not that writer's theme, because a field editor does
      not know which template or accent a document will be generated under. The SHAPE is what has
      to match.
    */
    const table = document.createElement("table");
    table.className = "my-2 w-full table-fixed border-collapse overflow-hidden rounded-md text-sm";
    const body = document.createElement("tbody");
    const width = tableColumnCount(block);
    block.rows.forEach((row, rowIndex) => {
      const tr = document.createElement("tr");
      // Zebra on alternate BODY rows — row 0 is the header, so the striping starts at row 2.
      if (rowIndex > 0 && rowIndex % 2 === 0) tr.className = "bg-surface-50";
      for (let column = 0; column < width; column += 1) {
        // The first row is the header — the same reading the server and the phone give it.
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
    /*
      A REAL `<figure>`: the picture, then the caption, which is the block's own spans.

      THE CAPTION IS EDITED THROUGH THE MODEL like any other prose, unlike a table's cells. That
      falls straight out of the shape: `blockText` on an IMAGE returns the caption, so a
      `{block, offset}` point addresses a position in it, and every command below — insertText,
      the marks, the selection arithmetic — works on it unchanged. The ONE requirement that buys
      is that this element must contain NO text node outside the `<figcaption>`, or `docPointToDom`
      (which walks the block's text nodes in order) would resolve offset 0 into that other text and
      put the caret somewhere the researcher did not click. Hence the placeholder below is an empty
      element carrying an `aria-label`, and never a word of visible text.

      The `<img>` is `contenteditable="false"` and undraggable so no engine offers its own resize
      handles or lets the picture be dragged into the middle of a paragraph — both produce markup
      this file's reader does not understand, and the mark would be dropped at the next sync.
    */
    const figure = document.createElement("figure");
    figure.className = FIGURE_CLASS;

    const effective = imageAlign(block.align);
    const image = document.createElement("img");
    // Carried on the IMG as well as on the figure so `ensureMediaUrls` can patch the src in place
    // when the id resolves, rather than re-rendering the surface and moving the caret.
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
      // `undefined` is "not looked up yet" and `null` is "looked up and there is no URL" — a media
      // row this browser is not entitled to the bytes of, or one deleted since it was placed. Both
      // draw the same dashed box, because both mean the same thing to the researcher standing here:
      // the picture is not on screen. The label says which. The STORED VALUE is unaffected either
      // way — the block holds the media id, not the URL, so a browser that cannot fetch the bytes
      // has still saved a document that points at the right row.
      image.className = FIGURE_IMAGE_PENDING_CLASS;
      image.setAttribute(
        "aria-label",
        url === null
          ? "This photograph cannot be shown here. It is still embedded in the generated file."
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

  // A table's content is already in its rows. Falling through would append the empty-block `<br>`
  // below INSIDE the `<table>`, where the HTML parser hoists it out to sit before the table — so
  // the surface would grow one stray blank line per table, and `readDocFrom` would read it back as
  // a paragraph the researcher never typed.
  if (block.kind === "TABLE") return element;

  // A figure's content is already its picture and its caption, and the two attributes below are
  // how `readImageElement` gets them back: the media id and the printed width live nowhere in the
  // element's text, so losing them here would turn a photograph into a bare caption on the next
  // sync — silently, because the caption still reads correctly.
  if (block.kind === "IMAGE") {
    element.setAttribute("data-media", block.media);
    element.setAttribute("data-width", String(block.widthPct));
    return element;
  }

  if (!block.spans.length) {
    // An empty block with no `<br>` has zero height and no place to put a caret, so the researcher
    // sees the paragraph they just created collapse to nothing and the keyboard appears dead.
    element.appendChild(document.createElement("br"));
    return element;
  }
  for (const span of block.spans) element.appendChild(spanNode(span));
  return element;
}

/**
 * Replace the whole surface with `doc`.
 *
 * Wholesale, not reconciled. A diffing renderer here would save nothing measurable (a narrative
 * field is tens of blocks, and this only runs on a STRUCTURAL command — never on a keystroke) and
 * would cost the one guarantee that makes the rest of this file tractable: after this call the DOM
 * is exactly what the model says, so `readDocFrom` reading it back is the identity function and a
 * model point maps to a DOM point without a stale-node case to reason about.
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * DOM → model
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A DOM text node's contents as model text.
 *
 * The non-breaking space is converted back to an ordinary space. Browsers insert U+00A0 for a
 * trailing space inside `contenteditable` to stop it collapsing, and storing that character would
 * put a non-breaking space into the .docx at the end of every line a researcher paused on — invisible
 * on screen, and a line that refuses to wrap wherever the field is printed.
 */
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

/**
 * A `<table>` in the surface, read back into a TABLE block.
 *
 * Reads the CELLS rather than the element's concatenated text — `collectSpans` over the whole
 * table would return one long run and the grid would be gone. Marks inside a cell survive because
 * `collectSpans` is the same walker a paragraph uses, so a researcher who bolds a total in a cell
 * keeps the bold all the way to the .docx.
 *
 * Rows are read as they are, ragged or not. Squaring them off is the renderer's job, and doing it
 * here would silently add cells the researcher never made.
 */
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

/**
 * A `<figure>` in the surface, read back into an IMAGE block.
 *
 * ONLY THE `<figcaption>` IS READ FOR TEXT. Running `collectSpans` over the whole figure would be
 * harmless today (the picture contributes no text node — see `blockElement`) and would silently
 * start swallowing whatever a future placeholder put there, which is the kind of coupling that
 * only shows up as a caption nobody typed.
 *
 * A FIGURE THAT HAS LOST ITS MEDIA ID BECOMES A PARAGRAPH, keeping the caption. That happens when
 * an engine rebuilds the element around a drag, and the alternative is worse in both directions:
 * an IMAGE block with no id is dropped outright by `fromStored` and by the server's `from_json`,
 * so keeping the kind would throw the words away as well as the picture. Degrading to prose loses
 * the photograph — which was already lost — and keeps the sentence.
 */
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
  // A `<table>` that lost its attribute — pasted from Word, or rebuilt by the engine after a drag
  // — is still a table, and reading it as a paragraph would flatten the grid into one line.
  if (element.tagName === "TABLE") return "TABLE";
  // Same reasoning for a `<figure>`: reading it as a paragraph would keep the caption and drop the
  // photograph, and the researcher would see a caption under nothing.
  if (element.tagName === "FIGURE") return "IMAGE";
  // No `data-kind` means the element was not written by `renderDocInto` — a wrapper the engine
  // invented during a drag or a spell-check replacement. Reading it as a paragraph keeps the words
  // and loses only a formatting the model may not have been able to express anyway.
  if (element.tagName === "BLOCKQUOTE") return "QUOTE";
  return "PARAGRAPH";
}

function collectBlocks(node: Node, out: RichBlock[]): void {
  if (node.nodeType === Node.TEXT_NODE) {
    const text = nodeText(node);
    // A bare text node directly under the host: some engines leave one behind after a deletion
    // that emptied the surface. Promoting it to a paragraph is what stops the next keystroke
    // building a document with no blocks in it.
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

/**
 * Keep each caption's outline in step with whether anything has been typed into it.
 *
 * The dashed box is the only thing that says "there is somewhere to type here" under a picture
 * with no caption yet, and it must go away the moment there is one — but typing does NOT re-render
 * the surface (that is the third layer of the caret fix, and it is what makes the editor feel
 * responsive), so nothing else would ever take it off. Called from the read-back path, where the
 * text has just changed. Changing a class does not move the caret.
 */
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

/** At least one block, and never more than the server will store. */
function normaliseForEditing(doc: RichDoc): RichDoc {
  if (!doc.blocks.length) return emptyDoc();
  if (doc.blocks.length <= MAX_BLOCKS) return doc;
  return { blocks: doc.blocks.slice(0, MAX_BLOCKS) };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Selection mapping — model point ⇄ DOM point
 * ──────────────────────────────────────────────────────────────────────────── */

function blockElementAt(host: HTMLElement, index: number): HTMLElement | null {
  return host.querySelector<HTMLElement>(`[${BLOCK_ATTRIBUTE}="${index}"]`);
}

function docPointToDom(host: HTMLElement, point: DocPoint): { node: Node; offset: number } | null {
  const block = blockElementAt(host, point.block);
  if (!block) return null;
  /*
    A FIGURE'S TEXT IS ITS CAPTION, AND THE CARET MUST LAND INSIDE THE `<figcaption>`.

    Without this scope the walker below finds no text node in an un-captioned figure and falls back
    to `{ node: <figure>, offset: 0 }` — which is the position BEFORE the `<img>`. The browser then
    happily inserts the caption the researcher types as a text node that is a direct child of the
    figure, `readImageElement` (which reads only the figcaption) does not see it, and the whole
    caption is discarded at the next sync. That is the very first thing anyone does after placing a
    picture, so it is silent data loss on the feature's main path.
  */
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

  // An element container: `offset` is a CHILD INDEX, not a character offset. This is the shape a
  // selection takes in an empty block (the block itself, index 0) and after a triple click.
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
    // `setBaseAndExtent` throws on a detached node — which can only happen if a render raced this
    // call. Swallowing it leaves the caret where the browser had it, which is survivable; letting
    // it escape into a native event handler is not, because the whole keystroke is lost.
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

/* ────────────────────────────────────────────────────────────────────────────
 * History
 * ──────────────────────────────────────────────────────────────────────────── */

type HistoryEntry = { doc: RichDoc; selection: DocRange };
type HistoryState = { entries: HistoryEntry[]; index: number; lastAt: number; lastLabel: string };

/**
 * How long a run of typing stays one undo step.
 *
 * 600ms of quiet closes the step. A researcher who presses Ctrl+Z expects to lose a phrase, not one
 * letter — an undo stack with one entry per keystroke means forty presses to remove a sentence, and
 * everybody who meets one gives up and retypes the paragraph instead.
 */
const HISTORY_COALESCE_MS = 600;
const HISTORY_LIMIT = 200;

/* ────────────────────────────────────────────────────────────────────────────
 * The toolbar
 * ──────────────────────────────────────────────────────────────────────────── */

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

/**
 * The contextual bar.
 *
 * `role="toolbar"` comes with an obligation — ARIA's own pattern says a toolbar is ONE tab stop and
 * its controls are reached with the arrow keys — and claiming the role without honouring it is
 * worse than not claiming it, because a screen-reader user is told to press an arrow key that does
 * nothing. So a roving `tabIndex` is implemented here: exactly one button is tabbable at a time,
 * Left/Right move between them, Home/End jump to the ends. Twenty-five separate tab stops between
 * a narrative field and the next form control would otherwise be the alternative.
 */
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

  // The set of buttons never changes shape, but a disabled one must not be the tab stop or the
  // toolbar becomes unreachable from the keyboard the moment the caret leaves a list.
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
      // THE LINE THAT MAKES THE TOOLBAR WORK AT ALL. Cancelling mousedown on the container as well
      // as on each button means a press that lands on the padding between two buttons also cannot
      // move focus out of the editor and collapse the selection the next press needs.
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
      // `w-full` and `justify-between`: the bar sits directly above the editing surface and is
      // exactly as wide as it, so the controls spread across the whole text column instead of
      // huddling at the left with two thirds of the row empty.
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
            // `aria-pressed` and not `aria-checked`: these are toggles, not radios. The active
            // state is also carried by the border and the fill, so it does not depend on colour
            // alone — the same rule every status chip in this app follows.
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

/* ────────────────────────────────────────────────────────────────────────────
 * The component
 * ──────────────────────────────────────────────────────────────────────────── */

export type RichTextEditorProps = {
  /** The stored value, in any shape `fromStored` accepts — including a plain string. */
  value: unknown;
  /** Called with exactly what should be written into the field: the document, or null when empty. */
  onChange: (value: StoredRichDoc | null) => void;
  disabled?: boolean;
  /** The id of the element naming this editor — the `field-label` span, on a record form. */
  ariaLabelledBy?: string;
  ariaLabel?: string;
  /**
   * Let the dictation button below draw its own "this browser cannot dictate" sentence, or not.
   *
   * FORWARDED, NOT DECIDED HERE. Every record form now renders `DictationUnavailableNotice` once at
   * the top and passes `false` to each control below it. This editor was the one control that could
   * not be told, so on a browser with no recogniser (Firefox, today) `ProductForm` printed the
   * form-level paragraph PLUS one copy under each of its four rich-text boxes — five copies of one
   * sentence down one form, which is how a true sentence becomes wallpaper.
   *
   * DEFAULTS TO UNDEFINED, which the button reads as its own default of true, so a caller that says
   * nothing behaves exactly as it did before this prop existed.
   *
   * IT IS NOT AN ID AND IT SELECTS NOTHING. The dictation button here is unconditional and there is
   * only one of it — see `e2e/record-form-dictation-unit.spec.ts`, "the editor hosts the on-device
   * button and has no id-shaped prop to route it elsewhere", which asserts that this editor has no
   * id-shaped prop and imports exactly one dictation module. This is a boolean about a SENTENCE, not
   * a route.
   */
  explainWhenUnavailable?: boolean;
  placeholder?: string;
  /** An advisory character ceiling, shown beside the live count — see the note on it below. */
  maxLength?: number;
  /**
   * Start this field as a list of the given kind.
   *
   * For a field whose help text says "one per line", so the researcher is inside a list the moment
   * the field opens and Enter starts the next item. It seeds the FIRST block only; everything after
   * that is ordinary editing, because `splitBlock` already carries a list kind across an Enter and
   * already leaves the list on an empty item. See `seedListDocument` for what it refuses to touch.
   *
   * NOT SET BY ANY OF THE FOUR RECORD FORMS TODAY, deliberately: the existing values in
   * `rawMaterialsUsed` and `mainToolsUsed` are comma-separated sentences, and opening them as a
   * bulleted list would be the editor arguing with what was written. The researcher can press the
   * bullet button, which is the difference between an offer and an imposition.
   */
  listKind?: RichBlockKind;
  /**
   * Where a photograph placed inside this prose is filed — the record it is linked to.
   *
   * WITHOUT IT THERE IS NO PICTURE BUTTON, and that is the honest degradation rather than a
   * missing feature: `uploadMediaBatch` links every upload to a record, and a file with no record
   * is an orphan object that `GET /media` cannot list and nothing can later resolve. An editor
   * mounted somewhere that cannot say which record it belongs to must not offer to upload.
   *
   * A generic `{recordType, recordId}` pair rather than any particular id, so this component knows
   * nothing about which of the four forms it is standing on.
   *
   * NOTE FOR THE RECORD FORMS: `RichTextField` does not pass it, and cannot on a NEW record — the
   * row has no id until the form is submitted, so there is nothing to link a photograph to. That is
   * why the picture button is absent on these forms today rather than present-and-broken. Wiring it
   * on the EDIT forms (where `initial.id` exists) is a real follow-up, not a bug.
   */
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
  placeholder = "Write here. Select text to format it, or type “## ” for a heading and “1. ” for a numbered list.",
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

  /**
   * Media id → something an `<img>` can load; `null` once it is known there is nothing to load.
   *
   * A REF AND NOT STATE, deliberately. The surface's children are not managed by React (see the
   * file header), so a resolved URL does not need a render — it needs one `src` attribute set, on
   * an element that already exists. Holding this in state would re-render the component on every
   * photograph resolved, and the useful version of that render would still have to reach into the
   * DOM to do the same thing. The map also has to survive `commit`, which rebuilds every node from
   * the model: a re-render must NOT cost a second round trip per picture, or scrolling a narrative
   * field with a dozen photographs in it re-fetches all of them.
   */
  const mediaUrlsRef = useRef<Map<string, string | null>>(new Map());
  /** Ids with a lookup in flight, so a re-render mid-fetch cannot fire a second request for one. */
  const mediaPendingRef = useRef<Set<string>>(new Set());
  /** Where the picture being uploaded will land — see `runImagePick` for why it is captured early. */
  const imageAnchorRef = useRef<DocRange | null>(null);

  /**
   * The document, the caret and the signature this editor starts life with, parsed exactly once.
   *
   * Held in lazy STATE rather than computed inline for each ref, and that is not decoration: the
   * refs below and the chrome state all need the same starting document, and reading one ref to
   * seed another is what `react-hooks/refs` refuses — correctly, because a ref read during render
   * is a read of whatever the last committed render left behind, which under concurrent rendering
   * is not necessarily the render being prepared. A lazy `useState` initialiser runs once per
   * mount, before any of it exists, and its result is a plain value everything else can share.
   */
  const [seed] = useState(() => ({
    doc: normaliseForEditing(listKind ? seedListDocument(value, listKind) : fromStored(value)),
    selection: collapsedAt({ block: 0, offset: 0 }),
    signature: storedSignature(value)
  }));

  const docRef = useRef<RichDoc>(seed.doc);
  const selectionRef = useRef<DocRange>(seed.selection);
  /**
   * The marks the NEXT typed character should carry, set by pressing Bold with nothing selected.
   *
   * Null means "inherit from the character before the caret". A separate slot is needed because a
   * collapsed selection cannot carry a mark in the document — there is no text to mark yet — and
   * without it Ctrl+B before typing would do nothing at all, which is how most people bold a word.
   */
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

  /**
   * Props read from inside native event listeners, refreshed through an effect with NO dependency
   * array — the house pattern (`useLeaveGuard`).
   *
   * A ref written during render would be written on a render React is free to discard under
   * concurrent rendering, leaving the listeners calling an `onChange` that belongs to a commit
   * that never happened. An effect only runs for a render that committed.
   */
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
    // Deliberately "" rather than the real signature: the first `refreshChrome` after mount must
    // find a mismatch and commit the true state, or an editor loaded with existing prose would draw
    // a toolbar describing an empty document until the researcher clicked into it.
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
  /** A transfer is in flight; the picture button says so and refuses a second one meanwhile. */
  const [uploading, setUploading] = useState(false);
  const [savedPanelOpen, setSavedPanelOpen] = useState(false);

  /**
   * Where the editing surface sat in the viewport just before something above it appeared or went
   * away, or null when nothing is expected to move. Spent by the layout effect below.
   *
   * Measured in the handler that CAUSES the change rather than in an effect, because a viewport
   * rectangle remembered across two renders is stale the moment the researcher scrolls between them,
   * and compensating by a stale delta would throw the page somewhere nobody asked for.
   */
  const surfaceTop = useRef<number | null>(null);

  /** Call immediately BEFORE anything that adds or removes a band above the writing surface. */
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

  /**
   * Pay back, as scroll, whatever the docking bar or the find card just moved.
   *
   * Both sit ABOVE the surface, so gaining or losing one shifts the words the researcher is looking
   * at by its height. Compensating means the paragraph stays under the cursor: the toolbar arrives
   * and nothing being read has moved. This is what replaced the old floating bar's one merit —
   * appearing at no layout cost — which it bought by covering the field's own title and help text.
   */
  useLayoutEffect(() => {
    const expected = surfaceTop.current;
    surfaceTop.current = null;
    const host = hostRef.current;
    // Full screen is its own scroll container and lifts the surface out of the page entirely, so
    // there is no page scroll that could hold it still and nothing to repay.
    if (expected === null || !host || fullscreen) return;
    const shift = host.getBoundingClientRect().top - expected;
    // Sub-pixel differences are ignored: a fractional layout is not a jump.
    if (Math.abs(shift) >= 1) window.scrollBy({ top: shift, behavior: "instant" as ScrollBehavior });
  }, [active, find.open, fullscreen]);

  /* ── Chrome refresh ─────────────────────────────────────────────────────── */

  const refreshChrome = useCallback(() => {
    const doc = docRef.current;
    const range = selectionRef.current;
    const bar = toolbarState(doc, range, pendingMarksRef.current);
    const counts = countDoc(doc);
    const history = historyRef.current;
    const canUndo = history.index > 0;
    const canRedo = history.index < history.entries.length - 1;
    const showPlaceholder = isEmptyDoc(doc);
    // One string decides whether React re-renders at all. Without it every arrow-key press through
    // a paragraph would re-render the toolbar, and on the product form's four narrative fields that
    // is a measurable stutter on the field laptops this runs on.
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

    // The find bar's tally has to follow the document, not just the query: replacing one of six
    // occurrences must leave "5" on screen, and a stale count is a control that says there is
    // something left to replace when there is not.
    const search = findRef.current;
    if (search.open && search.query) {
      const matches = findMatches(doc, search.query, { caseSensitive: search.caseSensitive, wholeWord: search.wholeWord });
      setFind((current) => (current.count === matches.length ? current : { ...current, count: matches.length }));
    }
  }, []);

  /* ── Resolving the photographs placed in the prose ──────────────────────── */

  /**
   * Exchange every media id the document holds for a URL, and patch the `<img>` where it sits.
   *
   * THE ID IS NOT A URL AND CANNOT BE ONE. Every media route is bearer-authenticated and an `<img>`
   * sends no Authorization header, so the id is traded for `MediaFile.url` (presigned or public)
   * first. `url` is gated server-side and may legitimately be absent, which is why the failure
   * here is a stated placeholder rather than a broken frame: THE STORED DOCUMENT IS UNAFFECTED. The
   * block holds the media id, so a photograph this browser cannot fetch is still correctly
   * referenced by the record and will draw for anyone entitled to it.
   *
   * Patched in place rather than re-rendered. `commit` rebuilds the whole surface from the model
   * and re-applies the caret; doing that again a few hundred milliseconds later, when a fetch
   * happens to land, would move the caret out from under whatever the researcher had started typing.
   */
  const ensureMediaUrls = useCallback((doc: RichDoc) => {
    const paint = (id: string, url: string | null) => {
      const host = hostRef.current;
      if (!host) return;
      // Compared attribute by attribute rather than through a `[data-media="…"]` selector, so an id
      // holding a quote cannot break the selector — a media id is server-issued, but a value read
      // back out of the DOM is not something to build a query string from on trust.
      for (const node of Array.from(host.querySelectorAll("img[data-media]"))) {
        if (!(node instanceof HTMLImageElement) || node.getAttribute("data-media") !== id) continue;
        if (url) {
          node.className = FIGURE_IMAGE_CLASS;
          node.removeAttribute("aria-label");
          node.src = url;
        } else {
          node.setAttribute(
            "aria-label",
            "This photograph cannot be shown here. It is still embedded in the generated file."
          );
        }
      }
    };

    for (const block of doc.blocks) {
      if (block.kind !== "IMAGE" || !block.media) continue;
      const id = block.media;
      if (mediaPendingRef.current.has(id)) continue;
      if (mediaUrlsRef.current.has(id)) {
        // Already known, but the surface may have just been rebuilt from the model with the id
        // still unresolved in this render — paint it now rather than waiting for the next edit.
        paint(id, mediaUrlsRef.current.get(id) ?? null);
        continue;
      }
      mediaPendingRef.current.add(id);
      void (async () => {
        let url: string | null = null;
        try {
          url = (await apiFetch<MediaFile>(`/media/${id}`)).url ?? null;
        } catch {
          // Not entitled to it, deleted since it was placed, or simply no connection. All three
          // are the same answer to the researcher standing here, and none of them is a reason to
          // rewrite the document: the id stays exactly where they put it.
          url = null;
        }
        mediaPendingRef.current.delete(id);
        mediaUrlsRef.current.set(id, url);
        paint(id, url);
      })();
    }
  }, []);

  /* ── Emitting ───────────────────────────────────────────────────────────── */

  /**
   * Hand the document to the form — but ONLY when it is actually a different document.
   *
   * The guard is not an optimisation. Every record form marks itself dirty on every `onChange` it
   * receives (`RichTextField` passes the form's `markDirty` straight through) and arms
   * `UnsavedChangesDialog` on the strength of it, so an `onChange` fired by
   * pressing Indent on a paragraph (which cannot indent), or by re-applying a mark that is already
   * there, would tell a researcher they have unsaved work before they have typed anything — and a
   * guard that cries wolf on a blank form is a guard people learn to click through, which it must
   * still mean something an hour later when there IS an afternoon's work in the form.
   *
   * The signature is remembered BEFORE the callback runs: `onChange` synchronously sets the
   * parent's state, which re-renders this component with the new prop, and the value effect has to
   * already know that this value is its own.
   */
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

  /**
   * Apply the result of a command: adopt the document, redraw the surface, put the caret back,
   * bank an undo step and tell the form.
   *
   * The composition guard is the first line and is not negotiable: redrawing the surface while an
   * input method is mid-word destroys the node it is composing into.
   */
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
        // Focus is only reclaimed when it was already here. A keyboard user driving the toolbar
        // must keep their place in it; a mouse user never lost focus, because the bar cancels
        // mousedown.
        if (activeElement === document.body || host.contains(activeElement)) host.focus({ preventScroll: true });
        writeSelection(host, selection);
      }
      // History follows the emit: a command that produced the same document — Indent on a
      // paragraph, Bold on text that is already bold across the whole selection — must not bank an
      // undo step, or Ctrl+Z spends three presses undoing three things that never happened.
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
    // Closing the coalescing window stops the next keystroke folding itself into the entry that
    // has just been restored, which would make a second Ctrl+Z appear to do nothing.
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

  /* ── Reading the surface back ───────────────────────────────────────────── */

  const syncFromDom = useCallback(() => {
    const host = hostRef.current;
    if (!host) return;
    const doc = normaliseForEditing(readDocFrom(host));
    docRef.current = doc;
    const range = readSelection(host);
    if (range) selectionRef.current = clampRange(doc, range);
    pendingMarksRef.current = null;
    // Only when there is a figure on the surface — this runs on every keystroke, and a narrative
    // field with no picture in it must not pay a DOM query for one.
    if (doc.blocks.some((block) => block.kind === "IMAGE")) paintCaptionStates(host);

    // Markdown input rules fire ONLY on the space that completed a prefix. Running them on every
    // input would convert "## " into a heading when a researcher BACKSPACED into that state, which
    // is a document rewriting itself under a key that is supposed to undo things.
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

  /* ── The controlled value ───────────────────────────────────────────────── */

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
        // Only re-assert the caret if this editor is where the caret actually is. Writing a
        // selection into an unfocused editor steals it from whatever the researcher is typing in.
        if (host.contains(document.activeElement)) writeSelection(host, selectionRef.current);
      }
      historyRef.current = { entries: [{ doc, selection: selectionRef.current }], index: 0, lastAt: 0, lastLabel: "" };
      refreshChrome();
    },
    [ensureMediaUrls, refreshChrome, listKind]
  );

  // Draw the initial document once. Not in the value effect: that one exists to react to CHANGES,
  // and it deliberately does nothing on the first pass because the signature already matches.
  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    renderDocInto(host, docRef.current, mediaUrlsRef.current);
    ensureMediaUrls(docRef.current);
    refreshChrome();
  }, [ensureMediaUrls, refreshChrome]);

  /**
   * THE CARET FIX. See point 5 of the file header.
   *
   * The whole of the controlled-contenteditable problem reduces to this comparison. A debounced
   * save, a sibling field's keystroke, a re-render from any of the dozen pieces of state on a
   * record form — an upload progress tick is the common one — all of them hand this component a
   * `value` prop again, and all but one of those
   * times the document inside it is identical to the one already on screen. Re-seeding on prop
   * identity, or on a shallow compare of a freshly parsed object, would redraw the surface and
   * throw the caret to the start of the field in the middle of a sentence.
   */
  useEffect(() => {
    const incoming = storedSignature(value);
    if (incoming === emittedSignatureRef.current) return;
    if (composingRef.current) {
      // Never mid-word. Parked and applied by the compositionend handler.
      pendingExternalRef.current = { value };
      return;
    }
    seedFrom(value);
  }, [value, seedFrom]);

  /* ── Commands the toolbar and the keyboard share ────────────────────────── */

  const currentRange = useCallback((): DocRange => {
    const host = hostRef.current;
    // Preferring the live DOM selection matters after a click: `selectionchange` is asynchronous in
    // some engines, so a toolbar press in the same gesture as the click would otherwise act on the
    // selection as it was before the click landed.
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
        // The same displacement `applyMark` performs on a real selection: pressing Superscript
        // with nothing selected must not leave a pending set claiming both, or the next character
        // typed would be a span the renderers have to tie-break.
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
      // Pressing the kind a block already is returns it to a paragraph, so every one of these
      // buttons is its own off switch and nobody has to hunt for the ¶ to undo a heading.
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

  /**
   * Insert a table, in the same shape as every other toolbar action.
   *
   * Written as its own callback rather than an inline `commit(...)` in the items list so that
   * `commit` stays out of that `useMemo`'s dependency array. Adding it there would rebuild every
   * toolbar item on each edit — `commit` changes identity when the document does — and the file
   * header is explicit that papering over the exhaustive-deps rule is how a dependency array stops
   * meaning anything.
   */
  const runTable = useCallback(() => {
    if (propsRef.current.disabled) return;
    const range = currentRange();
    selectionRef.current = range;
    commit(insertTable(docRef.current, range), "table");
  }, [commit, currentRange]);

  /**
   * Where the caret is inside a table: which block, which row, which column.
   *
   * Read from the DOM because that is where a table caret lives — see the note in
   * `onBeforeInput`. Returns null when the caret is not in a table, which is what makes the
   * structure buttons inert everywhere else rather than acting on the wrong table.
   */
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

  /**
   * Apply a structural table edit at the caret.
   *
   * `commit` re-renders the whole surface from the model, which throws the caret away — so the
   * cell to land in is worked out here and restored afterwards. Without that, adding a row moves
   * the researcher's caret to the top of the field and they lose their place on every click.
   */
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

      // Put the caret back into a cell of the same table, on the next frame — the surface is
      // rebuilt synchronously inside `commit`, but focus has to follow the new nodes.
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

  /* ── Dictation ──────────────────────────────────────────────────────────── */

  /**
   * A finished spoken phrase, inserted where the caret is.
   *
   * LIFTED OUT OF THE JSX rather than written inline in the button's `onCommit`, so that the insertion
   * path has a name and one place to be fixed. The plain-textarea microphone next door
   * (`DictatedTextArea`) appends a string; this one must not, and keeping the two visibly different
   * pieces of code is how that stays true.
   *
   * It is the same `insertText` an ordinary keystroke goes through, with the same mark inheritance
   * and the same history entry, so a dictated phrase behaves exactly like a typed one: it lands
   * where the caret is rather than at the end, it can be undone in one step, and it picks up the
   * run's marks instead of arriving unstyled in the middle of a bolded sentence.
   */
  const commitDictated = useCallback(
    (text: string) => {
      const phrase = text.trim();
      if (!phrase) return;
      const range = selectionRef.current;
      const block = docRef.current.blocks[range.focus.block];
      // A space before the phrase unless the caret already follows one or begins the block — the
      // recogniser is stopped and started across a long answer, and without this a paragraph
      // dictated in five goes comes out as "…the warpis sized…".
      const before = block ? blockText(block).slice(0, range.focus.offset) : "";
      const joiner = !before || /\s$/.test(before) ? "" : " ";
      const marks = pendingMarksRef.current ?? (block ? marksAt(block, range.focus.offset) : []);
      commit(insertText(docRef.current, range, `${joiner}${phrase}`, marks), "dictate");
    },
    [commit]
  );

  /* ── Inline photographs ─────────────────────────────────────────────────── */

  /**
   * Open the file picker, having first pinned down where the picture will go.
   *
   * The caret is captured HERE and not when the upload finishes, and that is the whole reason this
   * is two functions. Choosing a file takes the focus out of the document — on some platforms it
   * opens a modal window and the page loses the selection entirely — and an upload over a rural
   * link takes long enough that the researcher will have clicked somewhere else by the time it
   * lands. Reading the caret then would drop the photograph into whichever paragraph they had
   * wandered to.
   */
  const runImagePick = useCallback(() => {
    if (propsRef.current.disabled || !propsRef.current.upload) return;
    imageAnchorRef.current = currentRange();
    selectionRef.current = imageAnchorRef.current;
    // Reset first: choosing the SAME file twice in a row fires no `change` event otherwise, and
    // the researcher would press the button and watch nothing happen.
    if (fileRef.current) fileRef.current.value = "";
    fileRef.current?.click();
  }, [currentRange]);

  /**
   * Push the chosen file through the app's OWN media pipeline and place the block that references it.
   *
   * `uploadMediaBatch` IS THE UPLOAD PATH, not a convenience: it links the file to the record, so
   * the photograph is a real `MediaFile` row that `GET /media` lists, that the media pages show
   * beside the record's other files, and that the staged-object sweeper will not delete. A second upload
   * implementation here — or a data URI in the JSON column — would produce a picture that renders
   * in this editor and is missing from every export the record appears in, which is the failure this
   * whole feature is arranged to avoid.
   */
  const runImageFile = useCallback(
    async (file: File) => {
      const target = propsRef.current.upload;
      if (!target || propsRef.current.disabled) return;
      // No renderer of this document can draw a video or a PDF inline, and `ImageBlock` takes an
      // `ImageRef`. Refusing here is the only place the researcher finds out before the upload.
      if (!file.type.startsWith("image/")) {
        setNotice(`“${file.name}” is not a photograph. Only images can be placed inside prose; video, audio and documents belong in this form's own media fields.`);
        return;
      }
      setNotice(null);
      setUploading(true);
      try {
        const { uploaded, failed } = await uploadMediaBatch({
          files: [file],
          linkedRecordType: target.recordType,
          linkedRecordId: target.recordId,
          // The field's own name is what a media list will show beside the row, and "Acknowledgement"
          // says far more about where this picture belongs than the camera's file name does.
          caption: propsRef.current.ariaLabel ?? undefined
        });
        const media = uploaded[0];
        if (!media) {
          setNotice(
            failed[0]?.error
              ? `That photograph did not upload: ${failed[0].error}`
              : "That photograph did not upload. Check the connection and try again."
          );
          return;
        }
        // Seeded from the upload's own answer, so a picture just placed draws immediately instead
        // of costing a second round trip to learn a URL this response already carried.
        mediaUrlsRef.current.set(media.id, media.url ?? null);
        const range = imageAnchorRef.current ?? selectionRef.current;
        // Taken back BEFORE the commit, because `commit` only reclaims focus when it already had
        // it — and choosing a file left it on the toolbar button, or on nothing at all. Without
        // this the caret is written into the caption of an editor that is not focused, so the
        // researcher's next keystroke goes wherever the browser thinks focus is.
        hostRef.current?.focus({ preventScroll: true });
        commit(insertImage(docRef.current, range, media.id), "image");
        setNotice(
          "Photograph placed. The line under it is the caption — it sits beneath the picture wherever this field is shown, and it can be formatted like any other sentence."
        );
      } catch (error) {
        // `uploadMediaBatch` throws only when NOTHING landed, so this is the whole-batch failure.
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

  /**
   * A structural edit on the photograph the caret is in — remove it, or step its printed width.
   *
   * The block index comes straight off the model selection, unlike the table's, which has to be
   * dug out of the DOM. A caption is ordinary prose in this model, so the caret really is at
   * `{block, offset}` inside the IMAGE block, and there is nothing to reconstruct.
   */
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

  /* ── Find and replace ───────────────────────────────────────────────────── */

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
    // Stated, not silent: a replace-all that reports nothing leaves the researcher to scroll the
    // document looking for evidence that the button did anything.
    setNotice(`Replaced ${result.count} occurrence${result.count === 1 ? "" : "s"}.`);
  }, [commit]);

  /**
   * Recount when the QUERY or its options change.
   *
   * Recounting after an EDIT is done inside {@link refreshChrome} instead, reading the query
   * through `findRef`. Doing it here as well would mean listing a document-changed signal in this
   * dependency array, and the only honest one available is a piece of state this effect does not
   * read — which the exhaustive-deps rule correctly calls out, and papering over that warning is
   * how a dependency array stops meaning anything.
   */
  useEffect(() => {
    if (!find.open || !find.query) return;
    const matches = findMatches(docRef.current, find.query, {
      caseSensitive: find.caseSensitive,
      wholeWord: find.wholeWord
    });
    setFind((current) => (current.count === matches.length ? current : { ...current, count: matches.length, index: 0 }));
  }, [find.open, find.query, find.caseSensitive, find.wholeWord]);

  /* ── Native listeners: one effect, installed once ───────────────────────── */

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

      /*
        INSIDE A TABLE, THE BROWSER EDITS AND THE MODEL READS BACK.

        This is the one inversion of the rule the rest of this handler follows, and it is forced by
        the selection model rather than chosen: a `DocPoint` is `{block, offset}` and cannot name a
        CELL. Every branch below resolves `current.focus.offset` against the block's concatenated
        text, so a character typed into the third cell of the second row would be inserted at that
        offset of the whole table's text — which lands in a different cell, or between two of them.
        Deleting and splitting are worse: `splitBlock` on a table would cut the grid in half.

        So the browser's own cell editing is allowed to run, and `onInput` -> `syncFromDom` ->
        `readTableElement` reads the result back. What is lost is the model-side undo granularity
        inside a cell; what is kept is that typing in a table does the obvious thing in all three
        engines. Marks still work, because `collectSpans` reads `<strong>`/`<em>` out of a cell
        exactly as it does out of a paragraph.

        Enter is the one keystroke still taken away — see `onKeyDown`, which moves to the next cell
        instead of letting the engine nest a `<div>` or a second paragraph inside a `<td>`.
      */
      if (docRef.current.blocks[current.focus.block]?.kind === "TABLE") return;

      switch (event.inputType) {
        case "insertText": {
          if (composingRef.current) return;
          // The browser is left to type INSIDE a block: that is the path a spell-checker, an
          // autocorrect and a soft keyboard all take, and interrupting it is what makes a
          // hand-rolled editor feel wrong on a phone. It is taken away only when the model has to
          // decide something the browser cannot know — a selection to replace, or a pending mark.
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
          // Shift+Enter lands here too and produces a new BLOCK, because the model has no soft
          // line break: `RichSpan` text with a "\n" in it is repaired by `from_json` on the server
          // and would render as a space in the .docx. A new paragraph is the honest equivalent.
          event.preventDefault();
          commit(splitBlock(docRef.current, current), "split");
          return;

        case "deleteContentBackward":
        case "deleteWordBackward":
        case "deleteSoftLineBackward":
        case "deleteHardLineBackward": {
          if (composingRef.current) return;
          // With text before the caret in this block, the deletion cannot escape the block, so the
          // browser's own word- and line-wise deletes are used as-is and read back afterwards.
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
          // The clipboard was already written by the `cut` event, which fires first; only the
          // removal is taken over, so the model decides how the two ends of the cut join up.
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
          // Everything else a browser can send — formatFontColor and formatBackColor from a
          // context menu, insertOrderedList from a native command, insertFromPaste if the paste
          // listener somehow did not fire — is REFUSED. Accepting it would put markup into the
          // surface that this file's reader does not understand, and the mark would be dropped at
          // the next keystroke with nothing on screen saying so. A refusal is visible; a silent
          // drop is not.
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
        // An external value that arrived mid-word is applied now — but only if it is still
        // different from what the researcher has just finished composing, or the word they typed
        // would be replaced by the value that was in flight before they typed it.
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
          // Parsed into a DETACHED document and read for its text only. Nothing from this parse is
          // ever attached to the page, no script in it can run, and no style in it can reach the
          // stylesheet — which is why reading `text/html` at all is safe here and inserting it
          // would not be.
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
        // Refused rather than truncated. A truncated paste is a document that looks complete on
        // screen and is missing its last twenty pages in the file, and the researcher has no way of
        // knowing which.
        setNotice(
          `That paste is too large for one field — ${size.characters.toLocaleString()} characters against a limit of ${MAX_DOCUMENT_CHARS.toLocaleString()}. Nothing was inserted; split it across the form's fields.`
        );
        return;
      }
      commit(result, "paste");
      setNotice(
        hadMarkup
          ? "Pasted as text. The source's own bold, colours and fonts are not part of this document's vocabulary, so only the words and the list structure came across — re-apply any formatting with the toolbar."
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
        // Escape is the keyboard route OUT of the editing surface and into the toolbar, which is
        // what makes Tab safe to use for list indentation below. Without a documented way out, a
        // keyboard user who indents a bullet is trapped in the field.
        event.preventDefault();
        const firstButton = toolbarRef.current?.querySelector<HTMLButtonElement>('button[tabindex="0"], button:not([disabled])');
        firstButton?.focus();
        return;
      }

      /*
        TAB AND ENTER INSIDE A TABLE MOVE BETWEEN CELLS.

        Both are taken from the browser here rather than in `onBeforeInput`, because neither
        produces an `input` event that could be read back usefully. Left alone, Enter inside a
        `<td>` makes the engine nest a `<div>` or a `<p>` inside the cell — which
        `readTableElement` then flattens on the next sync, so the researcher watches their line break
        vanish — and Tab moves focus out of the editor entirely, which is the wrong answer in a
        grid and the right one everywhere else.

        Moving the caret is done against the DOM rather than the model for the same reason the
        editing is: a `DocPoint` cannot name a cell, so there is nothing in the model to move to.
      */
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
        // Enter goes DOWN a row, Tab goes ACROSS — the two things a person means in a grid.
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
        // Tab only means "indent" inside a list. Everywhere else it must move focus to the next
        // form control, because a rich-text field is one field among thirty on a record form and a
        // control that eats Tab is a form nobody can fill in from the keyboard.
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
        // The browser's own find cannot reach a match that is scrolled out of a long field's box
        // and cannot replace anything, so inside a focused editor Ctrl+F is this one. Escape closes
        // it and the browser's own find is one press away again.
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
      // A pending mark belongs to the caret it was set at. Moving the caret abandons it — which is
      // what every editor does, and what stops Bold-then-click-elsewhere bolding the wrong word.
      // Toggling a mark does NOT move the caret, so the pending set survives its own toggle.
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

  /* ── Focus tracking: which editor owns the toolbar ──────────────────────── */

  useEffect(() => {
    function evaluate() {
      const element = document.activeElement;
      const inside = !!element && (hostRef.current?.contains(element) === true || toolbarRef.current?.contains(element) === true);
      setActive((current) => {
        // Measured HERE, before the bar mounts or unmounts, so the layout effect below has a
        // rectangle from the same tick rather than one remembered across an unknown amount of
        // scrolling. Only when the answer actually changes: an unchanged `active` renders nothing
        // new, the layout effect would not fire, and a stored rectangle would sit there stale
        // waiting to be spent on the next unrelated change.
        if (current !== inside) holdSurfaceStill();
        return inside;
      });
    }
    function onFocusOut() {
      // `focusout` fires BEFORE the next element takes focus, when `document.activeElement` is
      // still `<body>`. Deferring by a frame is what lets a click on a toolbar button — or a Tab
      // from the surface into the bar — be seen as staying inside rather than as leaving.
      window.requestAnimationFrame(evaluate);
    }
    document.addEventListener("focusin", evaluate);
    document.addEventListener("focusout", onFocusOut);
    return () => {
      document.removeEventListener("focusin", evaluate);
      document.removeEventListener("focusout", onFocusOut);
    };
  }, [holdSurfaceStill]);

  /* ── Full screen ────────────────────────────────────────────────────────── */

  useEffect(() => {
    if (!fullscreen) return;
    const root = document.documentElement;
    // The same scroll-lock protocol the navigation sheet uses, reused rather than reinvented: the
    // lock lives on <html> because iOS Safari ignores `overflow: hidden` on the body and keeps
    // panning the page underneath, and the vanished scrollbar's width is paid back through
    // `--nav-scroll-gutter` so nothing behind the overlay jumps sideways.
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
      // Only when the surface is not handling Escape itself (it moves focus to the toolbar).
      if (hostRef.current?.contains(document.activeElement)) return;
      setFullscreen(false);
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [fullscreen]);

  /* ── The toolbar's contents ─────────────────────────────────────────────── */

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
      // Raised and lowered text survives INTACT into every one of the five renderers — a single
      // `<w:vertAlign>` in the .docx, a smaller glyph off the baseline in both PDF writers — which
      // is the whole test a control has to pass to appear on this bar. Applying one clears the
      // other, because a character cannot be both.
      markButton("SUPERSCRIPT", "Superscript", "m², a footnote marker", <Superscript className={ICON} aria-hidden />),
      markButton("SUBSCRIPT", "Subscript", "H₂O", <Subscript className={ICON} aria-hidden />),
      // One colour and no picker: `w:highlight` is a closed vocabulary of named colours rather
      // than an RGB value, so a palette here would store something Word cannot express.
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
        // Says what the researcher gets, because a table is the one control here whose result cannot
        // be undone by pressing the same button again — it is an insertion, not a toggle.
        hint: "3×3, first row is the header",
        icon: <Table className={ICON} aria-hidden />,
        active: bar.kind === "TABLE",
        run: () => runTable()
      },
      /*
        THE PICTURE BUTTON, PRESENT ONLY WHERE THERE IS A RECORD TO FILE THE UPLOAD AGAINST.

        `upload` is absent for any caller that cannot name one, and an editor that cannot say which
        record a photograph belongs to must not offer to upload: `uploadMediaBatch` would leave an
        orphan object that `GET /media` cannot list and nothing can later resolve. Absent
        rather than disabled, for the same reason the table structure controls are absent outside a
        table — the bar's width is the scarce resource here.
      */
      ...(upload
        ? ([
            {
              kind: "button",
              id: "insert-image",
              label: uploading ? "Placing the photograph…" : "Place a photograph",
              // Says where it lands and what the line under it is, because this is an insertion and
              // not a toggle: pressing the button again does not undo it.
              hint: "goes below this paragraph, with a caption under it",
              icon: <ImagePlus className={ICON} aria-hidden />,
              disabled: uploading,
              run: runImagePick
            }
          ] as ToolItem[])
        : []),
      /*
        THE PHOTOGRAPH'S OWN CONTROLS, SHOWN ONLY WHILE THE CARET IS IN ONE.

        The width is stepped rather than typed: `widthPct` is a percentage of a text column the
        researcher cannot see, so a box asking for a number is a question nobody can answer, while
        two buttons and the picture in front of them is a question anybody can. The bounds are the
        model's own (10-100), so the editor cannot show a width the file will not print.
      */
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
      /*
        THE STRUCTURE CONTROLS, SHOWN ONLY INSIDE A TABLE.

        They appear when the caret is in a cell and are absent otherwise, rather than being present
        and disabled. A toolbar that carries six permanently greyed buttons for a block type most
        fields never contain spends its width on nothing — and width is the scarce resource here,
        because this bar has to fit a narrow field without wrapping to three rows.

        Every one of them acts at the CARET's row or column, which is what makes them predictable:
        "add a row" means "below this one", not "at the end of the table".
      */
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
        hint: "Shift+Tab. Nesting is stored; the generated file prints one level",
        icon: <ListIndentDecrease className={ICON} aria-hidden />,
        disabled: !bar.canOutdent,
        run: () => runIndent(-1)
      },
      {
        kind: "button",
        id: "indent",
        label: "Indent",
        hint: "Tab. Nesting is stored; the generated file prints one level",
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

  /* ── Render ─────────────────────────────────────────────────────────────── */

  const overLimit = typeof maxLength === "number" && maxLength > 0 && chrome.counts.characters > maxLength;

  return (
    <div
      className={
        fullscreen
          ? "fixed inset-0 z-[100] flex flex-col gap-2 overflow-y-auto bg-bg-0 p-4 sm:p-6"
          : "grid min-w-0 gap-2"
      }
      // A fixed overlay cannot inherit the body padding the scroll lock adds, so it repays the
      // vanished scrollbar itself — the same obligation `.nav-island-frame` carries.
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

      {/*
        DOCKED, NOT FLOATING, and it is the first thing in this grid.

        It used to be portalled to <body> and positioned at the editor's top edge minus its own
        height — which put it on top of whatever the form had drawn immediately above the box: the
        field's title and its help text, both unreadable behind it exactly when a researcher is being
        told what to write. There is no free space over a form for an overlay to occupy. Sitting in
        the flow, it can only ever push, never cover; being first here puts it under the title and
        above the find bar, which is the order the two are read in.

        The old arrangement's one merit was that appearing cost no layout shift. That is repaid by
        `holdSurfaceStill` below rather than by covering the labels.
      */}
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
              // Enter walks the matches. This input sits inside the record form, whose own Enter
              // handler walks to the next FIELD — so the event is stopped here as well as
              // defaulted, exactly as the tag box does.
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
            <label className="inline-flex items-center gap-1.5">
              <input
                type="checkbox"
                className="h-3.5 w-3.5 accent-purple-700"
                checked={find.caseSensitive}
                onChange={(event) => setFind((current) => ({ ...current, caseSensitive: event.target.checked, index: 0 }))}
              />
              Match case
            </label>
            <label className="inline-flex items-center gap-1.5">
              <input
                type="checkbox"
                className="h-3.5 w-3.5 accent-purple-700"
                checked={find.wholeWord}
                onChange={(event) => setFind((current) => ({ ...current, wholeWord: event.target.checked, index: 0 }))}
              />
              Whole words
            </label>
            {/* A live region, because the count is the only answer to "did that find anything" and
                it changes without anything moving on screen. */}
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
          // `role="textbox"` with `aria-multiline` is what tells a screen reader this is a place to
          // type rather than a region to read. The label comes from the form's own `field-label`
          // span through `aria-labelledby`, so there is exactly one accessible name.
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
            "field-input min-h-32 whitespace-pre-wrap break-words",
            fullscreen ? "min-h-0 flex-1 overflow-y-auto" : "max-h-[32rem] overflow-y-auto",
            disabled ? "cursor-not-allowed opacity-70" : ""
          )}
        />
      </div>

      {/*
        The file picker for an inline photograph, driven entirely from the toolbar button.

        `hidden` and not a visually-hidden input: this is never tabbed to, because the toolbar
        button IS the control and a second tab stop for a `<input type="file">` that looks like
        nothing would be a keyboard trap with no label a screen reader could read out. It sits
        OUTSIDE the contenteditable — a form control inside one is edited by the engine rather than
        operated, and `readDocFrom` would then read its shadow content back as prose.
      */}
      {upload ? (
        <input
          ref={fileRef}
          type="file"
          accept="image/*"
          hidden
          tabIndex={-1}
          // Named so a test can hand it a file directly: clicking the button opens the operating
          // system's chooser, which no browser automation can drive. A record form carries several other
          // file inputs (every media field has one), so the marker has to be specific to this
          // control or a test picks up whichever input happens to be first in the document.
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
        {/*
          DICTATION LIVES INSIDE THE EDITOR, AND IT HAS TO — this is not a layout preference.

          The obvious alternative is a microphone BESIDE the box, wired to a string setter, which is
          exactly what `DictatedTextArea` does for the plain multi-line fields. It cannot work here:
          a string setter can only replace or append to the whole value, and the whole value of a
          rich-text field is a document. One dictated sentence would flatten every heading, list and
          bold run already in the field, silently, at the moment the researcher was least watching
          the screen — they are talking, not reading.

          So the phrase goes through `commitDictated` (defined above), which is the same `insertText`
          an ordinary keystroke goes through: it lands at the caret, inherits the surrounding run's
          marks and is one undo step.

          IT IS THE ON-DEVICE BUTTON AND THERE IS NO OTHER OPTION HERE. Nothing this editor hears
          becomes a file or a request — see `components/dictation/OnDeviceDictationButton.tsx` for
          why a record form gets no server transcription rung.
        */}
        {!disabled ? <OnDeviceDictationButton fieldLabel={ariaLabel ?? "this field"} explainWhenUnavailable={explainWhenUnavailable} onCommit={commitDictated} /> : null}
        <button
          type="button"
          className="inline-flex items-center gap-1.5 text-xs font-medium text-purple-700 transition hover:text-purple-800"
          aria-expanded={savedPanelOpen}
          aria-controls={savedPanelOpen ? `${editorId}-saved` : undefined}
          onClick={() => setSavedPanelOpen((current) => !current)}
        >
          <Info className="h-3.5 w-3.5" aria-hidden />
          What gets saved
        </button>
      </div>

      {/*
        THE VISIBLE DEGRADATION CHANNEL, AND THE WORDING IS SPECIFIC TO THIS APPLICATION.

        In the application this editor was ported from, the honest list was "what survives into the
        generated .docx and .pdf". Field Repository generates no such file, and it has no rich-text
        reader on the server or on Android either (see the file header) — so the true statement here
        is a different and blunter one: FORMATTING IS FAITHFUL IN THIS BOX AND NOWHERE ELSE YET.

        Saying it is not optional politeness. `encodeStoredRichText` writes plain prose for as long
        as the field is unformatted, which is why typing and dictating are completely safe; the
        moment somebody bolds a word the column holds a document, and the CSV, the Excel workbook,
        the review panel and the Android app show that document's stored form rather than the
        sentence. A researcher who is told this once picks correctly. A researcher who is not finds
        out from a colleague looking at an export three weeks later, and by then it is in fifty rows.

        One panel and not a tooltip per control: twenty-five tooltips are twenty-five places to keep
        in step, and none of them is readable before the control is pressed.
      */}
      {savedPanelOpen ? (
        <div id={`${editorId}-saved`} className="rounded-md border border-line-200 bg-surface-50 p-3 text-xs leading-5 text-ink-700">
          <p className="font-medium text-ink-900">This field is stored as a document, not as a web page.</p>
          <ul className="mt-1.5 ml-4 list-disc space-y-1">
            <li>
              <span className="font-medium">Kept exactly, and shown here:</span> bold, italic, underline, strikethrough,
              superscript, subscript and the yellow highlight; paragraphs, headings 1 to 4 and quotes; bulleted and
              numbered lists; left, centre, right and justified alignment; tables; a photograph placed inside the prose
              with its caption.
            </li>
            <li>
              <span className="font-medium">Not carried into exports yet:</span> the CSV and Excel downloads and the
              review panel do not read formatting. A field you have formatted appears there as its stored form rather
              than as the sentence you typed. If a field has to read cleanly in an export today, leave it unformatted.
            </li>
            <li>
              <span className="font-medium">Unaffected by all of this:</span> plain typing and dictation. A field with no
              formatting applied is saved as ordinary text, exactly as it was before this editor existed — searchable,
              exportable, and identical on a handset.
            </li>
            <li>
              <span className="font-medium">Not offered at all:</span> links, text colour and font choices. The document
              format has no way to carry them, so a button for them here would look as though it worked and leave
              nothing behind.
            </li>
            <li>
              Pasted text arrives as words and list structure only — the source&apos;s own formatting is not carried across.
            </li>
          </ul>
        </div>
      ) : null}

      {notice ? (
        <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">{notice}</p>
      ) : null}

    </div>
  );
}

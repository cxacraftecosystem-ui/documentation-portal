import type { PreviewLine, TranscriptPreview } from "./contract";
import { speakerColorKey } from "./speakers";

/**
 * A TRANSCRIPT, READ FOR A LIST ROW (owner, 1 Nov 2026: the recordings list showed
 * `Transcript **Speaker 1:** Wow! …`, asterisks and all).
 *
 * The server sends each row's `TranscriptPreview` (contract v2): plain text under speaker labels,
 * mapped names included. This is the same builder, ported line for line from the server's
 * `transcription/transcript/preview.py` and held to the contract's `fixtures.preview` by
 * `e2e/transcript-preview-unit.spec.ts`, for a row that carries only the stored Markdown (an older
 * server, a recording read offline). Headings, table rows, rules and `>` lines are skipped; a
 * leading "Transcript" is dropped; `**Name:** text` and `**Name** · \`00:00:04\`` open a line;
 * unlabelled lines join the line above; at most three lines of at most 160 characters.
 */

export const PREVIEW_LINES = 3;
export const LINE_CHARS = 160;
const ELLIPSIS = "…";

const HEADING = /^\s*#{1,6}\s+/;
const RULE = /^\s*(?:-{3,}|\*{3,}|_{3,})\s*$/;
const TABLE = /^\s*\|/;
const QUOTE = /^\s*>/;
const INLINE_LABEL = /^\s*\*\*\s*([^*\n]{1,80}?)\s*(?::\s*\*\*|\*\*\s*:)\s*(.*)$/;
const BLOCK_LABEL = /^\s*\*\*\s*([^*\n]{1,80}?)\s*\*\*\s*(?:·\s*`[^`]*`)?\s*$/;
const LEAD_WORD = /^\s*transcript\s*[:\-–—]?\s*(?=\*\*|$)/i;
const BULLET = /^\s*[-*+]\s+(?=\S)/;

export type PreviewTurn = { speakerKey: string | null; speaker: string | null; text: string };

/** One line of text with Markdown emphasis, code ticks and runs of whitespace removed. */
export function plain(text: string | null | undefined): string {
  return String(text ?? "")
    .replace(/`([^`\n]*)`/g, "$1")
    .replace(/\*\*([^*\n]+?)\*\*|\*([^*\n]+?)\*/g, (_whole, bold?: string, italic?: string) => bold ?? italic ?? "")
    .replace(/[*`]+/g, "")
    .split(/\s+/)
    .filter(Boolean)
    .join(" ");
}

/** `text` cut to `limit` code points at a word boundary, with an ellipsis when cut. */
export function clip(text: string, limit = LINE_CHARS): string {
  const points = Array.from(text);
  if (points.length <= limit) return text;
  let cut = points.slice(0, limit - 1);
  const space = cut.lastIndexOf(" ");
  if (space >= Math.floor(limit / 2)) cut = cut.slice(0, space);
  return cut.join("").replace(/[ ,;:.\-–—]+$/, "") + ELLIPSIS;
}

export function turnsFromMarkdown(markdown: string | null | undefined): PreviewTurn[] {
  const out: PreviewTurn[] = [];
  let speaker: string | null = null;
  let current: string[] = [];
  let started = false;
  const flush = () => {
    const text = plain(current.join(" "));
    if (text) out.push({ speakerKey: null, speaker, text });
    current = [];
  };
  for (const raw of String(markdown ?? "").replace(/\r\n?/g, "\n").split("\n")) {
    const line = started ? raw : raw.replace(LEAD_WORD, "");
    if (!line.trim()) continue;
    if (HEADING.test(line) || RULE.test(line) || TABLE.test(line) || QUOTE.test(line)) continue;
    started = true;
    const block = BLOCK_LABEL.exec(line);
    const inline = block ? null : INLINE_LABEL.exec(line);
    if (block || inline) {
      flush();
      const label = plain((block ?? inline)![1]).replace(/:+$/, "").trim();
      speaker = label || null;
      if (inline && inline[2].trim()) current.push(inline[2]);
      continue;
    }
    current.push(line.replace(BULLET, ""));
  }
  flush();
  return out;
}

export function previewOf(turns: PreviewTurn[], maxLines = PREVIEW_LINES): TranscriptPreview | null {
  const lines: PreviewLine[] = [];
  for (const turn of turns) {
    const text = plain(turn.text);
    if (!text) continue;
    const speaker = plain(turn.speaker ?? "") || null;
    lines.push({ speaker, speakerKey: turn.speakerKey, colorKey: speakerColorKey(turn.speakerKey, speaker), text: clip(text) });
    if (lines.length >= maxLines) break;
  }
  if (!lines.length) return null;
  return { text: lines.map((line) => (line.speaker ? `${line.speaker}: ${line.text}` : line.text)).join(" "), lines };
}

export function previewMarkdown(markdown: string | null | undefined): TranscriptPreview | null {
  return previewOf(turnsFromMarkdown(markdown));
}

/** Markdown punctuation out of a search snippet's piece, its words untouched. */
export function plainSnippet(text: string): string {
  return text.replace(/\*\*|`/g, "").replace(/^\s*#{1,6}\s+/, "");
}

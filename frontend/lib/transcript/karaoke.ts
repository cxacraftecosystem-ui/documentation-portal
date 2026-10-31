import type { TranscriptTurn } from "./contract";

/**
 * KARAOKE: which turn and which word the audio is on (design §10.1).
 *
 * The turn is the last one that has started by `ms` (the pause after a turn keeps it lit until the
 * next speaker begins); the word is the last word of that turn that has started, while it is still
 * being spoken. Both are binary searches, because this runs on every animation frame while playing.
 */
export type KaraokePosition = { turnIndex: number; wordIndex: number | null };

function lastStartedBy<T extends { startMs: number }>(items: readonly T[], ms: number): number {
  let low = 0;
  let high = items.length - 1;
  let found = -1;
  while (low <= high) {
    const mid = (low + high) >> 1;
    if (items[mid].startMs <= ms) {
      found = mid;
      low = mid + 1;
    } else high = mid - 1;
  }
  return found;
}

export function locate(turns: readonly TranscriptTurn[], ms: number): KaraokePosition | null {
  const turnIndex = lastStartedBy(turns, ms);
  if (turnIndex < 0) return null;
  const words = turns[turnIndex].words;
  const at = lastStartedBy(words, ms);
  return { turnIndex, wordIndex: at >= 0 && ms < words[at].endMs ? at : null };
}

/**
 * The turn's text cut into pieces for drawing: each word (with its index) and the text between
 * words, each piece marked when it overlaps a low-confidence span. The contract's offsets are CODE
 * POINTS, so the text is walked as code points, never as UTF-16 units. With no words (an edited turn
 * keeps none) the low-confidence spans alone cut it.
 */
export type TextPiece = { text: string; word: number | null; lowConfidence: boolean };

export function pieces(turn: TranscriptTurn): TextPiece[] {
  const points = Array.from(turn.text);
  const spans = turn.lowConfidence;
  const unsure = (start: number, end: number) => spans.some(([a, b]) => start < b && a < end);
  const cuts: { start: number; end: number; word: number | null }[] = [];
  if (turn.words.length) {
    let cursor = 0;
    turn.words.forEach((word, index) => {
      const start = Math.max(cursor, Math.min(word.offset, points.length));
      const end = Math.min(points.length, start + Array.from(word.text).length);
      if (start > cursor) cuts.push({ start: cursor, end: start, word: null });
      if (end > start) cuts.push({ start, end, word: index });
      cursor = Math.max(cursor, end);
    });
    if (cursor < points.length) cuts.push({ start: cursor, end: points.length, word: null });
  } else {
    const edges = new Set<number>([0, points.length]);
    for (const [a, b] of spans) {
      edges.add(Math.max(0, Math.min(a, points.length)));
      edges.add(Math.max(0, Math.min(b, points.length)));
    }
    const sorted = [...edges].sort((x, y) => x - y);
    for (let i = 0; i < sorted.length - 1; i++) {
      if (sorted[i + 1] > sorted[i]) cuts.push({ start: sorted[i], end: sorted[i + 1], word: null });
    }
  }
  return cuts.map((cut) => ({ text: points.slice(cut.start, cut.end).join(""), word: cut.word, lowConfidence: unsure(cut.start, cut.end) }));
}

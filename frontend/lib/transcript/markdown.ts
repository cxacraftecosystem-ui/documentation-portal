import type { CopyTrack, Transcript, TranscriptTurn } from "./contract";
import { clock, lengthLabel } from "./format";

/**
 * THE TEXT EVERY COPY CARRIES, built here from the stored turns and never taken from a model
 * (design §2.4). The contract allows a client to build copy text only if it reproduces
 * `fixtures.copy` byte for byte, which `e2e/transcript-contract-unit.spec.ts` checks; the server's
 * `render.py` is held to the same fixtures, so a paste and a downloaded .md read identically.
 *
 * Rules (fixtures.copy.rules): line ends are \n with no trailing newline; English of an
 * ALREADY_ENGLISH turn, or of a turn with no translation text, is its original, and "both" then
 * prints the original once; STALE English still prints; multi-line English quotes every line;
 * turn text is not escaped; speaker names lose `*`, backtick and `|`.
 */

const cleanName = (name: string) => name.replace(/[*`|]/g, "");

/** True when "both" has a second line to print for this turn. */
export function hasEnglish(turn: TranscriptTurn): boolean {
  return turn.translation.status !== "ALREADY_ENGLISH" && typeof turn.translation.text === "string" && turn.translation.text !== "";
}

export function englishOf(turn: TranscriptTurn): string {
  return hasEnglish(turn) ? (turn.translation.text as string) : turn.text;
}

function quote(text: string): string {
  return text
    .split("\n")
    .map((line) => `> ${line}`)
    .join("\n");
}

/** One turn's text on one track, with no speaker line: the per-turn copy. */
export function turnText(turn: TranscriptTurn, track: CopyTrack): string {
  if (track === "original") return turn.text;
  if (track === "english") return englishOf(turn);
  return hasEnglish(turn) ? `${turn.text}\n${quote(turn.translation.text as string)}` : turn.text;
}

export function turnBlock(turn: TranscriptTurn, track: CopyTrack, timestamps = true): string {
  if (!timestamps) return `**${cleanName(turn.speakerName)}**\n${turnText(turn, track)}`;
  return `**${cleanName(turn.speakerName)}** · \`${clock(turn.startMs)}\`\n${turnText(turn, track)}`;
}

export function transcriptHeader(transcript: Transcript): string {
  const languages = transcript.languages.map((language) => language.label).join(", ");
  return `# ${transcript.title}\n| Language | ${languages} | Length | ${lengthLabel(transcript.durationMs)} | Speakers | ${transcript.speakerCount} |`;
}

/** The heading pasted whole-transcript English sits under in a "both" copy (fixtures.manualEnglish). */
export const ENGLISH_HEADING = "## English";

/**
 * Copy all: the header, then every turn. `timestamps: false` keeps only the speaker on each turn.
 * With `manualEnglish` (English pasted whole, contract 3) "english" is the header and that text, and
 * "both" prints it under `ENGLISH_HEADING` after the turns.
 */
export function copyAll(transcript: Transcript, track: CopyTrack, timestamps = true): string {
  const manual = (transcript.manualEnglish ?? "").trim();
  if (manual && track === "english") return [transcriptHeader(transcript), manual].join("\n\n");
  const parts = [transcriptHeader(transcript), ...transcript.turns.map((turn) => turnBlock(turn, track, timestamps))];
  if (manual && track === "both") parts.push(ENGLISH_HEADING, manual);
  return parts.join("\n\n");
}

/** Copy selected: the chosen turns in transcript order, no header. */
export function copySelected(transcript: Transcript, turnIds: Iterable<string>, track: CopyTrack): string {
  const chosen = new Set(turnIds);
  return transcript.turns
    .filter((turn) => chosen.has(turn.id))
    .map((turn) => turnBlock(turn, track))
    .join("\n\n");
}

/** One turn's copy menu: that turn's text only. */
export function copySingle(turn: TranscriptTurn, track: CopyTrack): string {
  return turnText(turn, track);
}

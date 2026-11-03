import type { CopyTrack, Transcript, TranscriptTurn } from "./contract";
import { clock, lengthLabel } from "./format";

const cleanName = (name: string) => name.replace(/[*`|]/g, "");

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

export const ENGLISH_HEADING = "## English";

export function copyAll(transcript: Transcript, track: CopyTrack, timestamps = true): string {
  const manual = (transcript.manualEnglish ?? "").trim();
  if (manual && track === "english") return [transcriptHeader(transcript), manual].join("\n\n");
  const parts = [transcriptHeader(transcript), ...transcript.turns.map((turn) => turnBlock(turn, track, timestamps))];
  if (manual && track === "both") parts.push(ENGLISH_HEADING, manual);
  return parts.join("\n\n");
}

export function copySelected(transcript: Transcript, turnIds: Iterable<string>, track: CopyTrack): string {
  const chosen = new Set(turnIds);
  return transcript.turns
    .filter((turn) => chosen.has(turn.id))
    .map((turn) => turnBlock(turn, track))
    .join("\n\n");
}

export function copySingle(turn: TranscriptTurn, track: CopyTrack): string {
  return turnText(turn, track);
}

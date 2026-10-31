import { fileName } from "@/lib/naming";

import type { CopyTrack, DownloadFormat } from "./contract";

/**
 * The names of the files the viewer saves (the naming convention; rows in
 * `shared/naming-golden.json`). A download keeps the server's Content-Disposition name, built by
 * `transcript_pipeline.download_name` from the same "What" table as `downloadWhat`; the web builds a
 * name itself only as that download's fallback, and for the quote card and its clip, which the
 * Android viewer names identically (`TranscriptNames.forView`: "Quote-Card", .png and .m4a).
 */
export function downloadWhat(format: DownloadFormat, track: CopyTrack): string {
  if (format === "srt" || format === "vtt") return track === "english" ? "Subtitles-With-Speakers-English" : "Subtitles-With-Speakers";
  return track === "english" ? "Transcript-English" : track === "both" ? "Transcript-Bilingual" : "Transcript";
}

export const QUOTE_CARD_WHAT = "Quote-Card";

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "24 Jun 2026" → "2026-06-24", else null. */
function dayOf(part: string): string | null {
  const match = /^(\d{1,2}) ([A-Z][a-z]{2}) (\d{4})$/.exec(part.trim());
  const month = match ? MONTHS.indexOf(match[2]) : -1;
  if (!match || month < 0) return null;
  return `${match[3]}-${String(month + 1).padStart(2, "0")}-${match[1].padStart(2, "0")}`;
}

/**
 * A name from what the view carries: its title, "Interview · Giriraj Prasad Chhipa · 24 Jun 2026",
 * gives the entity, the subject and the day. Place and craft are not on the view, so they are left
 * out (an unknown field is omitted, never guessed).
 */
export function nameForView(title: string, what: string, ext: string): string {
  const parts = title
    .split(" · ")
    .map((part) => part.trim())
    .filter(Boolean);
  const day = parts.map(dayOf).find((value) => value !== null) ?? null;
  const head = (parts[0] ?? "").toLowerCase();
  const entity = head === "group interview" || head === "group-interview" ? "Group-Interview" : "Interview";
  const subject = parts.slice(1).find((part) => dayOf(part) === null) ?? null;
  return fileName({ entity, date: day, subject, what, ext });
}

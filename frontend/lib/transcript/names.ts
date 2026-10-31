import { fileName } from "@/lib/naming";

import type { CopyTrack, DownloadFormat } from "./contract";

/**
 * The "What" field of every file the viewer saves (naming convention; rows in
 * `shared/naming-golden.json`). The server names its downloads by the same table
 * (`transcript_pipeline.download_name`) and sends the name in Content-Disposition; the web builds
 * one itself only as the fallback, and for the quote card it draws on the device.
 */
export function downloadWhat(format: DownloadFormat, track: CopyTrack): string {
  if (format === "srt" || format === "vtt") return track === "english" ? "Subtitles-With-Speakers-English" : "Subtitles-With-Speakers";
  return track === "english" ? "Transcript-English" : track === "both" ? "Transcript-Bilingual" : "Transcript";
}

export const QUOTE_CARD_WHAT = "Quote-Card";
export const QUOTE_CLIP_WHAT = "Quote-Clip";

/** A transcript file's fallback name: the fields the client knows (no place or date). */
export function transcriptFileName(format: DownloadFormat, track: CopyTrack, subject?: string | null, craft?: string | null): string {
  return fileName({ entity: "Interview", craft, subject, what: downloadWhat(format, track), ext: format });
}

export function quoteFileName(what: typeof QUOTE_CARD_WHAT | typeof QUOTE_CLIP_WHAT, ext: string, subject?: string | null, craft?: string | null): string {
  return fileName({ entity: "Interview", craft, subject, what, ext });
}

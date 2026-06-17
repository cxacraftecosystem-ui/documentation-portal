"use client";

import { Loader2 } from "lucide-react";

import type { MediaFile } from "@/lib/types";

const PROCESSING = new Set(["QUEUED", "PROCESSING", "PENDING", "RUNNING"]);
const DONE = new Set(["COMPLETED", "DONE", "EMPTY"]);

/**
 * Inline transcript for an audio (or any transcribable) media file.
 * - While the backend Whisper job is queued/processing, shows a spinner ("buffer icon").
 * - When done, shows the transcript text (or an "empty" note).
 * - On failure / unavailable, shows the reason so it never silently disappears.
 * Renders nothing for non-audio media with no transcript data.
 */
export function TranscriptBlock({ media }: { media: MediaFile }) {
  const status = (media.transcriptStatus ?? "").toUpperCase();
  const isAudio = media.mediaType === "AUDIO";
  const hasText = !!media.transcriptText && media.transcriptText.trim().length > 0;

  if (!isAudio && !status && !hasText) return null;

  if (hasText) {
    return (
      <div className="mt-2 rounded-md border border-[#e6dfd8] bg-field-50 p-3">
        <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-ink-soft">Transcript</div>
        <p className="whitespace-pre-wrap text-sm text-ink">{media.transcriptText}</p>
      </div>
    );
  }

  if (PROCESSING.has(status) || (isAudio && !status)) {
    return (
      <div className="mt-2 flex items-center gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
        <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
        <span>Transcribing audio… this appears here automatically once processing finishes.</span>
      </div>
    );
  }

  if (DONE.has(status)) {
    return <div className="mt-2 rounded-md border border-[#e6dfd8] bg-field-50 px-3 py-2 text-sm text-ink-muted">Transcript completed — no speech detected.</div>;
  }

  return (
    <div className="mt-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
      Transcript {status ? status.toLowerCase() : "unavailable"}
      {media.transcriptError ? `: ${media.transcriptError}` : "."}
    </div>
  );
}

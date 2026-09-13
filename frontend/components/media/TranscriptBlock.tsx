"use client";

import { Bot, Loader2, Pencil } from "lucide-react";

import { Markdown } from "@/components/Markdown";
import { AudioPlayer } from "@/components/ui/AudioPlayer";
import { formatDateTime } from "@/lib/format";
import type { MediaFile } from "@/lib/types";

const PROCESSING = new Set(["QUEUED", "PROCESSING", "PENDING", "RUNNING"]);
const DONE = new Set(["COMPLETED", "DONE", "EMPTY"]);

/**
 * WHO STANDS BEHIND THIS TEXT — and it has THREE answers, not two.
 *
 * ── WHY `edited` IS `boolean | undefined` AND WHY `!!` IS BANNED ────────────────────────────────
 *
 * `MediaFile.transcriptEditedAt` is NULL for two completely different reasons:
 *
 *   - **the machine's own words** — the transcription queue wrote this text and nobody has touched
 *     it since. `services/media_queue` writes `transcriptText` and deliberately never touches either
 *     stamp, so NULL here really does mean "not edited"...
 *   - **...OR nobody can say.** `POST /media/{id}/transcript` has been able to replace a transcript
 *     since long before these columns existed (`api/routes/media.py:610`), so every row written
 *     before migration 20260913120200 is NULL whether a researcher rewrote it line by line or not.
 *
 * `edited={!!media.transcriptEditedAt}` collapses the second into the first and prints "the machine
 * said this" over text a researcher may well have typed — which is the single assertion these
 * columns were added to stop being made silently. Callers pass `transcriptEditedAt ? true :
 * undefined`, and `undefined` draws NOTHING: saying nothing is the honest rendering of "this row
 * cannot answer", and it is also byte-for-byte what every reader saw before the columns existed.
 *
 * `false` IS NOT DEAD CODE, and it is not reachable from `transcriptEditedAt` alone. It is for a
 * caller that knows the row post-dates the migration — a future list that has read
 * `createdAt`, or an API that starts answering the question outright. When such a caller exists
 * this component already draws the right thing; until then the branch costs three lines and keeps
 * the type honest. Do NOT "simplify" the prop to a required boolean: that is the `!!` above in a
 * different coat, one call site further out, where the census cannot see it.
 *
 * ── WHY IT DOES NOT NAME THE EDITOR ─────────────────────────────────────────────────────────────
 *
 * `transcriptEditedById` is a bare id with no Prisma relation behind it — an audit stamp, not a
 * navigable edge — so the API answers an id and no name. Printing a CUID at a reader is worse than
 * printing nothing, and resolving it would mean a second request per media row on a page that
 * already lists twenty. The date is what a reader can actually use.
 *
 * ── THE WORDING IS NOT "EDITED BY A HUMAN" ──────────────────────────────────────────────────────
 *
 * The route stamps both a researcher who retyped a paragraph AND one who read an AI refinement and
 * pressed Accept, on the argument that the second is still a person deciding these are the right
 * words. So the sentence says a person STANDS BEHIND the text rather than that a person typed every
 * character of it — and it must keep saying that, or the copy will be claiming something the stamp
 * does not mean.
 */
export function EditedFlag({ edited, at }: { edited?: boolean; at?: string | null }) {
  // "Not stated" — see above. Nothing is drawn, which is exactly what a reader saw before the
  // columns existed, and is the only honest rendering of a row that cannot answer.
  if (edited === undefined) return null;
  if (edited) {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-purple-50 px-2 py-0.5 text-[0.6875rem] font-medium normal-case tracking-normal text-purple-700">
        <Pencil className="h-3 w-3" aria-hidden />
        Checked by a researcher{at ? ` · ${formatDateTime(at)}` : ""}
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-surface-50 px-2 py-0.5 text-[0.6875rem] font-medium normal-case tracking-normal text-ink-500">
      <Bot className="h-3 w-3" aria-hidden />
      As transcribed
    </span>
  );
}

/**
 * Inline transcript for an audio (or any transcribable) media file. Audio items first render an
 * inline themed player, then the transcript below it:
 * - While the backend transcription job is queued/processing, shows a spinner ("buffer icon").
 * - When done, shows the transcript text (or an "empty" note).
 * - On failure / unavailable, shows the reason so it never silently disappears.
 * Renders nothing for non-audio media with no transcript data.
 */
export function TranscriptBlock({ media }: { media: MediaFile }) {
  const status = (media.transcriptStatus ?? "").toUpperCase();
  const isAudio = media.mediaType === "AUDIO" || (media.mimeType ?? "").toLowerCase().startsWith("audio/");
  const hasText = !!media.transcriptText && media.transcriptText.trim().length > 0;

  if (!isAudio && !status && !hasText) return null;

  // Inline playback above whatever transcript state renders below.
  const player = isAudio && media.url ? <AudioPlayer src={media.url} className="mt-2" /> : null;

  if (hasText) {
    return (
      <>
        {player}
        <div className="mt-2 rounded-md border border-line-200 bg-field-50 p-3">
          <div className="mb-1 flex flex-wrap items-center gap-2">
            <span className="text-xs font-semibold uppercase tracking-wide text-ink-soft">Transcript</span>
            {/*
              `transcriptEditedAt ? true : undefined`, NEVER `!!media.transcriptEditedAt`. The two
              are not the same value: `!!` answers `false` for a row that simply cannot say, and
              `EditedFlag` would then print "As transcribed" over text a researcher may have written
              every word of. Read the header of this file before changing this expression — the
              whole argument lives there, and it is also written out at
              `backend/app/api/routes/media.py:643` and in `lib/types.ts` beside the column.
            */}
            <EditedFlag edited={media.transcriptEditedAt ? true : undefined} at={media.transcriptEditedAt} />
          </div>
          <Markdown text={media.transcriptText ?? ""} />
        </div>
      </>
    );
  }

  if (PROCESSING.has(status) || (isAudio && !status)) {
    return (
      <>
        {player}
        <div className="mt-2 flex items-center gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
          <span>Transcribing audio… refreshes automatically while processing.</span>
        </div>
      </>
    );
  }

  if (DONE.has(status)) {
    return (
      <>
        {player}
        <div className="mt-2 rounded-md border border-line-200 bg-field-50 px-3 py-2 text-sm text-ink-muted">Transcript completed — no speech detected.</div>
      </>
    );
  }

  return (
    <>
      {player}
      <div className="mt-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
        Transcript {status ? status.toLowerCase() : "unavailable"}
        {media.transcriptError ? `: ${media.transcriptError}` : "."}
      </div>
    </>
  );
}

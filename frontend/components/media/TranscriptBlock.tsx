"use client";

import { useState } from "react";
import { Bot, Loader2, Pencil } from "lucide-react";

import { useMediaUrl } from "@/components/hooks/useMediaUrl";
import { Markdown } from "@/components/Markdown";
import { TranscriptInsights } from "@/components/media/TranscriptInsights";
import { TranscriptRefine } from "@/components/media/TranscriptRefine";
import { TranscriptViewer } from "@/components/transcript/TranscriptViewer";
import { AudioPlayer } from "@/components/ui/AudioPlayer";
import { formatDateTime } from "@/lib/format";
import type { MediaFile } from "@/lib/types";

const PROCESSING = new Set(["QUEUED", "PROCESSING", "PENDING", "RUNNING"]);
const DONE = new Set(["COMPLETED", "DONE", "EMPTY"]);

export function EditedFlag({ edited, at }: { edited?: boolean; at?: string | null }) {
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

export function TranscriptBlock({ media: stored, readOnly = false }: { media: MediaFile; readOnly?: boolean }) {
  const [updated, setUpdated] = useState<MediaFile | null>(null);
  const media = updated && updated.id === stored.id ? updated : stored;
  const isAudio = media.mediaType === "AUDIO" || (media.mimeType ?? "").toLowerCase().startsWith("audio/");
  const isVideo = media.mediaType === "VIDEO" || (media.mimeType ?? "").toLowerCase().startsWith("video/");
  const refine = <TranscriptRefine media={media} readOnly={readOnly} onUpdated={setUpdated} />;
  const untranscribed = !(media.transcriptStatus ?? "").trim() && !(media.transcriptText ?? "").trim();
  if ((!isAudio && !isVideo) || untranscribed) {
    return (
      <>
        <LegacyTranscriptBlock media={media} />
        {refine}
      </>
    );
  }
  return (
    <>
      <TranscriptViewer
        key={updated?.transcriptText ?? "stored"}
        mediaId={media.id}
        audioUrl={media.url}
        video={isVideo}
        fallback={<LegacyTranscriptBlock media={media} />}
      />
      {refine}
    </>
  );
}

function LegacyTranscriptBlock({ media }: { media: MediaFile }) {
  const { src: playable } = useMediaUrl(media);
  const status = (media.transcriptStatus ?? "").toUpperCase();
  const isAudio = media.mediaType === "AUDIO" || (media.mimeType ?? "").toLowerCase().startsWith("audio/");
  const hasText = !!media.transcriptText && media.transcriptText.trim().length > 0;

  if (!isAudio && !status && !hasText) return null;

  const player = isAudio && playable ? <AudioPlayer src={playable} className="mt-2" /> : null;

  if (hasText) {
    return (
      <>
        {player}
        <div className="mt-2 rounded-md border border-line-200 bg-field-50 p-3">
          <div className="mb-1 flex flex-wrap items-center gap-2">
            <span className="text-xs font-semibold uppercase tracking-wide text-ink-500">Transcript</span>
            <EditedFlag edited={media.transcriptEditedAt ? true : undefined} at={media.transcriptEditedAt} />
          </div>
          <Markdown text={media.transcriptText ?? ""} />
        </div>
        {status === "COMPLETED" && media.id ? <TranscriptInsights mediaId={media.id} /> : null}
      </>
    );
  }

  if (!status) return player;

  if (PROCESSING.has(status)) {
    return (
      <>
        {player}
        <div className="mt-2 flex items-center gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
          <span>Transcribing…</span>
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

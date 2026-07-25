"use client";

import { useEffect, useRef, useState } from "react";
import { FileText, Headphones, Image as ImageIcon, Video } from "lucide-react";

import { MediaLightbox, type PreviewMedia } from "@/components/media/MediaLightbox";
import { bytes } from "@/lib/format";
import type { BatchProgress } from "@/lib/media";
import { useUploads, type CompletedUpload } from "@/lib/uploads";
import type { MediaType } from "@/lib/types";

export function formatEta(seconds: number | null): string {
  if (seconds === null) return "estimating…";
  if (seconds <= 0) return "almost done";
  if (seconds < 60) return `~${seconds}s remaining`;
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return `~${minutes}m ${rest}s remaining`;
}

function iconFor(mediaType: MediaType) {
  if (mediaType === "IMAGE") return <ImageIcon className="h-3.5 w-3.5" aria-hidden />;
  if (mediaType === "VIDEO") return <Video className="h-3.5 w-3.5" aria-hidden />;
  if (mediaType === "AUDIO") return <Headphones className="h-3.5 w-3.5" aria-hidden />;
  return <FileText className="h-3.5 w-3.5" aria-hidden />;
}

function toPreview(item: CompletedUpload): PreviewMedia {
  const { file } = item;
  return {
    key: item.key,
    id: file.id,
    name: file.originalFilename,
    mediaType: file.mediaType,
    mimeType: file.mimeType,
    sizeBytes: file.sizeBytes,
    url: file.url,
    caption: file.caption,
    transcriptStatus: file.transcriptStatus,
    transcriptText: file.transcriptText,
    transcriptError: file.transcriptError
  };
}

/** Chips for media that finished uploading — tap one to preview it in the lightbox. */
export function UploadedMediaChips({ items, showSection = false }: { items: CompletedUpload[]; showSection?: boolean }) {
  const [active, setActive] = useState<PreviewMedia | null>(null);
  if (!items.length) return null;
  return (
    <>
      <div className="flex flex-wrap gap-2">
        {items.map((item) => (
          <button
            key={item.key}
            type="button"
            onClick={() => setActive(toPreview(item))}
            title={`Preview ${item.file.originalFilename}`}
            className="inline-flex max-w-full items-center gap-2 rounded-md border border-line-200 bg-card px-2.5 py-1.5 text-left text-xs text-ink-700 transition hover:border-purple-300 hover:bg-purple-50"
          >
            <span className="shrink-0 text-purple-700">{iconFor(item.file.mediaType)}</span>
            <span className="max-w-[14rem] truncate font-medium text-ink-900">{item.file.originalFilename}</span>
            {showSection ? <span className="shrink-0 text-ink-500">· {item.sectionLabel}</span> : null}
            <span className="shrink-0 text-ink-500">{bytes(item.file.sizeBytes)}</span>
          </button>
        ))}
      </div>
      {active ? <MediaLightbox item={active} onClose={() => setActive(null)} /> : null}
    </>
  );
}

/**
 * Section-scope upload state: a determinate bar with percentage, file counter and ETA while the
 * batch runs, and the media this section finished uploading afterwards. Passing `sectionId`
 * publishes the same progress to the page-level <UploadTray>, so one call site feeds both scopes.
 */
export function UploadProgress({
  progress,
  sectionId,
  label,
  className = ""
}: {
  progress: BatchProgress | null;
  sectionId?: string;
  label?: string;
  /** Extra classes for the wrapper — grid parents pass their column span here. */
  className?: string;
}) {
  const { reportSection, completed } = useUploads();
  const sectionLabel = label ?? "Media";

  useEffect(() => {
    if (!sectionId) return;
    reportSection(sectionId, sectionLabel, progress);
  }, [sectionId, sectionLabel, progress, reportSection]);

  // Clearing has to happen on UNMOUNT only — doing it in the effect above would drop and re-add the
  // section on every progress tick, restarting the page-level ETA clock each time.
  const latest = useRef({ sectionId, sectionLabel, reportSection });
  useEffect(() => {
    latest.current = { sectionId, sectionLabel, reportSection };
  });
  useEffect(() => {
    return () => {
      const { sectionId: id, sectionLabel: name, reportSection: report } = latest.current;
      if (id) report(id, name, null);
    };
  }, []);

  const done = sectionId ? completed.filter((item) => item.sectionId === sectionId) : [];
  if (!progress && !done.length) return null;
  const percent = progress ? Math.round(progress.fraction * 100) : 100;

  return (
    <div className={`grid gap-2 ${className}`}>
      {progress ? (
        <div className="grid gap-1 rounded-md border border-line-200 bg-surface-50 p-3" role="status" aria-live="polite">
          <div className="flex items-center justify-between gap-3 text-sm text-ink-900">
            <span className="truncate">
              {label ? `${label} — ` : ""}Uploading file {Math.min(progress.fileIndex + 1, progress.fileCount)} of {progress.fileCount}
            </span>
            <span className="shrink-0 font-semibold tabular-nums">{percent}%</span>
          </div>
          <div className="h-2 overflow-hidden rounded-full bg-line-200">
            <div className="h-full rounded-full bg-purple-700 transition-all" style={{ width: `${percent}%` }} />
          </div>
          <div className="truncate text-xs text-ink-500" title={progress.currentFileName}>
            {progress.currentFileName} · {bytes(progress.uploadedBytes)} of {bytes(progress.totalBytes)} · {formatEta(progress.etaSeconds)}
          </div>
        </div>
      ) : null}
      {done.length ? (
        <div className="grid gap-1.5">
          <p className="text-xs font-semibold uppercase tracking-wide text-ink-500">
            {done.length} file{done.length === 1 ? "" : "s"} uploaded
          </p>
          <UploadedMediaChips items={done} />
        </div>
      ) : null}
    </div>
  );
}

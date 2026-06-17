"use client";

import type { BatchProgress } from "@/lib/media";

function formatEta(seconds: number | null): string {
  if (seconds === null) return "estimating…";
  if (seconds <= 0) return "almost done";
  if (seconds < 60) return `~${seconds}s remaining`;
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return `~${minutes}m ${rest}s remaining`;
}

/** Determinate progress bar for a multi-file upload: percentage, file counter and ETA. */
export function UploadProgress({ progress }: { progress: BatchProgress | null }) {
  if (!progress) return null;
  const percent = Math.round(progress.fraction * 100);
  return (
    <div className="grid gap-1 rounded-md border border-[#e6dfd8] bg-field-50 p-3" role="status" aria-live="polite">
      <div className="flex items-center justify-between text-sm text-ink">
        <span>
          Uploading file {Math.min(progress.fileIndex + 1, progress.fileCount)} of {progress.fileCount}
        </span>
        <span className="font-semibold">{percent}%</span>
      </div>
      <div className="h-2 overflow-hidden rounded-full bg-[#e6dfd8]">
        <div className="h-full rounded-full bg-field-600 transition-all" style={{ width: `${percent}%` }} />
      </div>
      <div className="truncate text-xs text-ink-muted" title={progress.currentFileName}>
        {progress.currentFileName} · {formatEta(progress.etaSeconds)}
      </div>
    </div>
  );
}

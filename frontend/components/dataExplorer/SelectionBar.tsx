"use client";

import { Download, FolderDown, Share2, X } from "lucide-react";

import { INCLUDE_LABELS, type IncludeKey } from "@/lib/dataExplorer/contract";
import { formatBytes } from "@/lib/dataExplorer/plan";
import { overSelectionLimit, type Selection } from "@/lib/dataExplorer/selection";

import type { Sized } from "@/components/dataExplorer/FolderHeader";

export function selectionSummary(count: number, sized: Sized | null): string {
  const base = `${count.toLocaleString("en-IN")} selected`;
  return sized ? `${base} · ${formatBytes(sized.bytes)}` : base;
}

export function typeBreakdown(sized: Sized | null): string | null {
  if (!sized) return null;
  const keys: IncludeKey[] = ["videos", "images", "audios", "documents", "transcripts", "text", "other"];
  const parts = keys
    .map((key) => ({ key, files: sized.byType[key]?.files ?? 0 }))
    .filter((part) => part.files > 0)
    .map((part) => `${part.files} ${INCLUDE_LABELS[part.key].toLowerCase()}`);
  return parts.length ? parts.join(" · ") : null;
}

export function SelectionBar({
  selection,
  sized,
  onClear,
  onDownload,
  onSaveFolder,
  onSelectAll,
  besideTray = false,
  shareUrl = null
}: {
  selection: Selection;
  sized: Sized | null;
  onClear: () => void;
  onDownload: () => void;
  onSaveFolder: (() => void) | null;
  onSelectAll?: (() => void) | null;
  besideTray?: boolean;
  shareUrl?: string | null;
}) {
  if (selection.size === 0) return null;
  const breakdown = typeBreakdown(sized);
  return (
    <div
      role="region"
      aria-label="Selection"
      data-testid="download-selection"
      className={`sticky bottom-[calc(var(--bottom-nav-h,0px)+0.75rem+env(safe-area-inset-bottom))] z-30 mx-auto mt-4 flex w-full max-w-3xl flex-wrap items-center gap-2 rounded-xl bg-ink-900 px-3 py-2.5 text-card shadow-lg sm:rounded-full sm:pl-5 lg:bottom-4 ${besideTray ? "sm:mr-[25.5rem] sm:ml-0" : ""}`}
    >
      <div className="min-w-0 flex-1">
        <p className="text-sm font-semibold" data-testid="selection-size">
          {selectionSummary(selection.size, sized)}
        </p>
        {breakdown ? <p className="truncate text-xs opacity-75">{breakdown}</p> : null}
        {overSelectionLimit(selection) ? <p className="text-xs text-amber-100">Too many picked. Only the first ones are included.</p> : null}
      </div>
      {onSelectAll ? (
        <button type="button" className="min-h-10 rounded-full border border-card/25 px-3.5 text-xs font-semibold hover:bg-card/10 pointer-coarse:min-h-11" onClick={onSelectAll}>
          Select all
        </button>
      ) : null}
      <button type="button" className="inline-flex min-h-10 items-center gap-1.5 rounded-full border border-card/25 px-3.5 text-xs font-semibold hover:bg-card/10 pointer-coarse:min-h-11" onClick={onClear}>
        <X className="h-3.5 w-3.5" aria-hidden />
        Clear
      </button>
      {shareUrl && typeof navigator !== "undefined" && typeof navigator.share === "function" ? (
        <button
          type="button"
          className="inline-flex min-h-10 items-center gap-1.5 rounded-full border border-card/25 px-3.5 text-xs font-semibold hover:bg-card/10 sm:hidden pointer-coarse:min-h-11"
          onClick={() => void navigator.share({ url: shareUrl }).catch(() => undefined)}
        >
          <Share2 className="h-3.5 w-3.5" aria-hidden />
          Share
        </button>
      ) : null}
      {onSaveFolder ? (
        <button type="button" className="inline-flex min-h-10 items-center gap-1.5 rounded-full border border-card/25 px-3.5 text-xs font-semibold hover:bg-card/10 pointer-coarse:min-h-11" onClick={onSaveFolder}>
          <FolderDown className="h-3.5 w-3.5" aria-hidden />
          Save to a folder
        </button>
      ) : null}
      <button type="button" className="inline-flex min-h-10 items-center gap-1.5 rounded-full bg-purple-300 px-4 text-xs font-bold text-ink-900 hover:bg-purple-200 pointer-coarse:min-h-11" onClick={onDownload}>
        <Download className="h-3.5 w-3.5" aria-hidden />
        Download selected{sized ? ` · ${formatBytes(sized.bytes)}` : ""}
      </button>
    </div>
  );
}

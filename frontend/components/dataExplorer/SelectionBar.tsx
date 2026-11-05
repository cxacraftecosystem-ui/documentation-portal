"use client";

import { X } from "lucide-react";
import { useState } from "react";

import { INCLUDE_LABELS, type IncludeKey } from "@/lib/dataExplorer/contract";
import { formatBytes } from "@/lib/dataExplorer/plan";
import { overSelectionLimit, type Selection } from "@/lib/dataExplorer/selection";

import type { Sized } from "@/components/dataExplorer/FolderHeader";

export function selectionSummary(count: number, sized: Sized | null): string {
  const base = `${count.toLocaleString("en-IN")} selected`;
  return sized ? `${base} · ${formatBytes(sized.bytes)}` : base;
}

const SINGULAR: Record<IncludeKey, string> = { videos: "video", images: "image", audios: "audio", documents: "document", transcripts: "transcript", text: "text file", other: "other file" };
const PLURAL: Record<IncludeKey, string> = { videos: "videos", images: "images", audios: "audio", documents: "documents", transcripts: "transcripts", text: "text files", other: "other files" };

export function typeBreakdown(sized: Sized | null): string | null {
  const parts = breakdownParts(sized);
  return parts.length ? parts.join(" · ") : null;
}

export function breakdownParts(sized: Sized | null): string[] {
  if (!sized) return [];
  const keys: IncludeKey[] = ["videos", "images", "audios", "documents", "text", "other"];
  const parts = keys
    .map((key) => ({ key, files: sized.byType[key]?.files ?? 0 }))
    .filter((part) => part.files > 0)
    .map((part) => `${part.files.toLocaleString("en-IN")} ${part.files === 1 ? SINGULAR[part.key] : PLURAL[part.key]}`);
  const transcripts = sized.byType.transcripts?.files ?? 0;
  if (transcripts > 0) parts.push(`+ ${transcripts.toLocaleString("en-IN")} ${transcripts === 1 ? "transcript" : "transcripts"}`);
  return parts.length ? parts : [INCLUDE_LABELS.other];
}

export function SelectionBar({
  selection,
  sized,
  onClear,
  onDownload,
  onSelectAll,
  total = null,
  shareUrl = null
}: {
  selection: Selection;
  sized: Sized | null;
  onClear: () => void;
  onDownload: () => void;
  onSaveFolder?: (() => void) | null;
  onSelectAll?: (() => void) | null;
  total?: number | null;
  shareUrl?: string | null;
}) {
  const [shared, setShared] = useState(false);
  if (selection.size === 0) return null;
  const size = sized ? formatBytes(sized.bytes) : null;
  const parts = breakdownParts(sized);
  const share = () => {
    if (!shareUrl) return;
    if (typeof navigator !== "undefined" && typeof navigator.share === "function") {
      void navigator.share({ url: shareUrl }).catch(() => undefined);
      return;
    }
    void navigator.clipboard
      ?.writeText(shareUrl)
      .then(() => {
        setShared(true);
        window.setTimeout(() => setShared(false), 2000);
      })
      .catch(() => undefined);
  };
  return (
    <>
      <div className="fixed inset-x-0 top-0 z-[60] flex items-center gap-2 border-b border-[#d6b6fb] bg-purple-100 px-4 pt-3.5 pb-3 sm:hidden dark:border-purple-800 dark:bg-[#2a0f47]" role="region" aria-label="Selecting">
        <button type="button" aria-label="Stop selecting" className="grid h-11 w-11 shrink-0 place-items-center rounded-md text-ink-900" onClick={onClear}>
          <X className="h-5 w-5" aria-hidden />
        </button>
        <div className="min-w-0 flex-1">
          <p className="text-[17px] leading-6 font-bold text-ink-900">{selection.size.toLocaleString("en-IN")} selected</p>
          <p className="truncate text-xs text-ink-500">{[size, "long-press or tap boxes"].filter(Boolean).join(" · ")}</p>
        </div>
        {onSelectAll ? (
          <button type="button" className="min-h-10 shrink-0 rounded-[10px] px-3 text-[13px] font-semibold text-purple-700 dark:text-purple-300" onClick={onSelectAll}>
            Select all{total ? ` ${total.toLocaleString("en-IN")}` : ""}
          </button>
        ) : null}
      </div>
      <div
        role="region"
        aria-label="Selection"
        data-testid="download-selection"
        className="sticky bottom-[calc(var(--bottom-nav-h,0px)+0.75rem+env(safe-area-inset-bottom))] z-30 mx-auto mt-4 flex w-full max-w-[760px] flex-wrap items-center gap-3 rounded-full bg-ink-900 py-2.5 pr-2.5 pl-[18px] text-card shadow-[0_8px_32px_rgba(46,16,101,.24)] max-lg:max-w-none max-lg:gap-2.5 max-lg:py-2 max-lg:pr-2 max-lg:pl-4 max-sm:fixed max-sm:inset-x-3 max-sm:bottom-3 max-sm:z-[60] max-sm:mt-0 max-sm:flex-col max-sm:items-stretch max-sm:rounded-[20px] max-sm:p-3 max-sm:shadow-[0_8px_32px_rgba(30,27,46,.3)] dark:bg-[#0b0a12] dark:text-ink-900"
      >
        <p className="text-sm font-semibold max-sm:sr-only" data-testid="selection-size">
          {selectionSummary(selection.size, sized)}
        </p>
        <p className="flex flex-wrap gap-2 text-xs text-[#cfcbe0] sm:hidden">
          {parts.map((part, index) => (
            <span key={part} className="contents">
              {index > 0 ? <span aria-hidden>·</span> : null}
              <span>{part}</span>
            </span>
          ))}
        </p>
        {overSelectionLimit(selection) ? <p className="text-xs text-amber-100">Too many picked. Only the first ones are included.</p> : null}
        <span className="flex-1 max-sm:hidden" />
        <div className="flex items-center gap-3 max-lg:gap-2">
          <button type="button" className="min-h-10 rounded-full border border-white/25 px-3.5 text-[13px] hover:bg-white/10 max-lg:hidden pointer-coarse:min-h-11" onClick={onClear}>
            Clear
          </button>
          {shareUrl ? (
            <button type="button" className="min-h-12 flex-1 rounded-md border border-[#3a3651] text-sm sm:hidden" onClick={share}>
              {shared ? "Link copied" : "Share"}
            </button>
          ) : null}
          <button
            type="button"
            className="min-h-10 rounded-full bg-[#c090f5] px-[18px] text-[13px] font-bold text-ink-900 hover:bg-purple-300 max-sm:min-h-12 max-sm:flex-[2] max-sm:rounded-md max-sm:text-sm max-sm:text-[#2a0f47] pointer-coarse:min-h-11 dark:text-[#2a0f47]"
            onClick={onDownload}
          >
            <span className="max-sm:hidden">Download selected</span>
            <span className="sm:hidden">
              Download {selection.size.toLocaleString("en-IN")}
              {size ? ` · ${size}` : ""}
            </span>
          </button>
        </div>
      </div>
    </>
  );
}
